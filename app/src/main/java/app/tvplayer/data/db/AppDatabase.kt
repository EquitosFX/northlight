package app.tvplayer.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

/** Changes whenever live channels are added, removed or re-imported. */
data class LiveStamp(val count: Int, val maxId: Long?)

/** A film or series for the browse screens. */
data class VodRow(
    val id: Long,
    val playlistId: Long,
    val kind: String,
    val name: String,
    val variantKey: String,
    val logo: String?,
    val url: String,
    val remoteId: Long?,
    val userAgent: String?,
    val referrer: String?,
)

data class GroupCount(val groupName: String?, val count: Int)

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

    @Query("SELECT COUNT(*) AS count, MAX(id) AS maxId FROM channels WHERE kind = 'live'")
    fun liveStampBlocking(): LiveStamp

    @Query(
        "SELECT variantKey, MIN(baseName) AS name, MAX(guideId) AS guideId FROM channels " +
            "WHERE playlistId = :playlistId AND kind = 'live' GROUP BY variantKey",
    )
    fun matchRowsBlocking(playlistId: Long): List<MatchRow>

    @Query(
        "SELECT groupName, COUNT(*) AS count FROM channels WHERE kind = :kind " +
            "GROUP BY groupName ORDER BY MIN(playlistId), MIN(position)",
    )
    suspend fun groups(kind: String): List<GroupCount>

    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer FROM channels " +
            "WHERE kind = :kind AND groupName IS :group ORDER BY playlistId, position",
    )
    suspend fun inGroup(kind: String, group: String?): List<VodRow>

    /** Names containing the search text: live channels first, then films, then series; shorter names first. */
    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer FROM channels " +
            "WHERE name LIKE :pattern ESCAPE '\\' " +
            "ORDER BY CASE kind WHEN 'live' THEN 0 WHEN 'movie' THEN 1 ELSE 2 END, LENGTH(name) LIMIT 300",
    )
    suspend fun search(pattern: String): List<VodRow>

    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer FROM channels " +
            "WHERE kind IN ('movie', 'series')",
    )
    suspend fun allVod(): List<VodRow>

    @Query("SELECT COUNT(*) FROM channels WHERE kind IN ('movie', 'series')")
    suspend fun vodCount(): Int

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

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 20")
    fun observeRecent(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE url = :url")
    suspend fun get(url: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
interface FavouriteDao {
    @Query("SELECT key FROM favourites ORDER BY addedAt")
    fun observeKeys(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM favourites WHERE key = :key")
    suspend fun count(key: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favourite: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE key = :key")
    suspend fun remove(key: String)
}

@Dao
interface MetadataDao {
    @Query("SELECT * FROM metadata WHERE cacheKey = :key")
    suspend fun get(key: String): MetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: MetadataEntity)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY watchedAt DESC LIMIT 20")
    fun observeRecent(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: HistoryEntity)
}

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        ProgrammeEntity::class,
        ProgressEntity::class,
        FavouriteEntity::class,
        MetadataEntity::class,
        HistoryEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun programmes(): ProgrammeDao
    abstract fun progress(): ProgressDao
    abstract fun favourites(): FavouriteDao
    abstract fun metadata(): MetadataDao
    abstract fun history(): HistoryDao
}
