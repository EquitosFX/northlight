package app.tvplayer

import android.content.Context
import app.tvplayer.ui.components.Timing
import java.io.File

/**
 * Keeps a note of why Skyfall last closed unexpectedly, so the next start can show it on screen
 * (there's no other easy way to see a crash on a TV). Only the error itself is kept: no playlist
 * addresses or account details.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(describe(app, thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Crashes inside the video and sound engines (VLC, FFmpeg) and "not responding" freezes don't go
     * through the handler above. Android records why the app last ended, so that's checked when it
     * starts and noted the same way. (Android 11 and later: both the Shield and Google TV.)
     */
    fun noteExitReason(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val app = context.applicationContext
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val last = manager.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull() ?: return
            val prefs = app.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
            if (last.timestamp <= prefs.getLong("seen_exit", 0L)) return
            prefs.edit().putLong("seen_exit", last.timestamp).apply()
            // How much memory Skyfall itself was using when it was closed (0 when Android didn't say).
            val usedMb = (maxOf(last.pss, last.rss) / 1024).toInt()
            val using = if (usedMb > 0) " Skyfall was using $usedMb MB." else ""
            // Cleared out of memory while in the background (another app open, or the TV asleep): that's
            // Android making room, as it does on every box, not a crash, so it's noted quietly instead.
            if (last.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY &&
                last.importance > android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
            ) {
                val at = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.UK).format(java.util.Date(last.timestamp))
                Timing.note(
                    "Last closed",
                    "$at · Android cleared Skyfall from memory while it was in the background.$using",
                )
                return
            }
            val what = when (last.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> "Skyfall stopped responding (froze) and Android closed it."
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Skyfall's video or sound engine crashed."
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed Skyfall while you were using it because the box ran out of memory.$using"
                else -> return
            }
            // A Java crash already wrote its own, fuller note.
            if (read(app) != null) return
            val build = runCatching {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0).versionCode
            }.getOrNull()
            val trace = if (last.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    last.traceInputStream?.bufferedReader()?.useLines { lines ->
                        // The main thread's lines in Skyfall's own code say where it was stuck.
                        lines.dropWhile { !it.startsWith("\"main\"") }.take(60).filter { "app.tvplayer" in it }.take(8).toList()
                    }
                }.getOrNull().orEmpty()
            } else {
                emptyList()
            }
            File(app.filesDir, FILE).writeText(
                buildString {
                    append("Build ").append(build ?: "?").append(" · ").append(device()).append('\n')
                    append(what).append('\n')
                    last.description?.let { append(scrub(it)).append('\n') }
                    trace.forEach { append(it.trim()).append('\n') }
                }.take(3_000),
            )
        }
    }

    private fun device() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"

    /** The note from the last crash, or null if Skyfall closed normally. */
    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    internal fun describe(context: Context, thread: Thread, error: Throwable): String {
        val build = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrNull()
        val root = generateSequence(error) { it.cause }.last()
        val frames = error.stackTrace
        // Skyfall's own lines first: they say where in the app it happened.
        val ours = frames.filter { it.className.startsWith("app.tvplayer") }.take(8)
        return buildString {
            append("Build ").append(build ?: "?").append(" · thread ").append(thread.name).append(" · ").append(device()).append('\n')
            append(error.javaClass.simpleName).append(": ").append(scrub(error.message)).append('\n')
            if (root !== error) append("Caused by ").append(root.javaClass.simpleName).append(": ").append(scrub(root.message)).append('\n')
            frames.firstOrNull()?.let { append("at ").append(it.toString()).append('\n') }
            ours.forEach { append("at ").append(it.className.removePrefix("app.tvplayer.")).append('.').append(it.methodName).append(':').append(it.lineNumber).append('\n') }
            // Where the underlying cause happened, when it's somewhere else in Skyfall.
            if (root !== error) {
                root.stackTrace.filter { it.className.startsWith("app.tvplayer") }.take(4).forEach {
                    append("cause at ").append(it.className.removePrefix("app.tvplayer.")).append('.').append(it.methodName).append(':').append(it.lineNumber).append('\n')
                }
            }
        }.take(3_000)
    }

    /** Takes addresses out of a message, in case one carries account details. */
    private fun scrub(message: String?): String =
        message.orEmpty().replace(Regex("""https?://\S+"""), "<address>").take(400)
}
