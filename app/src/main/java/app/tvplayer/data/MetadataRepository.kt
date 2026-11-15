package app.tvplayer.data

import app.tvplayer.core.tmdb.LibraryIndex
import app.tvplayer.core.tmdb.TitleCleaner
import app.tvplayer.core.tmdb.TmdbPerson
import app.tvplayer.core.tmdb.TmdbParser
import app.tvplayer.core.tmdb.TmdbUrls
import app.tvplayer.data.db.AppDatabase
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.MetadataEntity
import app.tvplayer.data.db.VodRow
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Looks films and series up on TMDB with the viewer's own key, and caches the answers.
 * Without a key, or offline, look-ups quietly return nothing and the screens use the playlist's own details.
 */
class MetadataRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    private class KeyRejected : IOException("TMDB didn't accept that key.")

    /** The playlist's films and series, indexed by cleaned title; rebuilt when the number of titles changes. */
    @Volatile private var index: LibraryIndex<VodRow>? = null

    suspend fun lookup(name: String, kind: String): MetadataEntity? = withContext(Dispatchers.IO) {
        val key = settings.tmdbKey.first().trim()
        if (key.isEmpty()) return@withContext null
        val type = if (kind == ChannelEntity.KIND_SERIES) "tv" else "movie"
        val (title, year) = TitleCleaner.clean(name)
        val cacheKey = "$type|${title.lowercase()}|${year ?: ""}"
        val now = System.currentTimeMillis()

        val cached = db.metadata().get(cacheKey)
        if (cached != null && (cached.found || cached.fetchedAt > now - RETRY_MISS_MILLIS)) {
            return@withContext cached.takeIf { it.found }
        }
        try {
            var best = TmdbParser.pickBest(TmdbParser.parseSearch(get(TmdbUrls.search(type, title, year), key)), title, year)
            // Playlists sometimes have the wrong year; try once more without it.
            if (best == null && year != null) {
                best = TmdbParser.pickBest(TmdbParser.parseSearch(get(TmdbUrls.search(type, title, null), key)), title, null)
            }
            val entry = if (best == null) {
                MetadataEntity(cacheKey = cacheKey, found = false, fetchedAt = now)
            } else {
                val details = TmdbParser.parseDetails(get(TmdbUrls.details(type, best.id), key))
                MetadataEntity(
                    cacheKey = cacheKey,
                    found = true,
                    fetchedAt = now,
                    tmdbId = details.id,
                    title = details.title,
                    year = details.year,
                    overview = details.overview,
                    poster = details.poster,
                    backdrop = details.backdrop,
                    rating = details.rating,
                    runtimeMinutes = details.runtimeMinutes,
                    genres = details.genres.joinToString(", "),
                    people = (details.directors.map { "D" to it } + details.cast.map { "C" to it })
                        .joinToString("\n") { (kind, p) ->
                            listOf(kind, p.id.toString(), p.name, p.role.orEmpty(), p.photo.orEmpty())
                                .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
                        },
                    seasons = details.seasons,
                )
            }
            db.metadata().save(entry)
            entry.takeIf { it.found }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun hasKey(): Boolean = settings.tmdbKey.first().isNotBlank()

    /** People whose names match [text]; empty without a key or when offline. */
    suspend fun searchPeople(text: String): List<TmdbPerson> = withContext(Dispatchers.IO) {
        val key = settings.tmdbKey.first().trim()
        if (key.isEmpty()) return@withContext emptyList()
        try {
            TmdbParser.parsePeopleSearch(get(TmdbUrls.searchPerson(text), key)).take(6)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Everything [personId] worked on that is in the viewer's playlists, newest first. Null when it can't be checked. */
    suspend fun personInLibrary(personId: Long): List<VodRow>? = withContext(Dispatchers.IO) {
        val key = settings.tmdbKey.first().trim()
        if (key.isEmpty()) return@withContext null
        try {
            val credits = TmdbParser.parseCredits(get(TmdbUrls.personCredits(personId), key))
            libraryIndex().find(credits)
        } catch (e: Exception) {
            null
        }
    }

    /** Suggestions already worked out this session, by watched title. */
    private val suggested = HashMap<WatchedTitle, List<VodRow>>()

    /**
     * Films and series in the viewer's playlists that TMDB recommends for the ones they've watched,
     * one list per watched title (most recent first). Titles already watched are left out. Empty without a key.
     */
    suspend fun recommendations(watched: List<WatchedTitle>, titles: Int = 3): List<Pair<WatchedTitle, List<VodRow>>> =
        withContext(Dispatchers.IO) {
            val key = settings.tmdbKey.first().trim()
            if (key.isEmpty() || watched.isEmpty()) return@withContext emptyList()
            val seen = watched.map { (if (it.isSeries) "S" else "F") + TitleCleaner.clean(it.name).first.lowercase().filter(Char::isLetterOrDigit) }.toSet()
            fun watchedAlready(row: VodRow): Boolean {
                val k = (if (row.kind == ChannelEntity.KIND_SERIES) "S" else "F") + TitleCleaner.clean(row.name).first.lowercase().filter(Char::isLetterOrDigit)
                return k in seen
            }
            val index = libraryIndex()
            val shown = HashSet<Long>()
            watched.take(titles * 2).mapNotNull { title ->
                val rows = suggested[title] ?: run {
                    val info = lookup(title.name, if (title.isSeries) ChannelEntity.KIND_SERIES else ChannelEntity.KIND_MOVIE)
                    val id = info?.tmdbId ?: return@mapNotNull null
                    val type = if (title.isSeries) "tv" else "movie"
                    val found = try {
                        index.find(TmdbParser.parseRecommendations(get(TmdbUrls.recommendations(type, id), key), type))
                    } catch (e: Exception) {
                        return@mapNotNull null
                    }
                    found.also { suggested[title] = it }
                }
                // Each suggestion appears once across the lists; M3U episodes collapse to their show.
                val fresh = rows.map { ChannelsRepository.asShow(it) }
                    .filterNot { watchedAlready(it) }
                    .distinctBy { it.kind + it.name }
                    .filter { shown.add(it.id) }
                if (fresh.isEmpty()) null else title to fresh.take(20)
            }.take(titles)
        }

    private suspend fun libraryIndex(): LibraryIndex<VodRow> {
        val count = db.channels().vodCount()
        index?.let { if (it.size == count) return it }
        val rows = db.channels().allVod()
        // M3U episodes ("Harbour Lights S01E03") are indexed under their show's name.
        return LibraryIndex(rows, { ChannelsRepository.asShow(it).name }, { it.kind == ChannelEntity.KIND_SERIES }).also { index = it }
    }

    /** Returns null when [key] works, otherwise a reason it doesn't. */
    suspend fun checkKey(key: String): String? = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext "Enter a key first."
        try {
            get(TmdbUrls.check(), key)
            null
        } catch (e: KeyRejected) {
            e.message
        } catch (e: UnknownHostException) {
            "Couldn't reach TMDB. Check the internet connection."
        } catch (e: Exception) {
            e.message ?: "The check failed."
        }
    }

    private fun get(url: String, key: String): String {
        val request = Request.Builder()
            .url(TmdbUrls.withKey(url, key))
            .header("Accept", "application/json")
            .apply { if (TmdbUrls.usesBearer(key)) header("Authorization", "Bearer ${key.trim()}") }
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 401) throw KeyRejected()
            if (!response.isSuccessful) throw IOException("TMDB answered with error ${response.code}.")
            response.body?.string() ?: throw IOException("TMDB sent nothing back.")
        }
    }

    private companion object {
        /** A title TMDB didn't know is tried again after a week. */
        const val RETRY_MISS_MILLIS = 7 * 86_400_000L
    }
}

/** Directors (and creators) first, then cast, as stored with a TMDB look-up. */
fun MetadataEntity.peopleList(): List<Pair<Boolean, TmdbPerson>> =
    people.lines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val id = parts[1].toLongOrNull() ?: return@mapNotNull null
        (parts[0] == "D") to TmdbPerson(
            id = id,
            name = parts[2],
            role = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
            photo = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
        )
    }
