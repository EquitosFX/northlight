package app.tvplayer.core.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdatesTest {

    private fun release(name: String = "Skyfall build 57", body: String = "", assets: String = apk()) =
        """{"tag_name":"latest","name":"$name","body":"$body","assets":[$assets]}"""

    private fun apk(name: String = "northlight.apk", size: Long = 1234, digest: String? = "sha256:" + "ab".repeat(32)) =
        """{"name":"$name","size":$size,"browser_download_url":"https://github.com/EquitosFX/northlight/releases/download/latest/$name"""" +
            (if (digest != null) ""","digest":"$digest"""" else "") + "}"

    @Test
    fun readsBuildNumberAddressSizeAndChecksum() {
        val b = ReleaseParser.parse(release())
        assertEquals(57, b.build)
        assertEquals("https://github.com/EquitosFX/northlight/releases/download/latest/northlight.apk", b.apkUrl)
        assertEquals(1234L, b.sizeBytes)
        assertEquals("ab".repeat(32), b.sha256)
    }

    @Test
    fun picksTheApkAmongOtherFilesAndAcceptsOlderTitles() {
        val b = ReleaseParser.parse(release(name = "Northlight build 12", assets = apk(name = "notes.txt") + "," + apk()))
        assertEquals(12, b.build)
        assertTrue(b.apkUrl.endsWith("/northlight.apk"))
    }

    @Test
    fun findsTheStartupProfileForEachAndroidVersion() {
        val b = ReleaseParser.parse(release(assets = apk() + "," + apk(name = "northlight-28.dm") + "," + apk(name = "northlight-31.dm")))
        assertEquals(2, b.profiles.size)
        assertTrue(b.profileFor(34)!!.endsWith("northlight-31.dm"))
        assertTrue(b.profileFor(30)!!.endsWith("northlight-28.dm"))
        assertNull(b.profileFor(26))
        assertNull(ReleaseParser.parse(release()).profileFor(34))
    }

    @Test
    fun fallsBackToTheNotesForTheBuildNumber() {
        assertEquals(88, ReleaseParser.parse(release(name = "Latest", body = "Automatic build 88.")).build)
    }

    @Test
    fun missingOrOddChecksumIsIgnored() {
        assertNull(ReleaseParser.parse(release(assets = apk(digest = null))).sha256)
        assertNull(ReleaseParser.parse(release(assets = apk(digest = "md5:1234"))).sha256)
    }

    @Test
    fun explainsWhatIsWrong() {
        fun message(text: String) = try {
            ReleaseParser.parse(text); fail("expected a failure"); ""
        } catch (e: UpdateException) {
            e.message.orEmpty()
        }
        assertTrue(message("<html>rate limited</html>").contains("couldn't read"))
        assertTrue(message(release(name = "Latest", body = "")).contains("which build"))
        assertTrue(message(release(assets = apk(name = "other.apk"))).contains("no northlight.apk"))
    }

    @Test
    fun onlyNewerBuildsCount() {
        val b = ReleaseParser.parse(release())
        assertTrue(ReleaseParser.isNewer(b, 54))
        assertFalse(ReleaseParser.isNewer(b, 57))
        assertFalse(ReleaseParser.isNewer(b, 60))
    }

    @Test
    fun verifiesSizeAndChecksum() {
        val f = File.createTempFile("apk", ".bin").apply { writeText("hello"); deleteOnExit() }
        val sha = FileCheck.sha256(f)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha)
        FileCheck.verify(f, AvailableBuild(1, "https://x", 5, sha))
        FileCheck.verify(f, AvailableBuild(1, "https://x", 0, null))
        try {
            FileCheck.verify(f, AvailableBuild(1, "https://x", 6, sha)); fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
        try {
            FileCheck.verify(f, AvailableBuild(1, "https://x", 5, "0".repeat(64))); fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("checksum"))
        }
    }
}
