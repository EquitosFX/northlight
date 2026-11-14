package app.tvplayer.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import app.tvplayer.ui.theme.AppTheme

/** Text in a theme colour. Single line with an ellipsis unless [maxLines] says otherwise. */
@Composable
fun Label(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.text,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
