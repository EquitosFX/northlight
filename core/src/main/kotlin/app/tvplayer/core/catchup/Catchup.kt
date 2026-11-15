package app.tvplayer.core.catchup

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A past programme to watch again: when it ran (UTC milliseconds). */
data class CatchupWindow(val startMs: Long, val endMs: Long, val nowMs: Long)

class CatchupException(message: String) : Exception(message)

/**
 * Builds the address for watching a past programme again on a channel whose provider keeps an archive
 * ("catch-up"). Xtream Codes servers use one address shape; M3U playlists say which style they use
 * with a catchup attribute and, usually, a catchup-source template.
 */
object Catchup {

    /**
     * @param liveUrl the channel's live address.
     * @param type the playlist's catchup style: xc, default, append, shift (timeshift) or flussonic.
     * @param source the playlist's catchup-source template, if any.
     * @param serverZone the time zone the server works in (Xtream Codes addresses use its local time).
     */
    fun url(liveUrl: String, type: String?, source: String?, window: CatchupWindow, serverZone: ZoneId = ZoneOffset.UTC): String {
        val style = (type ?: if (source.isNullOrBlank()) "xc" else if (source.startsWith("http", true)) "default" else "append").lowercase()
        return when (style) {
            "xc", "xtream" -> xtream(liveUrl, window, serverZone)
            "default" -> fill(source ?: throw CatchupException("This channel's catch-up address is missing."), window)
            "append" -> liveUrl + fill(source.orEmpty(), window)
            "shift", "timeshift" -> {
                val start = window.startMs / 1000
                liveUrl + (if ('?' in liveUrl) "&" else "?") + "utc=$start&lutc=${window.nowMs / 1000}"
            }
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl, window)
            else -> throw CatchupException("This channel uses a kind of catch-up Skyfall doesn't know (\"$style\").")
        }
    }

    /** Xtream Codes: {server}/timeshift/{user}/{pass}/{minutes}/{yyyy-MM-dd:HH-mm}/{id}.ts, in the server's time. */
    private fun xtream(liveUrl: String, window: CatchupWindow, zone: ZoneId): String {
        val uri = URI(liveUrl)
        val parts = uri.path.trim('/').split('/').filter { it.isNotEmpty() }.let { if (it.firstOrNull() == "live") it.drop(1) else it }
        if (parts.size < 3) throw CatchupException("This channel's address doesn't look like an Xtream Codes one.")
        val (user, pass) = parts[parts.size - 3] to parts[parts.size - 2]
        val id = parts.last().substringBefore('.')
        val base = "${uri.scheme}://${uri.rawAuthority}"
        val minutes = ((window.endMs - window.startMs + 59_999) / 60_000).coerceAtLeast(1)
        val start = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.US).format(Instant.ofEpochMilli(window.startMs).atZone(zone))
        return "$base/timeshift/$user/$pass/$minutes/$start/$id.ts"
    }

    /** Flussonic: .../index.m3u8 becomes .../timeshift_abs-{start}.m3u8; MPEG-TS becomes timeshift_abs_mpegts-{start}.ts. */
    private fun flussonic(liveUrl: String, window: CatchupWindow): String {
        val start = window.startMs / 1000
        val base = liveUrl.substringBefore('?')
        val query = liveUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val dir = base.substringBeforeLast('/')
        val file = base.substringAfterLast('/')
        return if (file.endsWith(".m3u8")) "$dir/timeshift_abs-$start.m3u8$query" else "$dir/timeshift_abs_mpegts-$start.ts$query"
    }

    private val placeholder = Regex("""\$?\{([a-zA-Z_]+)(?::([^}]*))?}""")

    /** Fills a catchup-source template: {utc}/{start}, {utcend}/{end}, {lutc}/{now}, {duration}, {offset}, {Y}{m}{d}{H}{M}{S}. */
    fun fill(template: String, window: CatchupWindow): String {
        val start = window.startMs / 1000
        val end = window.endMs / 1000
        val now = window.nowMs / 1000
        val utcStart = Instant.ofEpochMilli(window.startMs).atZone(ZoneOffset.UTC)
        return placeholder.replace(template) { m ->
            val name = m.groupValues[1]
            val arg = m.groupValues[2]
            fun divided(seconds: Long) = (seconds / (arg.toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString()
            when (name.lowercase()) {
                "utc", "start", "timestamp" -> if (arg.isNotEmpty() && arg.toLongOrNull() == null) format(utcStart, arg) else start.toString()
                "utcend", "end" -> end.toString()
                "lutc", "now" -> now.toString()
                "duration" -> divided(end - start)
                "offset" -> divided(now - start)
                else -> when (name) {
                    "Y" -> "%04d".format(utcStart.year)
                    "m" -> "%02d".format(utcStart.monthValue)
                    "d" -> "%02d".format(utcStart.dayOfMonth)
                    "H" -> "%02d".format(utcStart.hour)
                    "M" -> "%02d".format(utcStart.minute)
                    "S" -> "%02d".format(utcStart.second)
                    else -> m.value
                }
            }
        }
    }

    /** "YmdHMS"-style formats used inside {utc:...}. */
    private fun format(time: java.time.ZonedDateTime, pattern: String): String = buildString {
        for (c in pattern) {
            append(
                when (c) {
                    'Y' -> "%04d".format(time.year)
                    'm' -> "%02d".format(time.monthValue)
                    'd' -> "%02d".format(time.dayOfMonth)
                    'H' -> "%02d".format(time.hour)
                    'M' -> "%02d".format(time.minute)
                    'S' -> "%02d".format(time.second)
                    else -> c.toString()
                },
            )
        }
    }

    /** Whether a programme that ran from [startMs] to [endMs] can be watched again on a channel keeping [days] of catch-up. */
    fun available(days: Int?, startMs: Long, endMs: Long, nowMs: Long): Boolean =
        days != null && days > 0 && endMs <= nowMs + 60_000 && startMs < nowMs && startMs >= nowMs - days * 86_400_000L
}
