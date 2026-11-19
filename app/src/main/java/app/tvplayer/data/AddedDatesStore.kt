package app.tvplayer.data

import app.tvplayer.core.vod.AddedDates
import app.tvplayer.data.db.VodRow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * When each playlist's films and series were added, kept in a small file per playlist beside the
 * database (not in it, so the database's layout doesn't change). Used for Films and Series' "Recently
 * added" category.
 */
class AddedDatesStore(private val dir: File) {
    private val loaded = ConcurrentHashMap<Long, AddedDates>()

    private fun file(playlistId: Long) = File(dir, "$playlistId.bin")

    fun forPlaylist(playlistId: Long): AddedDates = loaded.getOrPut(playlistId) {
        runCatching { file(playlistId).inputStream().use(AddedDates::read) }.getOrDefault(AddedDates.EMPTY)
    }

    /** Saves the dates for a playlist just read: [found] is each title's [key] and the provider's date, if any. */
    fun save(playlistId: Long, found: List<Pair<String, Long?>>, nowMillis: Long = System.currentTimeMillis()) {
        val previous = forPlaylist(playlistId).takeIf { it.size > 0 }
        val merged = AddedDates.merge(previous, found, nowMillis / 1000)
        runCatching {
            dir.mkdirs()
            val temp = File(dir, "$playlistId.tmp")
            temp.outputStream().use(merged::write)
            if (!temp.renameTo(file(playlistId))) {
                file(playlistId).delete()
                temp.renameTo(file(playlistId))
            }
        }
        loaded[playlistId] = merged
    }

    fun remove(playlistId: Long) {
        loaded.remove(playlistId)
        file(playlistId).delete()
    }

    /** Whether any dates are known for [playlistId]. */
    fun has(playlistId: Long): Boolean = forPlaylist(playlistId).size > 0

    /** When [row] was added, in seconds since 1970, if known. */
    fun dateOf(row: VodRow): Long? = forPlaylist(row.playlistId).dateOf(key(row.kind, row.remoteId, row.url))

    companion object {
        /** The provider's id where there is one (it stays put if the file type changes), else the address. */
        fun key(kind: String, remoteId: Long?, url: String): String = if (remoteId != null) "$kind#$remoteId" else url
    }
}
