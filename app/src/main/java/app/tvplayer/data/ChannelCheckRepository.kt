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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    /** How many channels are checked at once (as many as the account's free connections, up to 4). */
    val lanes: Int = 1,
    /** When the check started (uptime), for the time left. */
    val startedAt: Long = 0L,
    /** Channels the provider was too busy to let us check (left as they were). */
    val skipped: Int = 0,
) {
    /** Roughly how long is left, in minutes, once a few channels have been checked. */
    val minutesLeft: Int?
        get() {
            if (!running || checked < 5 || startedAt == 0L) return null
            val each = (android.os.SystemClock.uptimeMillis() - startedAt).toDouble() / checked
            return ((total - checked) * each / 60_000).toInt() + 1
        }
}

private enum class Result { WORKS, DEAD, BUSY }

/**
 * Checks channels by opening each stream briefly: one at a time, or as many at once as the account has
 * free connections (up to 4). A channel is marked "not working" only if none of its copies send any
 * video. If the provider says it's busy (too many connections), that channel is tried again on its own
 * at the end, and left as it was if it's still busy. The marks are kept until the next check, and shown
 * in the TV guide.
 */
class ChannelCheckRepository(
    context: Context,
    private val db: AppDatabase,
    http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("channel_check", Context.MODE_PRIVATE)
    private val client = http.newBuilder()
        // A working channel answers in a second or two; waiting longer only slows the check down on
        // dead ones (it gave up after 12 seconds at first, then 9, now 7).
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    private val deadState = MutableStateFlow(prefs.getStringSet(DEAD, emptySet()).orEmpty().toSet())
    /** Line-up keys of channels that failed their last check. */
    val dead: StateFlow<Set<String>> = deadState.asStateFlow()

    private val progressState = MutableStateFlow(CheckProgress())
    val progress: StateFlow<CheckProgress> = progressState.asStateFlow()

    private var job: Job? = null

    fun check(label: String, channels: List<LineupChannel>, lanes: Int = 1) {
        if (job?.isActive == true) return
        val width = lanes.coerceIn(1, MAX_LANES)
        job = scope.launch(Dispatchers.IO) {
            val failed = ArrayList<String>()
            val busy = ArrayList<LineupChannel>()
            var checked = 0
            val lock = Any()
            progressState.value = CheckProgress(true, label, 0, channels.size, lanes = width, startedAt = android.os.SystemClock.uptimeMillis())
            fun record(channel: LineupChannel, result: Result) = synchronized(lock) {
                val marks = deadState.value.toMutableSet()
                when (result) {
                    Result.WORKS -> marks.remove(channel.key)
                    Result.DEAD -> {
                        marks.add(channel.key)
                        failed += channel.name
                    }
                    Result.BUSY -> Unit
                }
                if (result != Result.BUSY) save(marks)
                checked++
                progressState.value = progressState.value.copy(checked = checked, failed = failed.toList())
            }
            // Several at once, each taking the next channel in the list.
            val next = java.util.concurrent.atomic.AtomicInteger(0)
            coroutineScope {
                repeat(width) {
                    launch {
                        while (isActive) {
                            val i = next.getAndIncrement()
                            if (i >= channels.size) break
                            val channel = channels[i]
                            val result = checkChannel(channel)
                            if (result == Result.BUSY) synchronized(lock) { busy += channel } else record(channel, result)
                        }
                    }
                }
            }
            // The ones the provider was too busy for: again, one at a time, after a moment.
            var skipped = 0
            if (busy.isNotEmpty() && isActive) delay(2_000)
            for (channel in busy) {
                if (!isActive) break
                val result = checkChannel(channel)
                if (result == Result.BUSY) skipped++
                record(channel, result)
            }
            progressState.value = progressState.value.copy(running = false, skipped = skipped)
        }
    }

    /** Whether any of a channel's first three copies sends video. */
    private fun checkChannel(channel: LineupChannel): Result {
        var sawBusy = false
        for (id in channel.variantIds.take(3)) {
            val row = db.channels().getBlocking(id) ?: continue
            when (works(row.url, row.userAgent, row.referrer)) {
                Result.WORKS -> return Result.WORKS
                Result.BUSY -> sawBusy = true
                Result.DEAD -> Unit
            }
        }
        return if (sawBusy) Result.BUSY else Result.DEAD
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

    /**
     * Opens the stream and reads its first bytes: video data, or an HLS playlist. "Busy" when the
     * provider turns it away for too many connections at once (or, checking several at once, any refusal).
     */
    private fun works(url: String, userAgent: String?, referrer: String?): Result = runCatching {
        val request = Request.Builder().url(url).apply {
            userAgent?.let { header("User-Agent", it) }
            referrer?.let { header("Referer", it) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code in BUSY_CODES || (response.code == 403 && progressState.value.lanes > 1)) return@use Result.BUSY
            if (!response.isSuccessful) return@use Result.DEAD
            val body = response.body ?: return@use Result.DEAD
            // Five video packets are enough to know it's sending a picture (it read 4 KB at first).
            val buffer = ByteArray(940)
            var read = 0
            val stream = body.byteStream()
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            val start = String(buffer, 0, minOf(read, 16), Charsets.ISO_8859_1)
            if (start.startsWith("#EXTM3U") || read >= 188) Result.WORKS else Result.DEAD
        }
    }.getOrDefault(Result.DEAD)

    companion object {
        private const val DEAD = "dead"
        /** The most channels checked at once. */
        const val MAX_LANES = 4
        /** What providers answer when there are too many connections at once. */
        private val BUSY_CODES = setOf(429, 456, 458, 509)
    }
}
