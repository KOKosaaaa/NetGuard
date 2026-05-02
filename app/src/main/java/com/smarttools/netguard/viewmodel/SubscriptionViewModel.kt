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

    private val _message = MutableSharedFlow<String>(replay = 1)
    val message: SharedFlow<String> = _message.asSharedFlow()

    fun addSubscription(name: String, url: String, autoUpdateHours: Int = 0) {
        viewModelScope.launch {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isBlank()) {
                _message.emit("URL is empty")
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

    fun deleteSubscription(sub: Subscription) {
        viewModelScope.launch {
            subRepo.delete(sub)
            _message.emit("Deleted: ${sub.name}")
        }
    }

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
