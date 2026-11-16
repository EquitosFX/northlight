package app.tvplayer.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.tvplayer.core.backup.BackupCodec
import app.tvplayer.core.backup.BackupData
import app.tvplayer.core.backup.PlaylistBackup
import app.tvplayer.data.db.AppDatabase
import app.tvplayer.data.db.FavouriteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Backups of playlists, favourites, settings, the watchlist and reminders: kept on the Shield, copied
 * to its Downloads folder, and passed to and from a phone over the home network.
 *
 * Favourites and reminders name channels by "playlistId|channel"; playlist ids differ between
 * devices, so in a backup they name the playlist by its place in the list ("p0|channel") instead.
 */
class BackupRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val myList: MyListRepository,
    private val playlists: PlaylistRepository,
) {
    private val saved = File(context.filesDir, "backups/skyfall-backup.json")

    /** When the backup kept on this Shield was made, or null if there isn't one. */
    fun savedAt(): Long? = saved.takeIf { it.exists() }?.lastModified()

    suspend fun create(): String = withContext(Dispatchers.IO) {
        val lists = db.playlists().getAllBlocking()
        val index = lists.withIndex().associate { (i, p) -> p.id to i }
        fun portable(key: String): String? {
            val id = key.substringBefore('|').toLongOrNull() ?: return null
            val i = index[id] ?: return null
            return "p$i|" + key.substringAfter('|')
        }
        BackupCodec.encode(
            BackupData(
                createdAt = System.currentTimeMillis(),
                playlists = lists.map { PlaylistBackup(it.name, it.type, it.address, it.username, it.password, it.guideUrls) },
                settings = settings.exportAll(),
                favourites = db.favourites().allKeys().mapNotNull(::portable),
                watchlist = myList.watchlist.value,
                reminders = myList.reminders.value.mapNotNull { r -> portable(r.channelKey)?.let { r.copy(channelKey = it) } },
            ),
        )
    }

    /** Saves a backup on this Shield and a copy in Downloads/Skyfall. Returns where the copy went, if anywhere. */
    suspend fun save(): String? = withContext(Dispatchers.IO) {
        val text = create()
        saved.parentFile?.mkdirs()
        saved.writeText(text)
        copyToDownloads(text)
    }

    /** Restores the backup kept on this Shield. Returns a message for the screen. */
    suspend fun restoreSaved(): String {
        val text = withContext(Dispatchers.IO) { saved.takeIf { it.exists() }?.readText() } ?: return "There's no backup saved on this Shield yet."
        return restore(text)
    }

    /**
     * Restores [text] (a backup file): playlists not already here are added and loaded, settings are
     * replaced, favourites are added, and the watchlist and reminders are replaced.
     */
    suspend fun restore(text: String): String {
        val data = BackupCodec.decode(text)
        settings.importAll(data.settings)
        playlists.restore(data.playlists) { ids ->
            fun local(key: String): String? {
                if (!key.startsWith("p")) return null
                val i = key.drop(1).substringBefore('|').toIntOrNull() ?: return null
                val id = ids.getOrNull(i) ?: return null
                return "$id|" + key.substringAfter('|')
            }
            kotlinx.coroutines.runBlocking {
                data.favourites.mapNotNull(::local).forEachIndexed { n, key -> db.favourites().add(FavouriteEntity(key, System.currentTimeMillis() + n)) }
            }
            myList.restore(data.reminders.mapNotNull { r -> local(r.channelKey)?.let { r.copy(channelKey = it) } }, data.watchlist)
        }
        val count = data.playlists.size
        return "Restored ${if (count == 1) "1 playlist" else "$count playlists"}, ${data.favourites.size} favourites and your settings. " +
            "New playlists are loading now."
    }

    private fun copyToDownloads(text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = "skyfall-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".json"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Skyfall")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            "Downloads/Skyfall/$name"
        }.getOrNull()
    }
}
