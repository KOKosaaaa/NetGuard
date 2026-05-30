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

    /** Build a [FriendlyError] from whatever blew up in the API call. */
    fun explain(throwable: Throwable): FriendlyError {
        // Agent returned a structured E_ error
        if (throwable is AgentApiError) {
            val raw = "Код: ${throwable.code}\n${throwable.errorMessage}"
            return mapCode(throwable.code, throwable.errorMessage, raw)
                ?: FriendlyError(
                    title = "Что-то пошло не так",
                    body = "Сервер вернул ошибку. Можно попробовать ещё раз; " +
                        "если повторится — посмотри подробности.",
                    retryable = true,
                    rawDetails = raw,
                )
        }
        // waitForTask wraps task failures as IllegalStateException
        val msg = throwable.message.orEmpty()
        if (msg.startsWith("xray deploy") || msg.contains("xray deploy")) {
            return explainXrayTaskMessage(msg)
        }
        if (msg.contains("timed out")) {
            return FriendlyError(
                title = "Сервер не ответил вовремя",
                body = "Сервер сейчас занят или есть проблемы со связью. " +
                    "Попробуй ещё раз через минуту.",
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
                title = "Серверу не хватило памяти",
                body = "Telemost-сборка убита OOM-киллером. На этом " +
                    "VPS не хватает RAM даже на один поток. " +
                    "Попробуй меньше потоков, либо возьми VPS с " +
                    "1 GB+ RAM — этого хватит для 3-6 потоков.",
                retryable = true,
                rawDetails = msg,
            )
        }
        // Hand-written user-facing messages from our own code (the chain
        // orchestrator, for instance) come in as IllegalStateException
        // with a long Russian text. If it looks like Russian prose, pass
        // it through instead of swallowing it into the generic fallback.
        if (msg.length >= 20 && containsCyrillic(msg)) {
            return FriendlyError(
                title = "Не получилось",
                body = msg,
                retryable = true,
                rawDetails = msg,
            )
        }
        // Generic last-resort
        return FriendlyError(
            title = "Неизвестная ошибка",
            body = "Не удалось завершить операцию. Можно повторить.",
            retryable = true,
            rawDetails = msg.ifBlank { throwable.javaClass.simpleName },
        )
    }

    private fun containsCyrillic(s: String): Boolean =
        s.any { it in 'Ѐ'..'ӿ' }

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
            title = "Не удалось установить xray",
            body = "Сервер не смог завершить установку. Часто помогает " +
                "просто повторить. Если ошибка повторяется — посмотри " +
                "подробности.",
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
                title = "Серверу не хватило памяти",
                body = "Telemost-сборка убита OOM-киллером. На этом " +
                    "VPS не хватает RAM даже на один поток. " +
                    "Попробуй меньше потоков, либо возьми VPS с " +
                    "1 GB+ RAM — этого хватит для 3-6 потоков.",
                retryable = true,
                rawDetails = raw,
            )
        }
        return when (code) {
            "E_DOWNLOAD",
            "E_APT_CURL" -> FriendlyError(
                title = "Сервер не смог скачать xray",
                body = "У сервера временные проблемы с сетью или GitHub " +
                    "не отдал файл. Это часто чинится повтором — " +
                    "нажми «Повторить» через минуту.",
                retryable = true,
                rawDetails = raw,
            )
            "E_EXTRACT",
            "E_EXTRACT_MKDIR",
            "E_EXTRACT_NO_BIN",
            "E_APT_UNZIP" -> FriendlyError(
                title = "Архив с xray повреждён",
                body = "Файл скачался, но распаковать не получилось. " +
                    "Можно попробовать ещё раз — обычно при повторной " +
                    "загрузке всё проходит.",
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
                title = "Не хватает прав на сервере",
                body = "Агент не смог записать файлы. Обычно это из-за " +
                    "переполненного диска или прав. Проверь, что на " +
                    "сервере есть свободное место, и повтори.",
                retryable = true,
                rawDetails = raw,
            )
            "E_DAEMON_RELOAD",
            "E_SYSTEMCTL_START",
            "E_HEALTHCHECK",
            "E_RESTART_XRAY",
            "E_SERVICE_FAILED" -> FriendlyError(
                title = "xray установился, но не стартовал",
                body = "Файлы на сервере есть, но xray не запустился. " +
                    "Часто причина в занятом порту. Можно повторить, " +
                    "выбрав другой SNI или порт.",
                retryable = true,
                rawDetails = raw,
            )
            "E_XRAY_PREEXISTING" -> FriendlyError(
                title = "На сервере уже стоит xray",
                body = "На сервере найдена чужая установка xray. " +
                    "Чтобы продолжить, удали её или нажми «Переустановить» " +
                    "в меню сервера.",
                retryable = false,
                rawDetails = raw,
            )
            "E_UNSUPPORTED_ARCH" -> FriendlyError(
                title = "Архитектура сервера не поддерживается",
                body = "Агент работает только на серверах amd64 и arm64. " +
                    "На этом сервере другая архитектура.",
                retryable = false,
                rawDetails = raw,
            )
            "E_BUILD_CONFIG",
            "E_BAD_REQUEST",
            "E_BAD_JSON" -> FriendlyError(
                title = "Некорректные параметры профиля",
                body = "Что-то не так с параметрами профиля. Проверь " +
                    "SNI (должен быть доменом) и попробуй снова.",
                retryable = false,
                rawDetails = raw,
            )
            "E_UNAUTHORIZED" -> FriendlyError(
                title = "Сервер не принял авторизацию",
                body = "Ключ для общения с сервером устарел. Удали " +
                    "сервер из приложения и добавь заново.",
                retryable = false,
                rawDetails = raw,
            )
            "E_AGENT_RESTARTED" -> FriendlyError(
                title = "Агент перезапустился во время операции",
                body = "Операция прервалась. Просто повтори — должно " +
                    "пройти.",
                retryable = true,
                rawDetails = raw,
            )
            else -> null
        }
    }
}
