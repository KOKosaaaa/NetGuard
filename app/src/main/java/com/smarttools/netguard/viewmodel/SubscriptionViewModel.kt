package com.smarttools.netguard.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.App
import com.smarttools.netguard.model.Subscription
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val subRepo = app.subscriptionRepository

    val subscriptions: StateFlow<List<Subscription>> = subRepo.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    // replay=1 so a message emitted while the fragment is paused (e.g.
    // updateAll fires from a background work flow) is still delivered
    // on its next resume. Callers must invoke [consumeMessage] right
    // after surfacing the Toast — otherwise every subsequent re-entry
    // to the Subs tab would re-show the same message via the replay
    // cache. There is no UI gesture that opens the Subs tab and
    // *legitimately* needs to see the previous message.
    private val _message = MutableSharedFlow<String>(replay = 1)
    val message: SharedFlow<String> = _message.asSharedFlow()

    /** Clears the replay-cached message after the UI has displayed it. */
    fun consumeMessage() {
        _message.resetReplayCache()
    }

    fun addSubscription(name: String, url: String, autoUpdateHours: Int = 0) {
        viewModelScope.launch {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isBlank()) {
                _message.emit("URL is empty")
                return@launch
            }
            // Shortcut: if the user pasted a single profile URI instead of a
            // subscription URL (common with Telemost links since we don't host
            // a subscription endpoint for them), save it directly as a single
            // standalone profile and skip the periodic HTTP refresh entirely.
            val asProfileUri = normalizeAsSingleProfileUri(trimmedUrl)
            if (asProfileUri != null) {
                val parsed = try {
                    com.smarttools.netguard.core.ProfileParser.parseSingleUri(asProfileUri)
                } catch (e: Exception) {
                    _message.emit("Invalid profile URI: ${e.message}")
                    return@launch
                }
                if (parsed == null) {
                    _message.emit("Unsupported profile URI")
                    return@launch
                }
                val finalName = name.trim().ifBlank { parsed.name.ifBlank { "Profile" } }
                val profile = parsed.copy(name = finalName.take(MAX_SUB_NAME_LENGTH))
                app.profileRepository.insert(profile)
                _message.emit("Profile added: $finalName")
                return@launch
            }
            // Validate before insert — otherwise a junk subscription persists
            // and WorkManager keeps retrying it every 24h.
            try {
                subRepo.validateUrl(trimmedUrl)
            } catch (e: Exception) {
                _message.emit("Invalid URL: ${e.message}")
                return@launch
            }
            // If the user typed a name, treat it as their explicit choice and
            // protect it from the next refresh (server's profile-title won't
            // overwrite). If they left it blank we'll auto-fill from the
            // server response, so userRenamed stays false. While we wait for
            // that first fetch, use the URL's host as a placeholder — much
            // more useful than the literal string "Subscription" when the
            // server doesn't expose a `profile-title` header.
            val userTypedName = name.isNotBlank()
            val safeName = if (userTypedName) {
                name.take(MAX_SUB_NAME_LENGTH)
            } else {
                hostFromUrl(trimmedUrl) ?: "Subscription"
            }
            val sub = Subscription(
                name = safeName,
                url = trimmedUrl,
                autoUpdateHours = autoUpdateHours,
                userRenamed = userTypedName
            )
            val id = subRepo.insert(sub)
            updateSubscription(sub.copy(id = id))
        }
    }

    private fun hostFromUrl(url: String): String? = try {
        java.net.URL(url).host?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /**
     * If [input] is a single-profile URI (e.g. `vless://...`, `telemost://...`),
     * or a bare Telemost join link (`https://telemost.yandex.ru/j/<id>`), return
     * the canonical URI representation. Otherwise null — caller should treat
     * the input as a subscription HTTP URL.
     */
    private fun normalizeAsSingleProfileUri(input: String): String? {
        val schemes = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "telemost://")
        if (schemes.any { input.startsWith(it) }) return input

        // Multi-line paste of bare Telemost links (one per line) → produce a
        // single multi-channel profile. The relay manager spawns one
        // librelay.so per link and round-robins TCP connections via SOCKS LB.
        val lines = input.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        val telemostLinks = lines.takeIf { it.isNotEmpty() && it.all { l -> l.startsWith("https://telemost.yandex.ru/j/") } }
        if (telemostLinks != null) {
            val joined = telemostLinks.joinToString("\n")
            val encoded = android.util.Base64.encodeToString(
                joined.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val name = if (telemostLinks.size > 1) "Telemost-x${telemostLinks.size}" else "Telemost"
            return "telemost://$encoded#$name"
        }
        return null
    }

    private companion object {
        // Mirrors ProfileParser.MAX_NAME_LENGTH so a malicious paste with a
        // multi-megabyte subscription name can't freeze the RecyclerView.
        const val MAX_SUB_NAME_LENGTH = 256
    }

    fun updateSubscription(sub: Subscription) {
        viewModelScope.launch {
            _updating.value = true
            val result = subRepo.updateSubscription(sub)
            result.fold(
                onSuccess = { count ->
                    _message.emit("Updated: $count profiles")
                },
                onFailure = { error ->
                    _message.emit("Error: ${error.message}")
                }
            )
            _updating.value = false
        }
    }

    fun updateAll() {
        viewModelScope.launch {
            _updating.value = true
            val results = subRepo.updateAll()
            val total = results.values.sumOf { it.getOrDefault(0) }
            val errors = results.values.count { it.isFailure }
            if (errors > 0) {
                _message.emit("Updated $total profiles, $errors errors")
            } else {
                _message.emit("Updated $total profiles")
            }
            _updating.value = false
        }
    }

    /**
     * Refresh subscriptions whose `lastUpdatedMs` is older than [staleAfterMs]
     * (or never updated). Called silently from the fragment's onResume so the
     * Subs cell always shows a recent "updated at" timestamp; we don't want
     * to hit the user with a toast for every tab switch, so this path does
     * not emit messages.
     */
    fun refreshIfStale(staleAfterMs: Long = 60L * 60 * 1000) {
        if (_updating.value) return
        viewModelScope.launch {
            val cutoff = System.currentTimeMillis() - staleAfterMs
            val stale = subRepo.getAll().filter { it.enabled && it.lastUpdatedMs < cutoff }
            if (stale.isEmpty()) return@launch
            _updating.value = true
            try {
                stale.forEach { subRepo.updateSubscription(it) }
            } finally {
                _updating.value = false
            }
        }
    }

    fun deleteSubscription(sub: Subscription) {
        viewModelScope.launch {
            subRepo.delete(sub)
            _message.emit("Deleted: ${sub.name}")
        }
    }

    /**
     * Same logic as [addSubscription] but returns the outcome directly instead
     * of routing it through the shared message flow. Used by the stateful
     * "Add subscription" dialog that renders its own loading / success / error
     * UI inside the dialog body.
     *
     * The result reflects whichever branch fired:
     *  - single profile URI → `profileCount = 1`, name = profile name
     *  - HTTP subscription   → `profileCount = N`, name = subscription name
     */
    suspend fun importSubscription(
        name: String,
        url: String,
        autoUpdateHours: Int
    ): Result<ImportResult> {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            return Result.failure(IllegalArgumentException("URL is empty"))
        }

        // Branch 1: single-profile URI or multi-line Telemost paste.
        val asProfileUri = normalizeAsSingleProfileUri(trimmedUrl)
        if (asProfileUri != null) {
            val parsed = try {
                com.smarttools.netguard.core.ProfileParser.parseSingleUri(asProfileUri)
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException("Invalid profile URI: ${e.message}"))
            }
            if (parsed == null) {
                return Result.failure(IllegalArgumentException("Unsupported profile URI"))
            }
            val finalName = name.trim().ifBlank { parsed.name.ifBlank { "Profile" } }
            val profile = parsed.copy(name = finalName.take(MAX_SUB_NAME_LENGTH))
            app.profileRepository.insert(profile)
            return Result.success(ImportResult(finalName, 1))
        }

        // Branch 2: HTTP subscription.
        try {
            subRepo.validateUrl(trimmedUrl)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid URL: ${e.message}"))
        }
        val userTypedName = name.isNotBlank()
        val safeName = if (userTypedName) {
            name.take(MAX_SUB_NAME_LENGTH)
        } else {
            hostFromUrl(trimmedUrl) ?: "Subscription"
        }
        val sub = Subscription(
            name = safeName,
            url = trimmedUrl,
            autoUpdateHours = autoUpdateHours,
            userRenamed = userTypedName
        )
        val id = subRepo.insert(sub)
        val fetched = subRepo.updateSubscription(sub.copy(id = id))
        return fetched.fold(
            onSuccess = { count ->
                // Re-read the subscription so we surface the final name —
                // updateSubscription may have overwritten safeName with the
                // server's profile-title header.
                val finalSub = subRepo.getById(id) ?: sub.copy(id = id)
                Result.success(ImportResult(finalSub.name, count))
            },
            onFailure = { error ->
                // Roll back the empty subscription row so the user does not
                // see a "0 profiles" ghost card after a failed import.
                subRepo.delete(sub.copy(id = id))
                Result.failure(error)
            }
        )
    }

    data class ImportResult(val name: String, val profileCount: Int)

    /**
     * Persist a user-chosen subscription name and mark the subscription as
     * `userRenamed=true` so subsequent `updateSubscription` calls keep the
     * chosen name instead of overwriting it from the server's `profile-title`.
     */
    fun renameSubscription(sub: Subscription, newName: String) {
        val safe = newName.trim().take(128).ifBlank { return }
        if (safe == sub.name && sub.userRenamed) return
        viewModelScope.launch {
            subRepo.update(sub.copy(name = safe, userRenamed = true))
        }
    }
}
