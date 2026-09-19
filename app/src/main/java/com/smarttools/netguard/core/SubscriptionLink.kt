package com.smarttools.netguard.core

import java.net.URLDecoder

/** Decode wrappers only; never lowercase the credential-bearing path or query. */
object SubscriptionLink {
    fun unwrap(input: String): String {
        var value = input.trim().removePrefix("\uFEFF")
        repeat(3) {
            val prefix = listOf("happ://add/", "happ://add-sub/", "v2rayng://install-config?url=")
                .firstOrNull { value.startsWith(it, ignoreCase = true) }
            if (prefix != null) {
                value = value.substring(prefix.length)
                if (!value.startsWith("https://", true) && !value.contains("://")) {
                    value = URLDecoder.decode(value, "UTF-8")
                }
            } else if (value.startsWith("happ://add?", true)) {
                val param = value.substringAfter('?').split('&').firstOrNull { it.startsWith("url=") }
                    ?: throw IllegalArgumentException("Happ link has no subscription URL")
                value = URLDecoder.decode(param.substringAfter('='), "UTF-8")
            } else return value
        }
        return value
    }
}
