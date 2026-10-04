package com.smarttools.netguard.model

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** Shared by paste, QR, export and relay startup. Never fetches a room as a subscription. */
object WbStreamLink {
    const val MAX_ROOMS = 12
    private const val MAX_INPUT = 64 * 1024
    private const val PREFIX = "https://stream.wb.ru/room/"
    private val roomId = Regex("[A-Za-z0-9_-]{1,128}")
    data class Rooms(val links: List<String>, val name: String)

    fun looksLike(input: String): Boolean {
        val first = input.trim().substringBefore('\n').trim()
        return first.startsWith("wbstream://", true) ||
            runCatching { val u = URI(first.substringBefore('#')); u.host.equals("stream.wb.ru", true) }.getOrDefault(false)
    }

    fun parse(input: String): Rooms {
        require(input.length <= MAX_INPUT) { "WB Stream link is too long" }
        val value = input.trim()
        require(value.isNotEmpty()) { "Enter a WB Stream room link" }
        var name = ""
        val body = if (value.startsWith("wbstream://multi/", true)) {
            val payload = value.substring("wbstream://multi/".length)
            name = decodeName(payload.substringAfter('#', ""))
            String(Base64.getUrlDecoder().decode(payload.substringBefore('#')), Charsets.UTF_8)
        } else value
        require(body.length <= MAX_INPUT) { "WB Stream link is too long" }
        val items = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(items.size in 1..MAX_ROOMS) { "Use between 1 and $MAX_ROOMS WB Stream rooms" }
        val links = items.map { item ->
            if (name.isEmpty()) name = decodeName(item.substringAfter('#', ""))
            val raw = item.substringBefore('#')
            val id = if (raw.startsWith("wbstream://", true)) {
                raw.substring("wbstream://".length).trimEnd('/')
            } else {
                val u = try { URI(raw) } catch (_: Exception) { throw IllegalArgumentException("Invalid WB Stream room link") }
                require(u.scheme.equals("https", true) && u.host.equals("stream.wb.ru", true) &&
                    u.rawUserInfo == null && (u.port == -1 || u.port == 443)) { "Use an HTTPS room link from stream.wb.ru" }
                val path = u.rawPath.orEmpty().trimEnd('/')
                require(path.startsWith("/room/")) { "WB Stream room ID is missing" }
                path.removePrefix("/room/")
            }
            require(roomId.matches(id)) { "Invalid WB Stream room ID" }
            PREFIX + id
        }.distinct()
        return Rooms(links, name)
    }

    fun encode(address: String, name: String): String {
        val links = parse(address).links
        val payload = if (links.size == 1) links.single().removePrefix(PREFIX) else
            "multi/" + Base64.getUrlEncoder().withoutPadding().encodeToString(links.joinToString("\n").toByteArray(Charsets.UTF_8))
        return "wbstream://$payload#${URLEncoder.encode(name.take(256), "UTF-8")}"
    }

    private fun decodeName(fragment: String): String =
        URLDecoder.decode(fragment, "UTF-8").filterNot { it.isISOControl() }.take(256)
}
