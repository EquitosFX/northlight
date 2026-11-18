package app.tvplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.data.ImportState
import app.tvplayer.data.PlaylistRepository
import app.tvplayer.data.db.PlaylistEntity
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.TvTextField
import app.tvplayer.ui.components.clockTime
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import java.net.URI
import java.text.NumberFormat

private enum class AddMode { XTREAM, M3U }

private fun count(value: Int): String = NumberFormat.getIntegerInstance().format(value)

/** Settings › Playlists: the sources already added, and the forms for adding another. */
@Composable
fun PlaylistsPane(repo: PlaylistRepository) {
    val colors = AppTheme.colors
    val playlists by repo.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val importState by repo.importState.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<AddMode?>(null) }

    val mode = adding
    if (mode != null) {
        AddForm(
            mode = mode,
            onCancel = { adding = null },
            onSubmit = { name, address, username, password ->
                if (mode == AddMode.XTREAM) {
                    repo.addXtream(name, address, username, password)
                } else {
                    repo.addM3u(name, address)
                }
                adding = null
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(text = "Playlists", style = AppType.headline)
        StatusLine(importState)

        if (playlists.isEmpty()) {
            Label(
                text = "No playlists yet. Add one to bring in your channels.",
                style = AppType.body,
                color = colors.textDim,
            )
        }
        val busy = importState is ImportState.Working
        playlists.forEach { playlist ->
            // The account's end date and streams in use, asked of the provider when the pane opens.
            val subscription by androidx.compose.runtime.produceState<PlaylistRepository.Subscription?>(null, playlist.id) {
                value = repo.subscription(playlist)
            }
            PlaylistRow(
                playlist = playlist,
                subscription = subscription,
                busy = busy,
                onRefresh = { repo.refresh(playlist.id) },
                onRemove = { repo.remove(playlist.id) },
            )
        }

        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallButton(text = "Add Xtream Codes account") {
                repo.acknowledge()
                adding = AddMode.XTREAM
            }
            SmallButton(text = "Add M3U link") {
                repo.acknowledge()
                adding = AddMode.M3U
            }
        }
    }
}

@Composable
private fun StatusLine(state: ImportState) {
    val colors = AppTheme.colors
    when (state) {
        ImportState.Idle -> Unit
        is ImportState.Working -> {
            val name = if (state.playlistName.isEmpty()) "" else "${state.playlistName}: "
            Label(
                text = "$name${state.step}… ${count(state.count)} items so far",
                style = AppType.body,
                color = colors.accent,
            )
        }
        is ImportState.Done -> {
            Label(
                text = "${state.playlistName} is ready: ${count(state.live)} live channels, " +
                    "${count(state.movies)} films, ${count(state.series)} series, " +
                    "${count(state.programmes)} programmes in the TV guide",
                style = AppType.body,
                color = colors.accent,
                maxLines = 2,
            )
            val note = state.guideNote
            if (note != null) Label(text = note, style = AppType.body, color = colors.textDim, maxLines = 2)
        }
        is ImportState.Failed -> Label(text = state.message, style = AppType.body, color = colors.accent, maxLines = 3)
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    subscription: PlaylistRepository.Subscription? = null,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(10.dp)
    var confirming by remember(playlist.id) { mutableStateOf(false) }

    val kind = if (playlist.type == PlaylistEntity.TYPE_XTREAM) "Xtream Codes" else "M3U link"
    val host = try {
        URI(playlist.address).host ?: playlist.address
    } catch (e: Exception) {
        playlist.address
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glass)
            .border(width = 1.dp, color = colors.glassBorder, shape = shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(text = playlist.name, style = AppType.title)
            Label(text = "$kind · $host", style = AppType.caption, color = colors.textDim)
            Label(
                text = "${count(playlist.liveCount)} live · ${count(playlist.movieCount)} films · " +
                    "${count(playlist.seriesCount)} series",
                style = AppType.caption,
                color = colors.textDim,
            )
            val guideUpdated = playlist.guideRefreshedAt
            Label(
                text = if (guideUpdated == null) {
                    "TV guide: not loaded"
                } else {
                    "TV guide: ${count(playlist.programmeCount)} programmes · updated ${clockTime(guideUpdated)}"
                },
                style = AppType.caption,
                color = colors.textDim,
            )
            if (subscription != null) {
                val text = subscriptionText(subscription)
                val soon = subscription.endsAt?.let { it - System.currentTimeMillis() < SUBSCRIPTION_WARN_MILLIS } == true
                Label(text = text, style = AppType.caption, color = if (soon) colors.accent else colors.textDim, maxLines = 2)
            }
        }
        if (!busy) {
            SmallButton(text = "Refresh", onClick = onRefresh)
            SmallButton(text = if (confirming) "Confirm remove" else "Remove") {
                if (confirming) onRemove() else confirming = true
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, accent: Boolean = false, onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        container = if (accent) colors.accent else null,
        focusedContainer = if (accent) colors.accent else null,
        outlined = !accent,
    ) {
        Label(
            text = text,
            style = AppType.label,
            color = if (accent) colors.onAccent else colors.text,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

@Composable
private fun AddForm(
    mode: AddMode,
    onCancel: () -> Unit,
    onSubmit: (name: String, address: String, username: String, password: String) -> Unit,
) {
    val colors = AppTheme.colors
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    BackHandler { onCancel() }

    val isXtream = mode == AddMode.XTREAM
    val fieldWidth = Modifier.width(430.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(text = if (isXtream) "Add Xtream Codes account" else "Add M3U link", style = AppType.headline)

        TvTextField(
            value = address,
            onValueChange = { address = it },
            label = if (isXtream) "Server address" else "Playlist link",
            placeholder = if (isXtream) "http://example.com:8080" else "http://example.com/playlist.m3u",
            keyboardType = KeyboardType.Uri,
            modifier = fieldWidth.focusRequester(firstFocus),
        )
        if (isXtream) {
            TvTextField(
                value = username,
                onValueChange = { username = it },
                label = "Username",
                modifier = fieldWidth,
            )
            TvTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                isPassword = true,
                keyboardType = KeyboardType.Password,
                modifier = fieldWidth,
            )
        }
        TvTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name (optional)",
            placeholder = "Shown in your playlist list",
            modifier = fieldWidth,
        )

        val message = problem
        if (message != null) {
            Label(text = message, style = AppType.body, color = colors.accent)
        }

        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallButton(text = "Connect", accent = true) {
                problem = when {
                    address.isBlank() -> if (isXtream) "Enter the server address." else "Enter the playlist link."
                    isXtream && (username.isBlank() || password.isBlank()) -> "Enter the username and password."
                    else -> null
                }
                if (problem == null) onSubmit(name, address, username, password)
            }
            SmallButton(text = "Cancel", onClick = onCancel)
        }
    }
}

/** How soon before an account ends Skyfall starts to say so. */
const val SUBSCRIPTION_WARN_MILLIS = 7L * 24 * 60 * 60 * 1000

/** "Subscription ends Tue 3 Mar 2027 (in 105 days) · 1 of 2 streams in use". */
fun subscriptionText(subscription: PlaylistRepository.Subscription): String {
    val parts = ArrayList<String>()
    val ends = subscription.endsAt
    parts += when {
        ends == null -> "Subscription: no end date given"
        else -> {
            val date = java.time.Instant.ofEpochMilli(ends).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", java.util.Locale.UK))
            val days = Math.floorDiv(ends - System.currentTimeMillis(), 24L * 60 * 60 * 1000)
            when {
                days < 0 -> "Subscription ended $date"
                days == 0L -> "Subscription ends today ($date)"
                days == 1L -> "Subscription ends tomorrow ($date)"
                else -> "Subscription ends $date (in $days days)"
            }
        }
    }
    if (subscription.trial) parts += "trial"
    val max = subscription.maxConnections
    if (max != null && max > 0) {
        parts += "${subscription.activeConnections ?: 0} of $max ${if (max == 1) "stream" else "streams"} in use"
    }
    subscription.status?.takeIf { !it.equals("Active", ignoreCase = true) }?.let { parts += it }
    return parts.joinToString(" · ")
}
