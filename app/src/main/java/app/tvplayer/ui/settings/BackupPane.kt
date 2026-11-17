package app.tvplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvplayer.data.BackupRepository
import app.tvplayer.data.BackupServer
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Settings › Backup and restore: on this Shield, and to or from a phone over the home network. */
@Composable
fun BackupPane(backup: BackupRepository) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var savedAt by remember { mutableStateOf(backup.savedAt()) }
    var address by remember { mutableStateOf<String?>(null) }
    var phoneStatus by remember { mutableStateOf<String?>(null) }
    // The phone page runs only while this pane is open.
    DisposableEffect(Unit) {
        val server = BackupServer(backup, scope) { event -> phoneStatus = event }
        address = server.start()
        onDispose { server.stop() }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(text = "Backup and restore", style = AppType.headline)
        Label(
            text = "A backup holds your playlists (with their sign-in details), favourites, settings, watchlist and reminders.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Save a backup now", accent = true) {
                status = "Saving…"
                scope.launch {
                    status = try {
                        val copy = backup.save()
                        savedAt = backup.savedAt()
                        "Saved on this TV box" + (copy?.let { ", with a copy in $it" } ?: "") + "."
                    } catch (e: Exception) {
                        "Couldn't save the backup: ${e.message}"
                    }
                }
            }
            val at = savedAt
            if (at != null) {
                ActionButton("Restore the backup from ${dateText(at)}") {
                    status = "Restoring…"
                    scope.launch {
                        status = try {
                            backup.restoreSaved()
                        } catch (e: Exception) {
                            e.message ?: "That backup couldn't be read."
                        }
                    }
                }
            }
        }
        status?.let { Label(text = it, style = AppType.body, color = colors.accent, maxLines = 3) }

        Label(text = "With your phone or computer", style = AppType.headline, modifier = Modifier.padding(top = 16.dp))
        val url = address
        if (url == null) {
            Label(text = "This TV box isn't on a network, so this isn't available.", style = AppType.body, color = colors.textDim)
        } else {
            Label(
                text = "On a phone or computer on the same Wi-Fi, open this address in the web browser. You can download a " +
                    "backup to keep, or choose a backup file to load onto this TV box. It works while this page is open.",
                style = AppType.body,
                color = colors.textDim,
                maxLines = 3,
            )
            Label(text = url, style = AppType.display.copy(fontSize = 28.sp, lineHeight = 34.sp), color = colors.text, modifier = Modifier.padding(vertical = 6.dp))
        }
        phoneStatus?.let { Label(text = it, style = AppType.body, color = colors.accent, maxLines = 3) }
    }
}

@Composable
internal fun ActionButton(text: String, accent: Boolean = false, onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(38.dp),
        shape = RoundedCornerShape(19.dp),
        container = if (accent) colors.glassStrong else null,
        focusedContainer = colors.accent,
    ) { focused ->
        Label(
            text = text,
            style = AppType.title,
            color = if (focused) colors.onAccent else colors.text,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
        )
    }
}

private fun dateText(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK))
