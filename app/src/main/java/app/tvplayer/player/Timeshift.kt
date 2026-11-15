package app.tvplayer.player

import app.tvplayer.core.live.Reconnect
import app.tvplayer.core.live.SegmentStore
import app.tvplayer.core.live.TsSegmenter
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Keeps the recording's slices and playlist as files in [dir]. */
private class FileSegmentStore(private val dir: File, private val onPlaylist: () -> Unit) : SegmentStore {
    override fun open(sequence: Long): OutputStream =
        BufferedOutputStream(File(dir, segmentName(sequence)).outputStream(), 64 * 1024)

    override fun delete(sequence: Long) {
        File(dir, segmentName(sequence)).delete()
    }

    override fun writePlaylist(text: String) {
        // Written to the side then swapped in, so the player never reads half a playlist.
        val temp = File(dir, "index.tmp")
        temp.writeText(text)
        temp.renameTo(File(dir, PLAYLIST))
        onPlaylist()
    }

    companion object {
        const val PLAYLIST = "index.m3u8"
    }
}

/**
 * Records the live channel being watched into a rolling set of files, which the player plays as a
 * local live stream. That's what lets live TV be paused, rewound and fast-forwarded, then caught up.
 * Only one connection to the provider is used, so it works with single-connection accounts.
 */
class TimeshiftSession(
    private val http: OkHttpClient,
    private val url: String,
    private val userAgent: String,
    private val referrer: String?,
    root: File,
    windowSeconds: Double,
    scope: CoroutineScope,
    /** Called when the stream can't be reached any more, after reconnecting has been tried. */
    private val onFailed: () -> Unit,
) {
    val dir: File = File(root, "s${System.nanoTime()}").apply { mkdirs() }
    val playlist: File = File(dir, FileSegmentStore.PLAYLIST)

    /** Completes once a few seconds are recorded, so playback starts with room to spare and never runs dry. */
    val ready = CompletableDeferred<Unit>()

    // Use at most half the free space, and never more than 8 GB.
    private val maxBytes = (dir.usableSpace / 2).coerceIn(256L * 1024 * 1024, 8L * 1024 * 1024 * 1024)
    private val segmenter: TsSegmenter = TsSegmenter(
        FileSegmentStore(dir) { if (!ready.isCompleted && segmenter.recordedSeconds >= READY_SECONDS) ready.complete(Unit) },
        windowSeconds = windowSeconds,
        maxBytes = maxBytes,
    )

    @Volatile private var call: Call? = null
    @Volatile private var stopped = false
    private val job: Job = scope.launch(Dispatchers.IO) { record() }

    private suspend fun CoroutineScope.record() {
        var failures = 0
        while (isActive && !stopped) {
            var received = 0L
            try {
                val request = Request.Builder().url(url).header("User-Agent", userAgent).apply {
                    referrer?.let { header("Referer", it) }
                }.build()
                val next = http.newCall(request)
                call = next
                next.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val input = response.body?.byteStream() ?: throw IOException("Empty stream")
                    val buffer = ByteArray(64 * 1024)
                    while (isActive && !stopped) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        segmenter.feed(buffer, 0, n)
                        received += n
                        if (received > 1_000_000) failures = 0
                    }
                }
            } catch (e: IOException) {
                // Falls through to reconnect below.
            }
            if (stopped || !isActive) break
            // The stream ended or dropped: reconnect, and tell the player the next slice is a fresh start.
            segmenter.discontinuity()
            failures++
            if (failures > Reconnect.MAX_ATTEMPTS) {
                if (!ready.isCompleted) ready.completeExceptionally(IOException("Stream unavailable"))
                onFailed()
                break
            }
            delay(Reconnect.delayMillis(failures))
        }
    }

    fun stop() {
        stopped = true
        call?.cancel()
        job.cancel()
        if (!ready.isCompleted) ready.cancel()
        dir.deleteRecursively()
    }

    companion object {
        /** Seconds recorded before playback starts. */
        const val READY_SECONDS = 3.0

        /** Clears recordings left behind by a previous run. */
        fun clearAll(root: File) {
            root.listFiles()?.forEach { it.deleteRecursively() }
        }
    }
}
