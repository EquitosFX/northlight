package app.tvplayer.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
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
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A walk through every area of the app with the remote, on the build machine (Robolectric), at the
 * Shield's screen size and filled with made-up content. Each step checks where focus landed and what
 * is on screen, and saves a picture to app/build/screenshots. A wrong step is recorded and the walk
 * carries on, so one problem doesn't hide the rest; the test fails at the end listing every problem.
 *
 * Run with ./gradlew :app:testDebugUnitTest --tests 'app.tvplayer.demo.ScreenshotTest'.
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
    private val problems = ArrayList<String>()

    private fun image(name: String) = "file://${File(art, name).path}"

    @Before
    fun fill() {
        Coil.setImageLoader(ImageLoader.Builder(app).allowHardware(false).crossfade(false).build())
        app.container.testResponder = DemoData.responder()
        runBlocking(Dispatchers.IO) { DemoData.fill(app.container, ::image) }
    }

    @After
    fun report() {
        if (problems.isNotEmpty()) fail("Problems found:\n" + problems.joinToString("\n") { "  • $it" })
    }

    // ---- Driving the app ----

    private fun open(dest: Dest, theme: ThemeId = ThemeId.SKYFALL) {
        leaveTouchMode()
        rule.setContent {
            AppTheme(themeId = theme) {
                AppRoot(themeId = theme, onThemeChange = {}, container = app.container, startAt = dest)
            }
        }
        settle()
    }

    /** Lets database reads, artwork loading and animations finish. */
    private fun settle(rounds: Int = 10) {
        repeat(rounds) {
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
            Thread.sleep(150)
        }
        rule.waitForIdle()
    }

    private val keys = mapOf(
        "right" to Key.DirectionRight, "left" to Key.DirectionLeft, "up" to Key.DirectionUp,
        "down" to Key.DirectionDown, "ok" to Key.DirectionCenter,
    )

    private fun press(vararg names: String) {
        for (name in names) {
            if (name == "back") {
                // Back goes through Android's back handling, as the remote's Back button does.
                rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            } else {
                val key = keys.getValue(name)
                val target = if (focusCount() > 0) rule.onNode(isFocused()) else rule.onRoot()
                runCatching { target.performKeyInput { pressKey(key) } }
                    .onFailure { problems += "Key $name wasn't delivered: $it" }
            }
            settle()
            println("  after $name: focus on \"${focusedText()}\"")
        }
    }

    private fun type(text: String) {
        val fields = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        if (fields.isEmpty()) {
            problems += "No text field to type \"$text\" into"
            return
        }
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement(text)
        settle(16)
    }

    // ---- Checks ----

    private fun focusCount() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().size

    private fun textOf(node: SemanticsNode): String {
        val own = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        return (listOf(own) + node.children.map { textOf(it) }).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun focusedText(): String =
        rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.let { textOf(it) } ?: "(nothing focused)"

    private fun expectFocus(text: String, step: String) {
        val now = focusedText()
        if (!now.contains(text, ignoreCase = true)) problems += "$step: expected focus on \"$text\", but it was on \"$now\""
    }

    private fun expectShown(text: String, step: String) {
        val found = rule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes()
        if (found.isEmpty()) problems += "$step: expected \"$text\" on screen"
    }

    private fun expectHidden(text: String, step: String) {
        val found = rule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes()
        if (found.isNotEmpty()) problems += "$step: didn't expect \"$text\" on screen"
    }

    /** The side panel is open when its menu sits on screen, and tucked away when it's pushed off the left edge. */
    private fun expectPanel(open: Boolean, step: String) {
        val menu = rule.onAllNodesWithText("Recents").fetchSemanticsNodes().firstOrNull()
        val left = menu?.boundsInRoot?.left ?: Float.NaN
        val isOpen = left >= 0f
        if (isOpen != open) problems += "$step: expected the side panel ${if (open) "open" else "tucked away"} (menu at x=$left)"
    }

    // ---- Pictures ----

    private fun shot(name: String) {
        val bitmap = runCatching { pixelCopy() }.onFailure { println("PixelCopy failed: $it") }.getOrNull()
            ?: runCatching { rule.onRoot().captureToImage().asAndroidBitmap() }.getOrElse {
                val view = rule.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Saved $name.png — focus on \"${focusedText()}\"")
    }

    /** Copies the window as the screen shows it (Robolectric draws it with its hardware renderer). */
    private fun pixelCopy(): Bitmap {
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var result: Int? = null
        rule.runOnUiThread {
            PixelCopy.request(rule.activity.window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
        }
        repeat(50) {
            if (result != null) return@repeat
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        check(result == PixelCopy.SUCCESS) { "PixelCopy result $result" }
        return bitmap
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
        }.onFailure { println("Couldn't leave touch mode: $it") }
    }

    // ---- The walk ----

    @Test fun home() {
        open(Dest.HOME)
        expectFocus("Home", "Start-up")
        expectPanel(open = true, step = "Start-up")
        expectShown("Ember Valley", "Home hero")
        shot("01-home-menu")

        press("right")
        expectFocus("Resume", "Right from the menu")
        expectPanel(open = false, step = "Browsing Home")
        shot("02-home-browsing")

        press("down", "down")
        expectFocus("Ember Valley", "Down to Continue watching")
        press("down")
        expectFocus("Meridian One", "Down to Recent channels")
        shot("03-home-rails")

        press("left")
        expectFocus("Home", "Left back to the menu")
        expectPanel(open = true, step = "Back in the menu")
        press("down")
        expectFocus("TV guide", "Down the menu")
        expectShown("All channels", "Previewing the TV guide from the menu")
        shot("04-menu-previews-guide")
    }

    @Test fun recents() {
        open(Dest.RECENTS)
        expectShown("Recent channels", "Recents")
        press("right")
        expectPanel(open = false, step = "Browsing Recents")
        shot("05-recents")
    }

    @Test fun guide() {
        open(Dest.GUIDE)
        expectShown("Meridian One", "Guide channels")
        shot("06-guide-menu")

        press("right")
        expectPanel(open = false, step = "Into the guide")
        if (isCategory(focusedText())) {
            problems += "Into the guide: landed on the category list (\"${focusedText()}\") rather than the listings"
        }
        press("down", "down")
        shot("07-guide-browsing")

        // Left along the listings until the category list.
        repeat(8) { if (!isCategory(focusedText())) press("left") }
        expectFocus("All channels", "Left from the listings returns to the open category")
        press("down", "down")
        expectFocus("News", "Down the categories")
        expectShown("Atlas News", "News category shows news channels")
        expectHidden("Meridian Two", "News category hides other channels")
        shot("08-guide-categories")

        press("right")
        expectFocus("", "Into the news listings")
        press("ok")
        expectShown("Atlas News", "Playing shows the channel banner")
        shot("09-playing-live")
        press("back")
        expectPanel(open = true, step = "Back from full-screen TV")
        expectFocus("TV guide", "Back from full-screen TV lands on the menu")
        shot("10-picture-in-panel")
        press("down")
        expectFocus("Recents", "Down the menu with the picture showing")
    }

    private fun isCategory(text: String) = listOf(
        "Favourites", "All channels", "Entertainment", "News", "Sport", "Movies", "Kids", "Documentaries", "Music",
    ).any { text.trim() == it }

    @Test fun films() {
        open(Dest.FILMS)
        expectShown("The Lantern Coast", "Films")
        shot("11-films-menu")

        press("right")
        expectFocus("New releases", "Right from the menu into Films")
        press("right")
        expectFocus("The Lantern Coast", "Right into the posters")
        expectPanel(open = false, step = "Browsing films")
        press("right")
        expectFocus("Harbour of Glass", "Next poster")
        expectShown("estranged sisters", "Header follows the highlighted poster")
        shot("12-films-browsing")

        press("ok")
        expectFocus("Play", "Opening a film")
        expectHidden("CARRY ON WATCHING", "Opening a film shouldn't go Home")
        shot("13-film-page")

        press("down")
        val chip = focusedText()
        if (chip.contains("Play")) problems += "Down from Play didn't reach the cast"
        press("ok")
        expectShown("Harbour of Glass", "Person page lists their titles")
        shot("14-person-page")

        press("back")
        expectFocus("Play", "Back from the person page")
        press("back")
        expectFocus("Harbour of Glass", "Back from the film page")
        shot("15-back-to-films")

        press("left", "left")
        expectFocus("New releases", "Left from the posters returns to the open category")
        press("left")
        expectFocus("Films", "Left back to the menu")
        expectPanel(open = true, step = "Back in the menu from Films")
    }

    @Test fun series() {
        open(Dest.SERIES)
        shot("16-series-menu")
        press("right", "right")
        expectFocus("Cobalt Street", "Into the series posters")
        press("ok")
        expectFocus("Play", "Opening a series")
        shot("17-series-page")
    }

    @Test fun search() {
        open(Dest.SEARCH)
        press("right")
        expectPanel(open = false, step = "Into Search")
        press("ok")
        type("the")
        expectShown("The Quiet Orchard", "Searching \"the\" finds films")
        expectShown("The Archivist", "Searching \"the\" finds series")
        shot("18-search-titles")
        type("priya")
        expectShown("Priya Desai", "Searching a name finds the person")
        expectHidden("Nothing found", "A person found isn't reported as nothing found")
        shot("19-search-people")
    }

    @Test fun settings() {
        open(Dest.SETTINGS)
        press("right")
        expectFocus("Playlists", "Into Settings")
        expectShown("Demo playlist", "Playlists pane")
        shot("20-settings-playlists")
        press("down", "ok")
        shot("21-settings-tmdb")
        press("down", "ok")
        expectShown("Skyfall", "Appearance pane")
        expectShown("Lagoon", "Appearance pane")
        shot("22-settings-appearance")
        press("down", "ok")
        expectShown("Build 999 is ready", "Updates pane finds the newer build")
        expectShown("Download and install", "Updates pane offers to install it")
        shot("22b-settings-updates")
    }

    @Test fun lagoon() {
        open(Dest.HOME, ThemeId.LAGOON)
        shot("23-theme-lagoon")
    }

    @Test fun orchid() {
        open(Dest.HOME, ThemeId.ORCHID)
        shot("24-theme-orchid")
    }

    @Test fun midnight() {
        open(Dest.HOME, ThemeId.MIDNIGHT)
        shot("25-theme-midnight")
    }
}
