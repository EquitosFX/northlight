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

    /**
     * Changes whenever a playlist is added, removed, refreshed or has its TV guide downloaded again (the
     * only times the channels change). Reading the few playlists is instant, unlike counting channels.
     */
    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(id * 1000003 + COALESCE(refreshedAt, 0) + COALESCE(guideRefreshedAt, 0) * 7), 0) AS maxId " +
            "FROM playlists",
    )
    fun stampBlocking(): LiveStamp

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
    val catchupDays: Int?,
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

/** A channel, film or series name, for searching. */
data class SearchName(val id: Long, val kind: String, val name: String)

/** A film or series' id and what its added date is kept under. */
data class AddedKeyRow(val id: Long, val playlistId: Long, val kind: String, val remoteId: Long?, val url: String)

/** A film or series with its group, for reading a whole section in one go. */
data class GroupedVodRow(
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
    val groupName: String?,
) {
    fun toRow() = VodRow(id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer)
}

/** One live channel (all its quality copies together), for matching against a guide. */
data class MatchRow(val variantKey: String, val name: String, val guideId: String?)

@Dao
interface ChannelDao {
    @Insert
    fun insertAllBlocking(rows: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    fun deleteForPlaylistBlocking(playlistId: Long)

    @Query(
        "SELECT id, playlistId, baseName, variantKey, quality, isBackup, logo, groupName, number, epgId, catchupDays " +
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

    /**
     * Just enough of every film or series to look up when it was added: the provider's id, or the address
     * where there's no id. In the order they were stored (the playlist's), which needs no sorting.
     */
    @Query(
        "SELECT id, playlistId, kind, remoteId, CASE WHEN remoteId IS NULL THEN url ELSE '' END AS url FROM channels " +
            "WHERE kind = :kind ORDER BY id",
    )
    suspend fun addedKeys(kind: String): List<AddedKeyRow>

    @Query("SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer FROM channels WHERE id IN (:ids)")
    suspend fun vodRows(ids: List<Long>): List<VodRow>

    /** Every film or series of [kind] with its group, in one read rather than one per group. */
    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer, groupName FROM channels " +
            "WHERE kind = :kind ORDER BY playlistId, position",
    )
    suspend fun allInSection(kind: String): List<GroupedVodRow>

    /** Only the entries listed episode by episode (M3U), which need gathering into shows; Xtream series are whole. */
    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer, groupName FROM channels " +
            "WHERE kind = :kind AND remoteId IS NULL ORDER BY playlistId, position",
    )
    suspend fun episodeEntries(kind: String): List<GroupedVodRow>

    /** Names containing the search text: live channels first, then films, then series; shorter names first. */
    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer FROM channels " +
            "WHERE name LIKE :pattern ESCAPE '\\' " +
            "ORDER BY CASE kind WHEN 'live' THEN 0 WHEN 'movie' THEN 1 ELSE 2 END, LENGTH(name) LIMIT 300",
    )
    suspend fun search(pattern: String): List<VodRow>

    /** Every name, for searching in memory (see ChannelsRepository.search). In the order they were stored. */
    @Query("SELECT id, kind, name FROM channels ORDER BY id")
    fun searchNamesBlocking(): List<SearchName>

    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer FROM channels " +
            "WHERE kind IN ('movie', 'series')",
    )
    suspend fun allVod(): List<VodRow>

    /** A film or series by its stream address (to find a saved title again). */
    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer FROM channels " +
            "WHERE url = :url LIMIT 1",
    )
    suspend fun vodByUrl(url: String): VodRow?

    /** A film or series by kind and name. */
    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer FROM channels " +
            "WHERE kind = :kind AND name = :name LIMIT 1",
    )
    suspend fun vodByName(kind: String, name: String): VodRow?

    /** Every series entry in a playlist; M3U playlists list one per episode. */
    @Query(
        "SELECT id, playlistId, kind, name, variantKey, logo, url, remoteId, userAgent, referrer FROM channels " +
            "WHERE kind = 'series' AND playlistId = :playlistId ORDER BY position",
    )
    suspend fun seriesEntries(playlistId: Long): List<VodRow>

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

    /** Changes whenever a TV guide is downloaded again (ids are never reused). */
    @Query("SELECT COALESCE(MAX(id), 0) FROM programmes")
    fun stampBlocking(): Long

    /** Programmes on one guide channel that overlap the time from [from] to [to]. */
    @Query(
        "SELECT * FROM programmes WHERE playlistId = :playlistId AND channelGuideId = :guideId " +
            "AND stop > :from AND start < :to ORDER BY start",
    )
    suspend fun inRange(playlistId: Long, guideId: String, from: Long, to: Long): List<ProgrammeEntity>

    /** Programmes that may be sport: a sport genre, two sides ("v"), or marked live. */
    @Query(
        "SELECT * FROM programmes WHERE stop > :from AND start < :to AND (category LIKE '%sport%' " +
            "OR title LIKE '% v %' OR title LIKE '% vs %' OR title LIKE 'live %' OR title LIKE 'live:%' OR title LIKE '%: live %') " +
            "ORDER BY start LIMIT 6000",
    )
    suspend fun sportCandidates(from: Long, to: Long): List<ProgrammeEntity>

    /** Everything on some guide channels in a time range. */
    @Query("SELECT * FROM programmes WHERE channelGuideId IN (:guideIds) AND stop > :from AND start < :to ORDER BY start LIMIT 6000")
    suspend fun onChannels(guideIds: List<String>, from: Long, to: Long): List<ProgrammeEntity>
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 20")
    fun observeRecent(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE url IN (:urls)")
    suspend fun getAll(urls: List<String>): List<ProgressEntity>

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

    @Query("SELECT key FROM favourites ORDER BY addedAt")
    suspend fun allKeys(): List<String>

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
