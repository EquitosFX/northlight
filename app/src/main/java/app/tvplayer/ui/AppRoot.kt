@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.tvplayer.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import app.tvplayer.data.ChannelsRepository
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import app.tvplayer.ui.components.LocalEntryFocus
import app.tvplayer.ui.components.EntryFocus
import androidx.compose.foundation.focusGroup
import app.tvplayer.ui.components.LocalHoldFocus
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.components.Sidebar
import app.tvplayer.ui.components.SidebarPeek
import app.tvplayer.ui.components.SidebarPictureHeight
import app.tvplayer.ui.components.SidebarPictureSide
import app.tvplayer.ui.components.SidebarPictureTop
import app.tvplayer.ui.components.SidebarPictureWidth
import app.tvplayer.ui.components.SidebarSections
import app.tvplayer.ui.components.SidebarWidth
import kotlinx.coroutines.launch
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
import app.tvplayer.data.db.MetadataEntity
import app.tvplayer.ui.vod.VodBrowseState
import app.tvplayer.ui.vod.VodScreen
import app.tvplayer.ui.components.Dest
import app.tvplayer.ui.components.Label
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

/** The app shell: themed background, side panel, the open section, and the video when something is playing. */
@Composable
fun AppRoot(
    themeId: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
    container: AppContainer,
    /** The section the app opens on. Always Home in the app; the screenshot test opens others. */
    startAt: Dest = Dest.HOME,
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

    var dest by rememberSaveable { mutableStateOf(startAt) }
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

    // A short message shown at the bottom of the screen, such as why catch-up didn't start.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000)
            notice = null
        }
    }

    fun playVod(item: VodItem, episodes: List<VodItem> = listOf(item)) {
        if (!item.catchup) container.watched.record(item.title)
        controller.playVod(item, episodes)
        mode = PlayerMode.FULL
    }

    // Kept here so Films and Series reopen where the viewer left them after watching something.
    val filmsState = remember { VodBrowseState() }
    val seriesState = remember { VodBrowseState() }

    // A person's page sits over whatever section opened it; Back returns there.
    var person by remember { mutableStateOf<TmdbPerson?>(null) }

    // An invisible spot in the content that holds focus while one view replaces another.
    val holder = remember { FocusRequester() }
    var holding by remember { mutableStateOf(false) }
    fun holdFocus() {
        holding = true
        runCatching { holder.requestFocus() }
    }

    /** Switches section from a button on a screen, keeping focus in the content while the screen changes. */
    fun goTo(section: Dest) {
        holdFocus()
        person = null
        dest = section
    }

    fun openTitle(row: VodRow) {
        holdFocus()
        person = null
        when {
            row.kind == ChannelEntity.KIND_SERIES -> {
                // An M3U episode opens its show's episode list.
                seriesState.series = ChannelsRepository.asShow(row)
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

    // The side panel is open while the menu or the live picture has focus, and tucks away while browsing content.
    var menuFocused by remember { mutableStateOf(false) }
    var pictureFocused by remember { mutableStateOf(false) }
    var startupPending by remember { mutableStateOf(true) }
    val onMenuSide = menuFocused || pictureFocused
    val sidebarOpen = onMenuSide || startupPending
    val menuFocus = remember { SidebarSections.associateWith { FocusRequester() } }
    val contentFocus = remember { FocusRequester() }
    // Skyfall checks GitHub for a newer build once a day; a dot on Settings says one is ready.
    val updateReady by container.updates.available.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.updates.checkDaily() }
    val entryFocus = remember { EntryFocus() }
    val autoFocusAllowed = remember { { !(menuFocused || pictureFocused || startupPending) } }

    fun focusMenu() {
        scope.launch {
            withFrameNanos { }
            runCatching { menuFocus.getValue(if (dest in SidebarSections) dest else Dest.HOME).requestFocus() }
        }
    }

    // The app opens with the menu focused on Home.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { menuFocus.getValue(if (startAt in SidebarSections) startAt else Dest.HOME).requestFocus() }
        // Whether or not the menu took focus, start-up is over: the panel follows focus from here on.
        withFrameNanos { }
        startupPending = false
    }
    LaunchedEffect(menuFocused) { if (menuFocused) startupPending = false }
    // Leaving full-screen live TV puts the picture in the side panel and focus on the menu.
    LaunchedEffect(mode) {
        // Focus can't be on the menu or picture while they're not on screen.
        if (mode == PlayerMode.FULL) menuFocused = false
        if (mode != PlayerMode.MINI) pictureFocused = false
        if (mode == PlayerMode.MINI) focusMenu()
    }

    // From the menu, Back goes Home, then stops playback, then leaves the app.
    BackHandler(enabled = onMenuSide && dest != Dest.HOME) {
        dest = Dest.HOME
        // The menu's highlight follows to Home.
        runCatching { menuFocus.getValue(Dest.HOME).requestFocus() }
    }
    BackHandler(enabled = onMenuSide && dest == Dest.HOME && mode == PlayerMode.MINI) {
        controller.close()
        mode = PlayerMode.NONE
    }
    // From the content, Back returns to the menu.
    BackHandler(enabled = !onMenuSide && mode != PlayerMode.FULL) { focusMenu() }
    BackHandler(enabled = person != null && mode != PlayerMode.FULL) {
        holdFocus()
        person = null
    }

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
        val row = tile.open
        when {
            row != null -> openTitle(row)
            vod != null -> playVod(vod)
            key != null -> playKey(key)
            id != null -> play(id)
        }
    }

    val lists = playlistList
    val lastWatched = history.firstOrNull()
    // A film or episode in progress leads Home, with its TMDB artwork when a key is set.
    val firstProgress = progress.firstOrNull()
    val heroInfo by produceState<MetadataEntity?>(initialValue = null, firstProgress?.url) {
        value = firstProgress?.let { saved ->
            val isEpisode = " · S" in saved.title
            container.metadata.lookup(
                saved.title.substringBefore(" · "),
                if (isEpisode) ChannelEntity.KIND_SERIES else ChannelEntity.KIND_MOVIE,
            )
        }
    }
    val hero = when {
        lists == null -> HomeHero("SKYFALL", "Loading…", null, null, null, "Settings", onPrimary = { goTo(Dest.SETTINGS) })
        lists.isEmpty() -> HomeHero(
            overline = "WELCOME",
            title = "Welcome to Skyfall",
            tagline = "Add your Xtream Codes account or M3U playlist to bring in your channels, films and series.",
            meta = null,
            logo = null,
            primaryLabel = "Add a playlist",
            onPrimary = { goTo(Dest.SETTINGS) },
        )
        firstProgress != null -> HomeHero(
            overline = "CARRY ON WATCHING",
            title = firstProgress.title,
            tagline = heroInfo?.overview,
            meta = listOfNotNull(
                heroInfo?.year?.toString(),
                heroInfo?.rating?.let { "★ %.1f".format(it) },
                continueWatching.firstOrNull()?.caption,
            ).joinToString(" · "),
            logo = null,
            backdrop = heroInfo?.backdrop,
            primaryLabel = "Resume",
            onPrimary = {
                playVod(VodItem(firstProgress.url, firstProgress.title, firstProgress.poster, firstProgress.userAgent, firstProgress.referrer))
            },
            // Names the channel it will open: the last one watched.
            secondaryLabel = lastWatched?.let { "Live: ${it.name}" } ?: "Live TV",
            onSecondary = { lastWatched?.let { playKey(it.key) } ?: play(null) },
        )
        lastWatched != null -> HomeHero(
            overline = "LIVE TV",
            title = lastWatched.name,
            tagline = recentTiles.firstOrNull()?.caption?.takeIf { !it.startsWith("Channel ") },
            meta = "Channel ${lastWatched.number}",
            logo = lastWatched.logo,
            primaryLabel = "Watch",
            onPrimary = { playKey(lastWatched.key) },
            secondaryLabel = "TV Guide",
            onSecondary = { goTo(Dest.GUIDE) },
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
            onSecondary = { goTo(Dest.GUIDE) },
        )
    }
    val (hasFavourites, shownChannels) = channelTiles
    // "Because you watched…": TMDB's suggestions for recent films and series, from the viewer's own playlists.
    val watchedTitles by container.watched.recent.collectAsStateWithLifecycle()
    val suggestions by produceState(initialValue = emptyList<HomeRail>(), watchedTitles.take(6), playlistList) {
        value = container.metadata.recommendations(watchedTitles).map { (watchedTitle, rows) ->
            HomeRail(
                "Because you watched ${watchedTitle.name}",
                rows.map { row ->
                    Tile(
                        title = row.name,
                        caption = if (row.kind == ChannelEntity.KIND_SERIES) "Series" else "Film",
                        color = tileColor(row.url),
                        logo = row.logo,
                        open = row,
                    )
                },
            )
        }
    }
    val homeRails = listOf(
        HomeRail("Continue watching", continueWatching),
    ) + suggestions + listOf(
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
        // The side panel slides in and out. Only the drawing moves during the slide: the screens are
        // laid out once at their new width, so nothing has to be measured again on every frame.
        val panelTarget = if (sidebarOpen) 0.dp else -(SidebarWidth - SidebarPeek)
        val panelSlide = animateDpAsState(
            targetValue = panelTarget,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            label = "sidebar",
        )
        if (mode != PlayerMode.FULL) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = SidebarWidth + panelTarget)
                    .offset { IntOffset((panelSlide.value - panelTarget).roundToPx(), 0) }
                    // Moving in from the menu lands on the screen's starting point, if it has named one.
                    .focusProperties { enter = { entryFocus.target ?: FocusRequester.Default } }
                    .focusRequester(contentFocus)
                    .focusGroup(),
            ) {
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .focusProperties { canFocus = holding }
                        .focusRequester(holder)
                        .onFocusChanged { if (!it.isFocused) holding = false }
                        .focusable(),
                )
                CompositionLocalProvider(
                    LocalAutoFocus provides autoFocusAllowed,
                    LocalHoldFocus provides { holdFocus() },
                    LocalEntryFocus provides entryFocus,
                ) {
                val shownPerson = person
                if (shownPerson != null) {
                    TopInset { PersonScreen(person = shownPerson, metadata = container.metadata, onOpen = { row -> openTitle(row) }) }
                } else when (dest) {
                    Dest.HOME -> HomeScreen(
                        hero = hero,
                        rails = homeRails,
                        shortcuts = shortcuts,
                        onTile = { tile -> openTile(tile) },
                        onShortcut = { label ->
                            when (label) {
                                "Live TV" -> play(null)
                                "TV Guide" -> goTo(Dest.GUIDE)
                                "Films" -> goTo(Dest.FILMS)
                                "Series" -> goTo(Dest.SERIES)
                                "Search" -> goTo(Dest.SEARCH)
                            }
                        },
                    )
                    Dest.RECENTS -> TopInset {
                        RecentsScreen(
                            rails = listOf(HomeRail("Recent channels", recentTiles), HomeRail("Continue watching", continueWatching)),
                            onTile = { tile -> openTile(tile) },
                        )
                    }
                    Dest.FILMS -> VodScreen(
                        kind = ChannelEntity.KIND_MOVIE,
                        state = filmsState,
                        channels = container.channels,
                        playlists = container.playlists,
                        metadata = container.metadata,
                        onPlay = { item, episodes -> playVod(item, episodes) },
                        onPerson = { chosen ->
                            holdFocus()
                            person = chosen
                        },
                    )
                    Dest.SERIES -> VodScreen(
                        kind = ChannelEntity.KIND_SERIES,
                        state = seriesState,
                        channels = container.channels,
                        playlists = container.playlists,
                        metadata = container.metadata,
                        onPlay = { item, episodes -> playVod(item, episodes) },
                        onPerson = { chosen ->
                            holdFocus()
                            person = chosen
                        },
                    )
                    Dest.SEARCH -> TopInset { SearchScreen(
                        channels = container.channels,
                        metadata = container.metadata,
                        voiceToken = voiceToken,
                        onPerson = { chosen ->
                            holdFocus()
                            person = chosen
                        },
                        onOpen = { row ->
                            holdFocus()
                            when {
                                row.kind == ChannelEntity.KIND_LIVE -> play(row.id)
                                row.kind == ChannelEntity.KIND_SERIES -> {
                                    seriesState.series = ChannelsRepository.asShow(row)
                                    dest = Dest.SERIES
                                }
                                row.kind == ChannelEntity.KIND_MOVIE -> {
                                    filmsState.film = row
                                    dest = Dest.FILMS
                                }
                                else -> playVod(VodItem(row.url, row.name, row.logo, row.userAgent, row.referrer))
                            }
                        },
                    ) }
                    Dest.GUIDE -> TopInset {
                        GuideScreen(
                            channels = container.channels,
                            onPlay = { channel -> play(channel.variantIds.first()) },
                            onCatchup = { channel, programme ->
                                scope.launch {
                                    try {
                                        val item = container.playlists.catchupItem(channel, programme)
                                        // The player must be driven from the main thread.
                                        withContext(Dispatchers.Main) { playVod(item) }
                                    } catch (e: Exception) {
                                        notice = container.playlists.explainError(e)
                                    }
                                }
                            },
                        )
                    }
                    Dest.SETTINGS -> TopInset {
                        SettingsScreen(
                            themeId = themeId,
                            onThemeChange = onThemeChange,
                            playlists = container.playlists,
                            settings = container.settings,
                            metadata = container.metadata,
                            updates = container.updates,
                            subtitles = container.subtitles,
                        )
                    }
                    else -> TopInset { ComingLater(dest) }
                }
                }
            }
            Sidebar(
                selected = dest,
                onSelect = { chosen ->
                    if (dest != chosen) {
                        person = null
                        dest = chosen
                    }
                },
                onEnter = { runCatching { contentFocus.requestFocus() } },
                appName = stringResource(R.string.app_name),
                badged = if (updateReady != null) setOf(Dest.SETTINGS) else emptySet(),
                showingPicture = mode == PlayerMode.MINI,
                itemFocus = menuFocus,
                modifier = Modifier
                    .offset { IntOffset(panelSlide.value.roundToPx(), 0) }
                    .onFocusChanged { menuFocused = it.hasFocus },
            )
        }

        notice?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppTheme.colors.scrim)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Label(text = text, style = AppType.body)
            }
        }

        if (mode != PlayerMode.NONE) {
            val colors = AppTheme.colors
            PlayerHost(
                controller = controller,
                subtitles = container.subtitles,
                metadata = container.metadata,
                intros = container.intros,
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
                    // Sits in the picture space of the side panel and slides with it.
                    Modifier
                        .offset { IntOffset((panelSlide.value + SidebarPictureSide).roundToPx(), SidebarPictureTop.roundToPx()) }
                        .size(width = SidebarPictureWidth, height = SidebarPictureHeight)
                        .border(width = 1.dp, color = colors.glassBorder)
                        .onFocusChanged { pictureFocused = it.hasFocus }
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

/** Space above screens without full-bleed artwork. Home, Films and Series run their artwork to the top edge. */
@Composable
private fun TopInset(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(top = 14.dp)) { content() }
}
