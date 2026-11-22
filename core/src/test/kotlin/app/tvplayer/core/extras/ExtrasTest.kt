package app.tvplayer.core.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ExtrasTest {
    @Test fun omdbRatings() {
        val text = """{"Title":"The Shawshank Redemption","imdbRating":"9.3","Metascore":"82",
            "Ratings":[{"Source":"Internet Movie Database","Value":"9.3/10"},{"Source":"Rotten Tomatoes","Value":"89%"},{"Source":"Metacritic","Value":"82/100"}],
            "Response":"True"}"""
        val ratings = Omdb.parse(text)!!
        assertEquals("IMDb 9.3 · RT 89% · MC 82", ratings.line)
        assertEquals(ratings, Ratings.decode(ratings.encode()))
        assertNull(Omdb.parse("""{"Response":"False","Error":"Invalid API key!"}"""))
        assertEquals("Invalid API key!", Omdb.error("""{"Response":"False","Error":"Invalid API key!"}"""))
        assertEquals("IMDb 7.1", Omdb.parse("""{"imdbRating":"7.1","Ratings":[],"Metascore":"N/A","Response":"True"}""")!!.line)
        assertEquals("tt0111161", Omdb.imdbIdFromTmdb("""{"id":278,"imdb_id":"tt0111161","tvdb_id":null}"""))
    }

    @Test fun tvmazeNextEpisode() {
        val text = """{"id":82,"name":"Game of Thrones","_embedded":{"nextepisode":{"id":1,"name":"Winter","season":3,"number":2,"airstamp":"2026-11-22T21:00:00+00:00"}}}"""
        val next = TvMaze.nextEpisode(text)!!
        assertEquals("S3 E2", next.code)
        assertEquals(Instant.parse("2026-11-22T21:00:00Z").toEpochMilli(), next.airsAt)
        assertEquals(82L, TvMaze.showId(text))
        assertNull(TvMaze.nextEpisode("""{"id":82,"name":"Ended show"}"""))
    }

    @Test fun channelLogosByIdAndName() {
        val channels = """[
            {"id":"BBCOne.uk","name":"BBC One","alt_names":["BBC 1"],"country":"UK","closed":null},
            {"id":"Channel4.uk","name":"Channel 4","country":"UK"},
            {"id":"Channel4.xx","name":"Channel 4","country":"XX"},
            {"id":"Dave.uk","name":"Dave","country":"UK"}]"""
        val logos = """[
            {"channel":"BBCOne.uk","feed":null,"url":"https://logo/bbcone.png"},
            {"channel":"Channel4.uk","feed":null,"url":"https://logo/c4uk.png"},
            {"channel":"Channel4.xx","feed":null,"url":"https://logo/c4xx.png"},
            {"channel":"Dave.uk","feed":null,"url":"https://logo/dave.png"}]"""
        val library = ChannelLogos.decode(ChannelLogos.build(channels, logos).encode())
        assertEquals("https://logo/bbcone.png", library.find("Anything", "BBCOne.uk"))
        assertEquals("https://logo/bbcone.png", library.find("UK: BBC One FHD", null))
        assertEquals("https://logo/bbcone.png", library.find("|UK| BBC 1 HD", null))
        assertEquals("https://logo/c4uk.png", library.find("Channel 4 (1080p)", null))
        assertEquals("https://logo/dave.png", library.find("Dave +1", null))
        assertNull(library.find("Unknown Channel", null))
    }

    @Test fun channelLogosPreferInUsePictures() {
        val channels = """[{"id":"Sky.uk","name":"Sky \u0026 More","alt_names":[],"country":"UK","closed":null,"broadcast_area":["c/UK"],"extra":{"a":[1,{"b":"]"}]}}]"""
        val logos = """[
            {"channel":"Sky.uk","feed":null,"in_use":false,"tags":[],"width":512,"height":512,"format":"PNG","url":"https://logo/old.png"},
            {"channel":"Sky.uk","feed":null,"in_use":true,"tags":["horizontal"],"width":1000,"height":400,"format":"SVG","url":"https://logo/new.svg"},
            {"channel":"Sky.uk","feed":null,"in_use":true,"tags":[],"width":512,"height":512,"format":"PNG","url":"https://logo/new.png"}]"""
        val library = ChannelLogos.build(channels, logos)
        assertEquals("https://logo/new.png", library.find("Sky & More HD", null))
    }

    @Test fun scanSkipsWhatIsntWanted() {
        val seen = ArrayList<Map<String, Any?>>()
        JsonScan(""" [ {"a":"x\"y","n":{"deep":[1,2,{"q":"}"}]},"b":[ "1" , "2" ],"c":12.5,"d":null} , {} ] """.reader())
            .objects(setOf("a", "b", "c", "d")) { seen += it }
        assertEquals(2, seen.size)
        assertEquals("x\"y", seen[0]["a"])
        assertEquals(listOf("1", "2"), seen[0]["b"])
        assertEquals("12.5", seen[0]["c"])
        assertEquals(null, seen[0]["d"])
    }
}
