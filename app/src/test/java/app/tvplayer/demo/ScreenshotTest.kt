package app.tvplayer.demo

import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
        // A TV is never in touch mode; in touch mode the remote-style focus doesn't switch on.
        runCatching { InstrumentationRegistry.getInstrumentation().setInTouchMode(false) }
            .onFailure { println("Couldn't leave touch mode: $it") }
        leaveTouchMode()
        rule.setContent {
            AppTheme(themeId = theme) {
                AppRoot(themeId = theme, onThemeChange = {}, container = app.container, startAt = dest)
            }
        }
        settle()
        // Robolectric's window may not hand focus over by itself; put it on the open section's menu item.
        println("Focused after opening ${dest.label}: ${focusedCount()}")
        if (focusedCount() == 0) {
            runCatching { rule.onAllNodesWithText(dest.label)[0].performSemanticsAction(SemanticsActions.RequestFocus) }
                .onFailure { println("Couldn't focus ${dest.label}: $it") }
            settle()
            println("Focused after asking: ${focusedCount()}")
        }
    }

    /** What a remote press does on a real box: takes the window out of touch mode, so focus can show. */
    private fun leaveTouchMode() {
        val view = rule.activity.window.decorView
        runCatching {
            val root = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(view)!!
            val method = generateSequence<Class<*>>(root.javaClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .first { (it.name == "ensureTouchModeLocally" || it.name == "ensureTouchMode") && it.parameterTypes.size == 1 }
            method.isAccessible = true
            rule.runOnUiThread { method.invoke(root, false) }
            println("Left touch mode with ${method.name}; in touch mode now: ${view.isInTouchMode}")
        }.onFailure { println("Couldn't leave touch mode: $it") }
    }

    private fun focusedCount() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().size

    /** Lets database reads, artwork loading and animations finish. */
    private fun settle() {
        repeat(10) {
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
            Thread.sleep(200)
        }
        rule.waitForIdle()
    }

    private fun press(vararg names: String) {
        val keys = names.map { mapOf("right" to Key.DirectionRight, "down" to Key.DirectionDown, "ok" to Key.DirectionCenter).getValue(it) }
        for (key in keys) {
            val target = if (focusedCount() > 0) rule.onNode(isFocused()) else rule.onRoot()
            runCatching { target.performKeyInput { pressKey(key) } }
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
        press("right")
        shot("02-home-browsing")
        press("down", "down")
        shot("03-home-rails")
    }

    @Test fun guide() {
        open(Dest.GUIDE)
        shot("04-guide-menu")
        press("right", "right")
        shot("05-guide-browsing")
    }

    @Test fun films() {
        open(Dest.FILMS)
        shot("06-films-menu")
        press("right", "right", "right")
        shot("07-films-browsing")
        press("ok")
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
