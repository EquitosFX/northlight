package app.tvplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.core.live.Lineup
import app.tvplayer.data.ChannelCheckRepository
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.SettingsRepository
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.tvplayer.core.live.LineupChannel

/** Settings › Refresh and check: how often playlists refresh, and a check for channels that don't work. */
@Composable
fun MaintenancePane(
    settings: SettingsRepository,
    channels: ChannelsRepository,
    check: ChannelCheckRepository,
    playlists: app.tvplayer.data.PlaylistRepository? = null,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val hours by settings.autoRefreshHours.collectAsStateWithLifecycle(initialValue = 24)
    val progress by check.progress.collectAsStateWithLifecycle()
    val dead by check.dead.collectAsStateWithLifecycle()
    val lineup by produceState<Lineup?>(initialValue = null) { value = channels.lineup() }
    val favourites by produceState(initialValue = emptySet<String>()) { value = channels.favouriteKeys.first() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpeedReport()
        Label(text = "Keep playlists up to date", style = AppType.headline)
        Label(
            text = "Skyfall downloads your playlists again by itself, so new channels, films and series appear. It happens quietly while the app is open.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 12 to "Every 12 hours", 24 to "Every day", 72 to "Every 3 days", 168 to "Every week").forEach { (value, label) ->
                SettingChip(label = label, selected = hours == value) { scope.launch { settings.setAutoRefreshHours(value) } }
            }
        }

        Label(text = "Check channels", style = AppType.headline, modifier = Modifier.padding(top = 16.dp))
        Label(
            text = "Opens each channel for a moment to see whether it sends a picture: several at once when your account has " +
                "connections free, otherwise one at a time. Channels that don't respond get a red mark in the TV guide. " +
                "Stop anything you're watching first.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 3,
        )
        val all = lineup?.channels.orEmpty()
        val sportEvents by produceState(initialValue = channels.cachedSport()) { value = runCatching { channels.sport() }.getOrNull() }
        val sportChannels = androidx.compose.runtime.remember(all, sportEvents) {
            val onSportTab = sportEvents.orEmpty().flatMap { event -> event.channels.map { it.key } }.toSet()
            all.filter { it.key in onSportTab || it.group?.contains("sport", ignoreCase = true) == true }
        }
        if (progress.running) {
            Label(
                text = "Checking ${progress.label}: ${progress.checked} of ${progress.total}" +
                    (if (progress.failed.isNotEmpty()) " · ${progress.failed.size} not working" else "") +
                    (if (progress.lanes > 1) " · ${progress.lanes} at a time" else "") +
                    (progress.minutesLeft?.let { " · about $it min left" } ?: ""),
                style = AppType.title,
                color = colors.accent,
            )
            val fraction = if (progress.total > 0) progress.checked.toFloat() / progress.total else 0f
            Box(Modifier.width(420.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(colors.accent))
            }
            ActionButton("Stop") { check.stop() }
        } else {
            // As many at once as the accounts have connections free (up to 4); one if they don't say.
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            fun start(label: String, list: List<LineupChannel>) {
                scope.launch {
                    val ids = list.map { it.playlistId }.toSet()
                    val free = playlists?.let { repo ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repo.allBlocking().filter { it.id in ids }.map { p ->
                                val account = repo.subscription(p, maxAgeMillis = 0)
                                val max = account?.maxConnections ?: return@map 1
                                (max - (account.activeConnections ?: 0)).coerceAtLeast(1)
                            }
                        }
                    }
                    check.check(label, list, lanes = free?.minOrNull() ?: 1)
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Every channel first, then the sport channels, favourites and each category.
                if (all.isNotEmpty()) item { SettingChip("All channels (${all.size})", false) { start("all channels", all) } }
                // Sport channels together: every category with "sport" in its name, and every channel
                // showing something on the Sport tab. Ones that don't work drop off the Sport tab.
                if (sportChannels.isNotEmpty()) {
                    item { SettingChip("Sport channels (${sportChannels.size})", false) { start("sport channels", sportChannels) } }
                }
                val favouriteChannels = all.filter { it.key in favourites }
                if (favouriteChannels.isNotEmpty()) {
                    item { SettingChip("Favourites (${favouriteChannels.size})", false) { start("Favourites", favouriteChannels) } }
                }
                items(all.mapNotNull { it.group }.distinct()) { group ->
                    val inGroup = all.filter { it.group == group }
                    SettingChip("$group (${inGroup.size})", false) { start(group, inGroup) }
                }
            }
            if (progress.total > 0) {
                Label(
                    text = "Last check (${progress.label}): ${progress.total - progress.failed.size - progress.skipped} of ${progress.total} working." +
                        if (progress.skipped > 0) " ${progress.skipped} couldn't be checked (the provider was busy) and keep their earlier mark." else "",
                    style = AppType.title,
                    color = colors.accent,
                )
            }
        }
        if (progress.failed.isNotEmpty()) {
            Label(text = "Not working: " + progress.failed.take(40).joinToString(", "), style = AppType.body, color = colors.textDim, maxLines = 4)
        }
        if (dead.isNotEmpty() && !progress.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(text = "${dead.size} channels marked as not working.", style = AppType.body, color = colors.textDim)
                ActionButton("Clear the marks") { check.clearMarks() }
            }
        }
    }
}

@Composable
private fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    app.tvplayer.ui.components.FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        container = if (selected) colors.accent else null,
        focusedContainer = if (selected) colors.accent else null,
    ) { focused ->
        Label(
            text = label,
            style = AppType.label,
            color = if (selected) colors.onAccent else colors.text,
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center).padding(horizontal = 14.dp),
        )
    }
}

/** How long Skyfall and its screens took to open on this box, since it was last opened. */
@Composable
private fun SpeedReport() {
    val colors = AppTheme.colors
    val first = app.tvplayer.ui.components.Timing.first
    val latest = app.tvplayer.ui.components.Timing.latest
    Label(text = "Speed", style = AppType.headline)
    if (first.isEmpty()) {
        Label(text = "Open a few screens, then come back here to see how long they took.", style = AppType.body, color = colors.textDim)
        return
    }
    Label(
        text = "How long each screen took to open since Skyfall started: the first time, then the most recent.",
        style = AppType.body,
        color = colors.textDim,
        maxLines = 2,
    )
    val order = listOf("Start-up", "TV guide", "TV guide: channel list", "TV guide: programmes", "Channel start", "Channel start: recording ready", "Films", "Series", "Series page", "Sport")
    for (name in order.filter { it in first } + first.keys.filter { it !in order }.sorted()) {
        val a = first[name] ?: continue
        val b = latest[name] ?: a
        Label(
            text = "$name: ${seconds(a)} first time" + if (b != a) " · ${seconds(b)} last time" else "",
            style = AppType.body,
            color = if (a > 1500) colors.accent else colors.text,
        )
    }
    for ((name, text) in app.tvplayer.ui.components.Timing.notes.toSortedMap()) {
        Label(text = "$name: $text", style = AppType.body, color = colors.text)
    }
    // Whether Android has compiled Skyfall ahead of time yet. After an update it runs slower (partly
    // interpreted) until the box does this, usually overnight while it's idle.
    val compiled by androidx.compose.runtime.produceState<String?>(initialValue = null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val status = androidx.profileinstaller.ProfileVerifier.getCompilationStatusAsync().get(3, java.util.concurrent.TimeUnit.SECONDS)
                when {
                    status.isCompiledWithProfile -> "yes"
                    status.hasProfileEnqueuedForCompilation() -> "not yet: the TV box will do it when idle (usually overnight), then it runs quicker"
                    // Android 11 (the Shield) doesn't say, but it compiles while installing.
                    status.profileInstallResultCode == (4 shl 16) -> // "unsupported Android version"
                        "done while installing (this Android version can't report it)"
                    else -> "not yet (code ${status.profileInstallResultCode})"
                }
            }.getOrNull()
        }
    }
    compiled?.let { Label(text = "Compiled for speed: $it", style = AppType.body, color = if (it == "yes" || it.startsWith("done")) colors.text else colors.accent, maxLines = 2) }
}

private fun seconds(millis: Long) = if (millis < 1000) "$millis ms" else "%.1f s".format(millis / 1000.0)
