package os.retinue.ara.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import os.retinue.ara.BuildConfig
import os.retinue.ara.R
import os.retinue.ara.data.AraStore

/** Where the updater is, for the settings screen. */
sealed class UpdateState {
    /** Not checked in this process (or the channel was just changed). */
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpToDate(val checkedAt: Long, val latest: ReleaseInfo) : UpdateState()
    data class NoBuilds(val checkedAt: Long) : UpdateState()

    /** A newer build; [waitingForWifi] when the automatic install holds off on a metered network. */
    data class Available(val checkedAt: Long, val release: ReleaseInfo, val apk: ReleaseAsset, val waitingForWifi: Boolean) : UpdateState()
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState()

    /** Handed to the package installer; the outcome comes back through [UpdateReceiver]. */
    data class Installing(val release: ReleaseInfo) : UpdateState()

    /**
     * The installer wants the user's confirmation: [confirm] opens its dialog.
     * The shell shows it once ([shown]) when it is in front; the settings
     * offer it again in case it was dismissed. [release] is null when the
     * process was restarted in between.
     */
    data class AwaitingConfirmation(val release: ReleaseInfo?, val confirm: Intent, val shown: Boolean) : UpdateState()

    /** Normally never seen: Android restarts the app with the new build. */
    data object Installed : UpdateState()
    data class Failed(val message: String) : UpdateState()

    /** A check, download or install is in progress. */
    val busy: Boolean get() = this is Checking || this is Downloading || this is Installing
}

/**
 * Process-wide owner of updates: checks the chosen [UpdateChannel] (the GitHub
 * releases of [UpdateSource.REPO]), downloads the build it offers and hands it
 * to the package installer as an update of this app. With auto-update on it
 * does all of that by itself when the shell comes to the front, at most every
 * [CHECK_INTERVAL_MS] and over Wi-Fi only; otherwise only the settings' buttons
 * start anything. Everything runs on the main thread except the network and
 * file work; the state is for the settings screen.
 */
object UpdateRuntime {

    private const val TAG = "UpdateRuntime"
    private const val DIR = "updates"
    private const val FILE = "ara-update.apk"

    /** Refuse anything larger: a nightly is about 85 MB. */
    private const val MAX_BYTES = 300L * 1024 * 1024

    /** Automatic checks are at least this far apart (the nightly is built once a day). */
    val CHECK_INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(6)

    /** The installer has this long to report (an 85 MB APK verifies in seconds) before the attempt counts as failed. */
    private val INSTALL_TIMEOUT_MS: Long = TimeUnit.MINUTES.toMillis(3)

    /** What this APK was built from (empty fields in a local build). */
    val installed = InstalledBuild(BuildConfig.BUILD_TAG, BuildConfig.BUILD_SHA)

    private lateinit var app: Context
    private lateinit var store: AraStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Settings and the last check time have been read from the store. */
    private val loaded = CompletableDeferred<Unit>()
    private var configured = false
    private var autoUpdate = false
    private var channel = UpdateChannel.Nightly
    private var lastCheckAt = 0L
    private var job: Job? = null

    /** The running job was started by the runtime, not from the settings. */
    private var automatic = false
    private var downloaded: File? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val userAgent: String get() = "Ara/${BuildConfig.VERSION_NAME} (Android)"

    fun init(context: Context) {
        app = context.applicationContext
        store = AraStore(app)
        scope.launch {
            withContext(Dispatchers.IO) { cleanUp() }
            lastCheckAt = store.updateCheckedAt.first()
            store.settings
                .map { it.autoUpdate to it.updateChannel }
                .distinctUntilChanged()
                .collect { (auto, ch) ->
                    configure(auto, ch)
                    if (!loaded.isCompleted) loaded.complete(Unit)
                }
        }
    }

    /** Leftovers of an earlier process: a downloaded APK, an installer session never finished. */
    private fun cleanUp() {
        dir().listFiles()?.forEach { it.delete() }
        val installer = app.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
    }

    private fun configure(auto: Boolean, ch: UpdateChannel) {
        val channelChanged = configured && ch != channel
        val turnedOn = configured && auto && !autoUpdate
        val turnedOff = configured && !auto && autoUpdate
        autoUpdate = auto
        channel = ch
        configured = true
        if (channelChanged) {
            // Whatever was found was found on the old channel.
            cancel()
            if (!_state.value.busy && _state.value !is UpdateState.AwaitingConfirmation) _state.value = UpdateState.Idle
            lastCheckAt = 0L
            scope.launch { store.setUpdateCheckedAt(0L) }
        } else if (turnedOff && automatic) {
            cancel()
        }
        if (auto && (channelChanged || turnedOn)) check(automatic = true)
    }

    /** Stop a check or download in progress (an install already handed over runs on). */
    private fun cancel() {
        val j = job ?: return
        if (j.isActive && (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading)) {
            j.cancel()
            _state.value = UpdateState.Idle
        }
    }

    /** The shell came to the front: with auto-update on, check when due, or finish a build that waited for Wi-Fi. */
    fun onShellResumed() {
        scope.launch {
            loaded.await()
            if (!autoUpdate) return@launch
            val s = _state.value
            if (s is UpdateState.Available && s.waitingForWifi && !metered()) {
                start(automatic = true) { downloadAndInstall(s.release, s.apk) }
            } else if (UpdateLogic.checkDue(System.currentTimeMillis(), lastCheckAt, CHECK_INTERVAL_MS)) {
                check(automatic = true)
            }
        }
    }

    /** The settings' "Check for updates". With auto-update on, what it finds is installed too. */
    fun checkNow() = check(automatic = false)

    /** The settings' "Install": the build the last check found, Wi-Fi or not. */
    fun installNow() {
        val s = _state.value as? UpdateState.Available ?: return
        start(automatic = false) { downloadAndInstall(s.release, s.apk) }
    }

    private fun check(automatic: Boolean) = start(automatic) {
        _state.value = UpdateState.Checking
        val releases = withContext(Dispatchers.IO) { fetchReleases() }
        val now = System.currentTimeMillis()
        lastCheckAt = now
        store.setUpdateCheckedAt(now)
        when (val d = UpdateLogic.decide(installed, releases, channel)) {
            is UpdateDecision.NoBuilds -> _state.value = UpdateState.NoBuilds(now)
            is UpdateDecision.UpToDate -> _state.value = UpdateState.UpToDate(now, d.latest)
            is UpdateDecision.Available -> {
                val waitForWifi = autoUpdate && metered()
                _state.value = UpdateState.Available(now, d.release, d.apk, waitingForWifi = waitForWifi)
                if (autoUpdate && !waitForWifi) downloadAndInstall(d.release, d.apk)
            }
        }
    }

    /** Run [block] as the one job, unless one runs or an install is in the installer's hands. */
    private fun start(automatic: Boolean, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        val s = _state.value
        if (s is UpdateState.Installing || s is UpdateState.AwaitingConfirmation) return
        this.automatic = automatic
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun fetchReleases(): List<ReleaseInfo> {
        val request = Request.Builder()
            .url(UpdateSource.RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 429 || (resp.code == 403 && resp.header("X-RateLimit-Remaining") == "0")) {
                throw IOException(app.getString(R.string.update_rate_limited))
            }
            if (!resp.isSuccessful) throw IOException(app.getString(R.string.update_http_error, resp.code))
            val body = resp.body?.string() ?: throw IOException(app.getString(R.string.update_http_error, resp.code))
            return UpdateLogic.parseReleases(body)
        }
    }

    private suspend fun downloadAndInstall(release: ReleaseInfo, apk: ReleaseAsset) {
        _state.value = UpdateState.Downloading(release, 0f)
        val file = withContext(Dispatchers.IO) {
            download(apk) { progress -> _state.value = UpdateState.Downloading(release, progress) }
        }
        downloaded = file
        _state.value = UpdateState.Installing(release)
        try {
            withContext(Dispatchers.IO) { UpdateInstaller.install(app, file) }
        } catch (e: Exception) {
            file.delete()
            downloaded = null
            throw e
        }
        // From here the outcome arrives in UpdateReceiver: installed (Android
        // restarts Ara), a confirmation to show, or a failure. Should none come,
        // the attempt is given up rather than blocking the next.
        delay(INSTALL_TIMEOUT_MS)
        val s = _state.value
        if (s is UpdateState.Installing && s.release == release) {
            onInstallFailed(PackageInstaller.STATUS_FAILURE, app.getString(R.string.update_install_no_report))
        }
    }

    /** Fetch the APK into the cache; the size the release states is what must arrive. */
    private suspend fun download(apk: ReleaseAsset, onProgress: (Float) -> Unit): File {
        if (apk.size > MAX_BYTES) throw IOException(app.getString(R.string.update_too_large))
        val dir = dir()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, FILE)
        val request = Request.Builder()
            .url(apk.downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", userAgent)
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException(app.getString(R.string.update_http_error, resp.code))
                val body = resp.body ?: throw IOException(app.getString(R.string.update_http_error, resp.code))
                val expected = if (apk.size > 0) apk.size else body.contentLength()
                if (expected > MAX_BYTES) throw IOException(app.getString(R.string.update_too_large))
                var total = 0L
                var shown = -1
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException(app.getString(R.string.update_too_large))
                            out.write(buf, 0, n)
                            if (expected > 0) {
                                val percent = (total * 100 / expected).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    }
                }
                if (apk.size > 0 && total != apk.size) throw IOException(app.getString(R.string.update_incomplete, total, apk.size))
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return file
    }

    // --- Reported by UpdateReceiver ---------------------------------------------

    /** The installer wants the user to confirm (first install through Ara, or "Install unknown apps" still off). */
    fun onUserActionNeeded(confirm: Intent?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        if (confirm == null) {
            onInstallFailed(PackageInstaller.STATUS_FAILURE, app.getString(R.string.update_no_confirmation))
            return
        }
        _state.value = UpdateState.AwaitingConfirmation(release, confirm, shown = false)
    }

    fun onInstalled() {
        downloaded?.delete()
        downloaded = null
        _state.value = UpdateState.Installed
    }

    fun onInstallFailed(status: Int, message: String?) {
        downloaded?.delete()
        downloaded = null
        _state.value = UpdateState.Failed(installFailure(status, message))
    }

    /**
     * The confirmation to show, once: the resumed activity starts it (an
     * activity start from the background would be refused). Null when there is
     * none or it was shown already.
     */
    fun takeConfirmation(): Intent? {
        val s = _state.value as? UpdateState.AwaitingConfirmation ?: return null
        if (s.shown) return null
        _state.value = s.copy(shown = true)
        return s.confirm
    }

    /** The confirmation again, for the settings' button (its dialog may have been dismissed). */
    fun confirmation(): Intent? = (_state.value as? UpdateState.AwaitingConfirmation)?.confirm

    private fun installFailure(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> app.getString(R.string.update_install_cancelled)
        PackageInstaller.STATUS_FAILURE_CONFLICT -> app.getString(R.string.update_install_conflict)
        PackageInstaller.STATUS_FAILURE_INVALID -> app.getString(R.string.update_install_invalid)
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> app.getString(R.string.update_install_incompatible)
        PackageInstaller.STATUS_FAILURE_STORAGE -> app.getString(R.string.update_install_storage)
        PackageInstaller.STATUS_FAILURE_BLOCKED -> app.getString(R.string.update_install_blocked)
        else -> message ?: app.getString(R.string.update_install_failed_code, status)
    }

    // --- Device state --------------------------------------------------------------

    /** Whether Android lets Ara install apps ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the user allows that. */
    fun installSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    private fun metered(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    private fun dir(): File = File(app.cacheDir, DIR).apply { mkdirs() }
}
