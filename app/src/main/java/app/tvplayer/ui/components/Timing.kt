package app.tvplayer.ui.components

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

/**
 * How long screens take to open, measured on this box: from the screen starting to build until its
 * content is drawn. Shown in Settings › Refresh and check, and written to the system log (tag
 * "SkyfallTiming") for the automatic speed check.
 */
object Timing {
    /** The most recent time for each screen this session, in milliseconds, with the first time too. */
    val latest = mutableStateMapOf<String, Long>()
    val first = mutableStateMapOf<String, Long>()

    fun record(name: String, millis: Long) {
        Log.i("SkyfallTiming", "$name $millis ms")
        if (name !in first) first[name] = millis
        latest[name] = millis
    }

    /** Other findings for the Speed report, as a line of text each (how smoothly the guide moves, and so on). */
    val notes = mutableStateMapOf<String, String>()

    fun note(name: String, text: String) {
        Log.i("SkyfallTiming", "$name: $text")
        notes[name] = text
    }
}

/**
 * Times the frames drawn while something is being moved (a key pressed in the last half second), and
 * notes how many came late: a late frame is a moment the screen visibly stuck.
 */
class FrameWatch(private val name: String) {
    internal val poked = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    @Volatile internal var lastPoke = 0L
    internal var frames = 0L
    internal var late = 0L
    internal var worst = 0L

    /** Something moved: watch the frames for the next half second. */
    fun poke() {
        lastPoke = SystemClock.uptimeMillis()
        poked.trySend(Unit)
    }

    internal fun report() {
        if (frames < 30) return
        Timing.note(name, "${late * 100 / frames}% of frames late, worst ${worst} ms (${frames} frames)")
    }
}

/** Watches the frames for [watch] while this is on screen. */
@Composable
fun WatchFrames(watch: FrameWatch) {
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(watch) {
        while (true) {
            watch.poked.receive()
            // A frame is late when it takes half as long again as the screen's refresh (50 or 60 times a second).
            val hz = (view.display?.refreshRate ?: 60f).coerceIn(20f, 144f)
            val lateAfter = (1000f / hz * 1.5f).toLong()
            var previous = withFrameNanos { it }
            while (SystemClock.uptimeMillis() - watch.lastPoke < 500) {
                val time = withFrameNanos { it }
                val millis = (time - previous) / 1_000_000
                previous = time
                watch.frames++
                if (millis > lateAfter) watch.late++
                if (millis > watch.worst) watch.worst = millis
            }
            watch.report()
        }
    }
}

/** Records how long [name] took to show its content, once [ready] is true (once per time the screen opens). */
@Composable
fun MeasureOpen(name: String, ready: Boolean) {
    val start = remember { SystemClock.uptimeMillis() }
    val done = remember { booleanArrayOf(false) }
    LaunchedEffect(ready) {
        if (!ready || done[0]) return@LaunchedEffect
        // Once the frame with the content has been drawn.
        withFrameNanos { }
        done[0] = true
        Timing.record(name, SystemClock.uptimeMillis() - start)
    }
}
