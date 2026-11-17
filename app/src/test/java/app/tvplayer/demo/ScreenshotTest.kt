package app.tvplayer.demo

import app.tvplayer.CrashLog
import java.time.LocalDate
import app.tvplayer.core.seasonal.Christmas
import app.tvplayer.core.mylist.WatchItem
import app.tvplayer.core.mylist.Reminder
import org.junit.rules.TestName
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
import androidx.compose.ui.test.onAllNodesWithTag
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
    @get:Rule val testName = TestName()

    private val app: NorthlightApp get() = ApplicationProvider.getApplicationContext()
    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val art = File("src/test/resources/demo").absoluteFile
    private val problems = ArrayList<String>()

    private fun image(name: String) = "file://${File(art, name).path}"

    @Before
    fun fill() {
        Coil.setImageLoader(ImageLoader.Builder(app).allowHardware(false).crossfade(false).build())
        app.container.testResponder = DemoData.responder()
        // The welcome test starts from a fresh install with no playlist.
        if (testName.methodName != "welcome") runBlocking(Dispatchers.IO) { DemoData.fill(app.container, ::image) }
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

    /**
     * Presses [key] and watches frame by frame (as the TV would draw them) that no guide row is ever
     * shown without its programmes: a category's listings change in one go, already filled in.
     */
    private fun pressWatchingRows(key: String, step: String) {
        val target = if (focusCount() > 0) rule.onNode(isFocused()) else rule.onRoot()
        rule.mainClock.autoAdvance = false
        try {
            target.performKeyInput { pressKey(keys.getValue(key)) }
            repeat(90) {
                rule.mainClock.advanceTimeBy(16)
                Thread.sleep(6)
                val waiting = rule.onAllNodesWithTag("guide-row-waiting", useUnmergedTree = true).fetchSemanticsNodes().size
                if (waiting > 0) {
                    problems += "$step: $waiting guide rows were drawn before their programmes were ready"
                    return
                }
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        settle()
        println("  after $key (watched): focus on \"${focusedText()}\"")
    }

    /** Every player button is fully on screen without scrolling, from the first 5 min to Sleep. */
    private fun expectButtonsFit(step: String) {
        val screenWidth = rule.onRoot().fetchSemanticsNode().size.width
        for (label in listOf("5 min", "Sleep")) {
            for (node in rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes()) {
                val b = node.boundsInRoot
                if (b.left < 0f || b.right > screenWidth) problems += "$step: the \"$label\" button should be fully on screen (from ${b.left} to ${b.right} of $screenWidth)"
            }
        }
    }

    /** Holds OK down for most of a second, as the remote does when OK is held. */
    private fun holdOk() {
        // What a remote sends: the press, repeats of it every so often while held, then the release.
        runCatching {
            rule.runOnUiThread {
                val code = android.view.KeyEvent.KEYCODE_DPAD_CENTER
                val t = android.os.SystemClock.uptimeMillis()
                for ((after, repeat) in listOf(0L to 0, 500L to 1, 550L to 2, 600L to 3)) {
                    rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t + after, android.view.KeyEvent.ACTION_DOWN, code, repeat))
                }
                rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t + 700, android.view.KeyEvent.ACTION_UP, code, 0))
            }
        }.onFailure { problems += "Holding OK wasn't delivered: $it" }
        settle()
        println("  after holding ok: focus on \"${focusedText()}\"")
    }

    /** Where a piece of text starts across the screen, in pixels. */
    private fun leftOf(text: String): Float =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left ?: Float.NaN

    /** Presses [key] until focus is on [text], up to [max] times. */
    private fun moveTo(text: String, key: String, max: Int = 8, step: String) {
        repeat(max) {
            if (focusedText().contains(text, ignoreCase = true)) return
            press(key)
        }
        expectFocus(text, step)
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

    private fun isShown(text: String) =
        rule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

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
        expectShown("Live: Meridian One", "The hero's live button names the channel it opens")
        shot("02-home-browsing")

        press("down", "down")
        expectFocus("Ember Valley", "Down to Continue watching")
        if (Christmas.inSeason(LocalDate.now())) {
            press("down")
            expectShown("Christmas films and series", "The seasonal row, from November")
            expectFocus("Paper Moons", "Down to the Christmas row")
            shot("03b-home-christmas")
        }
        press("down")
        expectShown("Because you watched Ember Valley", "Suggestions from what's been watched")
        expectFocus("Signal Lost", "Down to the suggestions")
        shot("03a-home-suggestions")
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

    @Test fun welcome() {
        open(Dest.HOME)
        expectShown("Welcome to Skyfall", "Fresh install")
        shot("00a-welcome")
        press("right")
        expectFocus("Add a playlist", "Right from the menu on a fresh install")
        press("ok")
        expectShown("Add Xtream Codes account", "Add a playlist opens Settings › Playlists")
        expectHidden("Welcome to Skyfall", "Add a playlist leaves Home")
        shot("00b-welcome-to-playlists")
    }

    @Test fun homeShortcuts() {
        open(Dest.HOME)
        press("right", "down")
        expectFocus("Live TV", "Down from the hero to the shortcuts")
        press("right", "right")
        expectFocus("Films", "Along the shortcuts")
        press("ok")
        expectShown("New releases", "The Films shortcut opens Films")
        expectHidden("CARRY ON WATCHING", "The Films shortcut leaves Home")
        open2Guide()
    }

    /** Back to Home from Films, then the TV Guide shortcut. */
    private fun open2Guide() {
        // Back once returns to the menu, again goes Home.
        press("back", "back")
        expectShown("CARRY ON WATCHING", "Back from Films returns to Home")
        expectFocus("Home", "The menu highlight follows Back to Home")
        press("right", "down", "right")
        expectFocus("TV Guide", "The TV Guide shortcut")
        press("ok")
        // It opens on the categories; OK on one shows its listings.
        expectShown("Meridian One", "The TV Guide shortcut opens the guide")
        expectFocus("All channels", "The TV Guide shortcut opens on the categories")
        press("ok")
        expectHidden("Favourites", "OK on a category shows just the listings")
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

        // Right from the menu: the categories, on the one in use.
        press("right")
        expectPanel(open = false, step = "Into the guide")
        expectFocus("All channels", "Into the guide lands on the categories")
        expectShown("Favourites", "The guide opens on its categories")
        // OK on a category: just the listings, no menu or categories.
        press("ok")
        expectHidden("Favourites", "The categories step aside in the listings")
        if (isCategory(focusedText())) problems += "OK on a category: focus stayed on the categories"
        // Holding OK on a programme adds its channel to Favourites (without playing it); again takes it off.
        val heldOn = focusedText()
        val wasFavourite = isShown("★ Favourite · hold OK")
        if (!wasFavourite) expectShown("Hold OK to make it a favourite", "The guide says how to add a favourite")
        holdOk()
        if (isShown("★ Favourite · hold OK") == wasFavourite) problems += "Holding OK should add or remove the channel from Favourites"
        if (focusedText() != heldOn) problems += "Holding OK should stay on \"$heldOn\", but focus was on \"${focusedText()}\""
        shot("07a-guide-favourite")
        holdOk()
        if (isShown("★ Favourite · hold OK") != wasFavourite) problems += "Holding OK again should undo it"
        // Down keeps to what's on now, whatever the lengths of the programmes above
        // (the demo guide changes with the time of day, so this checks the header says "On now").
        val startedOn = focusedText()
        press("down")
        expectShown("On now", "Down lands on what's on now")
        press("down")
        expectShown("On now", "Down again lands on what's on now")
        press("up", "up")
        if (focusedText() != startedOn) problems += "Up twice should return to \"$startedOn\", but focus was on \"${focusedText()}\""
        press("down", "down")
        shot("07-guide-listings")

        // Back once: the categories, with the one in use highlighted.
        press("back")
        expectFocus("All channels", "Back from the listings goes to the categories")
        expectShown("Favourites", "The categories come back")
        // The listings move aside for the categories, clear of them.
        if (!(leftOf("Meridian One") > (rule.onAllNodesWithText("Favourites").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.right ?: Float.MAX_VALUE))) {
            problems += "With the categories showing, the listings should sit to their right"
        }
        press("down")
        pressWatchingRows("down", "Changing category")
        expectFocus("News", "Down the categories")
        expectShown("Atlas News", "Resting on News shows its channels")
        shot("08-guide-categories")

        // OK on a category: its listings fill the screen.
        val beside = leftOf("Atlas News")
        press("ok")
        if (!(leftOf("Atlas News") < beside)) problems += "Into the listings, they should slide left to fill the screen"
        expectHidden("Favourites", "Choosing a category hides the categories")
        expectShown("Atlas News", "News category shows news channels")
        expectHidden("Meridian Two", "News category hides other channels")
        expectPanel(open = false, step = "Choosing a category")
        shot("08b-guide-news-listings")

        // Back twice: the categories, then the side menu.
        press("back")
        expectFocus("News", "Back returns to the chosen category")
        // Right on a category goes in just as OK does: the top channel, on what's on now.
        press("right")
        expectHidden("Favourites", "Right on a category goes into its listings")
        expectShown("On now", "Right on a category lands on what's on now")
        press("back")
        expectFocus("News", "Back from the listings returns to the category again")
        press("back")
        expectFocus("TV guide", "Back again goes to the side menu")
        expectPanel(open = true, step = "Back in the side menu")

        // Play the news channel and open the player controls.
        press("right")
        expectFocus("News", "Right from the menu returns to the chosen category")
        press("ok", "ok")
        expectShown("Atlas News", "Playing shows the channel")
        shot("09-playing-live")
        // Up: another channel's programmes, without switching; Back stays on this one.
        press("up")
        expectShown("OK to watch", "Up shows another channel without switching")
        shot("09a-browse-channels")
        press("back")
        expectHidden("OK to watch", "Back closes the browse banner")
        expectPanel(open = false, step = "Back from browsing stays full screen")
        press("ok")
        expectShown("Subtitles", "OK shows the player controls")
        expectShown("Channels", "The controls offer the channel list")
        expectShown("min left", "The controls show the programme's times")
        if (focusedText().contains("Pause")) problems += "The controls opened with Pause already highlighted"
        expectButtonsFit("When the controls open")
        shot("09b-player-controls")
        // OK again pauses, with Play highlighted; OK once more carries on.
        press("ok")
        expectShown("Subtitles", "OK again keeps the controls open")
        // (The demo channel has no real video, so whether it shows Play or Pause depends on the test player.)
        if (!focusedText().contains("Pause") && !focusedText().contains("Play")) {
            problems += "OK again should pause and highlight Play, but focus was on \"${focusedText()}\""
        }
        shot("09b2-paused")
        press("ok")
        if (!focusedText().contains("Pause") && !focusedText().contains("Play")) {
            problems += "OK on Play/Pause moved focus to \"${focusedText()}\""
        }
        // The star adds the channel to Favourites, and takes it off again.
        fun starSays() = when {
            isShown("is in Favourites · OK to take it off") -> "in"
            isShown("to Favourites") -> "out"
            else -> null
        }
        repeat(12) { if (starSays() == null) press("right") }
        val starBefore = starSays()
        if (starBefore == null) problems += "Along the controls to the favourite star: it should say what it does"
        press("ok")
        if (starSays() == starBefore) problems += "The star should add or remove the channel from Favourites"
        shot("09b4-favourite-star")
        press("ok")
        if (starSays() != starBefore) problems += "The star again should undo it"
        // The last button, Sleep, is on screen and can be reached.
        moveTo("Sleep", "right", max = 12, step = "Along the controls to Sleep")
        expectButtonsFit("Moved along to Sleep")
        press("ok")
        expectShown("Sleep 30m", "Sleep sets 30 minutes")
        shot("09b3-sleep-timer")
        press("ok", "ok", "ok", "ok")
        expectFocus("Sleep", "Sleep goes back to off")
        moveTo("Subtitles", "left", step = "Back along the controls to Subtitles")
        press("ok")
        expectShown("Off", "The subtitles menu")
        expectShown("isn't sending any subtitles", "The subtitles menu explains there are none")
        shot("09c-subtitles-menu")
        press("back", "back")
        expectHidden("Subtitles", "Back closes the menu, then the controls")
        press("back")
        expectPanel(open = true, step = "Back from full-screen TV")
        expectFocus("TV guide", "Back from full-screen TV lands on the menu")
        shot("10-picture-in-panel")
        // The guide behind already shows the channel playing, in its category; OK goes straight to it.
        expectHidden("Favourites", "Back from TV: the guide shows the playing channel's listings")
        press("ok")
        expectShown("· Atlas News", "OK on TV guide lands on the channel playing")
        expectShown("On now", "OK on TV guide lands on what's on now")
        shot("10b-guide-on-playing-channel")
        press("back")
        expectFocus("News", "Back from there goes to the playing channel's category")
        press("back")
        expectFocus("TV guide", "Back from the categories goes to the menu")
        press("down")
        expectFocus("Recents", "Down the menu with the picture showing")
    }

    /** Holding Down on the menu, then letting go: it stops where it was let go, without jumping back a place. */
    @Test fun menuHold() {
        open(Dest.HOME)
        expectFocus("Home", "The menu starts on Home")
        // What a remote sends while Down is held: the press, quick repeats, then letting go.
        rule.runOnUiThread {
            val code = android.view.KeyEvent.KEYCODE_DPAD_DOWN
            val t = android.os.SystemClock.uptimeMillis()
            for (repeat in 0..4) {
                rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t + repeat * 50L, android.view.KeyEvent.ACTION_DOWN, code, repeat))
            }
        }
        settle(2)
        rule.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0))
        }
        settle()
        expectFocus("Sport", "Holding Down five places and letting go stays there")
        settle()
        expectFocus("Sport", "A moment later, still there")
    }

    /** A big, untidy guide: opening it, moving around, sliding in time and changing category mustn't crash. */
    @Test fun largeGuide() {
        runBlocking(Dispatchers.IO) { DemoData.fillLarge(app.container) }
        open(Dest.HOME)
        // In from the menu with OK, and with Right; from Home's shortcut.
        press("down", "ok")
        expectShown("All channels", "OK on TV guide opens the guide")
        press("ok")
        press("down", "down", "down", "down", "down", "down", "down", "down", "down", "down", "down", "down")
        press("left", "left", "left", "right", "right", "right", "right", "right")
        shot("07d-large-guide")
        press("back")
        // Moved to another time: the first Back returns to now; the next, to the categories.
        if (!focusedText().startsWith("Group") && !isCategory(focusedText())) press("back")
        // A category far down the list, then back to it.
        repeat(30) { press("down") }
        expectFocus("Group 23", "Far down the categories")
        press("ok", "down", "back")
        expectFocus("Group 23", "Back from a far-down category's listings returns to it")
        press("back")
        expectFocus("TV guide", "Back again goes to the side menu")
        press("right")
        expectFocus("Group 23", "Right from the menu returns to the chosen category")
        press("back", "back")
        press("down", "right", "ok", "back", "back")
    }

    @Test fun reminders() {
        open(Dest.GUIDE)
        press("right", "ok")
        // Right moves to the next programme, still to come.
        press("right")
        expectShown("OK to set a reminder", "A programme still to come offers a reminder")
        press("ok")
        expectShown("Reminder set", "OK sets the reminder")
        shot("07f-guide-reminder")
        // Up from the top channel reaches the days.
        press("up")
        expectFocus("Today", "Up from the top channel lands on today")
        press("right")
        expectFocus("Tomorrow", "Along the days to Tomorrow")
        press("ok")
        shot("07g-guide-tomorrow")
    }

    @Test fun myList() {
        val now = System.currentTimeMillis()
        app.container.myList.toggleReminder(
            Reminder("1|meridian one", "Meridian One", null, "Quiz at Nine", now + 3_600_000, now + 7_200_000),
        )
        app.container.myList.toggleWatch(WatchItem("movie", "Harbour of Glass", null, "http://demo.invalid/movie/demo/demo/2.mkv", now))
        app.container.myList.toggleWatch(WatchItem("series", "Cobalt Street", null, "", now))
        // Watched to the end: a tick; a series with episodes added since last time: a badge.
        app.container.watched.mark("http://demo.invalid/movie/demo/demo/2.mkv", true)
        app.getSharedPreferences("new_episodes", 0).edit().putStringSet("flagged", setOf("series|cobalt street")).commit()
        open(Dest.MY_LIST)
        press("right")
        expectShown("Quiz at Nine", "My list shows reminders")
        expectShown("Harbour of Glass", "My list shows the watchlist")
        expectShown("NEW EPISODES", "A series with new episodes is flagged")
        shot("28-my-list")
    }

    @Test fun reminderPopup() {
        val now = System.currentTimeMillis()
        app.container.myList.toggleReminder(
            Reminder("1|meridian one", "Meridian One", null, "Quiz at Nine", now + 30_000, now + 3_600_000, "Tonight's quiz."),
        )
        open(Dest.HOME)
        expectShown("Watch now", "A reminder pops up as its programme starts")
        expectShown("Quiz at Nine", "The pop-up names the programme")
        shot("29-reminder-popup")
    }

    @Test fun sport() {
        open(Dest.SPORT)
        press("right")
        expectFocus("All sport", "Into Sport")
        expectShown("Football", "Sport sorts events by sport")
        moveTo("Football", "right", step = "Along to Football")
        press("ok")
        expectShown("Harbour City v Kestrel United", "Sport finds matches in the guide")
        expectHidden("Cricket: Second Test", "The Football filter shows only football")
        shot("30-sport")
    }

    @Test fun catchup() {
        open(Dest.GUIDE)
        press("right", "ok")
        expectFocus("", "Into the listings")
        // Left goes back in time; past programmes on catch-up channels can be played again.
        var found = false
        repeat(6) {
            if (!found) {
                press("left")
                found = rule.onAllNodesWithText("OK to watch from the start", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (!found) problems += "Left in the guide didn't reach a catch-up programme"
        shot("07b-guide-catch-up")
        press("ok")
        expectShown("Meridian One ·", "OK on a past programme plays it from the start")
        shot("07c-catch-up-playing")

        // The controls: Up goes to the progress bar; Left/Right move a marker along it.
        press("ok")
        press("up")
        expectShown("Left/Right to move along", "Up from the controls highlights the progress bar")
        // Catch-up moves a minute at a time (the server works in whole minutes).
        press("right", "right")
        expectShown("2:00", "Right moves along the programme a minute at a time")
        shot("07e-catch-up-scrub")
    }

    @Test fun movieNight() {
        open(Dest.MOVIE_NIGHT)
        press("right")
        expectFocus("Let's start", "Into Movie night")
        shot("25b-movie-night-intro")
        press("ok")
        expectShown("QUESTION 1 OF 10", "The first question")
        expectFocus("A film", "The first answer is highlighted")
        shot("26-movie-night-question")
        // Answer all ten with the first choice each time.
        repeat(10) { press("ok") }
        expectShown("YOUR SHORTLIST", "A shortlist after the last question")
        expectHidden("Ember Valley", "Titles already watched are left out")
        expectShown("★", "The shortlist shows ratings")
        expectShown("1h", "The shortlist shows running times")
        shot("27-movie-night-shortlist")
        press("right")
        expectShown("YOUR SHORTLIST", "Moving along the shortlist")
        press("ok")
        expectFocus("Play", "OK on a pick opens its page")
        press("back")
        expectShown("YOUR SHORTLIST", "Back from the page returns to the shortlist")
        press("back")
        expectShown("QUESTION 10 OF 10", "Back from the shortlist goes to the last question")

        // Same answers, different titles.
        press("ok")
        expectShown("YOUR SHORTLIST", "The shortlist again")
        moveTo("Show me others", "right", max = 12, step = "Along to Show me others")
        press("ok")
        if (!isShown("YOUR SHORTLIST") && !isShown("That's everything")) problems += "Show me others gave neither titles nor a message"
        shot("27b-movie-night-others")

        // Home from the shortlist.
        if (isShown("YOUR SHORTLIST")) {
            moveTo("Show me others", "right", max = 12, step = "Back along to Show me others")
            moveTo("Home", "down", max = 3, step = "Down to Home")
            press("ok")
            expectHidden("YOUR SHORTLIST", "Home leaves Movie night")
            expectShown("CARRY ON WATCHING", "Home from the shortlist goes Home")
        }
    }

    /** The crash note says which box it was, where the cause was, and never carries addresses. */
    @Test fun crashNote() {
        val cause = IllegalStateException("bad state at http://user:secret@example.invalid/live/1.ts")
        val error = RuntimeException("Wrapped", cause)
        val note = CrashLog.describe(app, Thread.currentThread(), error)
        if ("Caused by IllegalStateException" !in note) problems += "The crash note should name the underlying cause"
        if (android.os.Build.MODEL !in note) problems += "The crash note should name the TV box model"
        if ("secret" in note || "example.invalid" in note) problems += "The crash note must not contain addresses"
        // Nothing recorded by Android means nothing is noted.
        CrashLog.clear(app)
        CrashLog.noteExitReason(app)
        if (CrashLog.read(app) != null) problems += "No exit reason should leave no crash note"
    }

    /** Back after moving to another time in the guide returns to what's on now, on the same channel. */
    @Test fun guideBackToNow() {
        open(Dest.GUIDE)
        press("right", "ok")
        press("down")
        val channel = focusedText()
        press("left", "left", "left", "left")
        shot("07h-guide-earlier")
        press("back")
        expectShown("On now", "Back returns to what's on now")
        expectHidden("Favourites", "Back to now stays in the listings")
        shot("07i-guide-back-to-now")
        if (channel.isEmpty()) problems += "No programme was highlighted before moving back in time"
        press("back")
        expectShown("Favourites", "Back again goes to the categories")
    }

    /** Quit part-way through the questions. */
    @Test fun movieNightQuit() {
        open(Dest.MOVIE_NIGHT)
        press("right", "ok")
        press("ok", "ok")
        expectShown("QUESTION 3 OF 10", "Part-way through the quiz")
        moveTo("Quit quiz", "down", max = 6, step = "Down to Quit quiz")
        shot("26b-movie-night-quit")
        press("ok")
        expectHidden("QUESTION", "Quit leaves the quiz")
        expectShown("CARRY ON WATCHING", "Quit goes Home")
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

        // Save it to My list.
        press("right")
        expectFocus("+ My list", "Next to Play, + My list")
        press("ok")
        expectShown("In My list", "The film is on My list")
        press("left")
        expectFocus("Play", "Back to Play")

        // Play the film and open its controls; subtitles offer OpenSubtitles.
        press("ok")
        press("ok")
        expectShown("Subtitles", "OK shows the film's controls")
        expectShown("30 s", "The film's controls skip 30 seconds")
        expectShown("5 min", "The film's controls skip 5 minutes")
        expectShown("Harbour of Glass", "The film's controls show its title")
        shot("13b-film-controls")
        press("right")
        moveTo("Subtitles", "right", step = "Along the film controls to Subtitles")
        press("ok")
        expectShown("Find on OpenSubtitles", "Films can search OpenSubtitles")
        press("down", "ok")
        expectShown("Add your free OpenSubtitles API key", "Without a key, it says how to add one")
        shot("13c-opensubtitles-no-key")
        press("back", "back", "back", "back")
        expectFocus("Play", "Back from the film returns to its page")
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
        // M3U episodes are gathered into one poster per show.
        expectHidden("S01 E01", "Episodes aren't listed as separate posters")
        shot("16-series-menu")
        press("right", "right")
        expectFocus("Cobalt Street", "Into the series posters")

        // An Xtream series: episodes fetched from the server.
        press("ok")
        expectFocus("Play S1 E1", "Opening a series lands on Play for its first episode")
        expectShown("Season 1", "The season picker shows the season")
        expectShown("Cold Case", "Episode titles from the server")
        expectShown("Cast:", "The page names the cast")
        shot("17-series-page")
        // The season picker opens a list of seasons.
        press("down", "down")
        expectFocus("Season 1", "Down to the season picker")
        press("ok")
        expectShown("Season 2", "The season list shows every season")
        shot("17a-series-seasons")
        moveTo("Season 2", "down", step = "Down the season list")
        press("ok")
        expectShown("Season 2 · 3 episodes", "Choosing a season shows its episodes")
        expectFocus("Season 2", "Choosing a season returns to the picker")
        // Down to the episode cards.
        press("down")
        expectShown("S2 E1", "Highlighting an episode describes it")
        shot("17b-series-episodes")
        press("back")
        expectFocus("Cobalt Street", "Back from a series returns to its poster")

        // A series whose server sends seasons as numbered objects.
        press("right", "ok")
        expectShown("Season 1", "Seasons sent as numbered objects still list")
        expectFocus("Play S1 E1", "Play for its first episode")
        press("back")

        // An M3U series: its separate episode entries gathered by season.
        press("right", "ok")
        expectShown("Season 1", "An M3U series lists its seasons")
        expectShown("Episode 4", "An M3U series lists its episodes")
        expectFocus("Play S1 E1", "Play for its first episode")
        shot("17d-m3u-series-page")

        // Playing an episode offers Skip intro while the intro runs.
        press("ok")
        expectShown("Skip intro", "An episode with a known intro offers Skip intro")
        shot("17c-skip-intro")
        press("ok")
        expectHidden("Skip intro", "OK skips the intro")
        // Back on the page, the button offers to resume it (skipping the intro moved it to 1:30).
        press("back")
        expectFocus("Resume S1 E1", "Back from an episode offers to resume it")
        expectShown("Start over", "A part-watched episode can be started over")
        shot("17e-series-resume")
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
        expectShown("Pause and rewind live TV", "Player pane")
        expectShown("OpenSubtitles API key", "Player pane has the subtitles key")
        expectShown("Catch-up timing", "Player pane has the catch-up timing")
        expectShown("No change", "Catch-up timing starts unchanged")
        expectShown("Backup player", "Player pane has the backup player setting")
        expectShown("Netflix style", "Player pane has the subtitle look")
        expectShown("Match frame rate", "Player pane has frame-rate matching")
        press("down", "ok")
        expectShown("Keep playlists up to date", "Refresh and check pane")
        expectShown("Check channels", "Refresh and check pane offers the channel check")
        shot("22c-settings-refresh-check")
        press("down", "ok")
        expectShown("Save a backup now", "Backup pane")
        shot("22d-settings-backup")
        shot("22a-settings-player")
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

    @Test fun christmas() {
        open(Dest.HOME, ThemeId.CHRISTMAS)
        shot("25c-theme-christmas")
        press("right")
        expectFocus("Resume", "The Christmas theme works like any other")
        shot("25d-theme-christmas-browsing")
    }
}
