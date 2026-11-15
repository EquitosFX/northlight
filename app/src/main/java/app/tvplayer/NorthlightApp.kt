package app.tvplayer

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
import okhttp3.OkHttpClient

class NorthlightApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
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

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val playlists: PlaylistRepository by lazy { PlaylistRepository(database, http, scope) }

    val channels: ChannelsRepository by lazy { ChannelsRepository(database) }

    val metadata: MetadataRepository by lazy { MetadataRepository(database, http, settings) }
}
