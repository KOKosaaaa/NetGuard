package com.smarttools.netguard.model

/** Native relay contract. WB uses its own per-connection reliability protocol. */
enum class RelayCarrier(val mode: String, val roomKey: String, val arqVariable: String) {
    TELEMOST("telemost-headless-joiner", "joinLink", "WLB_CARRIER_ARQ"),
    WB_STREAM("wbstream-headless-joiner", "roomId", "WLB_CARRIER_PCARQ");

    fun configureEnvironment(environment: MutableMap<String, String>) {
        environment.remove("WLB_CARRIER_ARQ")
        environment.remove("WLB_CARRIER_PCARQ")
        environment.remove("WLB_CARRIER_KBPS")
        environment.remove("WLB_PC_RATE_MODE")
        environment.remove("WLB_PC_FPS")
        environment.remove("WLB_PC_CHUNK")
        environment["WLB_VALID_VP8_TUNNEL"] = "1"
        environment[arqVariable] = "1"
        // A ceiling for ACK/loss-driven pacing, not a forced sending rate.
        if (this == WB_STREAM) {
            environment["WLB_CARRIER_KBPS"] = "10000"
            environment["WLB_PC_RATE_MODE"] = "probe"
            environment["WLB_PC_FPS"] = "96"
            environment["WLB_PC_CHUNK"] = "4096"
        }
    }

    companion object {
        fun forRoom(link: String): RelayCarrier =
            if (WbStreamLink.looksLike(link)) WB_STREAM else TELEMOST
    }
}
