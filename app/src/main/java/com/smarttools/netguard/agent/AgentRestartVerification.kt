package com.smarttools.netguard.agent

/** A healthy response from the still-running old process is not an update. */
internal fun HealthResponse.confirmsRestartOf(previous: HealthResponse): Boolean =
    ok && previous.ok && startedAt.isNotBlank() && previous.startedAt.isNotBlank() &&
        startedAt != previous.startedAt
