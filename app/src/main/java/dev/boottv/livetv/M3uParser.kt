package dev.boottv.livetv

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest

/** Raw playlist entry before ids are assigned. */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val groups: List<String>,
    val number: String?,
    val userAgent: String?,
    val headers: Map<String, String>,
)

data class M3uPlaylist(val entries: List<M3uEntry>, val epgUrls: List<String>)

/**
 * Streaming, forgiving M3U / M3U8 (IPTV "extended M3U") parser. Reads line by line, so large
 * playlists never need to be held in memory as one string.
 */
object M3uParser {
    private const val MAX_ENTRIES = 20_000

    fun parse(input: InputStream): M3uPlaylist {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        val entries = ArrayList<M3uEntry>()
        val epgUrls = ArrayList<String>()

        var attrs: Map<String, String> = emptyMap()
        var title: String? = null
        var extGroup: String? = null
        var userAgent: String? = null
        val headers = LinkedHashMap<String, String>()
        var first = true

        fun reset() {
            attrs = emptyMap(); title = null; extGroup = null; userAgent = null; headers.clear()
        }

        while (true) {
            var line = reader.readLine() ?: break
            if (first) {
                line = line.removePrefix("﻿"); first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val a = parseAttributes(line.substring(7))
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { k ->
                        a[k]?.split(',')?.map { it.trim() }?.filter { it.startsWith("http") }?.let { epgUrls.addAll(it) }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    reset()
                    val body = line.substringAfter(':', "")
                    val comma = findTitleComma(body)
                    val attrPart = if (comma >= 0) body.substring(0, comma) else body
                    title = if (comma >= 0) body.substring(comma + 1).trim() else null
                    attrs = parseAttributes(attrPart)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11).trim()
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) when (key) {
                        "http-user-agent" -> userAgent = value
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                        "http-origin" -> headers["Origin"] = value
                    }
                }
                line.startsWith("#") -> Unit // other directives / comments
                else -> {
                    // A URL line. Support Kodi-style "url|User-Agent=x&Referer=y" headers.
                    var url = line
                    val pipe = line.indexOf('|')
                    if (pipe > 0) {
                        url = line.substring(0, pipe)
                        line.substring(pipe + 1).split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim()
                            val v = java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8").trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) {
                                if (k.equals("User-Agent", true)) userAgent = v else headers[normalizeHeader(k)] = v
                            }
                        }
                    }
                    if (url.contains("://")) {
                        val tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                        val name = title?.takeIf { it.isNotBlank() } ?: tvgName ?: url.substringAfterLast('/')
                        val groupRaw = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup
                        val groups = groupRaw?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                        val number = (attrs["tvg-chno"] ?: attrs["channel-number"] ?: attrs["tvg-num"] ?: attrs["channel-id"]
                            ?.takeIf { it.all(Char::isDigit) })?.trim()?.takeIf { it.isNotEmpty() }
                        entries.add(
                            M3uEntry(
                                name = name,
                                url = url,
                                tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() },
                                tvgName = tvgName,
                                logo = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotEmpty() },
                                groups = groups,
                                number = number,
                                userAgent = userAgent ?: attrs["user-agent"] ?: attrs["http-user-agent"],
                                headers = HashMap(headers).also { h ->
                                    attrs["http-referrer"]?.let { h.putIfAbsent("Referer", it) }
                                },
                            )
                        )
                        if (entries.size >= MAX_ENTRIES) break
                    }
                    reset()
                }
            }
        }
        return M3uPlaylist(entries, epgUrls)
    }

    private fun normalizeHeader(k: String) = when (k.lowercase()) {
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> k
    }

    /** Index of the comma separating attributes from the display title, skipping quoted values. */
    private fun findTitleComma(s: String): Int {
        var inQuote = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return i
        }
        return -1
    }

    /** Parses key="value" / key=value pairs; keys lower-cased. */
    fun parseAttributes(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i].isWhitespace())) i++
            val keyStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
            if (i >= n || s[i] != '=') { // bare token (e.g. duration "-1")
                while (i < n && !s[i].isWhitespace()) i++
                continue
            }
            val key = s.substring(keyStart, i).lowercase()
            i++ // '='
            val value: String
            if (i < n && (s[i] == '"' || s[i] == '\'')) {
                val q = s[i]; i++
                val vs = i
                while (i < n && s[i] != q) i++
                value = s.substring(vs, minOf(i, n))
                i++
            } else {
                val vs = i
                while (i < n && !s[i].isWhitespace()) i++
                value = s.substring(vs, i)
            }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }
}

internal fun shortHash(s: String): String {
    val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
    return d.take(6).joinToString("") { "%02x".format(it) }
}
