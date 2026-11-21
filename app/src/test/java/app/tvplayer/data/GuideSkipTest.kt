package app.tvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tvplayer.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A TV guide that hasn't changed since it was last stored is recognised and left as it is (no
 * rewriting every programme); one that has changed is stored again. The guide is read from the file
 * it was downloaded to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuideSkipTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Volatile private var guideTitle = "Morning News"
    private var guideRequests = 0

    private fun xmltv(): String {
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val start = System.currentTimeMillis() / 3_600_000 * 3_600_000
        val programmes = (0 until 6).joinToString("\n") { i ->
            """<programme start="${format.format(Date(start + i * 3_600_000L))}" stop="${format.format(Date(start + (i + 1) * 3_600_000L))}" channel="one"><title>$guideTitle $i</title></programme>"""
        }
        return """<?xml version="1.0"?><tv><channel id="one"><display-name>One</display-name></channel>$programmes</tv>"""
    }

    private val server = Interceptor { chain ->
        val path = chain.request().url.encodedPath
        val body = when {
            path.endsWith("list.m3u") -> "#EXTM3U url-tvg=\"http://guide.invalid/guide.xml\"\n#EXTINF:-1 tvg-id=\"one\",One\nhttp://stream.invalid/one.ts\n"
            path.endsWith("guide.xml") -> { guideRequests++; xmltv() }
            else -> ""
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("text/plain".toMediaType())).build()
    }

    private fun waitIdle(repo: PlaylistRepository) {
        Thread.sleep(300)
        repeat(200) {
            if (repo.importState.value !is ImportState.Working) return
            Thread.sleep(50)
        }
    }

    @Test fun unchangedGuideIsLeftAsItIs() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dir = File(context.cacheDir, "guides-test").apply { deleteRecursively() }
        val http = OkHttpClient.Builder().addInterceptor(server).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = PlaylistRepository(db, http, scope, guideDir = dir)
        repo.addM3u("Test", "http://list.invalid/list.m3u")
        waitIdle(repo)
        val first = db.programmes().stampBlocking()
        assertTrue("The guide was stored", first > 0)
        assertEquals(1, guideRequests)

        // Due again (as twelve hours on), the same guide: left as it is.
        fun makeDue() {
            val p = db.playlists().getAllBlocking().single()
            db.playlists().updateBlocking(p.copy(guideRefreshedAt = 0L))
        }
        makeDue()
        repo.refreshStaleGuides()
        waitIdle(repo)
        assertEquals("The guide was downloaded again", 2, guideRequests)
        assertEquals("An unchanged guide isn't stored again", first, db.programmes().stampBlocking())

        // A changed guide, due again (a fresh start, so the check made a moment ago is forgotten): stored again.
        guideTitle = "Evening News"
        File(dir, "guides.properties").let { file ->
            val kept = file.readLines().filterNot { it.startsWith("checked.") }
            file.writeText(kept.joinToString("\n"))
        }
        val again = PlaylistRepository(db, http, scope, guideDir = dir)
        makeDue()
        again.refreshStaleGuides()
        waitIdle(again)
        assertEquals(3, guideRequests)
        assertNotEquals("A changed guide is stored again", first, db.programmes().stampBlocking())
        assertTrue("No downloaded guides are left behind", dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        println("  guide stored first ${first}, unchanged skip kept it, changed guide stored as ${db.programmes().stampBlocking()}")
    }
}
