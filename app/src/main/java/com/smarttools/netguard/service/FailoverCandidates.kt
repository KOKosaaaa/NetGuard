package com.smarttools.netguard.service

import com.smarttools.netguard.model.ServerProfile

internal fun failoverCandidates(current: ServerProfile?, profiles: List<ServerProfile>): List<ServerProfile> =
    profiles.filter { current?.isWbStream != true || it.isWbStream }
        .sortedWith(compareBy<ServerProfile> { if (it.subscriptionId == current?.subscriptionId) 0 else 1 }
            .thenBy { if (it.lastPingMs > 0) it.lastPingMs else Int.MAX_VALUE })
