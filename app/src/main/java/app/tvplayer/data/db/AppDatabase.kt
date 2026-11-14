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

    @Query("SELECT * FROM playlists ORDER BY addedAt")
    fun getAllBlocking(): List<PlaylistEntity>

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
    val epgId: String?,
)

/** One live channel (all its quality copies together), for matching against a guide. */
data class MatchRow(val variantKey: String, val name: String, val guideId: String?)

@Dao
interface ChannelDao {
    @Insert
    fun insertAllBlocking(rows: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    fun deleteForPlaylistBlocking(playlistId: Long)

    @Query(
        "SELECT id, playlistId, baseName, variantKey, quality, isBackup, logo, groupName, number, epgId " +
            "FROM channels WHERE kind = 'live' ORDER BY playlistId, position",
    )
    fun liveRowsBlocking(): List<LiveRow>

    @Query("SELECT * FROM channels WHERE id = :id")
    fun getBlocking(id: Long): ChannelEntity?

    @Query(
        "SELECT variantKey, MIN(baseName) AS name, MAX(guideId) AS guideId FROM channels " +
            "WHERE playlistId = :playlistId AND kind = 'live' GROUP BY variantKey",
    )
    fun matchRowsBlocking(playlistId: Long): List<MatchRow>

    @Query("UPDATE channels SET epgId = :epgId WHERE playlistId = :playlistId AND variantKey = :variantKey")
    fun setEpgIdBlocking(playlistId: Long, variantKey: String, epgId: String?)

    @Query("SELECT * FROM channels WHERE kind = 'live' ORDER BY playlistId, position LIMIT :limit")
    fun observeFirstLive(limit: Int): Flow<List<ChannelEntity>>
}

@Dao
interface ProgrammeDao {
    @Insert
    fun insertAllBlocking(rows: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE playlistId = :playlistId")
    fun deleteForPlaylistBlocking(playlistId: Long)

    /** Programmes on one guide channel that overlap the time from [from] to [to]. */
    @Query(
        "SELECT * FROM programmes WHERE playlistId = :playlistId AND channelGuideId = :guideId " +
            "AND stop > :from AND start < :to ORDER BY start",
    )
    suspend fun inRange(playlistId: Long, guideId: String, from: Long, to: Long): List<ProgrammeEntity>
}

@Database(
    entities = [PlaylistEntity::class, ChannelEntity::class, ProgrammeEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun programmes(): ProgrammeDao
}
