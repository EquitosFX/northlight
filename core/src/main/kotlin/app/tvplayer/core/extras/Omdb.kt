package app.tvplayer.core.extras

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** IMDb, Rotten Tomatoes and Metacritic scores, as OMDb gives them ("8.1", "92%", "76"). */
data class Ratings(val imdb: String?, val rottenTomatoes: String?, val metacritic: String?) {
    val isEmpty: Boolean get() = imdb == null && rottenTomatoes == null && metacritic == null

    /** "IMDb 8.1 · RT 92% · MC 76" (only those known). */
    val line: String
        get() = listOfNotNull(imdb?.let { "IMDb $it" }, rottenTomatoes?.let { "RT $it" }, metacritic?.let { "MC $it" }).joinToString(" · ")

    /** Kept as "imdb|rt|mc". */
    fun encode(): String = listOf(imdb, rottenTomatoes, metacritic).joinToString("|") { it.orEmpty() }

    companion object {
        fun decode(text: String): Ratings {
            val parts = text.split("|") + listOf("", "", "")
            return Ratings(parts[0].ifEmpty { null }, parts[1].ifEmpty { null }, parts[2].ifEmpty { null })
        }
    }
}

/** OMDb (omdbapi.com, a free key): ratings for a film or series by its IMDb id. */
object Omdb {
    private val json = Json { ignoreUnknownKeys = true }

    fun url(imdbId: String, key: String) = "https://www.omdbapi.com/?i=${imdbId.trim()}&apikey=${java.net.URLEncoder.encode(key.trim(), "UTF-8")}"

    /** A title everyone has (The Shawshank Redemption), for checking a key. */
    const val TEST_ID = "tt0111161"

    /** The ratings in an OMDb answer; null when OMDb says it doesn't know the title (or the key is wrong). */
    fun parse(text: String): Ratings? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        if (root.text("Response").equals("False", ignoreCase = true)) return null
        val sources = (root["Ratings"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .associate { it.text("Source").orEmpty() to it.text("Value").orEmpty() }
        Ratings(
            imdb = root.text("imdbRating")?.takeIf { it != "N/A" && it.isNotBlank() },
            rottenTomatoes = sources["Rotten Tomatoes"]?.takeIf { it.isNotBlank() },
            metacritic = (sources["Metacritic"]?.substringBefore("/") ?: root.text("Metascore"))?.takeIf { it != "N/A" && it.isNotBlank() },
        )
    }.getOrNull()

    /** OMDb's reason when it turns a request down ("Invalid API key!"), if it did. */
    fun error(text: String): String? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        if (root.text("Response").equals("False", ignoreCase = true)) root.text("Error") else null
    }.getOrNull()

    /** The IMDb id in TMDB's external ids answer ("tt0111161"). */
    fun imdbIdFromTmdb(text: String): String? = runCatching {
        (json.parseToJsonElement(text) as? JsonObject)?.text("imdb_id")?.takeIf { it.startsWith("tt") }
    }.getOrNull()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
