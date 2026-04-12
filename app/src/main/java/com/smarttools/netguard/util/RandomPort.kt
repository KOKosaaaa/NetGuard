package com.smarttools.netguard.util

import java.net.ServerSocket

object RandomPort {

    /**
     * Let OS assign a free ephemeral port.
     * Port 0 tells the kernel to pick one that's currently unused,
     * minimizing the TOCTOU race between check and xray bind.
     */
    fun getAvailable(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }
}
