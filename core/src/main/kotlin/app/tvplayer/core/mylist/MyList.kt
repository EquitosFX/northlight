package app.tvplayer.core.mylist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A programme the viewer wants to be told about when it starts. */
data class Reminder(
    /** The line-up key of the channel ("playlistId|variantKey"), so it survives playlist refreshes. */
    val channelKey: String,
    val channelName: String,
    val channelLogo: String?,
    val title: String,
    val start: Long,
    val stop: Long,
    val description: String? = null,
) {
    val id: String get() = "$channelKey|$start"
}

/** A film or series saved to watch later. */
data class WatchItem(
    /** "movie" or "series". */
    val kind: String,
    val name: String,
    val poster: String?,
    /** The stream address, used to find the title again after a playlist refresh. */
    val url: String,
    val addedAt: Long,
) {
    val key: String get() = "$kind|${name.trim().lowercase()}"
}

object Reminders {
    /** How long before the start the pop-up appears. */
    const val LEAD_MILLIS = 60_000L

    /** Reminders whose programme is starting (from a minute before) and hasn't been on for more than 5 minutes. */
    fun due(list: List<Reminder>, now: Long): List<Reminder> =
        list.filter { now >= it.start - LEAD_MILLIS && now < it.start + 5 * 60_000L && now < it.stop }.sortedBy { it.start }

    /** Reminders still to come or on now, soonest first. */
    fun upcoming(list: List<Reminder>, now: Long): List<Reminder> = list.filter { it.stop > now }.sortedBy { it.start }

    /** Adds [reminder], or removes it if it's already set. */
    fun toggle(list: List<Reminder>, reminder: Reminder): List<Reminder> =
        if (list.any { it.id == reminder.id }) list.filterNot { it.id == reminder.id } else list + reminder
}

/** Reads and writes reminders and the watchlist as JSON (stored on the device and in backups). */
object MyListJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encodeReminders(list: List<Reminder>): String = remindersJson(list).toString()

    internal fun remindersJson(list: List<Reminder>): JsonArray = buildJsonArray {
        list.forEach { r ->
            add(
                buildJsonObject {
                    put("channelKey", r.channelKey)
                    put("channelName", r.channelName)
                    r.channelLogo?.let { put("channelLogo", it) }
                    put("title", r.title)
                    put("start", r.start)
                    put("stop", r.stop)
                    r.description?.let { put("description", it) }
                },
            )
        }
    }

    fun decodeReminders(text: String?): List<Reminder> = parseArray(text)?.let(::remindersFrom).orEmpty()

    internal fun remindersFrom(array: JsonArray): List<Reminder> = array.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        Reminder(
            channelKey = o.str("channelKey") ?: return@mapNotNull null,
            channelName = o.str("channelName").orEmpty(),
            channelLogo = o.str("channelLogo"),
            title = o.str("title") ?: return@mapNotNull null,
            start = o.long("start") ?: return@mapNotNull null,
            stop = o.long("stop") ?: return@mapNotNull null,
            description = o.str("description"),
        )
    }

    fun encodeWatchlist(list: List<WatchItem>): String = watchlistJson(list).toString()

    internal fun watchlistJson(list: List<WatchItem>): JsonArray = buildJsonArray {
        list.forEach { w ->
            add(
                buildJsonObject {
                    put("kind", w.kind)
                    put("name", w.name)
                    w.poster?.let { put("poster", it) }
                    put("url", w.url)
                    put("addedAt", w.addedAt)
                },
            )
        }
    }

    fun decodeWatchlist(text: String?): List<WatchItem> = parseArray(text)?.let(::watchlistFrom).orEmpty()

    internal fun watchlistFrom(array: JsonArray): List<WatchItem> = array.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        WatchItem(
            kind = o.str("kind") ?: return@mapNotNull null,
            name = o.str("name") ?: return@mapNotNull null,
            poster = o.str("poster"),
            url = o.str("url").orEmpty(),
            addedAt = o.long("addedAt") ?: 0L,
        )
    }

    private fun parseArray(text: String?): JsonArray? =
        if (text.isNullOrBlank()) null else runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()

    fun remindersJsonPublic(list: List<Reminder>): JsonArray = remindersJson(list)
    fun remindersFromPublic(array: JsonArray): List<Reminder> = remindersFrom(array)
    fun watchlistJsonPublic(list: List<WatchItem>): JsonArray = watchlistJson(list)
    fun watchlistFromPublic(array: JsonArray): List<WatchItem> = watchlistFrom(array)
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
