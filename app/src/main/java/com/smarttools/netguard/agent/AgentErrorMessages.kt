package com.smarttools.netguard.agent

/**
 * Human-readable error mapping for agent error codes. The raw `code` /
 * `message` from the agent is too technical for the Add-Server /
 * Add-Profile flow — operators see things like
 * `xray deploy failed: read tcp 172.17.0.3:34970->185.199.109.133:443:
 *  read: connection reset by peer` and don't know whether to retry,
 * change something, or give up.
 *
 * For each known code we return:
 *  - [title]: a short summary suitable for a dialog title
 *  - [body]: 1-3 sentences in Russian that say WHAT happened + what to
 *    try. Avoid jargon.
 *  - [retryable]: whether a "Повторить" button makes sense (transient
 *    network errors yes; config-level errors no — those need the user
 *    to change input).
 *
 * Unknown codes fall back to a generic message; the raw text is still
 * available as expandable "Подробнее" details in the dialog.
 */
data class FriendlyError(
    val title: String,
    val body: String,
    val retryable: Boolean,
    val rawDetails: String,
)

object AgentErrorMessages {
    fun explain(throwable: Throwable, context: android.content.Context): FriendlyError = Presenter(context).explain(throwable)

    private class Presenter(private val context: android.content.Context) {
    private fun l10n(id: Int, vararg args: Any): String = com.smarttools.netguard.util.LocalizedResources.string(context, id, *args)


    /** Build a [FriendlyError] from whatever blew up in the API call. */
    fun explain(throwable: Throwable): FriendlyError {
        if (throwable is WbRoomDeletionException) return FriendlyError(l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_7), l10n(throwable.messageId), true, throwable.message.orEmpty())
        // Agent returned a structured E_ error
        if (throwable is AgentApiError) {
            val raw = "HTTP ${throwable.httpCode}; ${throwable.code}\n${throwable.errorMessage}"
            if (throwable.code == "E_AGENT_UPDATE" && throwable.errorMessage.contains("timeout", ignoreCase = true)) {
                return FriendlyError(
                    l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_3),
                    l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_4), true, raw,
                )
            }
            return mapCode(throwable.code, throwable.errorMessage, raw)
                ?: FriendlyError(
                    title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_1),
                    body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_2),
                    retryable = true,
                    rawDetails = raw,
                )
        }
        // waitForTask wraps task failures as IllegalStateException of the
        // shape "<op> failed (status): <agent msg>". Any such message may
        // carry an E_ code — map it generically so a swap/Telemost failure
        // isn't mislabeled as an xray problem (the message used to be
        // hard-coded to "xray deploy …").
        val msg = throwable.message.orEmpty()
        Regex("E_[A-Z_]+").find(msg)?.value?.let { code ->
            mapCode(code, msg, msg)?.let { return it }
        }
        if (msg.startsWith("xray deploy") || msg.contains("xray deploy")) {
            return explainXrayTaskMessage(msg)
        }
        if (msg.contains("timed out")) {
            return FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_3),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_4),
                retryable = true,
                rawDetails = msg,
            )
        }
        // OOM marker bubbles up from the Telemost runner via either an
        // AgentApiError (sync call) or an IllegalStateException wrapped
        // around the failed task body (async). Catch both shapes via
        // substring match on the agent-injected sentinel.
        if (msg.contains("OOM_KILLED")) {
            return FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_5),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_6),
                retryable = true,
                rawDetails = msg,
            )
        }
        // Server prose stays in rawDetails; display a localized fallback.
        // Generic last-resort
        return FriendlyError(
            title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_8),
            body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_9),
            retryable = true,
            rawDetails = msg.ifBlank { throwable.javaClass.simpleName },
        )
    }

    /** Parse a "xray deploy failed: <code>: <message>" string from waitForTask. */
    private fun explainXrayTaskMessage(msg: String): FriendlyError {
        // Try to pull an E_* code out of the wrapped task error message.
        val codeMatch = Regex("E_[A-Z_]+").find(msg)
        val code = codeMatch?.value
        if (code != null) {
            mapCode(code, msg, msg)?.let { return it }
        }
        // No code — show the network-y bit if present.
        return FriendlyError(
            title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_10),
            body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_11),
            retryable = true,
            rawDetails = msg,
        )
    }

    private fun mapCode(code: String, message: String, raw: String): FriendlyError? {
        // OOM marker injected by the agent's Telemost runner — friendlier
        // than the generic "creator exit" line because the user is then
        // sure WHY it failed (not enough RAM) and what to do (fewer
        // streams or a bigger VPS).
        if (code == "E_TELEMOST_PENDING" && message.contains("OOM_KILLED")) {
            return FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_5),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_6),
                retryable = true,
                rawDetails = raw,
            )
        }
        return when (code) {
            "E_DOWNLOAD",
            "E_APT_CURL" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_12),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_13),
                retryable = true,
                rawDetails = raw,
            )
            "E_EXTRACT",
            "E_EXTRACT_MKDIR",
            "E_EXTRACT_NO_BIN",
            "E_APT_UNZIP" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_14),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_15),
                retryable = true,
                rawDetails = raw,
            )
            "E_INSTALL_BIN",
            "E_WRITE_CONFIG",
            "E_WRITE_UNIT",
            "E_DATA_DIR",
            "E_BACKUP_DIR",
            "E_BACKUP_CONFIG",
            "E_BACKUP_UNIT" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_16),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_17),
                retryable = true,
                rawDetails = raw,
            )
            "E_DAEMON_RELOAD",
            "E_SYSTEMCTL_START",
            "E_HEALTHCHECK",
            "E_RESTART_XRAY",
            "E_SERVICE_FAILED" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_18),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_19),
                retryable = true,
                rawDetails = raw,
            )
            "E_XRAY_PREEXISTING" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_20),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_21),
                retryable = false,
                rawDetails = raw,
            )
            "E_UNSUPPORTED_ARCH" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_22),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_23),
                retryable = false,
                rawDetails = raw,
            )
            "E_BUILD_CONFIG",
            "E_BAD_REQUEST",
            "E_BAD_JSON" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_24),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_25),
                retryable = false,
                rawDetails = raw,
            )
            "E_UNAUTHORIZED" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_26),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_27),
                retryable = false,
                rawDetails = raw,
            )
            "E_AGENT_RESTARTED" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_28),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_29),
                retryable = true,
                rawDetails = raw,
            )
            "E_PORT_BUSY" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_30),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_31),
                retryable = false,
                rawDetails = raw,
            )
            "E_TELEMOST_NOT_DEPLOYED" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_32),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_33),
                retryable = false,
                rawDetails = raw,
            )
            "E_TELEMOST_NO_COOKIES" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_34),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_35),
                retryable = false,
                rawDetails = raw,
            )
            "E_OOM",
            "E_CREATE_ROOM" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_5),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_36),
                retryable = true,
                rawDetails = raw,
            )
            "E_NO_DISK" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_37),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_38),
                retryable = false,
                rawDetails = raw,
            )
            "E_ALLOCATE",
            "E_MKSWAP",
            "E_SWAPON" -> FriendlyError(
                title = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_39),
                body = l10n(com.smarttools.netguard.R.string.loc_agent_error_messages_40),
                retryable = true,
                rawDetails = raw,
            )
            else -> null
        }
    }
}
}
