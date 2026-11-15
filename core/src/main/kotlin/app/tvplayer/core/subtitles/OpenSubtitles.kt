package app.tvplayer.core.subtitles

import java.net.URLEncoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One subtitle file OpenSubtitles offers for a film or episode. */
data class SubtitleResult(
    val fileId: Long,
    val language: String,
    val release: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
)

class SubtitlesException(message: String) : Exception(message)

/** What to look for: a film by TMDB id, an episode by its series' TMDB id, or by name. */
data class SubtitleQuery(
    val tmdbId: Long? = null,
    val seriesTmdbId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val year: Int? = null,
)

/** Builds requests for, and reads replies from, the OpenSubtitles.com REST API. */
object OpenSubtitles {
    const val BASE = "https://api.opensubtitles.com/api/v1"
    const val USER_AGENT = "Skyfall v1.0"

    /** Languages offered in Settings, as OpenSubtitles codes with their names. */
    val LANGUAGES = listOf(
        "en" to "English", "fr" to "French", "de" to "German", "es" to "Spanish", "it" to "Italian",
        "pt-PT" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "ro" to "Romanian", "tr" to "Turkish",
        "ar" to "Arabic", "el" to "Greek",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun searchUrl(query: SubtitleQuery, language: String): String {
        val params = LinkedHashMap<String, String>()
        when {
            query.seriesTmdbId != null && query.season != null && query.episode != null -> {
                params["episode_number"] = query.episode.toString()
                params["parent_tmdb_id"] = query.seriesTmdbId.toString()
                params["season_number"] = query.season.toString()
            }
            query.tmdbId != null -> params["tmdb_id"] = query.tmdbId.toString()
            !query.title.isNullOrBlank() -> {
                params["query"] = query.title.trim().lowercase()
                if (query.season != null && query.episode != null) {
                    params["season_number"] = query.season.toString()
                    params["episode_number"] = query.episode.toString()
                } else if (query.year != null) {
                    params["year"] = query.year.toString()
                }
            }
            else -> throw SubtitlesException("Not enough is known about this title to search for subtitles.")
        }
        params["languages"] = language.lowercase()
        // OpenSubtitles asks for parameters in alphabetical order (it serves them from a cache).
        return BASE + "/subtitles?" + params.toSortedMap().entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        }
    }

    fun loginBody(username: String, password: String): String =
        buildJsonObject { put("username", username); put("password", password) }.toString()

    fun downloadBody(fileId: Long): String = buildJsonObject { put("file_id", fileId) }.toString()

    private fun obj(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw SubtitlesException("OpenSubtitles sent a reply the app couldn't read.")

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    fun parseSearch(text: String): List<SubtitleResult> {
        val data = obj(text)["data"] as? JsonArray ?: return emptyList()
        return data.filterIsInstance<JsonObject>().mapNotNull { item ->
            val a = item["attributes"] as? JsonObject ?: return@mapNotNull null
            val file = (a["files"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull() ?: return@mapNotNull null
            val id = (file["file_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            SubtitleResult(
                fileId = id,
                language = a.str("language").orEmpty(),
                release = a.str("release")?.takeIf { it.isNotBlank() } ?: file.str("file_name").orEmpty(),
                downloads = (a["download_count"] as? JsonPrimitive)?.intOrNull ?: 0,
                hearingImpaired = (a["hearing_impaired"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }.sortedWith(compareBy<SubtitleResult> { it.hearingImpaired }.thenByDescending { it.downloads })
    }

    fun parseToken(text: String): String =
        obj(text).str("token")?.takeIf { it.isNotBlank() } ?: throw SubtitlesException("OpenSubtitles didn't accept that username and password.")

    fun parseDownloadLink(text: String): String {
        val o = obj(text)
        return o.str("link")?.takeIf { it.startsWith("https://") }
            ?: throw SubtitlesException(o.str("message") ?: "OpenSubtitles didn't give a download link.")
    }

    /** A friendly explanation for an OpenSubtitles error code. */
    fun explain(code: Int): String = when (code) {
        401 -> "OpenSubtitles didn't accept the API key or sign-in. Check them in Settings › Player."
        403 -> "OpenSubtitles refused the request. Check the API key in Settings › Player."
        406, 429 -> "You've reached today's OpenSubtitles download limit. Signing in to a free account raises it."
        in 500..599 -> "OpenSubtitles is having problems. Try again later."
        else -> "OpenSubtitles replied with an error ($code)."
    }

    /** Season and episode from titles like "Show · S1 E3" or "Show S01E03". */
    fun seasonEpisode(title: String): Pair<Int, Int>? {
        val m = Regex("""[Ss](\d{1,2})\s*[Ee](\d{1,3})""").find(title) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}
