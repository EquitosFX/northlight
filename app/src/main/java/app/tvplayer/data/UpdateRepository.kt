package app.tvplayer.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.tvplayer.NorthlightApp
import app.tvplayer.core.update.AvailableBuild
import app.tvplayer.core.update.FileCheck
import app.tvplayer.core.update.ReleaseParser
import app.tvplayer.core.update.UpdateException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where the in-app updater is up to. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val build: Long) : UpdateState
    data class Available(val update: AvailableBuild) : UpdateState
    data class Downloading(val update: AvailableBuild, val fraction: Float) : UpdateState
    data class Installing(val update: AvailableBuild) : UpdateState

    /** Android needs the viewer to allow Skyfall to install apps before it can go ahead. */
    data class NeedsPermission(val update: AvailableBuild) : UpdateState
    data class Failed(val message: String, val update: AvailableBuild?) : UpdateState
}

/**
 * Checks GitHub for a newer build, downloads it, checks it against GitHub's size and checksum,
 * and hands it to Android to install over the top of this copy.
 */
class UpdateRepository(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** A newer build than the one installed, once a check has found one. Drives the dot on Settings. */
    private val _available = MutableStateFlow<AvailableBuild?>(null)
    val available: StateFlow<AvailableBuild?> = _available.asStateFlow()

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var job: Job? = null

    val currentBuild: Long by lazy {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Checks quietly at most once every 20 hours; called when the app opens. */
    fun checkDaily() {
        val last = prefs.getLong(LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last > 20 * 60 * 60 * 1000L) check()
    }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val update = withContext(Dispatchers.IO) { fetchLatest() }
                prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
                if (ReleaseParser.isNewer(update, currentBuild)) {
                    _available.value = update
                    UpdateState.Available(update)
                } else {
                    _available.value = null
                    UpdateState.UpToDate(currentBuild)
                }
            } catch (e: UpdateException) {
                UpdateState.Failed(e.message.orEmpty(), null)
            } catch (e: IOException) {
                UpdateState.Failed("Couldn't reach GitHub. Check the Shield's internet connection and try again.", null)
            }
        }
    }

    private fun fetchLatest(): AvailableBuild {
        val request = Request.Builder()
            .url(ReleaseParser.LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw UpdateException("No release found on GitHub. If the repository is private, the app can't see it.")
                response.code == 403 || response.code == 429 -> throw UpdateException("GitHub is limiting checks for now. Try again in an hour.")
                !response.isSuccessful -> throw UpdateException("GitHub replied with an error (${response.code}). Try again later.")
            }
            return ReleaseParser.parse(response.body?.string().orEmpty())
        }
    }

    /** Whether Android will let Skyfall install apps. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system screen where the viewer allows Skyfall to install apps, if this device has one. */
    fun permissionScreen(): Intent? {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    fun downloadAndInstall() {
        val update = when (val s = _state.value) {
            is UpdateState.Available -> s.update
            is UpdateState.NeedsPermission -> s.update
            is UpdateState.Failed -> s.update
            else -> null
        } ?: _available.value ?: return
        if (job?.isActive == true) return
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(update)
            return
        }
        job = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(update) }
                withContext(Dispatchers.IO) { FileCheck.verify(file, update) }
                _state.value = UpdateState.Installing(update)
                withContext(Dispatchers.IO) { install(file, update) }
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(e.message.orEmpty(), update)
            } catch (e: IOException) {
                _state.value = UpdateState.Failed("The download stopped part-way. Check the connection and try again.", update)
            }
        }
    }

    private fun download(update: AvailableBuild): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "skyfall-${update.build}.apk")
        _state.value = UpdateState.Downloading(update, 0f)
        http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw UpdateException("GitHub couldn't send the update (${response.code}). Try again later.")
            val body = response.body ?: throw UpdateException("GitHub sent an empty download. Try again.")
            val total = update.sizeBytes.takeIf { it > 0 } ?: body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = 0f
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val fraction = (done.toFloat() / total).coerceAtMost(1f)
                            if (fraction - shown >= 0.01f) {
                                shown = fraction
                                _state.value = UpdateState.Downloading(update, fraction)
                            }
                        }
                    }
                }
            }
        }
        return file
    }

    private fun install(file: File, update: AvailableBuild) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("skyfall.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).putExtra(EXTRA_BUILD, update.build)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Called with Android's answer once it has finished (or refused) installing. */
    internal fun onInstallResult(status: Int, message: String?) {
        val update = (_state.value as? UpdateState.Installing)?.update ?: _available.value
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate(update?.build?.toLong() ?: currentBuild)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed("The install was cancelled.", update)
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            -> UpdateState.Failed(
                "Android wouldn't install it over this copy" + (message?.let { " ($it)" } ?: "") +
                    ". Uninstall Skyfall and install the new build with Downloader.",
                update,
            )
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("There isn't enough free space on the Shield.", update)
            else -> UpdateState.Failed("Android couldn't install the update" + (message?.let { ": $it" } ?: "."), update)
        }
    }

    private companion object {
        const val LAST_CHECK = "last_check"
    }
}

internal const val EXTRA_BUILD = "build"

/** Receives Android's progress on an install: asks the viewer to confirm, then reports the result. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val app = context.applicationContext as? NorthlightApp ?: return
        app.container.updates.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
