package app.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.AppContainer
import app.tvplayer.R
import app.tvplayer.player.LivePlayerController
import app.tvplayer.player.PlayerHost
import app.tvplayer.player.PlayerMode
import app.tvplayer.player.VodItem
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.ui.search.SearchScreen
import app.tvplayer.ui.vod.VodBrowseState
import app.tvplayer.ui.vod.VodScreen
import app.tvplayer.ui.components.Dest
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.TopNavBar
import app.tvplayer.ui.guide.GuideScreen
import app.tvplayer.ui.home.HomeScreen
import app.tvplayer.ui.home.Tile
import app.tvplayer.ui.settings.SettingsScreen
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import app.tvplayer.ui.theme.ThemeId

// Stand-in tile colours behind channel logos.
private val tileColors = listOf(0xFF2F7D50, 0xFF3E5C8A, 0xFF8A4B2E, 0xFF5A3B78, 0xFF2E6F78, 0xFF4B5D67)

/** The app shell: themed background, top navigation, the open section, and the video when something is playing. */
@Composable
fun AppRoot(
    themeId: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
    container: AppContainer,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        LivePlayerController(context.applicationContext, container.http, container.channels, container.settings, scope)
    }
    DisposableEffect(controller) { onDispose { controller.release() } }

    // Let go of the stream when the app goes into the background, and pick it up again on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onStop()
                Lifecycle.Event.ON_START -> controller.onStart()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Bring TV guides up to date in the background when the app opens.
    LaunchedEffect(Unit) { container.playlists.refreshStaleGuides() }

    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }
    var mode by remember { mutableStateOf(PlayerMode.NONE) }
    var backHeld by remember { mutableStateOf(false) }

    fun play(channelId: Long?) {
        controller.open(channelId)
        mode = PlayerMode.FULL
    }

    fun playVod(item: VodItem, episodes: List<VodItem> = listOf(item)) {
        controller.playVod(item, episodes)
        mode = PlayerMode.FULL
    }

    // Kept here so Films and Series reopen where the viewer left them after watching something.
    val filmsState = remember { VodBrowseState() }
    val seriesState = remember { VodBrowseState() }

    val progress by container.channels.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    val continueWatching = remember(progress) {
        progress.map { saved ->
            val minutesLeft = ((saved.durationMs - saved.positionMs) / 60_000).coerceAtLeast(1)
            Tile(
                title = saved.title,
                caption = if (saved.durationMs > 0) "$minutesLeft min left" else "Carry on watching",
                color = tileColors[(saved.url.hashCode() and 0x7fffffff) % tileColors.size],
                progress = if (saved.durationMs > 0) (saved.positionMs.toFloat() / saved.durationMs).coerceIn(0f, 1f) else null,
                logo = saved.poster,
                vod = VodItem(saved.url, saved.title, saved.poster, saved.userAgent, saved.referrer),
            )
        }
    }

    // Back returns to Home from any other section; from Home it leaves the app as usual.
    BackHandler(enabled = dest != Dest.HOME) { dest = Dest.HOME }
    // On Home with the small picture showing, Back stops playback first.
    BackHandler(enabled = dest == Dest.HOME && mode == PlayerMode.MINI) {
        controller.close()
        mode = PlayerMode.NONE
    }

    val liveChannels by container.playlists.firstLiveChannels.collectAsStateWithLifecycle(initialValue = emptyList())
    val yourChannels = remember(liveChannels) {
        liveChannels
            .distinctBy { it.playlistId to it.variantKey } // one tile per channel, however many quality copies it has
            .take(20)
            .map { channel ->
                Tile(
                    title = channel.baseName,
                    caption = channel.groupName ?: "Live",
                    color = tileColors[(channel.variantKey.hashCode() and 0x7fffffff) % tileColors.size],
                    channelId = channel.id,
                    logo = channel.logo,
                )
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.ground)
            .onPreviewKeyEvent { event ->
                // Holding Back while the small picture shows returns it to full screen.
                if (event.key != Key.Back) return@onPreviewKeyEvent false
                when {
                    backHeld -> {
                        // Swallow the rest of the held press so it isn't also treated as a normal Back.
                        if (event.type == KeyEventType.KeyUp) backHeld = false
                        true
                    }
                    mode == PlayerMode.MINI &&
                        event.type == KeyEventType.KeyDown &&
                        event.nativeKeyEvent.repeatCount > 0 -> {
                        backHeld = true
                        mode = PlayerMode.FULL
                        true
                    }
                    else -> false
                }
            },
    ) {
        if (mode != PlayerMode.FULL) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopNavBar(
                    selected = dest,
                    onSelect = { chosen -> dest = chosen },
                    appName = stringResource(R.string.app_name),
                )
                Spacer(Modifier.height(10.dp))
                when (dest) {
                    Dest.HOME -> HomeScreen(
                        yourChannels = yourChannels,
                        continueWatching = continueWatching,
                        onPlayChannel = { id -> play(id) },
                        onPlayVod = { item -> playVod(item) },
                        onLiveTv = { play(null) },
                        onShortcut = { label ->
                            when (label) {
                                "Films" -> dest = Dest.FILMS
                                "Series" -> dest = Dest.SERIES
                            }
                        },
                    )
                    Dest.FILMS -> VodScreen(
                        kind = ChannelEntity.KIND_MOVIE,
                        state = filmsState,
                        channels = container.channels,
                        playlists = container.playlists,
                        metadata = container.metadata,
                        onPlay = { item, episodes -> playVod(item, episodes) },
                    )
                    Dest.SERIES -> VodScreen(
                        kind = ChannelEntity.KIND_SERIES,
                        state = seriesState,
                        channels = container.channels,
                        playlists = container.playlists,
                        metadata = container.metadata,
                        onPlay = { item, episodes -> playVod(item, episodes) },
                    )
                    Dest.SEARCH -> SearchScreen(
                        channels = container.channels,
                        onOpen = { row ->
                            when {
                                row.kind == ChannelEntity.KIND_LIVE -> play(row.id)
                                row.kind == ChannelEntity.KIND_SERIES && row.remoteId != null -> {
                                    seriesState.series = row
                                    dest = Dest.SERIES
                                }
                                row.kind == ChannelEntity.KIND_MOVIE -> {
                                    filmsState.film = row
                                    dest = Dest.FILMS
                                }
                                else -> playVod(VodItem(row.url, row.name, row.logo, row.userAgent, row.referrer))
                            }
                        },
                    )
                    Dest.GUIDE -> GuideScreen(
                        channels = container.channels,
                        onPlay = { channel -> play(channel.variantIds.first()) },
                    )
                    Dest.SETTINGS -> SettingsScreen(
                        themeId = themeId,
                        onThemeChange = onThemeChange,
                        playlists = container.playlists,
                        settings = container.settings,
                        metadata = container.metadata,
                    )
                    else -> ComingLater(dest)
                }
            }
        }

        if (mode != PlayerMode.NONE) {
            val colors = AppTheme.colors
            PlayerHost(
                controller = controller,
                mode = mode,
                onMinimise = { mode = PlayerMode.MINI },
                onExpand = { mode = PlayerMode.FULL },
                onClose = {
                    controller.close()
                    mode = PlayerMode.NONE
                },
                modifier = if (mode == PlayerMode.FULL) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 48.dp, bottom = 24.dp)
                        .size(width = 256.dp, height = 144.dp)
                        .border(width = 1.dp, color = colors.glassBorder)
                },
            )
        }
    }
}

@Composable
private fun ComingLater(dest: Dest) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Label(
            text = "${dest.label} is built in a later phase",
            style = AppType.headline,
            color = AppTheme.colors.textDim,
        )
    }
}
