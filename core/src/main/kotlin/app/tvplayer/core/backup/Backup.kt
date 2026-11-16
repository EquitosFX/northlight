package app.tvplayer.core.backup

import app.tvplayer.core.mylist.MyListJson
import app.tvplayer.core.mylist.Reminder
import app.tvplayer.core.mylist.WatchItem
import app.tvplayer.core.mylist.arr
import app.tvplayer.core.mylist.long
import app.tvplayer.core.mylist.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One playlist as saved in a backup: enough to add it again. */
data class PlaylistBackup(
    val name: String,
    val type: String,
    val address: String,
    val username: String? = null,
    val password: String? = null,
    val guideUrls: String = "",
)

/** Everything a backup holds. */
data class BackupData(
    val createdAt: Long,
    val playlists: List<PlaylistBackup>,
    /** Settings as text, by name. */
    val settings: Map<String, String> = emptyMap(),
    /** Favourite channels, by line-up key. */
    val favourites: List<String> = emptyList(),
    val watchlist: List<WatchItem> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
)

class BackupException(message: String) : Exception(message)

/** The backup file: JSON, marked so a wrong file is recognised. */
object BackupCodec {
    private const val FORMAT = "skyfall-backup"
    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    fun encode(data: BackupData): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("createdAt", data.createdAt)
            put(
                "playlists",
                buildJsonArray {
                    data.playlists.forEach { p ->
                        add(
                            buildJsonObject {
                                put("name", p.name)
                                put("type", p.type)
                                put("address", p.address)
                                p.username?.let { put("username", it) }
                                p.password?.let { put("password", it) }
                                put("guideUrls", p.guideUrls)
                            },
                        )
                    }
                },
            )
            put("settings", buildJsonObject { data.settings.toSortedMap().forEach { (k, v) -> put(k, v) } })
            put("favourites", buildJsonArray { data.favourites.forEach { add(JsonPrimitive(it)) } })
            put("watchlist", MyListJson.watchlistJsonPublic(data.watchlist))
            put("reminders", MyListJson.remindersJsonPublic(data.reminders))
        },
    )

    fun decode(text: String): BackupData {
        val root = runCatching { json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull()
            ?: throw BackupException("That isn't a Skyfall backup file.")
        if (root.str("format") != FORMAT) throw BackupException("That isn't a Skyfall backup file.")
        val version = root.long("version")?.toInt() ?: 0
        if (version > VERSION) throw BackupException("That backup comes from a newer Skyfall. Update this one first.")
        val playlists = root["playlists"].arr()?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            PlaylistBackup(
                name = o.str("name") ?: return@mapNotNull null,
                type = o.str("type") ?: return@mapNotNull null,
                address = o.str("address") ?: return@mapNotNull null,
                username = o.str("username"),
                password = o.str("password"),
                guideUrls = o.str("guideUrls").orEmpty(),
            )
        }.orEmpty()
        val settings = (root["settings"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        val favourites = root["favourites"].arr()?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return BackupData(
            createdAt = root.long("createdAt") ?: 0L,
            playlists = playlists,
            settings = settings,
            favourites = favourites,
            watchlist = root["watchlist"].arr()?.let { MyListJson.watchlistFromPublic(it) }.orEmpty(),
            reminders = root["reminders"].arr()?.let { MyListJson.remindersFromPublic(it) }.orEmpty(),
        )
    }
}
