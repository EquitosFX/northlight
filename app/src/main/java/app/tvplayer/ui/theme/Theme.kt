package app.tvplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import app.tvplayer.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The colour themes the user can pick in Settings › Appearance. */
enum class ThemeId(val key: String, val label: String, val description: String) {
    SKYFALL("skyfall", "Skyfall", "Navy and ice"),
    LAGOON("lagoon", "Lagoon", "Teal and amber"),
    ORCHID("orchid", "Orchid", "Plum and lime"),
    MIDNIGHT("midnight", "Midnight", "Navy and mint");

    companion object {
        fun fromKey(key: String?): ThemeId = entries.firstOrNull { it.key == key } ?: SKYFALL
    }
}

/** Every colour a screen may use. Screens never hard-code colours; they read these tokens. */
@Immutable
data class AppColors(
    val groundTop: Color,
    val groundMid: Color,
    val groundBottom: Color,
    val accent: Color,
    val onAccent: Color,
) {
    val text: Color = Color(0xFFF2F7F6)
    val textDim: Color = text.copy(alpha = 0.85f)
    val textFaint: Color = text.copy(alpha = 0.72f)
    val glass: Color = Color.White.copy(alpha = 0.12f)
    val glassStrong: Color = Color.White.copy(alpha = 0.22f)
    val glassBorder: Color = Color.White.copy(alpha = 0.18f)
    val focusRing: Color = Color.White
    val scrim: Color get() = groundBottom.copy(alpha = 0.95f)
    val ground: Brush get() = Brush.linearGradient(listOf(groundTop, groundMid, groundBottom))
}

fun paletteFor(id: ThemeId): AppColors = when (id) {
    // From the Skyfall brand: midnight navy, electric blue, ice blue and white.
    ThemeId.SKYFALL -> AppColors(
        groundTop = Color(0xFF0C2560),
        groundMid = Color(0xFF061338),
        groundBottom = Color(0xFF030819),
        accent = Color(0xFF00BEEB),
        onAccent = Color(0xFF02121E),
    )
    ThemeId.LAGOON -> AppColors(
        groundTop = Color(0xFF0B4A52),
        groundMid = Color(0xFF063039),
        groundBottom = Color(0xFF041E25),
        accent = Color(0xFFFFB84D),
        onAccent = Color(0xFF1A1205),
    )
    ThemeId.ORCHID -> AppColors(
        groundTop = Color(0xFF4A1B5E),
        groundMid = Color(0xFF2E0F3D),
        groundBottom = Color(0xFF1A0824),
        accent = Color(0xFFC9F25C),
        onAccent = Color(0xFF18200A),
    )
    ThemeId.MIDNIGHT -> AppColors(
        groundTop = Color(0xFF16306B),
        groundMid = Color(0xFF0D1C45),
        groundBottom = Color(0xFF070F28),
        accent = Color(0xFF5EEAD4),
        onAccent = Color(0xFF04201B),
    )
}

/** Figtree (SIL Open Font License, see third_party/figtree/OFL.txt): one variable font file, four weights. */
@OptIn(ExperimentalTextApi::class)
private val Figtree = FontFamily(
    listOf(400, 500, 600, 700).map { weight ->
        Font(
            resId = R.font.figtree,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/** Type scale, sized for a TV viewed from the sofa (a 1080p TV is 960 x 540 dp). */
object AppType {
    private val family = Figtree
    val display = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 46.sp)
    val headline = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp)
    val title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp)
    val caption = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp)
    val menu = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 22.sp)
    val menuSelected = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 23.sp)
    val clock = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp)
    val overline = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
    )
}

private val LocalAppColors = staticCompositionLocalOf { paletteFor(ThemeId.SKYFALL) }

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

@Composable
fun AppTheme(themeId: ThemeId, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides paletteFor(themeId), content = content)
}
