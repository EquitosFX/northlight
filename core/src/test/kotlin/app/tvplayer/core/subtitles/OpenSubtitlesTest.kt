package app.tvplayer.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesTest {

    @Test
    fun searchesFilmsByTmdbIdAndEpisodesByTheirSeries() {
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?languages=en&tmdb_id=27205",
            OpenSubtitles.searchUrl(SubtitleQuery(tmdbId = 27205), "en"),
        )
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?episode_number=3&languages=pt-pt&parent_tmdb_id=1399&season_number=1",
            OpenSubtitles.searchUrl(SubtitleQuery(tmdbId = 5, seriesTmdbId = 1399, season = 1, episode = 3), "pt-PT"),
        )
    }

    @Test
    fun fallsBackToTheTitle() {
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?languages=en&query=the%20long%20thaw&year=2021",
            OpenSubtitles.searchUrl(SubtitleQuery(title = "The Long Thaw", year = 2021), "en"),
        )
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?episode_number=3&languages=en&query=cobalt%20street&season_number=1",
            OpenSubtitles.searchUrl(SubtitleQuery(title = "Cobalt Street", season = 1, episode = 3), "en"),
        )
    }

    @Test
    fun readsResultsMostDownloadedFirstWithHearingImpairedLast() {
        val reply = """{"total_count":3,"data":[
            {"id":"1","attributes":{"language":"en","release":"Film.2021.WEB","download_count":50,"hearing_impaired":false,"files":[{"file_id":11,"file_name":"a.srt"}]}},
            {"id":"2","attributes":{"language":"en","release":"","download_count":900,"hearing_impaired":true,"files":[{"file_id":22,"file_name":"b.srt"}]}},
            {"id":"3","attributes":{"language":"en","release":"Film.2021.BluRay","download_count":300,"hearing_impaired":false,"files":[{"file_id":33,"file_name":"c.srt"}]}},
            {"id":"4","attributes":{"language":"en","files":[]}}]}"""
        val results = OpenSubtitles.parseSearch(reply)
        assertEquals(listOf(33L, 11L, 22L), results.map { it.fileId })
        assertEquals("b.srt", results.last().release)
    }

    @Test
    fun readsTokensLinksAndExplainsErrors() {
        assertEquals("abc", OpenSubtitles.parseToken("""{"user":{},"token":"abc","status":200}"""))
        assertEquals("https://dl.opensubtitles.org/x.srt", OpenSubtitles.parseDownloadLink("""{"link":"https://dl.opensubtitles.org/x.srt","remaining":4}"""))
        try {
            OpenSubtitles.parseDownloadLink("""{"message":"You have downloaded your allowed 5 subtitles"}""")
            throw AssertionError("expected failure")
        } catch (e: SubtitlesException) {
            assertTrue(e.message!!.contains("allowed 5"))
        }
        assertTrue(OpenSubtitles.explain(429).contains("limit"))
        assertEquals("""{"file_id":42}""", OpenSubtitles.downloadBody(42))
    }

    @Test
    fun findsSeasonAndEpisodeInTitles() {
        assertEquals(1 to 3, OpenSubtitles.seasonEpisode("Cobalt Street · S1 E3"))
        assertEquals(2 to 10, OpenSubtitles.seasonEpisode("Show S02E10 Title"))
        assertEquals(null, OpenSubtitles.seasonEpisode("Just a film"))
    }
}
