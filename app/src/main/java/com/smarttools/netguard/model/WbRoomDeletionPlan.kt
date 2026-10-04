package com.smarttools.netguard.model

object WbRoomDeletionPlan {
    /** Shared rooms stay alive until their last local profile is removed. */
    fun unusedRooms(profile: ServerProfile, all: List<ServerProfile>): Set<String> {
        if (!profile.isWbStream) return emptySet()
        val shared = all.filter { it.id != profile.id && it.isWbStream }
            .flatMap { WbStreamLink.parse(it.address).links }.toSet()
        return WbStreamLink.parse(profile.address).links.toSet() - shared
    }
}
