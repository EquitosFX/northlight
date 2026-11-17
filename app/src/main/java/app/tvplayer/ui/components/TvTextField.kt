package app.tvplayer.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType

/**
 * A text box that works with a remote.
 *
 * It sits as an ordinary focusable row, so the D-pad moves past it like any button. Pressing OK
 * opens it for typing with the on-screen keyboard; Done or Back closes it and puts focus back on the row.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** Typing finished (the keyboard's Done, or Back out of it). */
    onDone: () -> Unit = {},
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(9.dp)
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val typingFocus = remember { FocusRequester() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Label(text = label, style = AppType.caption, color = colors.textDim)

        if (editing) {
            BackHandler {
                editing = false
                returnFocus = true
                onDone()
            }
            LaunchedEffect(Unit) { runCatching { typingFocus.requestFocus() } }

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = AppType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        editing = false
                        returnFocus = true
                        onDone()
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .focusRequester(typingFocus)
                    .clip(shape)
                    .background(colors.glassStrong)
                    .border(width = 2.dp, color = colors.accent, shape = shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            LaunchedEffect(returnFocus) {
                if (returnFocus) {
                    runCatching { rowFocus.requestFocus() }
                    returnFocus = false
                }
            }

            FocusSurface(
                onClick = { editing = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .focusRequester(rowFocus),
                focusScale = 1.01f,
            ) {
                val shown = when {
                    value.isEmpty() -> placeholder
                    isPassword -> "•".repeat(value.length.coerceAtMost(24))
                    else -> value
                }
                Label(
                    text = shown,
                    style = AppType.body,
                    color = if (value.isEmpty()) colors.textFaint else colors.text,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 12.dp),
                )
            }
        }
    }
}
