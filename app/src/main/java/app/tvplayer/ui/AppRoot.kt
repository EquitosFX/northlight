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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.tvplayer.ui.person.PersonScreen
import app.tvplayer.core.tmdb.TmdbPerson
import app.tvplayer.data.db.VodRow
import app.tvplayer.ui.vod.VodBrowseState
import app.tvplayer.ui.vod.VodScreen
import app.tvplayer.ui.components.Dest
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.TopNavBar
import app.tvplayer.ui.guide.GuideScreen
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.ui.home.HomeHero
import app.tvplayer.ui.home.HomeRail
import app.tvplayer.ui.home.HomeScreen
import app.tvplayer.ui.home.RecentsScreen
import app.tvplayer.ui.home.Tile
import app.tvplayer.ui.settings.SettingsScreen
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import app.tvplayer.ui.theme.ThemeId

// Stand-in tile colours behind channel logos.
private val tileColors = listOf(0xFF2F7D50, 0xFF3E5C8A, 0xFF8A4B2E, 0xFF5A3B78, 0xFF2E6F78, 0xFF4B5D67)

private fun tileColor(seed: String): Long = tileColors[(seed.hashCode() and 0x7fffffff) % tileColors.size]

private val shortcuts = listOf("Live TV", "TV Guide", "Films", "Series", "Search")

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

    var voiceToken by remember { mutableIntStateOf(0) }

    fun play(channelId: Long?) {
        controller.open(channelId)
        mode = PlayerMode.FULL
    }

    fun playKey(key: String) {
        controller.open(null, key)
        mode = PlayerMode.FULL
    }

    fun playVod(item: VodItem, episodes: List<VodItem> = listOf(item)) {
        controller.playVod(item, episodes)
        mode = PlayerMode.FULL
    }

    // Kept here so Films and Series reopen where the viewer left them after watching something.
    val filmsState = remember { VodBrowseState() }
    val seriesState = remember { VodBrowseState() }

    // A person's page sits over whatever section opened it; Back returns there.
    var person by remember { mutableStateOf<TmdbPerson?>(null) }

    fun openTitle(row: VodRow) {
        person = null
        when {
            row.kind == ChannelEntity.KIND_SERIES && row.remoteId != null -> {
                seriesState.series = row
                dest = Dest.SERIES
            }
            row.kind == ChannelEntity.KIND_SERIES -> {
                seriesState.film = row
                dest = Dest.SERIES
            }
            else -> {
                filmsState.film = row
                dest = Dest.FILMS
            }
        }
    }

    val progress by container.channels.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    val continueWatching = remember(progress) {
        progress.map { saved ->
            val minutesLeft = ((saved.durationMs - saved.positionMs) / 60_000).coerceAtLeast(1)
            Tile(
                title = saved.title,
                caption = if (saved.durationMs > 0) "$minutesLeft min left" else "Carry on watching",
                color = tileColor(saved.url),
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

    BackHandler(enabled = person != null && mode != PlayerMode.FULL) { person = null }

    // Home and Recents are built from the viewer's own playlists, history and favourites.
    val playlistList by container.playlists.playlists.collectAsStateWithLifecycle(initialValue = null)
    val history by container.channels.recentChannels.collectAsStateWithLifecycle(initialValue = emptyList())
    val favouriteKeys by container.channels.favouriteKeys.collectAsStateWithLifecycle(initialValue = emptySet())

    val recentTiles by produceState(initialValue = emptyList<Tile>(), history) {
        value = history.map { entry ->
            val now = container.channels.nowOn(entry.playlistId, entry.guideId)
            Tile(
                title = entry.name,
                caption = now?.title ?: "Channel ${entry.number}",
                color = tileColor(entry.key),
                channelKey = entry.key,
                logo = entry.logo,
            )
        }
    }

    // Favourites, or the first channels when there are none yet, with what's on each.
    val channelTiles by produceState(initialValue = (false to emptyList<Tile>()), favouriteKeys, playlistList) {
        val lineup = container.channels.lineup()
        val favourites = favouriteKeys.mapNotNull { lineup.findByKey(it) }
        val shown = favourites.ifEmpty { lineup.channels.take(30) }
        val now = System.currentTimeMillis()
        suspend fun tileFor(channel: LineupChannel): Tile {
            val programme = container.channels.nowAndNext(channel).firstOrNull { it.start <= now }
            return Tile(
                title = channel.name,
                caption = programme?.title ?: channel.group ?: "Live",
                color = tileColor(channel.key),
                channelKey = channel.key,
                logo = channel.logo,
            )
        }
        value = favourites.isNotEmpty() to shown.map { tileFor(it) }
    }

    fun openTile(tile: Tile) {
        val vod = tile.vod
        val key = tile.channelKey
        val id = tile.channelId
        when {
            vod != null -> playVod(vod)
            key != null -> playKey(key)
            id != null -> play(id)
        }
    }

    val lists = playlistList
    val lastWatched = history.firstOrNull()
    val hero = when {
        lists == null -> HomeHero("NORTHLIGHT", "Loading…", null, null, null, "Settings", { dest = Dest.SETTINGS })
        lists.isEmpty() -> HomeHero(
            overline = "WELCOME",
            title = "Welcome to Northlight",
            tagline = "Add your Xtream Codes account or M3U playlist to bring in your channels, films and series.",
            meta = null,
            logo = null,
            primaryLabel = "Add a playlist",
            onPrimary = { dest = Dest.SETTINGS },
        )
        lastWatched != null -> HomeHero(
            overline = "CARRY ON WATCHING",
            title = lastWatched.name,
            tagline = recentTiles.firstOrNull()?.caption?.takeIf { !it.startsWith("Channel ") },
            meta = "Channel ${lastWatched.number}",
            logo = lastWatched.logo,
            primaryLabel = "Watch",
            onPrimary = { playKey(lastWatched.key) },
            secondaryLabel = "TV Guide",
            onSecondary = { dest = Dest.GUIDE },
        )
        else -> HomeHero(
            overline = "LIVE TV",
            title = "Your channels are ready",
            tagline = "${lists.sumOf { it.liveCount }} live channels, ${lists.sumOf { it.movieCount }} films and " +
                "${lists.sumOf { it.seriesCount }} series.",
            meta = null,
            logo = null,
            primaryLabel = "Watch live TV",
            onPrimary = { play(null) },
            secondaryLabel = "TV Guide",
            onSecondary = { dest = Dest.GUIDE },
        )
    }
    val (hasFavourites, shownChannels) = channelTiles
    val homeRails = listOf(
        HomeRail("Continue watching", continueWatching),
        HomeRail("Recent channels", recentTiles),
        HomeRail(if (hasFavourites) "Favourites" else "Channels", shownChannels),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.ground)
            .onPreviewKeyEvent { event ->
                // The remote's search or voice key opens Search and starts listening.
                if ((event.key == Key.Search || event.key == Key.VoiceAssist) && mode != PlayerMode.FULL) {
                    if (event.type == KeyEventType.KeyDown) {
                        person = null
                        dest = Dest.SEARCH
                        voiceToken++
                    }
                    return@onPreviewKeyEvent true
                }
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
                    onSelect = { chosen ->
                        person = null
                        dest = chosen
                    },
                    appName = stringResource(R.string.app_name),
                )
                Spacer(Modifier.height(10.dp))
                val shownPerson = person
                if (shownPerson != null) {
                    PersonScreen(person = shownPerson, metadata = container.metadata, onOpen = { row -> openTitle(row) })
                } else when (dest) {
                    Dest.HOME -> HomeScreen(
                        hero = hero,
                        rails = homeRails,
                        shortcuts = shortcuts,
                        onTile = { tile -> openTile(tile) },
                        onShortcut = { label ->
                            when (label) {
                                "Live TV" -> play(null)
                                "TV Guide" -> dest = Dest.GUIDE
                                "Films" -> dest = Dest.FILMS
                                "Series" -> dest = Dest.SERIES
                                "Search" -> dest = Dest.SEARCH
                            }
                        },
                    )
                    Dest.RECENTS -> RecentsScreen(
                        rails = listOf(HomeRail("Recent channels", recentTiles), HomeRail("Continue watching", continueWatching)),
                        onTile = { tile -> openTile(tile) },
                    )
                    Dest.FILMS -> VodScreen(
                        kind = ChannelEntity.KIND_MOVIE,
                        state = filmsState,
                        channels = container.channels,
                        playlists = container.playlists,
                        metadata = container.metadata,
                        onPlay = { item, episodes -> playVod(item, episodes) },
                        onPerson = { chosen -> person = chosen },
                    )
                    Dest.SERIES -> VodScreen(
                        kind = ChannelEntity.KIND_SERIES,
                        state = seriesState,
                        channels = container.channels,
                        playlists = container.playlists,
                        metadata = container.metadata,
                        onPlay = { item, episodes -> playVod(item, episodes) },
                        onPerson = { chosen -> person = chosen },
                    )
                    Dest.SEARCH -> SearchScreen(
                        channels = container.channels,
                        metadata = container.metadata,
                        voiceToken = voiceToken,
                        onPerson = { chosen -> person = chosen },
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
