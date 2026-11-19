package app.tvplayer.demo

import androidx.test.core.app.ApplicationProvider
import app.tvplayer.NorthlightApp
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.PlaylistEntity
import app.tvplayer.data.db.ProgrammeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * How long each thing the screens load takes with a big provider's playlist: 24,000 live channel
 * entries (many in HD and FHD copies), 80,000 films, 20,000 series and 400,000 guide programmes.
 * Printed as a table, first time (nothing remembered yet) and again (remembered).
 *
 * The build machine is several times quicker than a TV box, so these are for comparing before and
 * after a change, and for finding which load is slowest — not the times on the box itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoadTimes {
    private val app: NorthlightApp get() = ApplicationProvider.getApplicationContext()

    private fun fill() {
        val db = app.container.database
        val now = System.currentTimeMillis()
        val pid = db.playlists().insertBlocking(
            PlaylistEntity(name = "Big", type = PlaylistEntity.TYPE_XTREAM, address = "http://big.invalid", username = "u", password = "p", addedAt = now, refreshedAt = now),
        )
        val random = Random(7)
        val rows = ArrayList<ChannelEntity>()
        var position = 0
        // Live: 8,000 channels, each in up to three copies.
        for (i in 0 until 8_000) {
            val base = "UK: Channel $i"
            for ((q, tag) in listOf(1 to "SD", 2 to "HD", 3 to "FHD").take(1 + i % 3)) {
                rows += ChannelEntity(
                    playlistId = pid, kind = ChannelEntity.KIND_LIVE, name = "$base $tag", baseName = base,
                    variantKey = base.lowercase(), quality = q, url = "http://big.invalid/live/u/p/${rows.size}.ts",
                    remoteId = rows.size.toLong(), groupName = "Group ${i % 120}", number = i + 1,
                    guideId = if (i % 4 == 0) null else "g$i", epgId = if (i % 4 == 0) null else "g$i",
                    position = position++, catchupDays = if (i % 5 == 0) 7 else null,
                )
            }
        }
        for (i in 0 until 80_000) {
            val name = "Film ${random.nextInt(1_000_000)} The ${listOf("Return", "Night", "Story", "Last", "Road")[i % 5]} (${1970 + i % 55})"
            rows += ChannelEntity(
                playlistId = pid, kind = ChannelEntity.KIND_MOVIE, name = name, baseName = name, variantKey = name.lowercase(),
                url = "http://big.invalid/movie/u/p/$i.mkv", remoteId = i.toLong(), groupName = "Films ${i % 300}", position = position++,
                logo = "http://img.invalid/$i.jpg",
            )
        }
        for (i in 0 until 20_000) {
            val name = "Series $i The ${listOf("Return", "Night", "Story", "Last", "Road")[i % 5]}"
            rows += ChannelEntity(
                playlistId = pid, kind = ChannelEntity.KIND_SERIES, name = name, baseName = name, variantKey = name.lowercase(),
                url = "http://big.invalid/series/$i", remoteId = i.toLong(), groupName = "Series ${i % 150}", position = position++,
                logo = "http://img.invalid/s$i.jpg",
            )
        }
        rows.chunked(5_000).forEach { db.channels().insertAllBlocking(it) }
        val guide = ArrayList<ProgrammeEntity>()
        for (i in 0 until 8_000) {
            if (i % 4 == 0) continue
            var start = now - 26 * 3_600_000L
            while (start < now + 26 * 3_600_000L && guide.size < 400_000) {
                val length = (15 + random.nextInt(0, 105)) * 60_000L
                guide += ProgrammeEntity(playlistId = pid, channelGuideId = "g$i", start = start, stop = start + length, title = "Programme ${guide.size}")
                start += length
            }
        }
        guide.chunked(10_000).forEach { db.programmes().insertAllBlocking(it) }
        println("Filled: ${rows.size} channel entries, ${guide.size} programmes")
    }

    private val results = ArrayList<String>()

    private fun <T> time(name: String, block: suspend () -> T): T {
        val first = System.nanoTime()
        val value = runBlocking(Dispatchers.IO) { block() }
        val firstMs = (System.nanoTime() - first) / 1_000_000
        val again = System.nanoTime()
        runBlocking(Dispatchers.IO) { block() }
        val againMs = (System.nanoTime() - again) / 1_000_000
        results += "%-44s %7d ms %7d ms".format(name, firstMs, againMs)
        return value
    }

    @Test fun loads() {
        // (The database can't be written from the main thread.)
        runBlocking(Dispatchers.IO) { fill() }
        val channels = app.container.channels
        val lineup = time("TV guide: channel list (lineup)") { channels.lineup() }
        time("TV guide: checking for a new guide (stamp)") { channels.programmeStamp() }
        val memory = app.tvplayer.ui.guide.GuideMemory()
        time("TV guide: background warm-up") { memory.warmUp(channels) }
        val group = lineup.groups.first()
        time("TV guide: one category") { lineup.inGroup(group) }
        val origin = System.currentTimeMillis() - 2 * 3_600_000L
        val shown: List<LineupChannel> = lineup.channels.take(12)
        time("TV guide: 12 rows of programmes") { shown.forEach { channels.programmes(it, origin, origin + 10 * 3_600_000L) } }
        time("Player: now and next") { channels.nowAndNext(shown.first { it.guideId != null }) }
        time("Films: categories") { channels.groups(ChannelEntity.KIND_MOVIE) }
        time("Films: all films (\"All\")") { channels.inGroup(ChannelEntity.KIND_MOVIE, null) }
        time("Films: one category") { channels.inGroup(ChannelEntity.KIND_MOVIE, "Films 7") }
        time("Series: categories") { channels.groups(ChannelEntity.KIND_SERIES) }
        time("Series: all series (\"All\")") { channels.inGroup(ChannelEntity.KIND_SERIES, null) }
        time("Sport: matches on now and soon") { channels.sport() }
        time("Search: \"the\"") { channels.search("the") }
        time("Search: \"channel 77\"") { channels.search("channel 77") }
        println()
        println("%-44s %10s %10s".format("Load", "First", "Again"))
        results.forEach(::println)
    }
}
