package app.tvplayer.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.tvplayer.NorthlightApp
import app.tvplayer.ui.AppRoot
import app.tvplayer.ui.components.Dest
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.ThemeId
import coil.Coil
import coil.ImageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Not a check: draws the real screens, filled with made-up content, at the Shield's screen size
 * and saves them to app/build/screenshots. Run with ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ScreenshotTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: NorthlightApp get() = ApplicationProvider.getApplicationContext()
    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val art = File("src/test/resources/demo").absoluteFile

    private fun image(name: String) = "file://${File(art, name).path}"

    @Before
    fun fill() {
        Coil.setImageLoader(ImageLoader.Builder(app).allowHardware(false).crossfade(false).build())
        runBlocking(Dispatchers.IO) { DemoData.fill(app.container, ::image) }
    }

    private fun open(dest: Dest, theme: ThemeId = ThemeId.LAGOON) {
        rule.setContent {
            AppTheme(themeId = theme) {
                AppRoot(themeId = theme, onThemeChange = {}, container = app.container, startAt = dest)
            }
        }
        settle()
    }

    /** Lets database reads, artwork loading and animations finish. */
    private fun settle() {
        repeat(10) {
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
            Thread.sleep(200)
        }
        rule.waitForIdle()
    }

    private fun press(vararg keys: Key) {
        for (key in keys) {
            runCatching { rule.onRoot().performKeyInput { pressKey(key) } }
                .onFailure { println("Key $key not delivered: $it") }
            settle()
        }
    }

    private fun shot(name: String) {
        val bitmap = runCatching { rule.onRoot().captureToImage().asAndroidBitmap() }.getOrElse {
            println("captureToImage failed ($it); drawing the window instead")
            val view = rule.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Saved $name.png (${bitmap.width}×${bitmap.height})")
    }

    @Test fun home() {
        open(Dest.HOME)
        shot("01-home-menu")
        press(Key.DirectionRight)
        shot("02-home-browsing")
        press(Key.DirectionDown, Key.DirectionDown)
        shot("03-home-rails")
    }

    @Test fun guide() {
        open(Dest.GUIDE)
        shot("04-guide-menu")
        press(Key.DirectionRight, Key.DirectionRight)
        shot("05-guide-browsing")
    }

    @Test fun films() {
        open(Dest.FILMS)
        shot("06-films-menu")
        press(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight)
        shot("07-films-browsing")
        press(Key.DirectionCenter)
        shot("08-film-page")
    }

    @Test fun series() {
        open(Dest.SERIES)
        shot("09-series-menu")
    }

    @Test fun search() {
        open(Dest.SEARCH)
        shot("10-search")
    }

    @Test fun settings() {
        open(Dest.SETTINGS)
        shot("11-settings")
    }

    @Test fun orchid() {
        open(Dest.HOME, ThemeId.ORCHID)
        shot("12-theme-orchid")
    }

    @Test fun midnight() {
        open(Dest.HOME, ThemeId.MIDNIGHT)
        shot("13-theme-midnight")
    }
}
