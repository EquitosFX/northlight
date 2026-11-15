package app.tvplayer.core.tmdb

import java.net.URLEncoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns an IPTV listing name into something TMDB can search for:
 * "EN - The Long Ferry (2021) [4K]" becomes "The Long Ferry" and 2021.
 */
object TitleCleaner {

    private const val CODE = "[A-Z0-9+]{2,5}(?:-[A-Z0-9+]{2,5})?"
    private val prefix = Regex("""^\s*(?:\[[^\]]{1,15}]|\|[^|]{1,15}\||$CODE\s*[:|]|$CODE\s+-\s)\s*""")
    private val bracketYear = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
    private val dashYear = Regex("""\s+-\s+((?:19|20)\d{2})\s*$""")
    private val bracketed = Regex("""[(\[][^)\]]*[)\]]""")
    private val tags = Regex(
        """(?i)\b(4k|uhd|fhd|hd|sd|2160p|1080p|720p|hevc|x265|x264|h\.?265|h\.?264|hdr|multi(?:-?sub)?|vostfr|dubbed)\b""",
    )

    fun clean(name: String): Pair<String, Int?> {
        var text = name.trim()
        repeat(2) { text = text.replaceFirst(prefix, "") }

        var year: Int? = null
        bracketYear.findAll(text).lastOrNull()?.let { year = it.groupValues[1].toInt() }
        if (year == null) {
            dashYear.find(text)?.let {
                year = it.groupValues[1].toInt()
                text = text.substring(0, it.range.first)
            }
        }
        // Everything from the first bracket on is tags or the year.
        val firstBracket = bracketed.find(text)
        if (firstBracket != null && firstBracket.range.first > 0) text = text.substring(0, firstBracket.range.first)
        text = text.replace(tags, " ")
        text = text.replace(Regex("""\s+"""), " ").trim().trim('-', '|', ':', '.', ' ')
        return (text.ifEmpty { name.trim() }) to year
    }
}

/** A search hit. */
data class TmdbResult(
    val id: Long,
    val title: String,
    val year: Int?,
    val popularity: Double,
)

/** What the details screens show. Image fields are full addresses. */
data class TmdbDetails(
    val id: Long,
    val title: String,
    val year: Int?,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val cast: List<String>,
    val seasons: Int?,
)

object TmdbUrls {
    private const val BASE = "https://api.themoviedb.org/3"
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    /** [type] is "movie" or "tv". */
    fun search(type: String, title: String, year: Int?): String {
        val yearParam = when {
            year == null -> ""
            type == "tv" -> "&first_air_date_year=$year"
            else -> "&year=$year"
        }
        return "$BASE/search/$type?query=${enc(title)}$yearParam&language=en-GB&include_adult=false"
    }

    fun details(type: String, id: Long): String = "$BASE/$type/$id?append_to_response=credits&language=en-GB"

    /** Cheap call used to check a key works. */
    fun check(): String = "$BASE/configuration?language=en-GB"

    fun image(path: String?, size: String): String? = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    /** TMDB issues two kinds of credential; the long read-access token goes in a header, the short key in the address. */
    fun usesBearer(key: String): Boolean = key.trim().startsWith("eyJ")

    fun withKey(url: String, key: String): String = if (usesBearer(key)) url else "$url&api_key=${enc(key.trim())}"
}

object TmdbParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseSearch(text: String): List<TmdbResult> {
        val results = root(text)["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.num("id")?.toLong() ?: return@mapNotNull null
            TmdbResult(
                id = id,
                title = item.str("title") ?: item.str("name") ?: return@mapNotNull null,
                year = yearOf(item.str("release_date") ?: item.str("first_air_date")),
                popularity = item.num("popularity") ?: 0.0,
            )
        }
    }

    /** Prefers an exact title match, then a matching year; otherwise TMDB's own order. */
    fun pickBest(results: List<TmdbResult>, title: String, year: Int?): TmdbResult? {
        if (results.isEmpty()) return null
        val wanted = normalise(title)
        return results.withIndex().maxWithOrNull(
            compareBy<IndexedValue<TmdbResult>> { (_, r) ->
                (if (normalise(r.title) == wanted) 2 else 0) + (if (year != null && r.year == year) 2 else 0)
            }.thenByDescending { it.index },
        )?.value
    }

    fun parseDetails(text: String): TmdbDetails {
        val item = root(text)
        val id = item.num("id")?.toLong() ?: throw SerializationException("No id")
        val cast = ((item["credits"] as? JsonObject)?.get("cast") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.str("name") }
            ?.take(6)
            .orEmpty()
        val runtime = item.num("runtime")?.toInt()
            ?: (item["episode_run_time"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        return TmdbDetails(
            id = id,
            title = item.str("title") ?: item.str("name") ?: "",
            year = yearOf(item.str("release_date") ?: item.str("first_air_date")),
            overview = item.str("overview"),
            poster = TmdbUrls.image(item.str("poster_path"), "w342"),
            backdrop = TmdbUrls.image(item.str("backdrop_path"), "w1280"),
            rating = item.num("vote_average")?.takeIf { it > 0 },
            runtimeMinutes = runtime?.takeIf { it > 0 },
            genres = (item["genres"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") }.orEmpty(),
            cast = cast,
            seasons = item.num("number_of_seasons")?.toInt(),
        )
    }

    private fun root(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw SerializationException("Not a TMDB answer")

    private fun normalise(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

    private fun JsonObject.str(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.num(key: String): Double? = str(key)?.toDoubleOrNull()
}
