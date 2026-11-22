package app.tvplayer.core.extras

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder

/** A series' next episode, as TVmaze lists it. */
data class NextEpisode(val season: Int?, val number: Int?, val name: String?, val airsAt: Long) {
    /** "S3 E2". */
    val code: String get() = listOfNotNull(season?.let { "S$it" }, number?.let { "E$it" }).joinToString(" ")
}

/** TVmaze (free, no key): when a series' next episode airs. */
object TvMaze {
    private val json = Json { ignoreUnknownKeys = true }
    private const val BASE = "https://api.tvmaze.com"

    /** The show by its IMDb id, with its next episode. */
    fun byImdb(imdbId: String) = "$BASE/lookup/shows?imdb=${URLEncoder.encode(imdbId, "UTF-8")}"

    /** The best match for a name, with its next episode. */
    fun byName(name: String) = "$BASE/singlesearch/shows?q=${URLEncoder.encode(name, "UTF-8")}&embed=nextepisode"

    fun withNextEpisode(showId: Long) = "$BASE/shows/$showId?embed=nextepisode"

    /** The show's TVmaze id in an answer. */
    fun showId(text: String): Long? = runCatching {
        ((json.parseToJsonElement(text) as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }.getOrNull()

    /** The next episode in an answer that embeds it; null when none is announced. */
    fun nextEpisode(text: String): NextEpisode? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        val next = ((root["_embedded"] as? JsonObject)?.get("nextepisode") as? JsonObject) ?: return null
        val airs = (next["airstamp"] as? JsonPrimitive)?.contentOrNull?.let { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            ?: return null
        NextEpisode(
            season = (next["season"] as? JsonPrimitive)?.intOrNull,
            number = (next["number"] as? JsonPrimitive)?.intOrNull,
            name = (next["name"] as? JsonPrimitive)?.contentOrNull,
            airsAt = airs,
        )
    }.getOrNull()
}
