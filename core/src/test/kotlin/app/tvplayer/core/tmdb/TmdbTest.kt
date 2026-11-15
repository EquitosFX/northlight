package app.tvplayer.core.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbTest {

    @Test
    fun cleansTypicalIptvNames() {
        assertEquals("The Long Ferry" to 2021, TitleCleaner.clean("EN - The Long Ferry (2021) [4K]"))
        assertEquals("Low Tide" to 2019, TitleCleaner.clean("UK: Low Tide (2019)"))
        assertEquals("Signal Fires" to null, TitleCleaner.clean("|NF| Signal Fires 4K"))
        assertEquals("Storm Glass" to null, TitleCleaner.clean("4K-EN - Storm Glass HEVC"))
        assertEquals("Salt and Signal" to 2020, TitleCleaner.clean("[EN] Salt and Signal [2020] [MULTI-SUB]"))
    }

    @Test
    fun leavesNumbersAndHyphensThatBelongToTheTitle() {
        assertEquals("Blade Runner 2049" to 2017, TitleCleaner.clean("Blade Runner 2049 (2017)"))
        assertEquals("WALL-E" to null, TitleCleaner.clean("WALL-E"))
        assertEquals("M3GAN 2.0" to 2025, TitleCleaner.clean("M3GAN 2.0 - 2025"))
        assertEquals("Se7en" to null, TitleCleaner.clean("Se7en"))
    }

    @Test
    fun buildsAddressesAndHandlesBothKindsOfKey() {
        assertEquals(
            "https://api.themoviedb.org/3/search/movie?query=Low+Tide&year=2019&language=en-GB&include_adult=false",
            TmdbUrls.search("movie", "Low Tide", 2019),
        )
        assertTrue(TmdbUrls.search("tv", "X", 2020).contains("first_air_date_year=2020"))
        assertTrue(TmdbUrls.usesBearer("eyJhbGciOiJIUzI1NiJ9.abc"))
        assertEquals("https://x/y?a=1&api_key=abc123", TmdbUrls.withKey("https://x/y?a=1", " abc123 "))
        assertEquals("https://x/y?a=1", TmdbUrls.withKey("https://x/y?a=1", "eyJtoken"))
        assertNull(TmdbUrls.image(null, "w342"))
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", TmdbUrls.image("/a.jpg", "w342"))
    }

    @Test
    fun readsSearchResultsForFilmsAndShows() {
        val films = TmdbParser.parseSearch(
            """{"results":[{"id":11,"title":"Low Tide","release_date":"2019-10-04","popularity":5.1},
                           {"id":12,"title":"Low Tide","release_date":"","popularity":9.0},{"title":"no id"}]}""",
        )
        assertEquals(listOf(11L, 12L), films.map { it.id })
        assertEquals(2019, films[0].year)
        assertNull(films[1].year)
        val shows = TmdbParser.parseSearch("""{"results":[{"id":5,"name":"The Paper Round","first_air_date":"2022-01-01"}]}""")
        assertEquals("The Paper Round", shows.single().title)
    }

    @Test
    fun picksTheExactTitleAndYear() {
        val results = listOf(
            TmdbResult(1, "Low Tide Rising", 2019, 50.0),
            TmdbResult(2, "Low Tide", 2001, 10.0),
            TmdbResult(3, "Low Tide", 2019, 1.0),
        )
        assertEquals(3L, TmdbParser.pickBest(results, "Low Tide", 2019)?.id)
        assertEquals(2L, TmdbParser.pickBest(results, "Low Tide", null)?.id)
        assertEquals(1L, TmdbParser.pickBest(results, "Something Else", null)?.id)
        assertNull(TmdbParser.pickBest(emptyList(), "x", null))
    }

    @Test
    fun readsFilmAndShowDetails() {
        val film = TmdbParser.parseDetails(
            """
            {"id":11,"title":"Low Tide","release_date":"2019-10-04","overview":"A summer on the shore.",
             "poster_path":"/p.jpg","backdrop_path":"/b.jpg","vote_average":6.8,"runtime":86,
             "genres":[{"id":1,"name":"Drama"},{"id":2,"name":"Thriller"}],
             "credits":{"cast":[{"name":"A"},{"name":"B"},{"name":"C"},{"name":"D"},{"name":"E"},{"name":"F"},{"name":"G"}]}}
            """.trimIndent(),
        )
        assertEquals(86, film.runtimeMinutes)
        assertEquals(listOf("Drama", "Thriller"), film.genres)
        assertEquals(6, film.cast.size)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", film.backdrop)
        assertEquals(6.8, film.rating)

        val show = TmdbParser.parseDetails("""{"id":5,"name":"The Paper Round","episode_run_time":[45],"number_of_seasons":3,"vote_average":0}""")
        assertEquals(45, show.runtimeMinutes)
        assertEquals(3, show.seasons)
        assertNull(show.rating)
    }
}
