package app.tvplayer.data

import kotlinx.coroutines.flow.distinctUntilChanged
import app.tvplayer.core.sport.SportKind
import app.tvplayer.core.sport.SportFinder
import app.tvplayer.core.vod.EpisodeNames
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
    /**
     * Live and upcoming sport from the TV guide over the next [days]: programmes in a sport genre,
     * matches ("A v B"), and live events on sports channels. The same event on several channels is
     * listed once with all of them.
     */
    suspend fun sport(now: Long = System.currentTimeMillis(), days: Int = 3): List<SportEvent> = withContext(Dispatchers.IO) {
        checkStamp()
        cachedSport()?.let { return@withContext it }
        findSport(now, days).also { listCache["sport"] = now to it }
    }

    private suspend fun findSport(now: Long, days: Int): List<SportEvent> = withContext(Dispatchers.IO) {
        val channels = lineup().channels.filter { it.guideId != null }
        val byGuide = channels.groupBy { it.playlistId to it.guideId!! }
        val sportsGuideIds = channels.filter { SportFinder.isSportsChannel(it.name, it.group) }.mapNotNull { it.guideId }.distinct()
        val until = now + days * 24 * HOUR
        val found = LinkedHashMap<Long, ProgrammeEntity>()
        db.programmes().sportCandidates(now, until).forEach { found[it.id] = it }
        sportsGuideIds.chunked(400).forEach { ids -> db.programmes().onChannels(ids, now, until).forEach { found[it.id] = it } }
        val events = LinkedHashMap<Pair<String, Long>, SportEvent>()
        for (p in found.values.sortedBy { it.start }) {
            val showing = byGuide[p.playlistId to p.channelGuideId] ?: continue
            val onSports = showing.any { SportFinder.isSportsChannel(it.name, it.group) }
            val kind = SportFinder.classify(p.title, p.category, onSports) ?: continue
            val title = SportFinder.cleanTitle(p.title)
            val key = title.lowercase() to p.start
            val existing = events[key]
            events[key] = if (existing == null) {
                SportEvent(kind, title, p.start, p.stop, SportFinder.isLive(p.title), p.description, showing)
            } else {
                existing.copy(channels = (existing.channels + showing).distinctBy { it.key }, live = existing.live || SportFinder.isLive(p.title))
            }
        }
        events.values.toList()
    }

    /** A saved film or series, found again by its address or else its name; series open as their show. */
    suspend fun findTitle(kind: String, name: String, url: String): VodRow? = withContext(Dispatchers.IO) {
        val row = (if (url.isNotEmpty()) db.channels().vodByUrl(url) else null)
            ?: db.channels().vodByName(if (kind == "series") ChannelEntity.KIND_SERIES else ChannelEntity.KIND_MOVIE, name)
        row?.let { if (it.kind == ChannelEntity.KIND_SERIES) asShow(it) else it }
    }

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
                    catchupDays = it.catchupDays,
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

    // Films and series lists, and Sport, are kept in memory until a playlist changes, so the
    // sections open straight away when you come back to them.
    private val listCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    @Volatile private var listStamp = Long.MIN_VALUE

    /** Changes whenever a playlist is added, removed or refreshed. */
    private suspend fun currentStamp(): Long = withContext(Dispatchers.IO) {
        val playlists = db.playlists().getAllBlocking()
        (playlists.sumOf { (it.refreshedAt ?: 0L) % 1_000_000_007L } * 31 + playlists.size) * 1_000_003L + db.channels().vodCount()
    }

    // After a playlist changes, the lists from before are still shown while the new ones are worked out.
    private val staleCache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private suspend fun checkStamp() {
        val stamp = currentStamp()
        if (stamp != listStamp) {
            staleCache.putAll(listCache)
            listCache.clear()
            listStamp = stamp
        }
    }

    /** What [groups] returned last time, for showing at once while it's checked. */
    @Suppress("UNCHECKED_CAST")
    fun cachedGroups(kind: String): List<GroupCount>? = (listCache["g|$kind"] ?: staleCache["g|$kind"]) as? List<GroupCount>

    @Suppress("UNCHECKED_CAST")
    fun cachedInGroup(kind: String, group: String?): List<VodRow>? =
        (listCache["i|$kind|$group"] ?: staleCache["i|$kind|$group"]) as? List<VodRow>

    @Suppress("UNCHECKED_CAST")
    suspend fun groups(kind: String): List<GroupCount> = withContext(Dispatchers.IO) {
        checkStamp()
        (listCache["g|$kind"] as? List<GroupCount>)?.let { return@withContext it }
        val groups = db.channels().groups(kind)
        val result = if (kind != ChannelEntity.KIND_SERIES) {
            groups
        } else {
            // Count shows, not the separate episodes an M3U playlist lists. The whole section is read
            // once (a read per group took many seconds with big playlists), and every group's shows kept.
            val byGroup = db.channels().allInSection(kind).groupBy({ it.groupName }, { it.toRow() })
            groups.map { group ->
                val shows = showsIn(byGroup[group.groupName].orEmpty())
                listCache["i|$kind|${group.groupName}"] = shows
                group.copy(count = shows.size)
            }
        }
        listCache["g|$kind"] = result
        result
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun inGroup(kind: String, group: String?): List<VodRow> = withContext(Dispatchers.IO) {
        checkStamp()
        (listCache["i|$kind|$group"] as? List<VodRow>)?.let { return@withContext it }
        val rows = db.channels().inGroup(kind, group)
        val result = if (kind == ChannelEntity.KIND_SERIES) showsIn(rows) else rows
        listCache["i|$kind|$group"] = result
        result
    }

    /** Sport found last time, if it's less than 15 minutes old. */
    @Suppress("UNCHECKED_CAST")
    fun cachedSport(): List<SportEvent>? {
        val (at, events) = (listCache["sport"] as? Pair<Long, List<SportEvent>>) ?: return null
        val now = System.currentTimeMillis()
        // Drop events that have finished since.
        return if (now - at < SPORT_FRESH_MILLIS) events.filter { it.stop > now } else null
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val SPORT_FRESH_MILLIS = 15 * 60_000L

        /**
         * One entry per show. M3U playlists list each episode separately ("Hollow Fen S01 E03"); those are
         * gathered under the show's name, and opening it lists the episodes. Xtream series are already whole.
         */
        fun showsIn(rows: List<VodRow>): List<VodRow> {
            val seen = HashSet<String>()
            return rows.mapNotNull { row ->
                val show = asShow(row)
                val key = "${row.playlistId}|${EpisodeNames.showKey(show.name)}"
                if (row.kind == ChannelEntity.KIND_SERIES && row.remoteId == null && !seen.add(key)) null else show
            }
        }

        /** An M3U episode entry renamed as its show; anything else unchanged. */
        fun asShow(row: VodRow): VodRow {
            if (row.kind != ChannelEntity.KIND_SERIES || row.remoteId != null) return row
            val episode = EpisodeNames.parse(row.name) ?: return row
            return row.copy(name = episode.show)
        }
    }

    /** Films and episodes started but not finished, most recent first. */
    /**
     * Saved positions, for Carry on watching. A film saves its place every few seconds while it
     * plays; Home only needs to hear about it when the minutes left change, not every save.
     */
    val continueWatching: Flow<List<ProgressEntity>> = db.progress().observeRecent()
        .distinctUntilChanged { old, new ->
            old.size == new.size && old.zip(new).all { (a, b) ->
                a.url == b.url && a.positionMs / 60_000 == b.positionMs / 60_000 && a.durationMs == b.durationMs
            }
        }

    suspend fun progressFor(url: String): ProgressEntity? = db.progress().get(url)

    /** Saved positions for any of [urls] (a series' episodes). */
    suspend fun progressFor(urls: List<String>): List<ProgressEntity> =
        urls.chunked(500).flatMap { db.progress().getAll(it) }

    suspend fun saveProgress(progress: ProgressEntity) = db.progress().save(progress)

    suspend fun clearProgress(url: String) = db.progress().delete(url)

    /** Channels, films and series whose names contain [text]. Quality copies of a channel appear once. */
    suspend fun search(text: String): List<VodRow> {
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.channels().search("%$escaped%")
            .distinctBy { if (it.kind == ChannelEntity.KIND_LIVE) "${it.playlistId}|${it.variantKey}" else it.id.toString() }
            // M3U episodes of one show appear once, as the show.
            .let { showsIn(it) }
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

}

/** One sporting event in the guide, with every channel showing it. */
data class SportEvent(
    val kind: SportKind,
    val title: String,
    val start: Long,
    val stop: Long,
    /** Marked live in the guide (rather than a replay or highlights). */
    val live: Boolean,
    val description: String?,
    val channels: List<LineupChannel>,
)
