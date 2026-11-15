package app.tvplayer.ui.components

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether a screen may move focus into itself when it opens. False while the viewer is moving
 * through the side menu, so previewing a section doesn't pull focus away from the menu.
 * It is a function so it is read at the moment the screen asks, not when it was drawn.
 */
val LocalAutoFocus = staticCompositionLocalOf<() -> Boolean> { { true } }

/**
 * Keeps focus inside the content while one view replaces another (a poster opening its film page,
 * Back from a film page). Without it, focus would fall onto the side menu when the focused item
 * disappears. Call it just before changing what's shown; the new view then takes focus as normal.
 */
val LocalHoldFocus = staticCompositionLocalOf<() -> Unit> { {} }

/** Where focus lands when the viewer moves from the side menu into the open screen. */
class EntryFocus {
    var target: FocusRequester? = null
}

val LocalEntryFocus = staticCompositionLocalOf { EntryFocus() }

/**
 * Marks [requester] as the open screen's starting point: Right or OK from the side menu lands here,
 * rather than on whatever happens to be nearest. Call it next to the `focusRequester` it names, so
 * it is withdrawn when that item leaves the screen.
 */
@Composable
fun EntryPoint(requester: FocusRequester) {
    val entry = LocalEntryFocus.current
    DisposableEffect(entry, requester) {
        entry.target = requester
        onDispose { if (entry.target === requester) entry.target = null }
    }
}
