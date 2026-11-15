package app.tvplayer.core.xtream

import java.io.InputStream
import java.net.URLEncoder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence

/** Raised when an Xtream Codes server answers with something that cannot be used. */
class XtreamException(message: String) : Exception(message)

/** The three things a user types to add an Xtream Codes account. */
data class XtreamAccount(val server: String, val username: String, val password: String) {

    /** The server address tidied up: scheme added if missing, pasted page names and trailing slashes removed. */
    val baseUrl: String = run {
        var address = server.trim()
        if (!address.startsWith("http://", ignoreCase = true) && !address.startsWith("https://", ignoreCase = true)) {
            address = "http://$address"
        }
        address = address.substringBefore('?')
        for (page in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
            if (address.endsWith(page, ignoreCase = true)) address = address.dropLast(page.length)
        }
        address.trimEnd('/')
    }
}

/** What the server says about the account when logging in. */
data class XtreamAuth(
    val authorised: Boolean,
    val status: String? = null,
    val expiresAtSeconds: Long? = null,
    val maxConnections: Int? = null,
    val activeConnections: Int? = null,
    val isTrial: Boolean = false,
    val serverTimezone: String? = null,
    val message: String? = null,
)

data class XtreamCategory(val id: String, val name: String)

/** A live channel or a film. */
data class XtreamStream(
    val streamId: Long,
    val name: String,
    val icon: String? = null,
    val categoryId: String? = null,
    val number: Int? = null,
    val guideChannelId: String? = null,
    val catchupDays: Int? = null,
    val containerExtension: String? = null,
)

data class XtreamSeries(
    val seriesId: Long,
    val name: String,
    val cover: String? = null,
    val categoryId: String? = null,
)

/** One episode of a series, from `get_series_info`. Season and episode count from 1. */
data class XtreamEpisode(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String? = null,
    val plot: String? = null,
    val durationSecs: Int? = null,
    val image: String? = null,
)

data class XtreamSeriesInfo(val plot: String?, val episodes: List<XtreamEpisode>)

/** Builds the addresses an Xtream Codes server understands. */
object XtreamUrls {

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** The account endpoint. With no [action] it is the login check. */
    fun api(account: XtreamAccount, action: String? = null): String {
        val base = "${account.baseUrl}/player_api.php?username=${enc(account.username)}&password=${enc(account.password)}"
        return if (action == null) base else "$base&action=$action"
    }

    fun live(account: XtreamAccount, streamId: Long, extension: String = "ts"): String =
        "${account.baseUrl}/live/${account.username}/${account.password}/$streamId.$extension"

    fun movie(account: XtreamAccount, streamId: Long, extension: String?): String =
        "${account.baseUrl}/movie/${account.username}/${account.password}/$streamId.${extension ?: "mp4"}"

    /** Series are listed without episodes; this address stands for the series until its episodes are fetched. */
    fun series(account: XtreamAccount, seriesId: Long): String =
        "${account.baseUrl}/series/${account.username}/${account.password}/$seriesId"

    fun episode(account: XtreamAccount, episodeId: Long, extension: String?): String =
        "${account.baseUrl}/series/${account.username}/${account.password}/$episodeId.${extension ?: "mp4"}"

    /** The full TV guide for the account, in XMLTV form. */
    fun guide(account: XtreamAccount): String =
        "${account.baseUrl}/xmltv.php?username=${enc(account.username)}&password=${enc(account.password)}"
}

/**
 * Reads Xtream Codes answers. Servers are inconsistent (numbers arrive as text, fields go missing),
 * so every field is read leniently. Lists are read one item at a time because a large provider's
 * channel list can be tens of megabytes.
 */
object XtreamParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseAuth(text: String): XtreamAuth {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw XtreamException("The server did not answer like an Xtream Codes server.")

        val user = root["user_info"] as? JsonObject
            ?: throw XtreamException("The server did not answer like an Xtream Codes server.")
        val server = root["server_info"] as? JsonObject

        return XtreamAuth(
            authorised = user.long("auth") == 1L,
            status = user.str("status"),
            expiresAtSeconds = user.long("exp_date"),
            maxConnections = user.long("max_connections")?.toInt(),
            activeConnections = user.long("active_cons")?.toInt(),
            isTrial = user.long("is_trial") == 1L,
            serverTimezone = server?.str("timezone"),
            message = user.str("message"),
        )
    }

    fun parseCategories(input: InputStream, onItem: (XtreamCategory) -> Unit): Int =
        forEachObject(input) { item ->
            val id = item.str("category_id") ?: return@forEachObject false
            onItem(XtreamCategory(id = id, name = item.str("category_name") ?: id))
            true
        }

    /** Reads the answer to `get_live_streams` or `get_vod_streams`. */
    fun parseStreams(input: InputStream, onItem: (XtreamStream) -> Unit): Int =
        forEachObject(input) { item ->
            val id = item.long("stream_id") ?: return@forEachObject false
            val hasArchive = item.long("tv_archive") == 1L
            onItem(
                XtreamStream(
                    streamId = id,
                    name = item.str("name") ?: "Stream $id",
                    icon = item.str("stream_icon"),
                    categoryId = item.str("category_id"),
                    number = item.long("num")?.toInt(),
                    guideChannelId = item.str("epg_channel_id"),
                    catchupDays = if (hasArchive) item.long("tv_archive_duration")?.toInt() else null,
                    containerExtension = item.str("container_extension"),
                ),
            )
            true
        }

    /** Reads the answer to `get_series`. */
    fun parseSeries(input: InputStream, onItem: (XtreamSeries) -> Unit): Int =
        forEachObject(input) { item ->
            val id = item.long("series_id") ?: return@forEachObject false
            onItem(
                XtreamSeries(
                    seriesId = id,
                    name = item.str("name") ?: "Series $id",
                    cover = item.str("cover"),
                    categoryId = item.str("category_id"),
                ),
            )
            true
        }

    /**
     * Reads the answer to `get_series_info`. Servers send the episodes either as an object keyed by
     * season number or as a list of lists; both are accepted. Episodes come back in season order.
     */
    fun parseSeriesInfo(text: String): XtreamSeriesInfo {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw XtreamException("The server didn't send this series' episodes.")

        val groups: List<Pair<String?, JsonElement>> = when (val episodes = root["episodes"]) {
            is JsonObject -> episodes.entries.map { (season, list) -> season to list }
            is JsonArray -> episodes.map { null to it }
            else -> emptyList()
        }
        val found = ArrayList<XtreamEpisode>()
        for ((seasonKey, list) in groups) {
            // Usually a list per season; some panels send a numbered object instead ({"0": {...}, "1": {...}}).
            val items: List<JsonElement> = when (list) {
                is JsonArray -> list
                is JsonObject -> list.values.toList()
                else -> continue
            }
            for (element in items) {
                val item = element as? JsonObject ?: continue
                val id = item.long("id") ?: continue
                val info = item["info"] as? JsonObject
                val season = item.long("season")?.toInt() ?: seasonKey?.toIntOrNull() ?: 1
                found.add(
                    XtreamEpisode(
                        id = id,
                        season = season,
                        number = item.long("episode_num")?.toInt() ?: (found.count { it.season == season } + 1),
                        title = item.str("title") ?: "Episode",
                        extension = item.str("container_extension"),
                        plot = info?.str("plot"),
                        durationSecs = info?.long("duration_secs")?.toInt(),
                        image = info?.str("movie_image"),
                    ),
                )
            }
        }
        val plot = (root["info"] as? JsonObject)?.str("plot")
        return XtreamSeriesInfo(plot, found.sortedWith(compareBy({ it.season }, { it.number })))
    }

    /** Calls [block] for each object in a JSON list and returns how many it accepted. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun forEachObject(input: InputStream, block: (JsonObject) -> Boolean): Int {
        var accepted = 0
        try {
            val items = json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.AUTO_DETECT)
            for (item in items) {
                // Anything other than objects means this is not a list of streams (an error page, say).
                if (item !is JsonObject) throw XtreamException("The server sent a list that could not be read.")
                if (block(item)) accepted++
            }
        } catch (e: SerializationException) {
            throw XtreamException("The server sent a list that could not be read.")
        }
        return accepted
    }

    private fun JsonObject.str(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.long(key: String): Long? {
        val text = str(key) ?: return null
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
    }
}
