package com.smarttools.netguard.model

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val startTimeMs: Long = System.currentTimeMillis()) : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    val isActive: Boolean
        get() = this is Connected || this is Connecting
}
