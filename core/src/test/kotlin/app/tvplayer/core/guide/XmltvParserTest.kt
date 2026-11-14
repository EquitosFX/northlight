package app.tvplayer.core.guide

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="north.one">
            <display-name>North One</display-name>
            <display-name>North One HD</display-name>
            <icon src="http://l.example/n1.png"/>
          </channel>
          <channel id="comedy.yard">
            <display-name>Comedy Yard</display-name>
          </channel>
          <programme start="20261114093000 +0000" stop="20261114104500 +0000" channel="north.one">
            <title lang="en">The Morning Table</title>
            <title lang="cy">Bwrdd y Bore</title>
            <sub-title>Saturday edition</sub-title>
            <desc>Live from the quayside studio &amp; beyond.</desc>
            <category>Magazine</category>
            <category>Live</category>
            <icon src="http://l.example/morning.jpg"/>
            <episode-num system="xmltv_ns">7.85.0/1</episode-num>
          </programme>
          <programme start="20261114103000 +0100" stop="20261114110000 +0100" channel="comedy.yard">
            <title>Kitchen Table Quiz</title>
            <episode-num system="onscreen">S02E04</episode-num>
          </programme>
          <programme start="202611141200" channel="comedy.yard">
            <title>The Paper Round</title>
          </programme>
          <programme stop="20261114130000 +0000" channel="comedy.yard">
            <title>No start time</title>
          </programme>
        </tv>
    """.trimIndent()

    private class Result(val channels: List<GuideChannel>, val programmes: List<GuideProgramme>)

    private fun parse(bytes: ByteArray): Result {
        val channels = ArrayList<GuideChannel>()
        val programmes = ArrayList<GuideProgramme>()
        XmltvParser.parse(ByteArrayInputStream(bytes), { channels.add(it) }, { programmes.add(it) })
        return Result(channels, programmes)
    }

    private fun utc(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 11, 14, hour, minute).toEpochSecond(ZoneOffset.UTC) * 1000

    @Test
    fun readsChannelsWithAllTheirNames() {
        val result = parse(sample.toByteArray())
        assertEquals(2, result.channels.size)
        assertEquals("north.one", result.channels[0].id)
        assertEquals(listOf("North One", "North One HD"), result.channels[0].displayNames)
        assertEquals("http://l.example/n1.png", result.channels[0].icon)
        assertNull(result.channels[1].icon)
    }

    @Test
    fun readsProgrammeDetails() {
        val programme = parse(sample.toByteArray()).programmes[0]
        assertEquals("north.one", programme.channelId)
        assertEquals("The Morning Table", programme.title)
        assertEquals("Saturday edition", programme.subTitle)
        assertEquals("Live from the quayside studio & beyond.", programme.description)
        assertEquals(listOf("Magazine", "Live"), programme.categories)
        assertEquals("http://l.example/morning.jpg", programme.icon)
        assertEquals(utc(9, 30), programme.startMillis)
        assertEquals(utc(10, 45), programme.stopMillis)
    }

    @Test
    fun convertsTimeOffsetsToUtc() {
        val programme = parse(sample.toByteArray()).programmes[1]
        assertEquals(utc(9, 30), programme.startMillis)
        assertEquals(utc(10, 0), programme.stopMillis)
    }

    @Test
    fun assumesUtcAndNoLengthWhenTheFileLeavesThemOut() {
        val programme = parse(sample.toByteArray()).programmes[2]
        assertEquals(utc(12, 0), programme.startMillis)
        assertEquals(utc(12, 0), programme.stopMillis)
    }

    @Test
    fun readsSeasonAndEpisodeInBothCommonForms() {
        val programmes = parse(sample.toByteArray()).programmes
        assertEquals(8, programmes[0].season)
        assertEquals(86, programmes[0].episode)
        assertEquals(2, programmes[1].season)
        assertEquals(4, programmes[1].episode)
        assertNull(programmes[2].season)
    }

    @Test
    fun dropsProgrammesThatHaveNoStartTime() {
        val titles = parse(sample.toByteArray()).programmes.map { it.title }
        assertEquals(listOf("The Morning Table", "Kitchen Table Quiz", "The Paper Round"), titles)
    }

    @Test
    fun unpacksGzippedGuides() {
        val packed = ByteArrayOutputStream()
        GZIPOutputStream(packed).use { it.write(sample.toByteArray()) }
        val result = parse(packed.toByteArray())
        assertEquals(2, result.channels.size)
        assertEquals(3, result.programmes.size)
    }

    @Test
    fun rejectsTimesItCannotRead() {
        assertNull(XmltvParser.parseTime(null))
        assertNull(XmltvParser.parseTime("soon"))
        assertNull(XmltvParser.parseTime("20261340250000 +0000"))
    }
}
