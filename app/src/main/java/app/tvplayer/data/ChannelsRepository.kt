package app.tvplayer.data

import app.tvplayer.core.live.Lineup
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.core.live.LineupRow
import app.tvplayer.data.db.AppDatabase
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.FavouriteEntity
import app.tvplayer.data.db.GroupCount
import app.tvplayer.data.db.ProgressEntity
import app.tvplayer.data.db.VodRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.tvplayer.data.db.ProgrammeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only access to the live channels and their TV guide, for the player and the guide screen. */
class ChannelsRepository(private val db: AppDatabase) {

    /** Every live channel across all playlists, quality copies merged. */
    suspend fun lineup(): Lineup = withContext(Dispatchers.IO) {
        val rows = db.channels().liveRowsBlocking()
        Lineup.build(
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
