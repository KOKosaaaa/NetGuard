package com.smarttools.netguard.agent

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URI

/** Only the WB origin and the minimal WB login state may be exported. */
internal object WbOwnerSession {
    // CookieManager filters by URL path, including HttpOnly cookies. The root
    // of auth-stream does not expose a refresh cookie scoped to /v2/auth.
    // Read the actual refresh endpoint first, so a stale root cookie cannot
    // overwrite the cookie WB would use for refreshing this session.
    val cookieUrls = listOf(
        "https://auth-stream.wb.ru/v2/auth/slide-v3",
        "https://auth-stream.wb.ru/",
        "https://stream.wb.ru/"
    )

    class CaptureException(val reason: Reason) : IllegalArgumentException(reason.name)
    enum class Reason(val messageId: Int) {
        ORIGIN(com.smarttools.netguard.R.string.wb_owner_origin),
        STORAGE(com.smarttools.netguard.R.string.wb_owner_storage),
        DEVICE(com.smarttools.netguard.R.string.wb_owner_device),
        ACCESS(com.smarttools.netguard.R.string.wb_owner_access),
        REFRESH(com.smarttools.netguard.R.string.wb_owner_refresh);
        fun message(context: android.content.Context): String = com.smarttools.netguard.util.LocalizedResources.string(context, messageId)
    }
    fun isWbOrigin(url: String?): Boolean = runCatching {
        val uri = URI(url ?: "")
        uri.scheme == "https" && uri.host.equals("stream.wb.ru", true) &&
            uri.userInfo == null && uri.port in listOf(-1, 443)
    }.getOrDefault(false)

    fun build(url: String?, storage: String, cookieHeaders: List<String>): String {
        if (!isWbOrigin(url)) throw CaptureException(Reason.ORIGIN)
        val data = runCatching { JsonParser.parseString(storage).asJsonObject }.getOrNull()
            ?: throw CaptureException(Reason.STORAGE)
        val device = runCatching { data.get("device_id")?.asString }.getOrNull().orEmpty()
        val slice = runCatching { data.get("auth_slice")?.asString }.getOrNull().orEmpty()
        if (device.isBlank() && slice.isBlank()) throw CaptureException(Reason.STORAGE)
        if (device.isBlank() || device.length > 256) throw CaptureException(Reason.DEVICE)
        val token = runCatching { JsonParser.parseString(slice).asJsonObject.get("accessToken")?.asString }.getOrNull()
        if (slice.length > 16384 || token.isNullOrBlank()) throw CaptureException(Reason.ACCESS)
        val cookies = linkedMapOf<String, String>()
        val allowed = setOf("wbx-refresh", "_wbauid", "wbx-validation-key")
        cookieHeaders.flatMap { it.split(';') }.forEach {
            val split = it.trim().split('=', limit = 2)
            if (split.size == 2 && split[0] in allowed && split[1].isNotBlank()) cookies.putIfAbsent(split[0], split[1])
        }
        if (cookies["wbx-refresh"].isNullOrBlank()) throw CaptureException(Reason.REFRESH)
        return Gson().toJson(mapOf("cookies" to cookies, "device_id" to device, "auth_slice" to slice))
    }
}
