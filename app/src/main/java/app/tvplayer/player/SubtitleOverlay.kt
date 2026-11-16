package app.tvplayer.player

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import app.tvplayer.R
import app.tvplayer.data.SubtitleLook

/**
 * Subtitles, drawn by Skyfall itself rather than the player, so they can look like Netflix's: a
 * chosen typeface, a soft shadow and faint outline (or a box, or plain), at a chosen size and height.
 * Picture subtitles (DVB, some films) are shown as the pictures they are.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SubtitleOverlay(cues: List<Cue>, look: SubtitleLook, modifier: Modifier = Modifier) {
    if (cues.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        // Pictures go where the stream puts them.
        for (cue in cues) {
            val bitmap = cue.bitmap ?: continue
            val cueWidth = if (cue.size != Cue.DIMEN_UNSET) width * cue.size else width * 0.8f
            val cueHeight = if (cue.bitmapHeight != Cue.DIMEN_UNSET) height * cue.bitmapHeight else cueWidth * (bitmap.height.toFloat() / bitmap.width)
            val left = if (cue.position != Cue.DIMEN_UNSET) {
                when (cue.positionAnchor) {
                    Cue.ANCHOR_TYPE_MIDDLE -> width * cue.position - cueWidth / 2
                    Cue.ANCHOR_TYPE_END -> width * cue.position - cueWidth
                    else -> width * cue.position
                }
            } else {
                (width - cueWidth) / 2
            }
            val top = if (cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION) {
                when (cue.lineAnchor) {
                    Cue.ANCHOR_TYPE_MIDDLE -> height * cue.line - cueHeight / 2
                    Cue.ANCHOR_TYPE_END -> height * cue.line - cueHeight
                    else -> height * cue.line
                }
            } else {
                height * 0.92f - cueHeight
            }
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.offset(x = left, y = top).size(cueWidth, cueHeight),
            )
        }

        val lines = cues.filter { it.bitmap == null && !it.text.isNullOrBlank() }
        if (lines.isEmpty()) return@BoxWithConstraints
        // Subtitles placed in the top part of the picture (to keep clear of on-screen text) stay up there.
        val atTop = lines.all { it.lineType == Cue.LINE_TYPE_FRACTION && it.line != Cue.DIMEN_UNSET && it.line < 0.4f }
        val fontSize = with(LocalDensity.current) { (height * BASE_SIZE * (look.size / 100f)).toSp() }
        Column(
            modifier = Modifier
                .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
                .padding(top = height * 0.06f, bottom = height * liftFraction(look.lift))
                .fillMaxWidth(0.86f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (cue in lines) SubtitleText(cue.text ?: "", look, fontSize, height)
        }
    }
}

/** One subtitle in [look]: the outline (if any) drawn first, then the text with its shadow. */
@Composable
fun SubtitleText(text: CharSequence, look: SubtitleLook, fontSize: TextUnit, screenHeight: Dp) {
    val styled = remember(text) { text.toStyled() }
    val family = subtitleFont(look.font)
    val weight = when (look.font) {
        "roboto_bold", "lato" -> FontWeight.Bold
        "inter_semibold", "open_sans", "source_sans" -> FontWeight.SemiBold
        else -> FontWeight.Medium
    }
    val colour = when (look.colour) {
        "yellow" -> Color(0xFFFFE650)
        "grey" -> Color(0xFFD9D9D9)
        else -> Color.White
    }
    val density = LocalDensity.current
    // Edges scale with the text, so they look the same at any size.
    val unit = with(density) { (screenHeight * 0.0016f).toPx() } * (look.size / 100f)
    val strength = when (look.edge) {
        1 -> 0.7f
        3 -> 1.45f
        else -> 1f
    }
    val shadow = when (look.style) {
        "netflix" -> Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, unit * 0.8f * strength), blurRadius = unit * 5f * strength)
        "shadow" -> Shadow(Color.Black, Offset(unit * 1.6f * strength, unit * 1.6f * strength), blurRadius = unit * 1.2f * strength)
        "outline" -> Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, unit * 0.6f), blurRadius = unit * 1.5f)
        else -> null
    }
    val outlineWidth = when (look.style) {
        "netflix" -> unit * 0.9f * strength
        "outline" -> unit * 2.2f * strength
        else -> 0f
    }
    val outlineAlpha = if (look.style == "netflix") 0.55f else 1f
    val base = TextStyle(
        fontSize = fontSize,
        fontFamily = family,
        fontWeight = weight,
        textAlign = TextAlign.Center,
        lineHeight = fontSize * 1.18f,
    )
    val box = if (look.style == "box") {
        Modifier
            .background(Color.Black.copy(alpha = look.boxOpacity / 100f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    } else {
        Modifier
    }
    Box(modifier = Modifier.padding(vertical = 1.dp).then(box), contentAlignment = Alignment.Center) {
        if (outlineWidth > 0f) {
            BasicText(
                text = styled,
                style = base.copy(color = Color.Black.copy(alpha = outlineAlpha), drawStyle = Stroke(width = outlineWidth * 2, join = androidx.compose.ui.graphics.StrokeJoin.Round)),
            )
        }
        BasicText(text = styled, style = base.copy(color = colour, shadow = shadow))
    }
}

/** The typeface for a [SubtitleLook.font] key. */
fun subtitleFont(key: String): FontFamily = when (key) {
    "inter" -> FontFamily(Font(R.font.sub_inter, FontWeight.Medium))
    "inter_semibold" -> FontFamily(Font(R.font.sub_inter_semibold, FontWeight.SemiBold))
    "open_sans" -> FontFamily(Font(R.font.sub_open_sans, FontWeight.SemiBold))
    "source_sans" -> FontFamily(Font(R.font.sub_source_sans, FontWeight.SemiBold))
    "lato" -> FontFamily(Font(R.font.sub_lato, FontWeight.Bold))
    "figtree" -> FontFamily(Font(R.font.figtree))
    "condensed" -> FontFamily(androidx.compose.ui.text.font.Typeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL)))
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "casual" -> FontFamily(androidx.compose.ui.text.font.Typeface(Typeface.create("casual", Typeface.NORMAL)))
    else -> FontFamily.SansSerif
}

/** Italic and bold from the subtitle file kept. */
private fun CharSequence.toStyled(): AnnotatedString = buildAnnotatedString {
    val source = this@toStyled
    append(source.toString())
    if (source is Spanned) {
        for (span in source.getSpans(0, source.length, StyleSpan::class.java)) {
            val style = when (span.style) {
                Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                Typeface.BOLD_ITALIC -> SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)
                else -> null
            } ?: continue
            addStyle(style, source.getSpanStart(span), source.getSpanEnd(span))
        }
    }
}

/** Text height as a share of the picture's height, at 100%. */
private const val BASE_SIZE = 0.058f

private fun liftFraction(lift: Int): Float = when (lift) {
    0 -> 0.045f
    2 -> 0.13f
    3 -> 0.2f
    else -> 0.08f
}
