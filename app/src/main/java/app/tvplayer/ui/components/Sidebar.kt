package app.tvplayer.ui.components

import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import app.tvplayer.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** Width of the side panel when open, and how much of it stays showing when tucked away. */
val SidebarWidth = 248.dp
val SidebarPeek = 18.dp

/** Where the live picture sits inside the side panel. */
val SidebarPictureTop = 62.dp
val SidebarPictureSide = 20.dp
val SidebarPictureWidth = 208.dp
val SidebarPictureHeight = 117.dp

/** The sections in the side menu, top to bottom. */
val SidebarSections = listOf(
    Dest.HOME, Dest.GUIDE, Dest.RECENTS, Dest.FILMS, Dest.SERIES, Dest.SPORT, Dest.SEARCH, Dest.MY_LIST, Dest.MOVIE_NIGHT, Dest.SETTINGS,
)

/**
 * The side panel: the time, the live picture (drawn over [SidebarPictureTop] by the player when something
 * is playing), and the menu. Moving through the menu shows each section straight away; OK or Right
 * moves into it. [itemFocus] lets the app put focus back on a menu item.
 */
@Composable
fun Sidebar(
    selected: Dest,
    onSelect: (Dest) -> Unit,
    onEnter: () -> Unit,
    appName: String,
    showingPicture: Boolean,
    /** Menu items that show a dot, such as Settings when an update is ready. */
    badged: Set<Dest> = emptySet(),
    itemFocus: Map<Dest, FocusRequester>,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .background(Brush.verticalGradient(listOf(colors.groundTop, colors.groundMid))),
    ) {
        // A faint edge line where the panel meets the content.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(1.dp)
                .fillMaxHeight()
                .background(colors.glassBorder),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = SidebarPictureSide, end = SidebarPictureSide, top = 18.dp, bottom = 20.dp),
        ) {
            Label(text = rememberClock(), style = AppType.clock, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(SidebarPictureTop - 18.dp - 24.dp))
            Box(
                modifier = Modifier
                    .size(SidebarPictureWidth, SidebarPictureHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                if (!showingPicture) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Image(
                            painter = painterResource(R.drawable.ic_skyfall_s),
                            contentDescription = appName,
                            modifier = Modifier.size(width = 26.dp, height = 44.dp),
                        )
                        Image(
                            painter = painterResource(R.drawable.ic_skyfall_wordmark),
                            contentDescription = null,
                            modifier = Modifier.size(width = 72.dp, height = 11.dp),
                        )
                        Label(text = "Live TV shows here", style = AppType.caption, color = colors.textDim)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Coming back to the menu from a screen lands on the open section, not the nearest item.
            val menuEntry = remember { EntryFocus() }
            // Set when Up or Down is pressed in the menu: only that changes the section.
            val movedByKey = remember { AtomicBoolean(false) }
            Column(
                modifier = Modifier
                    .entersAt(menuEntry)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
                            movedByKey.set(true)
                        }
                        false
                    },
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                for (dest in SidebarSections) {
                    if (dest == selected) EntryPoint(itemFocus.getValue(dest), menuEntry)
                    MenuItem(
                        dest = dest,
                        current = dest == selected,
                        badged = dest in badged,
                        focusRequester = itemFocus.getValue(dest),
                        onFocus = {
                            when {
                                // Moving up and down the menu shows each section.
                                movedByKey.getAndSet(false) -> onSelect(dest)
                                dest == selected -> Unit
                                // Focus landed here by itself (say, a button that was pressed disappeared):
                                // stay on the open section rather than switching.
                                else -> runCatching { itemFocus[selected]?.requestFocus() }
                            }
                        },
                        onClick = onEnter,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    dest: Dest,
    current: Boolean,
    badged: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .focusRequester(focusRequester),
        shape = RoundedCornerShape(3.dp),
        container = Color.Transparent,
        focusedContainer = colors.glassStrong,
        outlined = false,
        focusScale = 1f,
    ) { focused ->
        LaunchedEffect(focused) { if (focused) onFocus() }
        if (focused) {
            // A lit edge above and below the highlighted item.
            val glow = Brush.horizontalGradient(listOf(Color.Transparent, colors.text, Color.Transparent))
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp).background(glow))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(glow))
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label(
                text = dest.label,
                style = if (focused || current) AppType.menuSelected else AppType.menu,
                color = if (focused || current) colors.text else colors.textDim,
            )
            if (badged) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
        }
    }
}

@Composable
private fun rememberClock(): String {
    val format = remember { DateTimeFormatter.ofPattern("h.mma", Locale.UK) }
    fun now() = LocalTime.now().format(format).lowercase(Locale.UK)
    var text by remember { mutableStateOf(now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            text = now()
        }
    }
    return text
}
