package app.tvplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.tvplayer.R
import app.tvplayer.data.db.HistoryEntity
import app.tvplayer.data.db.ProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Skyfall on the Shield's own home screen: films and episodes to carry on with in the "Continue
 * watching" (Play next) row, and a Skyfall row of recently watched channels. Selecting one opens
 * Skyfall straight into it. Stream addresses (which hold sign-in details) never leave the app: the
 * home screen only gets a short code that Skyfall looks up.
 */
class HomeScreenRows(private val context: Context) {
    private val prefs = context.getSharedPreferences("home_rows", Context.MODE_PRIVATE)

    suspend fun update(progress: List<ProgressEntity>, recent: List<HistoryEntity>) = withContext(Dispatchers.IO) {
        runCatching { updateContinueWatching(progress) }
        runCatching { updateChannelRow(recent) }
    }

    /** The stream address behind a home-screen code, if it's still known. */
    fun addressFor(code: String): String? = prefs.getString(LINK + code, null)

    private fun updateContinueWatching(progress: List<ProgressEntity>) {
        val resolver = context.contentResolver
        prefs.getStringSet(WATCH_NEXT, emptySet()).orEmpty().forEach { id ->
            id.toLongOrNull()?.let { runCatching { resolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) } }
        }
        val editor = prefs.edit()
        val ids = progress.filter { it.durationMs > 0 }.take(MAX_ITEMS).mapNotNull { saved ->
            val code = code(saved.url)
            editor.putString(LINK + code, saved.url)
            val episode = EPISODE.containsMatchIn(saved.title)
            val program = WatchNextProgram.Builder()
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setType(if (episode) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setTitle(saved.title)
                .setLastEngagementTimeUtcMillis(saved.updatedAt)
                .setLastPlaybackPositionMillis(saved.positionMs.toInt())
                .setDurationMillis(saved.durationMs.toInt())
                .setIntentUri(Uri.parse("$SCHEME://resume/$code"))
                .setInternalProviderId(code)
                .apply { saved.poster?.let { setPosterArtUri(Uri.parse(it)) } }
                .build()
            resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())?.let { ContentUris.parseId(it).toString() }
        }
        editor.putStringSet(WATCH_NEXT, ids.toSet()).apply()
    }

    private fun updateChannelRow(recent: List<HistoryEntity>) {
        val helper = PreviewChannelHelper(context)
        var channelId = prefs.getLong(CHANNEL, -1L)
        if (channelId >= 0 && runCatching { helper.getPreviewChannel(channelId) }.getOrNull() == null) channelId = -1L
        if (channelId < 0) {
            val logo = ContextCompat.getDrawable(context, R.drawable.ic_app)?.toBitmap(160, 160)
            val channel = PreviewChannel.Builder()
                .setDisplayName("Skyfall")
                .setDescription("Channels you watched lately")
                .setAppLinkIntentUri(Uri.parse("$SCHEME://home"))
                .setInternalProviderId("recent")
                .apply { if (logo != null) setLogo(logo) }
                .build()
            channelId = helper.publishChannel(channel)
            prefs.edit().putLong(CHANNEL, channelId).apply()
            runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
        }
        context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
        recent.take(MAX_ITEMS).forEach { channel ->
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(channel.name)
                .setDescription("Channel ${channel.number}")
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(Uri.parse("$SCHEME://channel/" + Uri.encode(channel.key)))
                .setInternalProviderId(channel.key)
                .apply { channel.logo?.let { setPosterArtUri(Uri.parse(it)) } }
                .build()
            helper.publishPreviewProgram(program)
        }
    }

    private fun code(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val SCHEME = "skyfall"
        private const val WATCH_NEXT = "watch_next"
        private const val CHANNEL = "channel"
        private const val LINK = "link_"
        private const val MAX_ITEMS = 12
        private val EPISODE = Regex(""" · S\d+ ?E\d+""")
    }
}
