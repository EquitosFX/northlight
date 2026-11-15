package app.tvplayer.core.movienight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MovieNightTest {
    private val answers = mapOf(
        "kind" to "film", "company" to "family", "mood" to "laugh", "flavour" to "animation", "tone" to "light",
        "length" to "short", "era" to "new", "rating" to "good", "popularity" to "popular", "language" to "english",
    )

    @Test
    fun hasTenQuestions() {
        assertEquals(10, MovieNight.questions.size)
    }

    @Test
    fun turnsAnswersIntoADiscoverSearch() {
        val c = MovieNight.criteria(answers, LocalDate.of(2026, 11, 15))
        assertTrue(c.films)
        assertFalse(c.series)
        val url = MovieNight.discoverUrl("movie", c, 1)
        assertTrue(url.startsWith("https://api.themoviedb.org/3/discover/movie?"))
        assertTrue(url.contains("with_genres=35"))
        assertTrue(url.contains("without_genres=27%2C53%2C10752"))
        assertTrue(url.contains("primary_release_date.gte=2023-01-01"))
        assertTrue(url.contains("with_runtime.lte=105"))
        assertTrue(url.contains("vote_average.gte=6.5"))
        assertTrue(url.contains("certification.lte=PG"))
        assertTrue(url.contains("with_original_language=en"))
        assertTrue(url.contains("sort_by=popularity.desc"))
    }

    @Test
    fun relaxesStepByStep() {
        val steps = MovieNight.relaxations(MovieNight.criteria(answers, LocalDate.of(2026, 11, 15)))
        assertEquals(105, steps[0].filmMaxRuntime)
        assertEquals(null, steps[1].filmMaxRuntime)
        assertEquals(null, steps[2].fromDate)
        assertEquals(null, steps.last().minRating)
        assertTrue(steps.last().filmGenres.isEmpty())
    }

    @Test
    fun readsDiscoverResults() {
        val text = """{"results":[{"id":5,"title":"Low Tide","release_date":"2019-05-01","vote_average":7.2,"vote_count":900,
            "popularity":40.5,"genre_ids":[35,18],"overview":"A tale.","poster_path":"/p.jpg"},{"id":6}]}"""
        val found = MovieNight.parseDiscover(text, "movie")
        assertEquals(1, found.size)
        assertEquals(Found(5, "movie", "Low Tide", 2019, 7.2, 900, 40.5, listOf(35, 18), "A tale.", "https://image.tmdb.org/t/p/w342/p.jpg"), found[0])
    }

    @Test
    fun saysHowPicksDiffer() {
        val picks = listOf(
            PickFacts("A", false, 2024, 7.9, 95, null, 10.0, listOf("Comedy")),
            PickFacts("B", false, 1998, 7.1, 140, null, 50.0, listOf("Comedy", "Romance")),
            PickFacts("C", true, 2015, 6.8, null, 3, 20.0, listOf("Comedy")),
        )
        val notes = MovieNight.differences(picks)
        assertTrue(notes[0].contains("Best reviewed of these (★ 7.9)"))
        assertTrue(notes[0].contains("The newest (2024)"))
        assertTrue(notes[1].contains("The most popular pick"))
        assertTrue(notes[1].any { it.startsWith("The only romance") || it.startsWith("The oldest") })
        assertTrue(notes[2].contains("3 seasons to get into"))
        notes.forEach { assertTrue(it.isNotEmpty() && it.size <= 3) }
    }
}
