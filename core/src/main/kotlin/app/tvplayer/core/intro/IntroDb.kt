package app.tvplayer.core.intro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A stretch of a film or episode. A null start means the beginning; a null end means the end. */
data class Segment(val startMs: Long?, val endMs: Long?) {
    fun start(): Long = startMs ?: 0L
    fun end(durationMs: Long): Long = endMs ?: durationMs
    fun contains(positionMs: Long, durationMs: Long): Boolean = positionMs >= start() && positionMs < end(durationMs)
}

/** The skippable parts TheIntroDB knows for one title. */
data class IntroSegments(
    val intro: List<Segment> = emptyList(),
    val recap: List<Segment> = emptyList(),
    val credits: List<Segment> = emptyList(),
    val preview: List<Segment> = emptyList(),
) {
    val isEmpty: Boolean get() = intro.isEmpty() && recap.isEmpty() && credits.isEmpty() && preview.isEmpty()
}

/** What the "Skip" button offers at a moment of playback. */
data class SkipOffer(val label: String, val toMs: Long)

/**
 * TheIntroDB (theintrodb.org): community-timed intros, recaps and credits, looked up by TMDB id.
 * No key is needed to read.
 */
object IntroDb {
    private const val BASE = "https://api.theintrodb.org/v4/media"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Films pass just the TMDB id; episodes add the season and episode. */
    fun url(tmdbId: Long, season: Int? = null, episode: Int? = null): String = buildString {
        append(BASE).append("?tmdb_id=").append(tmdbId)
        if (season != null && episode != null) append("&season=").append(season).append("&episode=").append(episode)
    }

    fun parse(text: String): IntroSegments {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return IntroSegments()
        // Some answers wrap the segments in a "segments" or "data" object.
        val body = (root["segments"] as? JsonObject) ?: (root["data"] as? JsonObject) ?: root
        fun read(key: String): List<Segment> = when (val value = body[key]) {
            is JsonArray -> value.mapNotNull { segment(it as? JsonObject) }
            is JsonObject -> listOfNotNull(segment(value))
            else -> emptyList()
        }
        return IntroSegments(read("intro"), read("recap"), read("credits"), read("preview"))
    }

    private fun segment(item: JsonObject?): Segment? {
        item ?: return null
        val start = millis(item, "start_ms", "start")
        val end = millis(item, "end_ms", "end")
        if (start == null && end == null) return null
        if (start != null && end != null && end <= start) return null
        return Segment(start, end)
    }

    /** Reads milliseconds from [msKey], or seconds from [secondsKey]. */
    private fun millis(item: JsonObject, msKey: String, secondsKey: String): Long? {
        (item[msKey] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { return it.toLong() }
        (item[secondsKey] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        return null
    }

    /** The skip on offer at [positionMs]: past an intro or a recap. Credits are left to Up next. */
    fun offer(segments: IntroSegments, positionMs: Long, durationMs: Long): SkipOffer? {
        val total = if (durationMs > 0) durationMs else Long.MAX_VALUE
        segments.recap.firstOrNull { it.contains(positionMs, total) }?.let { return SkipOffer("Skip recap", it.end(total)) }
        segments.intro.firstOrNull { it.contains(positionMs, total) }?.let { return SkipOffer("Skip intro", it.end(total)) }
        return null
    }

    /**
     * When the credits begin, for showing Up next: TheIntroDB's credits when known (the last ones
     * that start in the second half), otherwise [fallbackMs] before the end. Null while the length is unknown.
     */
    fun creditsStart(segments: IntroSegments, durationMs: Long, fallbackMs: Long = 30_000): Long? {
        if (durationMs <= 0) return null
        val known = segments.credits.filter { it.start() > durationMs / 2 }.maxOfOrNull { it.start() }
        return known ?: (durationMs - fallbackMs).coerceAtLeast(0)
    }
}
