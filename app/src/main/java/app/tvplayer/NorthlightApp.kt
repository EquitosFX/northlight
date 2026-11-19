package app.tvplayer

import app.tvplayer.data.NewEpisodes
import app.tvplayer.data.AddedDatesStore
import app.tvplayer.data.HomeScreenRows
import app.tvplayer.data.ChannelCheckRepository
import app.tvplayer.data.BackupRepository
import app.tvplayer.data.MyListRepository
import app.tvplayer.data.WatchedTitles
import app.tvplayer.data.IntroRepository
import app.tvplayer.data.SubtitlesRepository
import app.tvplayer.data.UpdateRepository
import android.app.Application
import android.content.Context
import androidx.room.Room
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.MetadataRepository
import app.tvplayer.data.PlaylistRepository
import app.tvplayer.data.SettingsRepository
import app.tvplayer.data.db.AppDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class NorthlightApp : Application(), coil.ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /**
     * Posters, logos and artwork. Many IPTV servers mark their pictures "don't keep", which made every
     * poster download again each time a screen opened; they're kept on disk regardless now (a picture
     * at the same address rarely changes), so screens fill in straight away.
     */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .respectCacheHeaders(false)
            .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.03)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashLog.noteExitReason(this)
        container = AppContainer(this)
    }
}

/** The app's long-lived objects, created once and shared. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Work started here carries on when the user moves between screens. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "northlight.db")
            // While the app is in early development a changed table layout simply rebuilds the database.
            .fallbackToDestructiveMigration()
            .build()
    }

    /** Only for the screenshot test, which answers web requests itself. Always null in the app. */
    @Volatile var testResponder: Interceptor? = null

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain -> testResponder?.intercept(chain) ?: chain.proceed(chain.request()) }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** When films and series were added, for "Recently added". */
    val addedDates: AddedDatesStore by lazy { AddedDatesStore(java.io.File(appContext.filesDir, "added")) }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(database, http, scope, addedDates) }

    val channels: ChannelsRepository by lazy { ChannelsRepository(database, addedDates) }

    val metadata: MetadataRepository by lazy { MetadataRepository(database, http, settings) }

    val updates: UpdateRepository by lazy { UpdateRepository(appContext, http, scope) }

    val subtitles: SubtitlesRepository by lazy { SubtitlesRepository(appContext, http, settings, metadata) }

    val intros: IntroRepository by lazy { IntroRepository(http, metadata) }

    val watched: WatchedTitles by lazy { WatchedTitles(appContext) }

    val myList: MyListRepository by lazy { MyListRepository(appContext) }

    val newEpisodes: NewEpisodes by lazy { NewEpisodes(appContext, channels, playlists) }

    val backup: BackupRepository by lazy { BackupRepository(appContext, database, settings, myList, playlists) }

    val channelCheck: ChannelCheckRepository by lazy { ChannelCheckRepository(appContext, database, http, scope) }

    val homeRows: HomeScreenRows by lazy { HomeScreenRows(appContext) }

    /**
     * A less powerful TV box (such as Chromecast with Google TV, with 2 GB of memory): moving
     * decorations are kept still so the menus and guide stay quick.
     */
    val lowPower: Boolean by lazy {
        val manager = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        // By model, not memory alone: a Shield has 2-3 GB but plenty of power.
        val model = android.os.Build.MODEL.orEmpty()
        manager.isLowRamDevice ||
            model.contains("Chromecast", ignoreCase = true) ||
            memory.totalMem < 1_600L * 1024 * 1024
    }

    /**
     * An NVIDIA Shield: enough graphics power for the snow to keep falling behind the TV guide too.
     * (Other boxes, the Google TV Streamer included, keep it still there so the guide stays smooth.)
     */
    val shield: Boolean by lazy {
        android.os.Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true) ||
            android.os.Build.MODEL.orEmpty().contains("SHIELD", ignoreCase = true)
    }

    /** A link from the Shield's home screen waiting to be opened ("skyfall://resume/…", "skyfall://channel/…"). */
    val openLink = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)
}
