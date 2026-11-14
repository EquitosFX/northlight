package app.tvplayer.core.playlist

import java.io.Reader

enum class EntryKind { LIVE, MOVIE, SERIES }

/** What the `#EXTM3U` line at the top of a playlist says. */
data class PlaylistHeader(val guideUrls: List<String>)

/** One channel, film or episode from a playlist. */
data class PlaylistEntry(
    val name: String,
    val url: String,
    val kind: EntryKind,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logo: String? = null,
    val group: String? = null,
    val channelNumber: Int? = null,
    val catchupType: String? = null,
    val catchupDays: Int? = null,
    val catchupSource: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
)

/**
 * Streaming M3U / M3U8 playlist parser.
 *
 * Entries are handed to [onEntry] one at a time and never collected here, so a playlist with
 * 100,000+ channels can go straight into the database without being held in memory.
 * Malformed lines are skipped rather than failing the whole import.
 */
object M3uParser {

    /** Parses from [reader] and closes it. Returns the number of entries found. */
    fun parse(
        reader: Reader,
        onHeader: (PlaylistHeader) -> Unit = {},
        onEntry: (PlaylistEntry) -> Unit,
    ): Int = reader.buffered().useLines { lines -> parse(lines, onHeader, onEntry) }

    fun parse(
        lines: Sequence<String>,
        onHeader: (PlaylistHeader) -> Unit = {},
        onEntry: (PlaylistEntry) -> Unit,
    ): Int {
        var count = 0
        var pending: Pending? = null
        var firstLine = true

        for (raw in lines) {
            var line = raw
            if (firstLine) {
                line = line.removePrefix("﻿") // byte-order mark some editors add
                firstLine = false
            }
            line = line.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U") -> onHeader(parseHeader(line))
                line.startsWith("#EXTINF:") -> pending = parseExtInf(line)
                line.startsWith("#EXTGRP:") -> pending?.groupFallback = line.substring("#EXTGRP:".length).trim()
                line.startsWith("#EXTVLCOPT:") -> pending?.applyVlcOption(line.substring("#EXTVLCOPT:".length))
                line.startsWith("#") -> Unit // comments and directives we do not use
                else -> {
                    // A plain line is a stream address. Without an #EXTINF before it, name it after the address.
                    val info = pending ?: Pending(name = "", attrs = emptyMap())
                    pending = null
                    onEntry(info.toEntry(line))
                    count++
                }
            }
        }
        return count
    }

    private class Pending(val name: String, val attrs: Map<String, String>) {
        var groupFallback: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        fun applyVlcOption(option: String) {
            val key = option.substringBefore('=').trim().lowercase()
            val value = option.substringAfter('=', "").trim()
            if (value.isEmpty()) return
            when (key) {
                "http-user-agent" -> userAgent = value
                "http-referrer", "http-referer" -> referrer = value
            }
        }

        fun toEntry(addressLine: String): PlaylistEntry {
            // Some lists append request headers to the address: http://host/stream|User-Agent=X&Referer=Y
            val url = addressLine.substringBefore('|').trim()
            var agent = userAgent
            var referer = referrer
            if ('|' in addressLine) {
                for (pair in addressLine.substringAfter('|').split('&')) {
                    val value = pair.substringAfter('=', "").trim()
                    if (value.isEmpty()) continue
                    when (pair.substringBefore('=').trim().lowercase()) {
                        "user-agent" -> agent = value
                        "referer", "referrer" -> referer = value
                    }
                }
            }

            val displayName = name.ifBlank { attr("tvg-name") ?: url.substringBefore('?').substringAfterLast('/') }
            return PlaylistEntry(
                name = displayName,
                url = url,
                kind = inferKind(url),
                tvgId = attr("tvg-id"),
                tvgName = attr("tvg-name"),
                logo = attr("tvg-logo") ?: attr("logo"),
                group = attr("group-title") ?: groupFallback?.takeIf { it.isNotBlank() },
                channelNumber = (attr("tvg-chno") ?: attr("channel-number"))?.toIntOrNull(),
                catchupType = attr("catchup") ?: attr("catchup-type"),
                catchupDays = (attr("catchup-days") ?: attr("timeshift"))?.toIntOrNull(),
                catchupSource = attr("catchup-source"),
                userAgent = agent,
                referrer = referer,
            )
        }

        private fun attr(key: String): String? = attrs[key]?.takeIf { it.isNotBlank() }
    }

    private fun parseHeader(line: String): PlaylistHeader {
        val (attrs, _) = scanAttributes(line, "#EXTM3U".length)
        val urls = (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]).orEmpty()
        return PlaylistHeader(urls.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }

    private fun parseExtInf(line: String): Pending {
        var i = "#EXTINF:".length
        // Skip the duration (usually -1); attributes or the name follow it.
        while (i < line.length && line[i] != ' ' && line[i] != ',') i++
        val (attrs, name) = scanAttributes(line, i)
        return Pending(name = name, attrs = attrs)
    }

    /**
     * Reads `key="value"` pairs starting at [start]. A comma outside quotes ends the attributes;
     * everything after it is the display name. Quoted values may contain commas and spaces.
     */
    private fun scanAttributes(line: String, start: Int): Pair<Map<String, String>, String> {
        val attrs = LinkedHashMap<String, String>()
        val n = line.length
        var i = start
        var name = ""

        while (i < n) {
            val c = line[i]
            if (c == ' ' || c == '\t') {
                i++
                continue
            }
            if (c == ',') {
                name = line.substring(i + 1).trim()
                break
            }

            val keyStart = i
            while (i < n && line[i] != '=' && line[i] != ',' && line[i] != ' ' && line[i] != '\t') i++
            if (i >= n || line[i] != '=') continue // a stray word, not a key=value pair

            val key = line.substring(keyStart, i).lowercase()
            i++ // the '='
            val value: String
            if (i < n && line[i] == '"') {
                val end = line.indexOf('"', i + 1)
                if (end == -1) {
                    value = line.substring(i + 1)
                    i = n
                } else {
                    value = line.substring(i + 1, end)
                    i = end + 1
                }
            } else {
                val valueStart = i
                while (i < n && line[i] != ' ' && line[i] != '\t' && line[i] != ',') i++
                value = line.substring(valueStart, i)
            }
            if (key.isNotEmpty()) attrs[key] = value.trim()
        }
        return attrs to name
    }

    private val filmExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv")

    /** Playlists do not say what an entry is, so it is read from the shape of the address. */
    internal fun inferKind(url: String): EntryKind {
        val path = url.substringBefore('?').lowercase()
        return when {
            "/series/" in path -> EntryKind.SERIES
            "/movie/" in path || "/movies/" in path -> EntryKind.MOVIE
            filmExtensions.any { path.endsWith(it) } -> EntryKind.MOVIE
            else -> EntryKind.LIVE
        }
    }
}
