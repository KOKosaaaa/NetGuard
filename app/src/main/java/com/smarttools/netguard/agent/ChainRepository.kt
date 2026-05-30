package com.smarttools.netguard.agent

import android.content.Context
import android.util.Log
import com.smarttools.netguard.database.AppDatabase
import com.smarttools.netguard.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Higher-level facade for the chains table — wraps [ChainDao] and
 * threads the "delete on all hops + drop ServerProfile" sequence the
 * UI calls when the user removes a chain.
 *
 * Note: deletion is best-effort. If an agent on one hop is unreachable
 * we keep going and still drop the chain rows locally, on the rationale
 * that leaving a chain in the app it can't manage is worse than the
 * orphan xray inbound the user can clean up later via the per-server
 * Profiles tab. We log a warning so the issue surfaces if anyone reads
 * the system log.
 */
class ChainRepository(
    private val dao: ChainDao,
    private val managedServers: ManagedServerRepository,
    private val serverProfiles: ProfileRepository,
    private val appVersion: String,
) {

    fun observeAll(): Flow<List<ChainWithHops>> =
        dao.observeAllChains().map { chains ->
            chains.map { ChainWithHops(it, dao.getHopsByChainId(it.id)) }
        }

    suspend fun create(
        label: String,
        entryProfileUri: String,
        hops: List<CreateHopRow>,
    ): Long {
        val id = dao.insertChain(
            ChainEntity(
                label = label,
                createdAt = System.currentTimeMillis(),
                entryProfileUri = entryProfileUri,
            )
        )
        dao.insertHops(
            hops.mapIndexed { idx, h ->
                ChainHopEntity(
                    chainId = id,
                    serverId = h.serverId,
                    serverName = h.serverName,
                    inboundId = h.inboundId,
                    position = idx,
                )
            }
        )
        return id
    }

    /**
     * Tear down a chain end-to-end: delete the xray inbound on each
     * managed server still known to the app, drop the matching
     * ServerProfile, then remove the chain rows themselves.
     */
    suspend fun delete(chain: ChainWithHops) = withContext(Dispatchers.IO) {
        for (hop in chain.hops) {
            val server = managedServers.getById(hop.serverId)
            if (server == null) {
                Log.w(TAG, "delete: server ${hop.serverId} no longer in app — skipping hop")
                continue
            }
            try {
                AgentApiClient(server, appVersion).deleteProfile(hop.inboundId)
            } catch (e: Exception) {
                Log.w(TAG, "delete: hop ${hop.inboundId} on ${server.host}: ${e.message}")
            }
        }
        // Drop the imported subscription profile if it's still there. We
        // match by name (we set it to the chain label at create time);
        // tolerate multiple matches by deleting all to recover from any
        // stale label-collision a previous bug could have introduced.
        runCatching {
            val all = serverProfiles.getAll()
            all.filter { it.name == chain.chain.label }
                .forEach { serverProfiles.delete(it) }
        }.onFailure { Log.w(TAG, "drop profile failed: ${it.message}") }
        dao.deleteChain(chain.chain.id)
    }

    data class CreateHopRow(
        val serverId: Long,
        val serverName: String,
        val inboundId: String,
    )

    companion object {
        private const val TAG = "ChainRepo"

        @Volatile private var instance: ChainRepository? = null

        fun get(context: Context): ChainRepository {
            val app = context.applicationContext as com.smarttools.netguard.App
            return instance ?: synchronized(this) {
                instance ?: ChainRepository(
                    dao = AppDatabase.getInstance(context).chainDao(),
                    managedServers = ManagedServerRepository.get(context),
                    serverProfiles = app.profileRepository,
                    appVersion = com.smarttools.netguard.BuildConfig.VERSION_NAME,
                ).also { instance = it }
            }
        }
    }
}
