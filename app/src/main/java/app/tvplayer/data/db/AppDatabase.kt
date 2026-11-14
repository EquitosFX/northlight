package app.tvplayer.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Methods ending in "Blocking" do their work on the calling thread. They are only ever called
 * from background threads during an import, where the parsers hand rows over one at a time.
 */
@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY addedAt")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getBlocking(id: Long): PlaylistEntity?

    @Insert
    fun insertBlocking(playlist: PlaylistEntity): Long

    @Update
    fun updateBlocking(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    fun deleteBlocking(id: Long)
}

/** The few columns the channel list needs, so a large playlist loads quickly. */
data class LiveRow(
    val id: Long,
    val playlistId: Long,
    val baseName: String,
    val variantKey: String,
    val quality: Int,
    val isBackup: Boolean,
    val logo: String?,
    val groupName: String?,
    val number: Int?,
)

@Dao
interface ChannelDao {
    @Insert
    fun insertAllBlocking(rows: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    fun deleteForPlaylistBlocking(playlistId: Long)

    @Query(
        "SELECT id, playlistId, baseName, variantKey, quality, isBackup, logo, groupName, number " +
            "FROM channels WHERE kind = 'live' ORDER BY playlistId, position",
    )
    fun liveRowsBlocking(): List<LiveRow>

    @Query("SELECT * FROM channels WHERE id = :id")
    fun getBlocking(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE kind = 'live' ORDER BY playlistId, position LIMIT :limit")
    fun observeFirstLive(limit: Int): Flow<List<ChannelEntity>>
}

@Database(entities = [PlaylistEntity::class, ChannelEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
}
