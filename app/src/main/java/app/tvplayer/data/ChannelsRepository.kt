package app.tvplayer.data

import app.tvplayer.core.search.SearchText
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
class ChannelsRepository(
    private val db: AppDatabase,
    private val added: AddedDatesStore? = null,
    /** Logos for channels the playlist gives none. */
    private val logoLibrary: LogoLibrary? = null,
) {

    @Volatile private var cached: Pair<LiveStamp, Lineup>? = null

    /** Goes up each time logos are found for channels that had none (the channel list is rebuilt then). */
    @Volatile var logoVersion: Int = 0
        private set

    /** Looks up logos for channels without one; rebuilds the channel list if any were found. */
    suspend fun findMissingLogos() = withContext(Dispatchers.IO) {
        val library = logoLibrary ?: return@withContext
        val missing = db.channels().liveRowsBlocking().filter { it.logo.isNullOrBlank() }.map { it.baseName to it.epgId }.distinct()
        if (library.update(missing)) {
            cached = null
            logoVersion++
        }
    }

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

    /** Changes whenever a playlist or TV guide has been downloaded again (an instant check). */
    suspend fun changeStamp(): app.tvplayer.data.db.LiveStamp = withContext(Dispatchers.IO) { db.playlists().stampBlocking() }

    suspend fun lineup(): Lineup = withContext(Dispatchers.IO) {
        val stamp = db.playlists().stampBlocking()
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
                    logo = it.logo?.takeIf { logo -> logo.isNotBlank() } ?: logoLibrary?.logoFor(it.baseName, it.epgId),
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

    /** Changes whenever a TV guide is downloaded again. */
    suspend fun programmeStamp(): Long = withContext(Dispatchers.IO) { runCatching { db.programmes().stampBlocking() }.getOrDefault(0L) }

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
        (playlists.sumOf { (it.refreshedAt ?: 0L) % 1_000_000_007L } * 31 + playlists.size) * 1_000_003L +
            playlists.sumOf { it.id }
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
            // Count shows, not the separate episodes an M3U playlist lists. Only those episode entries are
            // read (Xtream series are already one entry per show, and the database counts them).
            val episodes = db.channels().episodeEntries(kind)
            if (episodes.isEmpty()) {
                groups
            } else {
                val byGroup = episodes.groupBy({ it.groupName }, { it.toRow() })
                groups.map { group ->
                    val entries = byGroup[group.groupName] ?: return@map group
                    group.copy(count = group.count - entries.size + showsIn(entries).size)
                }
            }
        }
        // "Recently added" first, when there are dates to go by.
        val withRecent = if (result.isNotEmpty() && hasDates()) {
            listOf(GroupCount(RECENTLY_ADDED, result.sumOf { it.count }.coerceAtMost(RECENT_LIMIT))) + result
        } else {
            result
        }
        listCache["g|$kind"] = withRecent
        withRecent
    }

    private suspend fun hasDates(): Boolean {
        val store = added ?: return false
        return db.playlists().getAllBlocking().any { store.has(it.id) }
    }

    /** When [row] was added, in seconds since 1970, if known. */
    fun addedDate(row: VodRow): Long? = added?.dateOf(row)

    /** The newest films or series, newest first. */
    private suspend fun recentlyAdded(kind: String): List<VodRow> {
        val store = added ?: return emptyList()
        // Only ids and keys are read for every title (a hundred thousand full rows would take seconds
        // on a TV box); each date is looked up once and sorted as plain numbers, newest first.
        val keys = db.channels().addedKeys(kind)
        val dates = LongArray(keys.size) { i -> keys[i].let { store.dateOf(it.playlistId, it.kind, it.remoteId, it.url) } ?: 0L }
        val order = LongArray(keys.size)
        sortNewestFirst(order, dates)
        // Then the full rows of the newest, a batch at a time. M3U episodes are gathered under their
        // show (dated by its newest episode, which comes first), so a batch may give fewer shows.
        val result = ArrayList<VodRow>(RECENT_LIMIT)
        val seenShows = HashSet<String>()
        var next = 0
        while (result.size < RECENT_LIMIT && next < order.size) {
            val ids = (next until minOf(next + RECENT_BATCH, order.size)).map { keys[order[it].toInt()].id }
            next += ids.size
            val byId = db.channels().vodRows(ids).associateBy { it.id }
            for (id in ids) {
                val row = byId[id] ?: continue
                if (row.kind == ChannelEntity.KIND_SERIES && row.remoteId == null) {
                    val show = asShow(row)
                    if (!seenShows.add("${row.playlistId}|${EpisodeNames.showKey(show.name)}")) continue
                    result += show
                } else {
                    result += row
                }
                if (result.size >= RECENT_LIMIT) break
            }
        }
        return result
    }

    /** Fills [order] with the positions in [dates], newest first; equal dates stay in their order. */
    private fun sortNewestFirst(order: LongArray, dates: LongArray) {
        // Packed into one number each (date in the top bits, position below), so a plain sort does it.
        val n = order.size
        val packed = LongArray(n) { i -> ((dates[i].coerceIn(0L, 0x7FFFFFFFL)) shl 31) or ((0x7FFFFFFFL - i).coerceAtLeast(0L)) }
        packed.sort()
        for (i in 0 until n) order[i] = 0x7FFFFFFFL - (packed[n - 1 - i] and 0x7FFFFFFFL)
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun inGroup(kind: String, group: String?): List<VodRow> = withContext(Dispatchers.IO) {
        checkStamp()
        (listCache["i|$kind|$group"] as? List<VodRow>)?.let { return@withContext it }
        if (group == RECENTLY_ADDED) {
            return@withContext recentlyAdded(kind).also { listCache["i|$kind|$group"] = it }
        }
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
        /** The "Recently added" category in Films and Series (a name no playlist will use). */
        const val RECENTLY_ADDED = "\u0001recently-added"

        /** How many titles "Recently added" shows. */
        const val RECENT_LIMIT = 200

        /** How many matches are read before quality copies and episodes are gathered up. */
        private const val SEARCH_LIMIT = 300
        private const val RECENT_BATCH = 400

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

    /** Every name, condensed for matching (see [SearchText]), kept until a playlist changes. */
    private class SearchIndex(val stamp: app.tvplayer.data.db.LiveStamp, val ids: LongArray, val kinds: ByteArray, val names: Array<String>)

    @Volatile private var searchIndex: SearchIndex? = null

    private fun searchIndex(): SearchIndex {
        val stamp = db.playlists().stampBlocking()
        searchIndex?.let { if (it.stamp == stamp) return it }
        val rows = db.channels().searchNamesBlocking()
        val built = SearchIndex(
            stamp = stamp,
            ids = LongArray(rows.size) { rows[it].id },
            // Live channels first, then films, then series, as results are listed.
            kinds = ByteArray(rows.size) { when (rows[it].kind) { ChannelEntity.KIND_LIVE -> 0; ChannelEntity.KIND_MOVIE -> 1; else -> 2 } },
            names = Array(rows.size) { SearchText.condense(rows[it].name) },
        )
        searchIndex = built
        return built
    }

    /** Gets the names ready for searching in the background, so the first search is quick. */
    suspend fun prepareSearch() = withContext(Dispatchers.IO) { runCatching { searchIndex() }; Unit }

    /**
     * Channels, films and series matching [text], however it's spaced or punctuated ("spiderman" finds
     * "Spider-Man"; see [SearchText]). Live channels first, then films, then series; within each, names
     * starting with the search first, then shorter names. Quality copies of a channel appear once.
     */
    suspend fun search(text: String): List<VodRow> = withContext(Dispatchers.IO) {
        val query = SearchText.Query(text)
        if (!query.usable) return@withContext emptyList()
        val index = searchIndex()
        val found = ArrayList<Long>()
        for (i in index.ids.indices) {
            val rank = SearchText.rank(index.names[i], query)
            if (rank == 0) continue
            // Packed for sorting: kind, then how well it matched, then length, then where it was stored.
            val order = (index.kinds[i].toLong() shl 60) or ((3L - rank) shl 56) or (index.names[i].length.toLong().coerceAtMost(0xFFFF) shl 40) or i.toLong()
            found += order
        }
        val chosen = found.sorted().take(SEARCH_LIMIT).map { index.ids[(it and 0xFFFFFFFFFFL).toInt()] }
        val rows = chosen.chunked(500).flatMap { db.channels().vodRows(it) }.associateBy { it.id }
        chosen.mapNotNull { rows[it] }
            .distinctBy { if (it.kind == ChannelEntity.KIND_LIVE) "${it.playlistId}|${it.variantKey}" else it.id.toString() }
            // M3U episodes of one show appear once, as the show.
            .let { showsIn(it) }
            .take(100)
    }

    /**
     * Of the ways voice search heard something (most likely first), the one that finds the most in the
     * playlists: "the wire" over "the why are", say. If none finds anything, the most likely.
     */
    suspend fun bestHeard(heard: List<String>): String? {
        val choices = heard.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (choices.size <= 1) return choices.firstOrNull()
        // The first with something starting with it, else the first with anything at all.
        var anything: String? = null
        for (choice in choices) {
            val results = search(choice)
            if (results.isEmpty()) continue
            if (results.any { SearchText.condense(it.name).startsWith(SearchText.condense(choice)) }) return choice
            if (anything == null) anything = choice
        }
        return anything ?: choices.first()
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
