package app.tvplayer.data

import app.tvplayer.core.live.Lineup
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.core.live.LineupRow
import app.tvplayer.data.db.AppDatabase
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.FavouriteEntity
import app.tvplayer.data.db.GroupCount
import app.tvplayer.data.db.HistoryEntity
import app.tvplayer.data.db.LiveStamp
import app.tvplayer.data.db.ProgressEntity
import app.tvplayer.data.db.VodRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.tvplayer.data.db.ProgrammeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only access to the live channels and their TV guide, for the player and the guide screen. */
class ChannelsRepository(private val db: AppDatabase) {

    @Volatile private var cached: Pair<LiveStamp, Lineup>? = null

    /** Every live channel across all playlists, quality copies merged. Built once and reused until the channels change. */
    suspend fun lineup(): Lineup = withContext(Dispatchers.IO) {
        val stamp = db.channels().liveStampBlocking()
        cached?.let { (seen, lineup) -> if (seen == stamp) return@withContext lineup }
        val rows = db.channels().liveRowsBlocking()
        val built = Lineup.build(
            rows.map {
                LineupRow(
                    id = it.id,
                    playlistId = it.playlistId,
                    name = it.baseName,
                    variantKey = it.variantKey,
                    quality = it.quality,
                    isBackup = it.isBackup,
                    logo = it.logo,
                    group = it.groupName,
                    number = it.number,
                    guideId = it.epgId,
                )
            },
        )
        cached = stamp to built
        built
    }

    /** Channels watched recently, newest first. */
    val recentChannels: Flow<List<HistoryEntity>> = db.history().observeRecent()

    suspend fun recordWatched(channel: LineupChannel) {
        db.history().save(
            HistoryEntity(
                key = channel.key,
                name = channel.name,
                logo = channel.logo,
                number = channel.number,
                playlistId = channel.playlistId,
                guideId = channel.guideId,
                watchedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** What's on now on a channel known only by its playlist and guide id. */
    suspend fun nowOn(playlistId: Long, guideId: String?): ProgrammeEntity? {
        if (guideId == null) return null
        val now = System.currentTimeMillis()
        return db.programmes().inRange(playlistId, guideId, now, now + 1).firstOrNull()
    }

    suspend fun row(id: Long): ChannelEntity? = withContext(Dispatchers.IO) { db.channels().getBlocking(id) }

    /** Programmes on [channel] that overlap the time from [from] to [to]; empty when it has no guide. */
    suspend fun programmes(channel: LineupChannel, from: Long, to: Long): List<ProgrammeEntity> {
        val guideId = channel.guideId ?: return emptyList()
        return db.programmes().inRange(channel.playlistId, guideId, from, to)
    }

    /** What is on [channel] now and next, if the guide knows. */
    suspend fun nowAndNext(channel: LineupChannel): List<ProgrammeEntity> {
        val now = System.currentTimeMillis()
        return programmes(channel, now, now + 12 * HOUR).take(2)
    }

    suspend fun groups(kind: String): List<GroupCount> = db.channels().groups(kind)

    suspend fun inGroup(kind: String, group: String?): List<VodRow> = db.channels().inGroup(kind, group)

    /** Films and episodes started but not finished, most recent first. */
    val continueWatching: Flow<List<ProgressEntity>> = db.progress().observeRecent()

    suspend fun progressFor(url: String): ProgressEntity? = db.progress().get(url)

    suspend fun saveProgress(progress: ProgressEntity) = db.progress().save(progress)

    suspend fun clearProgress(url: String) = db.progress().delete(url)

    /** Channels, films and series whose names contain [text]. Quality copies of a channel appear once. */
    suspend fun search(text: String): List<VodRow> {
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.channels().search("%$escaped%")
            .distinctBy { if (it.kind == ChannelEntity.KIND_LIVE) "${it.playlistId}|${it.variantKey}" else it.id.toString() }
            .take(100)
    }

    val favouriteKeys: Flow<Set<String>> = db.favourites().observeKeys().map { it.toSet() }

    suspend fun toggleFavourite(key: String) {
        if (db.favourites().count(key) > 0) {
            db.favourites().remove(key)
        } else {
            db.favourites().add(FavouriteEntity(key, System.currentTimeMillis()))
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
