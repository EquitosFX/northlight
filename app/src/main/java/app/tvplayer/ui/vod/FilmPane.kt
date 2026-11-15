package app.tvplayer.ui.vod

import app.tvplayer.ui.components.EntryPoint
import app.tvplayer.ui.components.fadeIntoBackground
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.core.tmdb.TmdbPerson
import app.tvplayer.data.MetadataRepository
import app.tvplayer.data.peopleList
import app.tvplayer.ui.person.PeopleRow
import app.tvplayer.data.db.MetadataEntity
import app.tvplayer.data.db.ProgressEntity
import app.tvplayer.data.db.VodRow
import app.tvplayer.player.VodItem
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import coil.compose.AsyncImage

/** A film's page: backdrop, details from TMDB when available, and Play or Resume. */
@Composable
internal fun FilmPane(
    film: VodRow,
    channels: ChannelsRepository,
    metadata: MetadataRepository,
    onPlay: (VodItem, List<VodItem>) -> Unit,
    onPerson: (TmdbPerson) -> Unit,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val info by produceState<MetadataEntity?>(initialValue = null, film.id) { value = metadata.lookup(film.name, film.kind) }
    val progress by produceState<ProgressEntity?>(initialValue = null, film.url) { value = channels.progressFor(film.url) }
    val firstButton = remember { FocusRequester() }
    EntryPoint(firstButton)
    val autoFocus = LocalAutoFocus.current

    BackHandler { onBack() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (autoFocus()) runCatching { firstButton.requestFocus() }
    }

    val details = info
    val title = details?.title ?: film.name
    val item = VodItem(film.url, title, details?.poster ?: film.logo, film.userAgent, film.referrer)
    val resumeMinutes = progress?.positionMs?.takeIf { it > 30_000 }?.let { it / 60_000 }

    Box(modifier = Modifier.fillMaxSize()) {
        val backdrop = details?.backdrop
        if (backdrop != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.68f)
                    .fillMaxHeight()
                    .fadeIntoBackground(solidFrom = 0.4f, solidUntil = 0.55f),
            ) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label(text = title, style = AppType.display, maxLines = 2, modifier = Modifier.width(470.dp))
            val meta = listOfNotNull(
                details?.year?.toString(),
                details?.runtimeMinutes?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                details?.rating?.let { "★ %.1f".format(it) },
                details?.genres?.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Label(text = meta, style = AppType.label, color = colors.accent)
            details?.overview?.let {
                Label(text = it, style = AppType.body, color = colors.textDim, maxLines = 5, modifier = Modifier.width(470.dp))
            }
            if (details == null) {
                Label(text = film.name, style = AppType.caption, color = colors.textFaint)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                PageButton(
                    text = if (resumeMinutes != null) "Resume from $resumeMinutes min" else "Play",
                    accent = true,
                    modifier = Modifier.focusRequester(firstButton),
                ) { onPlay(item, listOf(item)) }
                if (resumeMinutes != null) {
                    PageButton(text = "Start over") { onPlay(item.copy(fromStart = true), listOf(item)) }
                }
            }
            // Down from the buttons reaches the director and cast; selecting one shows their other titles.
            // The row runs to the screen edge and scrolls, so no name is cut short.
            details?.let { PeopleRow(people = it.peopleList(), onPerson = onPerson, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
internal fun PageButton(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        container = if (accent) colors.accent else null,
        focusedContainer = if (accent) colors.accent else null,
        outlined = !accent,
    ) {
        Label(
            text = text,
            style = AppType.title,
            color = if (accent) colors.onAccent else colors.text,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}
