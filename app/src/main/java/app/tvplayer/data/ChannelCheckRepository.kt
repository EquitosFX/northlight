package app.tvplayer.data

import android.content.Context
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Where a channel check has got to. */
data class CheckProgress(
    val running: Boolean = false,
    val label: String = "",
    val checked: Int = 0,
    val total: Int = 0,
    /** Names of the channels that didn't respond in this check. */
    val failed: List<String> = emptyList(),
)

/**
 * Checks channels one at a time (most providers allow only one connection) by opening each stream
 * briefly. A channel is marked "not working" only if none of its copies send any video. The marks are
 * kept until the next check, and shown in the TV guide.
 */
class ChannelCheckRepository(
    context: Context,
    private val db: AppDatabase,
    http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("channel_check", Context.MODE_PRIVATE)
    private val client = http.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val deadState = MutableStateFlow(prefs.getStringSet(DEAD, emptySet()).orEmpty().toSet())
    /** Line-up keys of channels that failed their last check. */
    val dead: StateFlow<Set<String>> = deadState.asStateFlow()

    private val progressState = MutableStateFlow(CheckProgress())
    val progress: StateFlow<CheckProgress> = progressState.asStateFlow()

    private var job: Job? = null

    fun check(label: String, channels: List<LineupChannel>) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val failed = ArrayList<String>()
            progressState.value = CheckProgress(true, label, 0, channels.size)
            for ((i, channel) in channels.withIndex()) {
                if (!isActive) break
                val works = channel.variantIds.take(3).any { id -> db.channels().getBlocking(id)?.let { works(it.url, it.userAgent, it.referrer) } == true }
                val marks = deadState.value.toMutableSet()
                if (works) marks.remove(channel.key) else {
                    marks.add(channel.key)
                    failed += channel.name
                }
                save(marks)
                progressState.value = CheckProgress(true, label, i + 1, channels.size, failed.toList())
            }
            progressState.value = progressState.value.copy(running = false)
        }
    }

    fun stop() {
        job?.cancel()
        progressState.value = progressState.value.copy(running = false)
    }

    fun clearMarks() {
        save(emptySet())
        progressState.value = CheckProgress()
    }

    private fun save(marks: Set<String>) {
        deadState.value = marks
        prefs.edit().putStringSet(DEAD, marks).apply()
    }

    /** Opens the stream and reads its first bytes: video data, or an HLS playlist. */
    private fun works(url: String, userAgent: String?, referrer: String?): Boolean = runCatching {
        val request = Request.Builder().url(url).apply {
            userAgent?.let { header("User-Agent", it) }
            referrer?.let { header("Referer", it) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body ?: return@use false
            val buffer = ByteArray(4_096)
            var read = 0
            val stream = body.byteStream()
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            val start = String(buffer, 0, minOf(read, 16), Charsets.ISO_8859_1)
            start.startsWith("#EXTM3U") || read >= 188
        }
    }.getOrDefault(false)

    private companion object {
        const val DEAD = "dead"
    }
}
