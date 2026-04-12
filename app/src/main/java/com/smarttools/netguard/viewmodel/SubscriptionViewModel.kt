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
            if (url.isBlank()) {
                _message.emit("URL is empty")
                return@launch
            }
            val sub = Subscription(
                name = name.ifBlank { "Subscription" },
                url = url.trim(),
                autoUpdateHours = autoUpdateHours
            )
            val id = subRepo.insert(sub)
            updateSubscription(sub.copy(id = id))
        }
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
}
