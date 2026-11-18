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
