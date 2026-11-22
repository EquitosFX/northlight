package app.tvplayer.ui.settings

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.data.MetadataRepository
import app.tvplayer.data.SettingsRepository
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.TvTextField
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.launch

/** Settings › Film info: the viewer's TMDB key, checked before it is saved. */
@Composable
fun TmdbPane(settings: SettingsRepository, metadata: MetadataRepository) {
    val colors = AppTheme.colors
    val saved by settings.tmdbKey.collectAsStateWithLifecycle(initialValue = "")
    var key by remember(saved) { mutableStateOf(saved) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val savedOmdb by settings.omdbKey.collectAsStateWithLifecycle(initialValue = "")
    var omdbKey by remember(savedOmdb) { mutableStateOf(savedOmdb) }
    var omdbStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(text = "Film and series information", style = AppType.headline)
        Label(
            text = "Posters, plots, ratings and cast come from TMDB, using your own free key. " +
                "Sign up at themoviedb.org, then open Settings › API there and copy the API key or read access token.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 3,
        )
        Label(
            text = if (saved.isEmpty()) "No key saved: films and series show the playlist's own details." else "A key is saved.",
            style = AppType.body,
            color = colors.accent,
        )
        TvTextField(
            value = key,
            onValueChange = { key = it },
            label = "TMDB API key or read access token",
            placeholder = "Press OK to type or paste",
            isPassword = true,
            modifier = Modifier.width(430.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            PaneButton(text = "Save and test", accent = true) {
                scope.launch {
                    status = "Checking with TMDB…"
                    val problem = metadata.checkKey(key)
                    if (problem == null) {
                        settings.setTmdbKey(key)
                        status = "The key works. Film and series pages now show TMDB details."
                    } else {
                        status = problem
                    }
                }
            }
            if (saved.isNotEmpty()) {
                PaneButton(text = "Remove key") {
                    scope.launch {
                        settings.setTmdbKey("")
                        status = "Key removed."
                    }
                }
            }
        }
        status?.let { Label(text = it, style = AppType.body, color = colors.accent, maxLines = 2) }

        // OMDb: IMDb, Rotten Tomatoes and Metacritic scores beside TMDB's.
        Label(text = "More ratings", style = AppType.title, modifier = Modifier.padding(top = 14.dp))
        Label(
            text = "Add a free OMDb key to show IMDb, Rotten Tomatoes and Metacritic scores beside TMDB's. " +
                "Get one at omdbapi.com (API Key, Free); it arrives by email. Needs the TMDB key above too.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 3,
        )
        TvTextField(
            value = omdbKey,
            onValueChange = { omdbKey = it },
            label = "OMDb key",
            placeholder = "Press OK to type or paste",
            isPassword = true,
            modifier = Modifier.width(430.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            PaneButton(text = "Save and test OMDb") {
                scope.launch {
                    omdbStatus = "Checking with OMDb…"
                    val problem = metadata.checkOmdbKey(omdbKey)
                    if (problem == null) {
                        settings.setOmdbKey(omdbKey)
                        omdbStatus = "The key works. Film and series pages now show IMDb, Rotten Tomatoes and Metacritic scores."
                    } else {
                        omdbStatus = problem
                    }
                }
            }
            if (savedOmdb.isNotEmpty()) {
                PaneButton(text = "Remove OMDb key") {
                    scope.launch {
                        settings.setOmdbKey("")
                        omdbStatus = "OMDb key removed."
                    }
                }
            }
        }
        omdbStatus?.let { Label(text = it, style = AppType.body, color = colors.accent, maxLines = 2) }
        Label(
            text = "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            style = AppType.caption,
            color = colors.textFaint,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun PaneButton(text: String, accent: Boolean = false, onClick: () -> Unit) {
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
