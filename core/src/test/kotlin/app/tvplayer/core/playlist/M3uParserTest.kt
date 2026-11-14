package app.tvplayer.core.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private class Result(val header: PlaylistHeader?, val entries: List<PlaylistEntry>)

    private fun parse(text: String): Result {
        var header: PlaylistHeader? = null
        val entries = ArrayList<PlaylistEntry>()
        M3uParser.parse(text.lineSequence(), { header = it }) { entries.add(it) }
        return Result(header, entries)
    }

    @Test
    fun headerListsEveryGuideAddress() {
        val result = parse("""#EXTM3U url-tvg="http://a.example/guide.xml, http://b.example/guide.xml.gz"""")
        assertEquals(
            listOf("http://a.example/guide.xml", "http://b.example/guide.xml.gz"),
            result.header?.guideUrls,
        )
    }

    @Test
    fun readsAttributesIncludingCommasInsideQuotes() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="north.one" tvg-name="North One, HD" tvg-logo="http://l.example/n1.png" group-title="News, Local" tvg-chno="101",North One HD
            http://host.example/live/user/pass/101.ts
            """.trimIndent(),
        )
        val entry = result.entries.single()
        assertEquals("North One HD", entry.name)
        assertEquals("north.one", entry.tvgId)
        assertEquals("North One, HD", entry.tvgName)
        assertEquals("http://l.example/n1.png", entry.logo)
        assertEquals("News, Local", entry.group)
        assertEquals(101, entry.channelNumber)
        assertEquals("http://host.example/live/user/pass/101.ts", entry.url)
        assertEquals(EntryKind.LIVE, entry.kind)
    }

    @Test
    fun usesExtGrpAndVlcOptionsWhenAttributesAreMissing() {
        val result = parse(
            """
            #EXTINF:-1,Metro News
            #EXTGRP:News
            #EXTVLCOPT:http-user-agent=TestAgent/1.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://host.example/metro.m3u8
            """.trimIndent(),
        )
        val entry = result.entries.single()
        assertEquals("Metro News", entry.name)
        assertEquals("News", entry.group)
        assertEquals("TestAgent/1.0", entry.userAgent)
        assertEquals("http://ref.example/", entry.referrer)
    }

    @Test
    fun groupTitleWinsOverExtGrp() {
        val result = parse(
            """
            #EXTINF:-1 group-title="Sport",Arena Sport 1
            #EXTGRP:Other
            http://host.example/a1.ts
            """.trimIndent(),
        )
        assertEquals("Sport", result.entries.single().group)
    }

    @Test
    fun tellsFilmsSeriesAndLiveApart() {
        val result = parse(
            """
            #EXTINF:-1,The Long Ferry
            http://host.example/movie/user/pass/55.mkv
            #EXTINF:-1,The Paper Round S01 E01
            http://host.example/series/user/pass/900.mp4
            #EXTINF:-1,Low Tide
            http://host.example/films/low-tide.mp4?token=abc
            #EXTINF:-1,North One
            http://host.example/live/user/pass/101.m3u8
            """.trimIndent(),
        )
        assertEquals(
            listOf(EntryKind.MOVIE, EntryKind.SERIES, EntryKind.MOVIE, EntryKind.LIVE),
            result.entries.map { it.kind },
        )
    }

    @Test
    fun readsRequestHeadersAppendedToTheAddress() {
        val result = parse(
            """
            #EXTINF:-1,Cinema Box
            http://host.example/cb.m3u8|User-Agent=PipeAgent&Referer=http://pipe.example/
            """.trimIndent(),
        )
        val entry = result.entries.single()
        assertEquals("http://host.example/cb.m3u8", entry.url)
        assertEquals("PipeAgent", entry.userAgent)
        assertEquals("http://pipe.example/", entry.referrer)
    }

    @Test
    fun readsCatchUpDetails() {
        val result = parse(
            """
            #EXTINF:-1 catchup="shift" catchup-days="7" catchup-source="http://host.example/archive",North Two
            http://host.example/n2.ts
            #EXTINF:-1 timeshift="3",Comedy Yard
            http://host.example/cy.ts
            """.trimIndent(),
        )
        assertEquals("shift", result.entries[0].catchupType)
        assertEquals(7, result.entries[0].catchupDays)
        assertEquals("http://host.example/archive", result.entries[0].catchupSource)
        assertEquals(3, result.entries[1].catchupDays)
        assertNull(result.entries[1].catchupType)
    }

    @Test
    fun copesWithByteOrderMarkWindowsLineEndingsAndUnknownLines() {
        val text = "﻿#EXTM3U x-tvg-url=\"http://a.example/g.xml\"\r\n\r\n" +
            "#KODIPROP:something=1\r\n" +
            "#EXTINF:-1,Tiny Planet\r\n" +
            "http://host.example/tp.ts\r\n"
        val result = parse(text)
        assertEquals(listOf("http://a.example/g.xml"), result.header?.guideUrls)
        assertEquals("Tiny Planet", result.entries.single().name)
        assertEquals("http://host.example/tp.ts", result.entries.single().url)
    }

    @Test
    fun acceptsAnAddressWithNoInfoLine() {
        val result = parse("http://host.example/streams/wildhours.m3u8")
        val entry = result.entries.single()
        assertEquals("wildhours.m3u8", entry.name)
        assertEquals(EntryKind.LIVE, entry.kind)
    }

    @Test
    fun survivesABrokenQuote() {
        val result = parse(
            """
            #EXTINF:-1 tvg-id="broken,Still Listed
            http://host.example/broken.ts
            #EXTINF:-1,Next One
            http://host.example/next.ts
            """.trimIndent(),
        )
        assertEquals(2, result.entries.size)
        assertEquals("Next One", result.entries[1].name)
    }

    @Test
    fun returnsTheNumberOfEntries() {
        val count = M3uParser.parse(
            "#EXTINF:-1,A\nhttp://h.example/a\n#EXTINF:-1,B\nhttp://h.example/b\n".reader(),
        ) { }
        assertEquals(2, count)
    }
}
