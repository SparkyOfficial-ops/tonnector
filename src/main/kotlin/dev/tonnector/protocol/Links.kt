package dev.tonnector.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ConnectQuery(
    val version: String,
    val clientId: String,
    val requestJson: String,
    val ret: String?,
    val raw: String,
)

fun parseConnectLink(link: String): Map<String, String> {
    val trimmed = link.trim()
    val query = when {
        trimmed.startsWith("{") -> return emptyMap()
        "?" in trimmed -> trimmed.substringAfter("?")
        trimmed.contains("=") && !trimmed.contains("://") -> trimmed
        else -> trimmed.substringAfter("?", "")
    }.substringBefore("#")
    if (query.isBlank()) return emptyMap()
    return query.split("&").mapNotNull { part ->
        if (part.isBlank()) return@mapNotNull null
        val idx = part.indexOf('=')
        if (idx < 0) part.urlDecode() to ""
        else part.substring(0, idx).urlDecode() to part.substring(idx + 1).urlDecode()
    }.toMap()
}

private fun String.urlDecode(): String =
    URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8)

fun domainOf(url: String): String = try {
    val uri = URI(url)
    val host = uri.host ?: return url
    if (uri.port == -1) host else "$host:${uri.port}"
} catch (_: Exception) {
    url
}

fun jsonObject(element: JsonElement?): JsonObject? = element as? JsonObject

fun jsonString(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

fun JsonObject.string(name: String): String? = jsonString(this[name])

fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray
