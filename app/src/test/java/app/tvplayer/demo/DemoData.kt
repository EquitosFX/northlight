package app.tvplayer.demo

import okhttp3.ResponseBody.Companion.asResponseBody
import okio.source
import okio.buffer
import app.tvplayer.AppContainer
import app.tvplayer.core.tmdb.TitleCleaner
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.FavouriteEntity
import app.tvplayer.data.db.HistoryEntity
import app.tvplayer.data.db.MetadataEntity
import app.tvplayer.data.db.PlaylistEntity
import app.tvplayer.data.db.ProgrammeEntity
import app.tvplayer.data.db.ProgressEntity
import kotlin.random.Random
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Made-up content for the screenshot test: invented channels, films, series and guide listings.
 * Nothing here is a real channel or title.
 */
object DemoData {

    private const val MINUTE = 60_000L
    private const val HALF_HOUR = 30 * MINUTE

    private val channels = listOf(
        "Meridian One" to "Entertainment", "Meridian Two" to "Entertainment", "Atlas News" to "News",
        "Atlas Sport 1" to "Sport", "Atlas Sport 2" to "Sport", "Harbour Movies" to "Movies",
        "Kestrel Kids" to "Kids", "Vista Docs" to "Documentaries", "Pulse Music" to "Music",
        "Orbit Comedy" to "Entertainment", "Tide Travel" to "Documentaries", "Summit Drama" to "Entertainment",
    )

    private val programmes = mapOf(
        "Entertainment" to listOf("The Saturday Kitchen Table", "Village Green", "Quiz at Nine", "Home Truths", "The Late Lounge", "Garden Rescue Live"),
        "News" to listOf("News at One", "World Desk", "Business Today", "The Briefing", "Evening Headlines"),
        "Sport" to listOf("Live Premier League: Harbour City v Kestrel United", "Six Nations Rugby: Wales v Scotland", "Cricket: Second Test", "Live Boxing: Fight Night", "Golf: Final Round"),
        "Movies" to listOf("Northbound", "Copper Sky", "Saltmarsh", "Paper Moons"),
        "Kids" to listOf("Puddle Patrol", "Robo Rangers", "The Treehouse Club", "Fox and Friends"),
        "Documentaries" to listOf("Wild Coastlines", "Engineering Giants", "Rail Journeys North", "The Deep Blue"),
        "Music" to listOf("Top 40 Countdown", "Unplugged Sessions", "Classic Anthems", "Festival Live"),
    )

    private val films = listOf(
        Film("The Lantern Coast", 2025, "New releases", "Thriller", "A lighthouse keeper on a remote island finds a signal that shouldn't exist, and a stranger who arrives with the storm."),
        Film("Harbour of Glass", 2024, "New releases", "Drama", "Two estranged sisters return to their father's failing glassworks and have one summer to save it."),
        Film("Northbound", 2025, "New releases", "Adventure", "A night train across the border, a stolen manuscript, and a conductor who knows more than he lets on."),
        Film("The Quiet Orchard", 2023, "New releases", "Drama", "A widowed farmer and a runaway teenager form an unlikely partnership during the apple harvest."),
        Film("Ember Valley", 2024, "New releases", "Thriller", "When wildfire cuts off a mountain town, a rookie ranger must lead its people out through the valley."),
        Film("Signal Lost", 2025, "New releases", "Science fiction", "A deep-space salvage crew answers a distress call from a ship that vanished forty years ago."),
        Film("Paper Moons", 2022, "Drama", "Romance", "A struggling illustrator and a night-shift baker trade notes in a shared flat they never see each other in."),
        Film("Kingfisher", 2023, "Drama", "Drama", "A champion rower's comeback is threatened by the secret she has kept since her last race."),
        Film("The Long Thaw", 2021, "Drama", "Drama", "Snowed in for winter, three generations of one family finally say what they mean."),
        Film("Saltmarsh", 2024, "Thriller", "Mystery", "A detective returns to the estuary town she left as a child to find a missing fisherman."),
        Film("Midnight Ferry", 2023, "Thriller", "Thriller", "Eight passengers, one crossing, and a body discovered before the ferry reaches port."),
        Film("Copper Sky", 2022, "Thriller", "Western", "A retired marshal is pulled back for one last job when a mining town falls to outlaws."),
    )

    private val series = listOf(
        Film("Cobalt Street", 2024, "Box sets", "Crime", "A city precinct's night shift works the cases nobody else wants. Three series."),
        Film("The Archivist", 2025, "Box sets", "Mystery", "A museum archivist uncovers a forgery ring hidden in the collection's oldest records."),
        Film("Hollow Fen", 2023, "Box sets", "Drama", "Life, love and feuds in a fenland village where everyone knows everyone's business."),
        Film("Brass and Ivy", 2022, "Box sets", "Period drama", "A Victorian music hall fights to survive as the age of variety comes to an end."),
        Film("Lowland", 2024, "Box sets", "Thriller", "A border town, a disappeared journalist, and a police chief with too many friends."),
        Film("Tidewater", 2025, "Box sets", "Drama", "A shipbuilding family's empire is tested when the yard's biggest order goes wrong."),
    )

    private val people = listOf(
        "Anna Reyes", "Tom Hallam", "Priya Desai", "Marcus Bell", "Elena Novak", "Owen Price", "Grace Okafor", "Lewis Carter",
    )

    private fun idOf(name: String) = people.indexOf(name) + 1L

    /** The director and cast of a title, by its seed (films 0–11, series 20–25). */
    private fun crew(seed: Int): Pair<String, List<String>> {
        val director = people[seed % people.size]
        return director to (1..5).map { people[(seed + it * 3) % people.size] }.distinct()
    }

    /**
     * Answers the app's web requests offline: TMDB people searches and filmographies come from the
     * demo titles above, and every stream address fails at once, as an unreachable server would.
     */
    fun responder() = Interceptor { chain ->
        val url = chain.request().url
        fun reply(code: Int, body: String) = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Not found")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        val path = url.encodedPath
        when {
            // Episodes of the two Xtream-style series: one sent as lists per season, one as numbered objects.
            url.host == "demo.invalid" && path.endsWith("/player_api.php") && url.queryParameter("action") == "get_series_info" -> {
                val id = url.queryParameter("series_id")?.toLongOrNull() ?: 0
                val titles = listOf("The Night Shift", "Cold Case", "The Informant", "Gridlock", "Blue Light", "Overtime")
                fun episode(season: Int, n: Int) =
                    """{"id":"${id * 100 + season * 10 + n}","episode_num":"$n","title":"${titles[(n - 1 + season) % titles.size]}","container_extension":"mkv",""" +
                        """"season":$season,"info":{"plot":"A demo episode.","duration_secs":"${2700 + n * 60}"}}"""
                val seasons = (1..2).joinToString(",") { season ->
                    if (id == 5000L) {
                        "\"$season\":[" + (1..3).joinToString(",") { episode(season, it) } + "]"
                    } else {
                        "\"$season\":{" + (1..3).joinToString(",") { "\"${it - 1}\":" + episode(season, it) } + "}"
                    }
                }
                reply(200, """{"info":{"name":"Series $id","plot":"A demo series."},"episodes":{$seasons}}""")
            }
            // The account check, which catch-up uses for the server's time zone.
            url.host == "demo.invalid" && path.endsWith("/player_api.php") && url.queryParameter("action") == null -> reply(
                200,
                """{"user_info":{"auth":1,"status":"Active"},"server_info":{"timezone":"Europe/London"}}""",
            )
            // The demo channels and films never finish loading, so the player stays on "Tuning…"
            // and its controls can be opened the same way every time.
            url.host == "demo.invalid" -> Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    object : java.io.InputStream() {
                        override fun read(): Int {
                            Thread.sleep(120_000)
                            return -1
                        }
                    }.source().buffer().asResponseBody("video/mp2t".toMediaType(), -1),
                )
                .build()
            // TheIntroDB: episodes open with a 90-second intro; films have no timings.
            url.host == "api.theintrodb.org" -> if (url.queryParameter("season") != null) {
                reply(200, """{"intro":[{"start_ms":null,"end_ms":90000}],"recap":[],"credits":[],"preview":[]}""")
            } else {
                reply(404, "")
            }
            // Settings › Updates: pretend GitHub has a newer build.
            url.host == "api.github.com" && path.endsWith("/releases/latest") -> reply(
                200,
                """{"name":"Skyfall build 999","body":"Automatic build 999.","assets":[{"name":"northlight.apk","size":13915963,""" +
                    """"browser_download_url":"https://github.com/EquitosFX/northlight/releases/download/latest/northlight.apk"}]}""",
            )
            url.host != "api.themoviedb.org" -> reply(404, "")
            // "Because you watched…": TMDB suggests three of the demo films.
            path.endsWith("/recommendations") -> reply(
                200,
                listOf(films[5], films[9], films[7]).joinToString(",", "{\"results\":[", "]}") { f ->
                    "{\"media_type\":\"movie\",\"title\":\"${f.title}\",\"release_date\":\"${f.year}-03-01\"}"
                },
            )
            // Movie night: full details (running time, seasons) for a suggestion.
            Regex("/(movie|tv)/(7\\d{3})$").find(path) != null -> {
                val m = Regex("/(movie|tv)/(7\\d{3})$").find(path)!!
                val tv = m.groupValues[1] == "tv"
                val i = m.groupValues[2].toInt() - 7000
                val f = (if (tv) series else films).getOrNull(i)
                if (f == null) reply(404, "{}") else reply(
                    200,
                    "{\"id\":${7000 + i},\"${if (tv) "name" else "title"}\":\"${f.title}\",\"${if (tv) "first_air_date" else "release_date"}\":\"${f.year}-03-01\"," +
                        "\"vote_average\":${6.1 + (i * 37 % 19) / 10.0},\"runtime\":${88 + i * 9 % 60},\"number_of_seasons\":${1 + i % 4}," +
                        "\"genres\":[{\"name\":\"${f.genre}\"}],\"overview\":\"${f.overview}\"}",
                )
            }
            // Movie night: TMDB's discover search suggests some of the demo films or series.
            path.contains("/discover/") -> {
                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                val tv = path.endsWith("/tv")
                val christmas = url.queryParameter("with_keywords") == "207317"
                val picks = when {
                    page > 1 -> emptyList()
                    // The seasonal row: TMDB calls a few of the demo films Christmas ones.
                    christmas -> if (tv) emptyList() else films.filter { it.title in setOf("The Long Thaw", "Paper Moons", "Kingfisher") }
                    tv -> series
                    else -> films
                }
                reply(200, picks.withIndex().joinToString(",", "{\"results\":[", "]}") { (i, f) ->
                    val dateKey = if (tv) "first_air_date" else "release_date"
                    val titleKey = if (tv) "name" else "title"
                    "{\"id\":${7000 + i},\"$titleKey\":\"${f.title}\",\"$dateKey\":\"${f.year}-03-01\"," +
                        "\"vote_average\":${6.1 + (i * 37 % 19) / 10.0},\"vote_count\":${400 + i * 90},\"popularity\":${20 + i * 7 % 50}," +
                        "\"genre_ids\":[${if (i % 3 == 0) "35,18" else if (i % 3 == 1) "53" else "18,10749"}],\"overview\":\"${f.overview}\"}"
                })
            }
            path.endsWith("/search/person") -> {
                val query = url.queryParameter("query").orEmpty().lowercase()
                val found = people.filter { query.isNotBlank() && it.lowercase().contains(query) }
                reply(200, found.joinToString(",", "{\"results\":[", "]}") { name ->
                    "{\"id\":${idOf(name)},\"name\":\"$name\",\"known_for_department\":\"Acting\"}"
                })
            }
            path.contains("/person/") && path.endsWith("/combined_credits") -> {
                val id = path.substringAfter("/person/").substringBefore("/").toLong()
                val name = people.getOrNull((id - 1).toInt())
                val cast = ArrayList<String>()
                val crewJobs = ArrayList<String>()
                fun entry(f: Film, tv: Boolean, roleKey: String, role: String) = if (tv) {
                    "{\"media_type\":\"tv\",\"name\":\"${f.title}\",\"first_air_date\":\"${f.year}-03-01\",\"$roleKey\":\"$role\"}"
                } else {
                    "{\"media_type\":\"movie\",\"title\":\"${f.title}\",\"release_date\":\"${f.year}-03-01\",\"$roleKey\":\"$role\"}"
                }
                films.forEachIndexed { i, f ->
                    val (director, actors) = crew(i)
                    if (director == name) crewJobs += entry(f, false, "job", "Director")
                    if (name in actors) cast += entry(f, false, "character", "Character")
                }
                series.forEachIndexed { i, f ->
                    val (director, actors) = crew(20 + i)
                    if (director == name) crewJobs += entry(f, true, "job", "Director")
                    if (name in actors) cast += entry(f, true, "character", "Character")
                }
                reply(200, "{\"cast\":[${cast.joinToString(",")}],\"crew\":[${crewJobs.joinToString(",")}]}")
            }
            else -> reply(404, "{}")
        }
    }

    private data class Film(val title: String, val year: Int, val group: String, val genre: String, val overview: String)

    fun fill(container: AppContainer, image: (String) -> String) {
        val db = container.database
        val now = System.currentTimeMillis()
        val random = Random(7)

        val pid = db.playlists().insertBlocking(
            PlaylistEntity(
                name = "Demo playlist",
                type = PlaylistEntity.TYPE_XTREAM,
                address = "http://demo.invalid",
                username = "demo",
                password = "demo",
                addedAt = now - 3 * 86_400_000L,
                refreshedAt = now - 3_600_000L,
                liveCount = channels.size,
                movieCount = films.size,
                seriesCount = series.size,
                accountStatus = "Active",
                expiresAt = now + 200 * 86_400_000L,
                guideRefreshedAt = now - 3_600_000L,
                programmeCount = 0,
            ),
        )

        val rows = ArrayList<ChannelEntity>()
        channels.forEachIndexed { i, (name, group) ->
            rows += ChannelEntity(
                playlistId = pid, kind = ChannelEntity.KIND_LIVE, name = "$name HD", baseName = name,
                variantKey = name.lowercase(), quality = 2, url = "http://demo.invalid/live/demo/demo/$i.ts", remoteId = i.toLong(),
                logo = image("ch$i.png"), groupName = group, number = 101 + i, guideId = "ch$i", epgId = "ch$i",
                position = i,
                // The first four channels keep a week of catch-up, as many Xtream servers do.
                catchupDays = if (i < 4) 7 else null,
            )
        }
        // Atlas News is listed twice, in HD and FHD, as many playlists do: one channel with two copies.
        channels.withIndex().firstOrNull { it.value.first == "Atlas News" }?.let { (i, entry) ->
            rows += ChannelEntity(
                playlistId = pid, kind = ChannelEntity.KIND_LIVE, name = "${entry.first} FHD", baseName = entry.first,
                variantKey = entry.first.lowercase(), quality = 3, url = "http://demo.invalid/live/demo/demo/${500 + i}.ts", remoteId = 500L + i,
                logo = image("ch$i.png"), groupName = entry.second, number = 101 + i, guideId = "ch$i", epgId = "ch$i",
                position = i,
                catchupDays = if (i < 4) 7 else null,
            )
        }
        films.forEachIndexed { i, f ->
            rows += ChannelEntity(
                playlistId = pid, kind = ChannelEntity.KIND_MOVIE, name = f.title, baseName = f.title,
                variantKey = f.title.lowercase(), url = "http://demo.invalid/movie/$i.mkv", remoteId = 1000L + i,
                logo = image("film$i.jpg"), groupName = f.group, position = 100 + i,
            )
        }
        // Two series as an Xtream server lists them (whole series, episodes fetched when opened),
        // and the rest as an M3U playlist lists them (one entry per episode).
        var position = 200
        series.forEachIndexed { i, f ->
            if (i < 2) {
                rows += ChannelEntity(
                    playlistId = pid, kind = ChannelEntity.KIND_SERIES, name = f.title, baseName = f.title,
                    variantKey = f.title.lowercase(), url = "", remoteId = 5000L + i,
                    logo = image("series$i.jpg"), groupName = f.group, position = position++,
                )
            } else {
                for (season in 1..2) for (episode in 1..4) {
                    val name = "${f.title} S%02d E%02d".format(season, episode)
                    rows += ChannelEntity(
                        playlistId = pid, kind = ChannelEntity.KIND_SERIES, name = name, baseName = name,
                        variantKey = name.lowercase(), url = "http://demo.invalid/series/$i/$season/$episode.mkv", remoteId = null,
                        logo = image("series$i.jpg"), groupName = f.group, position = position++,
                    )
                }
            }
        }
        db.channels().insertAllBlocking(rows)

        // A guide running from a day ago (for catch-up) to six hours ahead.
        val guide = ArrayList<ProgrammeEntity>()
        val first = now - now % HALF_HOUR - 48 * HALF_HOUR
        channels.forEachIndexed { i, (_, group) ->
            val titles = programmes.getValue(group)
            // Real guides don't keep to the half hour, so some programmes end just after the guide's left edge.
            var start = first - random.nextInt(0, 2) * HALF_HOUR - random.nextInt(0, 6) * 5 * MINUTE
            var n = i
            while (start < now + 6 * 60 * MINUTE) {
                val length = listOf(25L, 35L, 45L, 60L, 75L, 90L, 120L)[random.nextInt(7)] * MINUTE
                val title = titles[n++ % titles.size]
                guide += ProgrammeEntity(
                    playlistId = pid, channelGuideId = "ch$i", start = start, stop = start + length, title = title,
                    description = "$title. A demo listing for Northlight's TV guide.", category = group,
                )
                start += length
            }
        }
        db.programmes().insertAllBlocking(guide)
        db.playlists().updateBlocking(db.playlists().getBlocking(pid)!!.copy(programmeCount = guide.size))

        kotlinx.coroutines.runBlocking {
            container.settings.setTmdbKey("demo-key")

            fun peopleFor(seed: Int): String {
                val (director, cast) = crew(seed)
                return (listOf("D\t${idOf(director)}\t$director\tDirector\t") +
                    cast.mapIndexed { k, name -> "C\t${idOf(name)}\t$name\tCharacter ${k + 1}\t" })
                    .joinToString("\n")
            }
            fun save(type: String, f: Film, index: Int, poster: String, backdrop: String) {
                val (title, year) = TitleCleaner.clean(f.title)
                kotlinx.coroutines.runBlocking {
                    db.metadata().save(
                        MetadataEntity(
                            cacheKey = "$type|${title.lowercase()}|${year ?: ""}", found = true, fetchedAt = now,
                            tmdbId = 5000L + index, title = f.title, year = f.year, overview = f.overview,
                            poster = poster, backdrop = backdrop, rating = 6.4 + (index % 7) * 0.3,
                            runtimeMinutes = if (type == "movie") 92 + index * 4 else null, genres = f.genre,
                            people = peopleFor(index), seasons = if (type == "tv") 1 + index % 3 else null,
                        ),
                    )
                }
            }
            films.forEachIndexed { i, f -> save("movie", f, i, image("film$i.jpg"), image("film${i}_bg.jpg")) }
            series.forEachIndexed { i, f -> save("tv", f, 20 + i, image("series$i.jpg"), image("series${i}_bg.jpg")) }

            // Part-watched: a film (leads Home) and an episode.
            db.progress().save(
                ProgressEntity(
                    url = "http://demo.invalid/series/0/e3", title = "Cobalt Street · S1 E3", poster = image("series0.jpg"),
                    positionMs = 21 * MINUTE, durationMs = 48 * MINUTE, updatedAt = now - 2 * 86_400_000L,
                ),
            )
            db.progress().save(
                ProgressEntity(
                    url = "http://demo.invalid/movie/4.mkv", title = "Ember Valley", poster = image("film4.jpg"),
                    positionMs = 47 * MINUTE, durationMs = 108 * MINUTE, updatedAt = now - 3_600_000L,
                ),
            )
            // A film played recently, for "Because you watched…".
            container.watched.record("Ember Valley")
            // Recently watched channels and a few favourites.
            listOf(0, 3, 2, 7, 5).forEachIndexed { order, i ->
                val (name, _) = channels[i]
                db.history().save(
                    HistoryEntity(
                        key = "$pid|${name.lowercase()}", name = name, logo = image("ch$i.png"), number = 101 + i,
                        playlistId = pid, guideId = "ch$i", watchedAt = now - order * 3_600_000L,
                    ),
                )
            }
            listOf(0, 1, 3, 4, 2, 9).forEachIndexed { order, i ->
                db.favourites().add(FavouriteEntity("$pid|${channels[i].first.lowercase()}", now - order * MINUTE))
            }
        }
    }

    /**
     * A large, untidy line-up like a real provider's: hundreds of channels in dozens of groups, some
     * without listings, uneven programme times across the past week and the next day, mixed catch-up.
     */
    fun fillLarge(container: AppContainer) {
        val db = container.database
        val now = System.currentTimeMillis()
        val random = Random(11)
        val pid = db.playlists().getAllBlocking().first().id
        val groups = (1..45).map { "Group $it" }
        val rows = (0 until 400).map { i ->
            val name = "Extra Channel $i"
            ChannelEntity(
                playlistId = pid, kind = ChannelEntity.KIND_LIVE, name = name, baseName = name,
                variantKey = name.lowercase(), quality = 1, url = "http://demo.invalid/live/demo/demo/${1000 + i}.ts",
                remoteId = 1000L + i, logo = null, groupName = groups[i % groups.size], number = 200 + i,
                guideId = if (i % 3 == 0) null else "x$i", epgId = if (i % 3 == 0) null else "x$i",
                position = 1000 + i, catchupDays = listOf(null, 0, 1, 3, 7, 14)[i % 6],
            )
        }
        db.channels().insertAllBlocking(rows)
        val guide = ArrayList<ProgrammeEntity>()
        for (i in 0 until 400) {
            if (i % 3 == 0) continue
            var start = now - 7 * 86_400_000L - random.nextLong(0, 3_600_000L)
            while (start < now + 86_400_000L) {
                val length = (5 + random.nextInt(0, 175)) * MINUTE
                guide += ProgrammeEntity(
                    playlistId = pid, channelGuideId = "x$i", start = start, stop = start + length,
                    title = "Programme ${guide.size}", description = if (random.nextBoolean()) "About programme ${guide.size}." else null,
                )
                start += length
            }
        }
        db.programmes().insertAllBlocking(guide)
    }
}
