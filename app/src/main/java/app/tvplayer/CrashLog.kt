package app.tvplayer

import android.content.Context
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
            append("Build ").append(build ?: "?").append(" · thread ").append(thread.name).append('\n')
            append(error.javaClass.simpleName).append(": ").append(scrub(error.message)).append('\n')
            if (root !== error) append("Caused by ").append(root.javaClass.simpleName).append(": ").append(scrub(root.message)).append('\n')
            frames.firstOrNull()?.let { append("at ").append(it.toString()).append('\n') }
            ours.forEach { append("at ").append(it.className.removePrefix("app.tvplayer.")).append('.').append(it.methodName).append(':').append(it.lineNumber).append('\n') }
        }.take(3_000)
    }

    /** Takes addresses out of a message, in case one carries account details. */
    private fun scrub(message: String?): String =
        message.orEmpty().replace(Regex("""https?://\S+"""), "<address>").take(400)
}
