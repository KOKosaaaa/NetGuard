package com.smarttools.netguard.agent

import android.util.Log
import com.smarttools.netguard.BuildConfig

/**
 * Walks a multi-hop VPN chain from EXIT → ENTRY, creating one VLESS
 * inbound per hop on the corresponding managed server. Each non-exit
 * hop is wired up with `chain_to` pointing at the previous hop's
 * profile, so traffic enters at the entry server and emerges on the
 * exit server's freedom outbound.
 *
 * Failure semantics: if any hop fails, all already-created inbounds
 * (on prior hops) are rolled back via DELETE /v1/xray/profile so the
 * user is never left with half-built chain config sitting on servers.
 *
 * Returns the final ENTRY vless:// URI — that's the single string the
 * user adds to their subscription.
 *
 * Threading: every call inside this class is **blocking** — invoke
 * from `Dispatchers.IO`.
 */
class ChainOrchestrator(
    private val appVersion: String = BuildConfig.VERSION_NAME,
    /**
     * Optional progress callback. Fires on stage transitions so the UI
     * can render "Хоп 2 из 3 — устанавливаю xray…" without polling.
     * Called from the IO thread the orchestrator runs on; the caller is
     * responsible for marshalling back to the main thread if needed.
     */
    private val onProgress: ((Progress) -> Unit)? = null,
) {

    /** Stages of one hop's setup, in the order they fire. */
    enum class Stage {
        STARTING,       // about to hit /xray/profile
        DEPLOYING_XRAY, // first call returned E_XRAY_ADD_PROFILE; running deploy
        ADDING_PROFILE, // deploy done, retrying the profile call
        DONE,           // hop's inbound is in the agent DB
    }

    data class Progress(
        /** 1-based index, counted in the order the user sees on screen. */
        val hopIndex: Int,
        val hopTotal: Int,
        val serverName: String,
        val stage: Stage,
        /**
         * During DEPLOYING_XRAY the agent's task FSM cycles through
         * fine-grained step names: detect, prereqs, download_xray,
         * extract_xray, install_bin, writing_config, install_unit,
         * daemon_reload, systemctl_start, healthcheck. The UI maps each
         * one to a human-readable line. Null outside DEPLOYING_XRAY.
         */
        val subStep: String? = null,
    )

    /**
     * One hop in the chain specification. `server` is where this hop's
     * inbound runs; `label` and `serverName` (SNI) tune what kind of
     * inbound we ask for.
     */
    data class HopSpec(
        val server: ManagedServer,
        val label: String,
        val serverName: String,
    )

    /** Outcome of a successful chain build. */
    data class ChainResult(
        /** Entry-hop vless:// URI — this is the user-facing profile. */
        val entryProfileUri: String,
        /** Inbound IDs created per server, in the same order as the input hops. */
        val createdInbounds: List<CreatedHop>,
    )

    data class CreatedHop(
        val server: ManagedServer,
        val inboundId: String,
        val profileUri: String,
    )

    /**
     * @param hops Ordered list, hop[0] = ENTRY (what the user connects
     *   to), hop[last] = EXIT (where traffic emerges).
     */
    fun build(hops: List<HopSpec>): ChainResult {
        require(hops.size >= 2) { "chain needs at least 2 hops" }
        require(hops.map { it.server.id }.distinct().size == hops.size) {
            "each hop must be a different server"
        }

        // We walk EXIT → ENTRY so by the time hop[i] is created the
        // hop right behind it (i+1) already exists and we can pass its
        // public host/UUID/Reality keys as chain_to.
        val created = ArrayDeque<CreatedHop>() // newest at head

        fun rollback() {
            for (h in created) {
                runCatching {
                    AgentApiClient(h.server, appVersion).deleteProfile(h.inboundId)
                }.onFailure { Log.w(TAG, "rollback delete failed on ${h.server.host}", it) }
            }
        }

        try {
            // Hop[N-1] = EXIT — regular profile, no chain_to.
            // Each previous hop chain_to's the *previously created* hop.
            //
            // We report progress with a 1-based iteration counter so the
            // bar moves forward in lockstep with the work, regardless of
            // whether the user thinks of "1" as entry or exit. The UI
            // copy just says "Setting up <serverName>" — direction-
            // agnostic.
            var iteration = 0
            for (i in hops.indices.reversed()) {
                iteration++
                val spec = hops[i]
                val client = AgentApiClient(spec.server, appVersion)
                val uiIndex = iteration
                onProgress?.invoke(Progress(uiIndex, hops.size, spec.server.name, Stage.STARTING))

                val chainTo: ChainTarget? = if (created.isNotEmpty()) {
                    // The "next hop" from this entry's perspective is
                    // the hop we created last (closer to exit).
                    val downstream = created.first()
                    // Use the downstream server's PUBLIC host (the one
                    // the app already uses to reach the agent) as the
                    // chain target — the URI returned by the agent has
                    // an internal hostname (often a Docker hostname /
                    // PTR) which is unreachable from outside.
                    ChainTarget.fromVlessUri(
                        downstream.profileUri,
                        overrideHost = downstream.server.host,
                    ) ?: throw IllegalStateException(
                        "could not parse downstream URI: ${downstream.profileUri}"
                    )
                } else null

                val req = AddProfileRequest(
                    protocol = "vless",
                    port = 0, // agent picks 443 for Reality
                    serverName = spec.serverName,
                    label = spec.label,
                    chainTo = chainTo,
                )
                val result = try {
                    client.addProfile(req)
                } catch (e: AgentApiError) {
                    val notInstalled = e.code == "E_XRAY_ADD_PROFILE" ||
                        e.errorMessage.contains("not installed", ignoreCase = true)
                    if (!notInstalled) throw e
                    // Fresh server with no xray yet — deploy first, then
                    // retry. We use an empty deploy (no first_inbound) so
                    // we can then run the regular addProfile path which
                    // already knows how to wire chain_to. Same idempotent
                    // contract as the single-server "create profile"
                    // wizard, which auto-deploys xray on first use.
                    onProgress?.invoke(Progress(uiIndex, hops.size, spec.server.name, Stage.DEPLOYING_XRAY))
                    val ack = client.deployXray(DeployXrayRequest())
                    waitForXrayDeploy(
                        client = client,
                        taskId = ack.taskId,
                        timeoutSec = 180,
                        onStep = { step ->
                            onProgress?.invoke(Progress(
                                uiIndex, hops.size, spec.server.name,
                                Stage.DEPLOYING_XRAY,
                                subStep = step,
                            ))
                        },
                    )
                    onProgress?.invoke(Progress(uiIndex, hops.size, spec.server.name, Stage.ADDING_PROFILE))
                    client.addProfile(req)
                }
                created.addFirst(
                    CreatedHop(
                        server = spec.server,
                        inboundId = result.inboundId,
                        profileUri = rewriteHostInUri(result.profileUri, spec.server.host),
                    )
                )
                onProgress?.invoke(Progress(uiIndex, hops.size, spec.server.name, Stage.DONE))
            }

            // After the loop: created[0] is the ENTRY (last one we
            // built) — that's the URI for the user's subscription.
            val entry = created.first()
            return ChainResult(
                entryProfileUri = entry.profileUri,
                createdInbounds = created.toList().reversed(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "chain build failed at hop, rolling back", e)
            rollback()
            throw e
        }
    }

    /**
     * Poll /v1/tasks/{id} until it leaves the running state. Fixed 1s
     * backoff matches the single-server path in ManagedServerDetailVM
     * so the user sees the same cadence whichever flow they take.
     *
     * `onStep` is invoked every time the task's `step` field changes
     * (or on the first poll that yielded a non-empty step), letting the
     * UI render fine-grained "downloading…" / "extracting…" text on
     * top of the per-hop progress.
     */
    private fun waitForXrayDeploy(
        client: AgentApiClient,
        taskId: String,
        timeoutSec: Int,
        onStep: ((String) -> Unit)? = null,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var lastStep: String? = null
        while (System.currentTimeMillis() < deadline) {
            val t = try { client.task(taskId) } catch (_: Exception) { null }
            if (t != null) {
                if (t.step.isNotEmpty() && t.step != lastStep) {
                    lastStep = t.step
                    onStep?.invoke(t.step)
                }
                if (t.isTerminal) {
                    if (t.status != "done") {
                        val msg = t.error?.message ?: t.status
                        throw IllegalStateException("xray deploy ${t.status}: $msg")
                    }
                    return
                }
            }
            Thread.sleep(1000)
        }
        throw IllegalStateException("xray deploy timed out after ${timeoutSec}s")
    }

    /**
     * Replace the host portion of a vless:// URI with [newHost]. Same
     * helper as ManagedServerDetailViewModel.rewriteHost — duplicated
     * here to keep ChainOrchestrator standalone (no ViewModel deps).
     */
    private fun rewriteHostInUri(uri: String, newHost: String): String {
        val at = uri.indexOf('@')
        if (at < 0) return uri
        val afterAt = uri.substring(at + 1)
        val end = afterAt.indexOfFirst { it == ':' || it == '?' || it == '#' }
        if (end < 0) return uri
        return uri.substring(0, at + 1) + newHost + afterAt.substring(end)
    }

    companion object { private const val TAG = "ChainOrch" }
}
