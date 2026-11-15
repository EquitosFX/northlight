package app.tvplayer.data

import android.content.Context
import app.tvplayer.core.subtitles.OpenSubtitles
import app.tvplayer.core.subtitles.SubtitleQuery
import app.tvplayer.core.subtitles.SubtitleResult
import app.tvplayer.core.subtitles.SubtitlesException
import app.tvplayer.core.tmdb.TitleCleaner
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.player.VodItem
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Finds and downloads subtitles for films and episodes from OpenSubtitles.com, using the viewer's
 * own API key (and account, if they added one).
 */
class SubtitlesRepository(
    context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val metadata: MetadataRepository,
) {
    private val dir = File(context.cacheDir, "subtitles")
    private var token: String? = null
    private var tokenFor: String? = null

    suspend fun language(): String = settings.subtitleLanguage.first()

    suspend fun search(item: VodItem): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val account = account()
        val query = queryFor(item)
        val text = call(account, Request.Builder().url(OpenSubtitles.searchUrl(query, language())).get())
        OpenSubtitles.parseSearch(text)
    }

    /** Downloads [result] and returns the subtitle file. */
    suspend fun download(result: SubtitleResult): File = withContext(Dispatchers.IO) {
        val account = account()
        val body = OpenSubtitles.downloadBody(result.fileId).toRequestBody(JSON)
        val link = OpenSubtitles.parseDownloadLink(call(account, Request.Builder().url("${OpenSubtitles.BASE}/download").post(body)))
        dir.mkdirs()
        val file = File(dir, "${result.fileId}.srt")
        http.newCall(Request.Builder().url(link).header("User-Agent", OpenSubtitles.USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) throw SubtitlesException("The subtitle file couldn't be downloaded (${response.code}).")
            file.writeBytes(response.body?.bytes() ?: ByteArray(0))
        }
        if (file.length() == 0L) throw SubtitlesException("The subtitle file was empty.")
        file
    }

    private suspend fun account(): OpenSubtitlesAccount {
        val account = settings.openSubtitles.first()
        if (account.apiKey.isBlank()) {
            throw SubtitlesException("Add your free OpenSubtitles API key in Settings › Player to search for subtitles.")
        }
        return account
    }

    private suspend fun queryFor(item: VodItem): SubtitleQuery {
        val episode = OpenSubtitles.seasonEpisode(item.title)
        return if (episode != null) {
            val series = item.title.substringBefore(" · ").trim()
            val info = metadata.lookup(series, ChannelEntity.KIND_SERIES)
            SubtitleQuery(seriesTmdbId = info?.tmdbId, season = episode.first, episode = episode.second, title = TitleCleaner.clean(series).first)
        } else {
            val info = metadata.lookup(item.title, ChannelEntity.KIND_MOVIE)
            val (title, year) = TitleCleaner.clean(item.title)
            SubtitleQuery(tmdbId = info?.tmdbId, title = title, year = info?.year ?: year)
        }
    }

    /** Sends a request with the API key (and sign-in, if any), signing in again once if it has expired. */
    private fun call(account: OpenSubtitlesAccount, builder: Request.Builder, retried: Boolean = false): String {
        val auth = signIn(account)
        val request = builder
            .header("Api-Key", account.apiKey)
            .header("User-Agent", OpenSubtitles.USER_AGENT)
            .header("Accept", "application/json")
            .apply { if (auth != null) header("Authorization", "Bearer $auth") }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401 && auth != null && !retried) {
                    token = null
                    return call(account, builder, retried = true)
                }
                if (!response.isSuccessful) {
                    // The download endpoint explains limits in its message; prefer that when it's there.
                    val message = runCatching { OpenSubtitles.parseDownloadLink(text) }.exceptionOrNull()?.message
                    throw SubtitlesException(
                        if (response.code == 406 && message != null) message else OpenSubtitles.explain(response.code),
                    )
                }
                return text
            }
        } catch (e: IOException) {
            throw SubtitlesException("Couldn't reach OpenSubtitles. Check the connection and try again.")
        }
    }

    private fun signIn(account: OpenSubtitlesAccount): String? {
        if (account.username.isBlank() || account.password.isBlank()) return null
        val who = account.username + "\u0000" + account.apiKey
        if (token != null && tokenFor == who) return token
        val request = Request.Builder()
            .url("${OpenSubtitles.BASE}/login")
            .header("Api-Key", account.apiKey)
            .header("User-Agent", OpenSubtitles.USER_AGENT)
            .header("Accept", "application/json")
            .post(OpenSubtitles.loginBody(account.username, account.password).toRequestBody(JSON))
            .build()
        val text = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SubtitlesException(
                        if (response.code == 401) "OpenSubtitles didn't accept that username and password." else OpenSubtitles.explain(response.code),
                    )
                }
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw SubtitlesException("Couldn't reach OpenSubtitles. Check the connection and try again.")
        }
        token = OpenSubtitles.parseToken(text)
        tokenFor = who
        return token
    }

    /** Checks the key (and account) by signing in or making a small search. Returns a message to show. */
    suspend fun check(): String = withContext(Dispatchers.IO) {
        try {
            val account = account()
            token = null
            if (signIn(account) != null) return@withContext "Signed in to OpenSubtitles."
            call(account, Request.Builder().url(OpenSubtitles.searchUrl(SubtitleQuery(tmdbId = 27205), "en")).get())
            "The OpenSubtitles key works."
        } catch (e: SubtitlesException) {
            e.message.orEmpty()
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
