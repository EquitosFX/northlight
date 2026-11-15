package app.tvplayer.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether a screen may move focus into itself when it opens. False while the viewer is moving
 * through the side menu, so previewing a section doesn't pull focus away from the menu.
 * It is a function so it is read at the moment the screen asks, not when it was drawn.
 */
val LocalAutoFocus = staticCompositionLocalOf<() -> Boolean> { { true } }
