package app.tvplayer.core.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamTest {

    private val account = XtreamAccount("http://host.example:8080", "user", "pass")

    @Test
    fun tidiesTheServerAddress() {
        assertEquals("http://host.example:8080", XtreamAccount("host.example:8080/", "u", "p").baseUrl)
        assertEquals("https://host.example", XtreamAccount(" https://host.example/ ", "u", "p").baseUrl)
        assertEquals(
            "http://host.example:8080",
            XtreamAccount("http://host.example:8080/player_api.php?username=u&password=p", "u", "p").baseUrl,
        )
        assertEquals("http://host.example", XtreamAccount("HTTP://host.example/get.php", "u", "p").baseUrl.lowercase())
    }

    @Test
    fun buildsAddresses() {
        assertEquals("http://host.example:8080/player_api.php?username=user&password=pass", XtreamUrls.api(account))
        assertEquals(
            "http://host.example:8080/player_api.php?username=user&password=pass&action=get_live_streams",
            XtreamUrls.api(account, "get_live_streams"),
        )
        assertEquals("http://host.example:8080/live/user/pass/101.ts", XtreamUrls.live(account, 101))
        assertEquals("http://host.example:8080/movie/user/pass/55.mkv", XtreamUrls.movie(account, 55, "mkv"))
        assertEquals("http://host.example:8080/movie/user/pass/56.mp4", XtreamUrls.movie(account, 56, null))
        assertEquals("http://host.example:8080/xmltv.php?username=user&password=pass", XtreamUrls.guide(account))
    }

    @Test
    fun escapesAwkwardCharactersInTheLoginAddress() {
        val awkward = XtreamAccount("host.example", "a b", "p&q=1")
        assertEquals("http://host.example/player_api.php?username=a+b&password=p%26q%3D1", XtreamUrls.api(awkward))
    }

    @Test
    fun readsASuccessfulLogin() {
        val auth = XtreamParser.parseAuth(
            """
            {"user_info":{"username":"user","auth":1,"status":"Active","exp_date":"1798761600",
              "is_trial":"0","active_cons":"1","max_connections":"2","message":"Welcome"},
             "server_info":{"url":"host.example","port":"8080","timezone":"Europe/London"}}
            """.trimIndent(),
        )
        assertTrue(auth.authorised)
        assertEquals("Active", auth.status)
        assertEquals(1798761600L, auth.expiresAtSeconds)
        assertEquals(2, auth.maxConnections)
        assertEquals(1, auth.activeConnections)
        assertFalse(auth.isTrial)
        assertEquals("Europe/London", auth.serverTimezone)
    }

    @Test
    fun readsARejectedLoginAndAnAccountWithNoExpiry() {
        assertFalse(XtreamParser.parseAuth("""{"user_info":{"auth":0}}""").authorised)
        val unlimited = XtreamParser.parseAuth("""{"user_info":{"auth":"1","exp_date":null}}""")
        assertTrue(unlimited.authorised)
        assertNull(unlimited.expiresAtSeconds)
    }

    @Test
    fun refusesAnAnswerThatIsNotXtream() {
        for (answer in listOf("<html>Not found</html>", "[]", """{"hello":"world"}""", "")) {
            val failed = try {
                XtreamParser.parseAuth(answer)
                false
            } catch (e: XtreamException) {
                true
            }
            assertTrue(failed)
        }
    }

    @Test
    fun readsCategories() {
        val found = ArrayList<XtreamCategory>()
        val count = XtreamParser.parseCategories(
            """[{"category_id":"12","category_name":"News","parent_id":0},{"category_id":7,"category_name":"Sport"},{"nope":1}]"""
                .byteInputStream(),
        ) { found.add(it) }
        assertEquals(2, count)
        assertEquals(listOf(XtreamCategory("12", "News"), XtreamCategory("7", "Sport")), found)
    }

    @Test
    fun readsLiveStreamsWhateverTypeTheNumbersArriveAs() {
        val found = ArrayList<XtreamStream>()
        val count = XtreamParser.parseStreams(
            """
            [
              {"num":1,"name":"North One HD","stream_type":"live","stream_id":101,"stream_icon":"http://l.example/n1.png",
               "epg_channel_id":"north.one","category_id":"12","tv_archive":1,"tv_archive_duration":7},
              {"num":"2","name":"Metro News","stream_id":"103","stream_icon":"","epg_channel_id":null,
               "category_id":12,"tv_archive":0,"tv_archive_duration":"3"},
              {"name":"No id, so skipped"}
            ]
            """.trimIndent().byteInputStream(),
        ) { found.add(it) }

        assertEquals(2, count)
        assertEquals(
            XtreamStream(
                streamId = 101,
                name = "North One HD",
                icon = "http://l.example/n1.png",
                categoryId = "12",
                number = 1,
                guideChannelId = "north.one",
                catchupDays = 7,
            ),
            found[0],
        )
        assertEquals(
            XtreamStream(streamId = 103, name = "Metro News", categoryId = "12", number = 2),
            found[1],
        )
    }

    @Test
    fun readsFilmsAndSeries() {
        val films = ArrayList<XtreamStream>()
        XtreamParser.parseStreams(
            """[{"num":1,"name":"The Long Ferry","stream_id":55,"container_extension":"mkv","category_id":"3"}]"""
                .byteInputStream(),
        ) { films.add(it) }
        assertEquals("mkv", films.single().containerExtension)

        val series = ArrayList<XtreamSeries>()
        val count = XtreamParser.parseSeries(
            """[{"series_id":900,"name":"The Paper Round","cover":"http://l.example/pr.jpg","category_id":"9"}]"""
                .byteInputStream(),
        ) { series.add(it) }
        assertEquals(1, count)
        assertEquals(XtreamSeries(900, "The Paper Round", "http://l.example/pr.jpg", "9"), series.single())
    }

    @Test
    fun treatsAnErrorObjectOrEmptyListAsNoItems() {
        assertEquals(0, XtreamParser.parseStreams("[]".byteInputStream()) { })
        assertEquals(0, XtreamParser.parseStreams("""{"user_info":{"auth":0}}""".byteInputStream()) { })
    }

    @Test
    fun reportsAListThatIsNotJson() {
        val failed = try {
            XtreamParser.parseStreams("<html>Bad gateway</html>".byteInputStream()) { }
            false
        } catch (e: XtreamException) {
            true
        }
        assertTrue(failed)
    }
}
