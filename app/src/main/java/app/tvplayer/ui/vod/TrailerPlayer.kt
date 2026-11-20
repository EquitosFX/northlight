package app.tvplayer.ui.vod

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.delay

/** What a YouTube id may look like (it comes from TMDB, so it is checked before going into the page). */
private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{6,20}")

/** How long a trailer may take to start before the YouTube app is tried instead. */
private const val START_TIMEOUT_MILLIS = 15_000L

/**
 * Plays a YouTube trailer inside Skyfall, full screen, using YouTube's own embedded player. OK pauses,
 * Left and Right skip 10 seconds, Back closes; it closes by itself at the end. If YouTube won't play it
 * here (some trailers can only be watched on YouTube itself), the YouTube app is opened instead.
 */
@Composable
internal fun TrailerPlayer(youtubeId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    var started by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var length by remember { mutableFloatStateOf(0f) }
    var lastKey by remember { mutableLongStateOf(0L) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val web = remember {
        trailerWebView(context, youtubeId, object : TrailerEvents {
            override fun state(code: Int) {
                when (code) {
                    1 -> { started = true; playing = true }
                    2 -> playing = false
                    0 -> onClose()
                }
            }
            override fun error(code: Int) { failed = true }
            override fun time(at: Float, of: Float) { position = at; length = of }
        })
    }
    DisposableEffect(web) {
        onDispose {
            runCatching {
                web.stopLoading()
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
    }
    // Couldn't play here (or not in time): over to the YouTube app.
    LaunchedEffect(failed) {
        if (failed) {
            onClose()
            openTrailer(context, youtubeId)
        }
    }
    LaunchedEffect(Unit) {
        delay(START_TIMEOUT_MILLIS)
        if (!started) failed = true
    }
    androidx.activity.compose.BackHandler { onClose() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    run {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focus)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    lastKey = android.os.SystemClock.uptimeMillis()
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.Spacebar -> {
                            web.evaluateJavascript("toggle()", null)
                            true
                        }
                        Key.DirectionLeft, Key.MediaRewind -> { web.evaluateJavascript("seek(-10)", null); true }
                        Key.DirectionRight, Key.MediaFastForward -> { web.evaluateJavascript("seek(10)", null); true }
                        Key.DirectionUp, Key.DirectionDown -> true
                        else -> false
                    }
                }
                .focusable(),
        ) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
            if (!started) {
                Label(text = "Loading trailer…", style = AppType.title, color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            // The time bar and what the buttons do: shown while paused and for a few seconds after a press.
            var recent by remember { mutableStateOf(true) }
            LaunchedEffect(lastKey, started) {
                recent = true
                delay(3_000)
                recent = false
            }
            if (started && (!playing || recent)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 48.dp, vertical = 22.dp),
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                        val done = if (length > 0f) (position / length).coerceIn(0f, 1f) else 0f
                        Box(modifier = Modifier.fillMaxWidth(done).height(4.dp).background(colors.accent))
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Label(
                            text = (if (playing) "" else "Paused · ") + "${clock(position)} / ${clock(length)}",
                            style = AppType.label,
                            color = Color.White,
                        )
                        Label(text = "OK pause · ◀ ▶ 10 seconds · Back to close", style = AppType.caption, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

private fun clock(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private interface TrailerEvents {
    fun state(code: Int)
    fun error(code: Int)
    fun time(at: Float, of: Float)
}

/** Passes what the page's player does back to Skyfall, on the main thread. */
private class Bridge(private val events: TrailerEvents) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun state(code: Int) { main.post { events.state(code) } }

    @JavascriptInterface fun error(code: Int) { main.post { events.error(code) } }

    @JavascriptInterface fun time(at: Double, of: Double) { main.post { events.time(at.toFloat(), of.toFloat()) } }
}

@SuppressLint("SetJavaScriptEnabled")
private fun trailerWebView(context: Context, youtubeId: String, events: TrailerEvents): WebView {
    val web = WebView(context)
    web.setBackgroundColor(android.graphics.Color.BLACK)
    // Keys go to Skyfall (which passes them to the player), not into the page.
    web.isFocusable = false
    web.isFocusableInTouchMode = false
    web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
    }
    web.webViewClient = WebViewClient()
    web.webChromeClient = WebChromeClient()
    if (!YOUTUBE_ID.matches(youtubeId)) {
        Handler(Looper.getMainLooper()).post { events.error(-1) }
        return web
    }
    web.addJavascriptInterface(Bridge(events), "Skyfall")
    // YouTube asks apps embedding its player to say who they are (the page's address is sent as the
    // Referer); without one some trailers refuse to play.
    web.loadDataWithBaseURL(ORIGIN, page(youtubeId), "text/html", "utf-8", null)
    return web
}

private const val ORIGIN = "https://app.tvplayer.skyfall"

private fun page(id: String) = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;left:0;top:0;width:100%;height:100%}</style>
</head><body>
<div id="p"></div>
<script>
var player = null;
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%', videoId: '$id',
    playerVars: { autoplay: 1, controls: 0, rel: 0, playsinline: 1, fs: 0, iv_load_policy: 3, disablekb: 1, origin: '$ORIGIN' },
    events: {
      onReady: function (e) { e.target.playVideo(); },
      onStateChange: function (e) { Skyfall.state(e.data); },
      onError: function (e) { Skyfall.error(e.data); }
    }
  });
  setInterval(function () {
    if (player && player.getCurrentTime) Skyfall.time(player.getCurrentTime() || 0, player.getDuration() || 0);
  }, 500);
}
function toggle() { if (!player || !player.getPlayerState) return; if (player.getPlayerState() == 1) player.pauseVideo(); else player.playVideo(); }
function seek(by) { if (!player || !player.getCurrentTime) return; player.seekTo(Math.max(0, player.getCurrentTime() + by), true); }
var tag = document.createElement('script');
tag.src = 'https://www.youtube.com/iframe_api';
tag.onerror = function () { Skyfall.error(-2); };
document.body.appendChild(tag);
</script>
</body></html>
""".trimIndent()
