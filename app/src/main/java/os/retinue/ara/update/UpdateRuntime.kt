package os.retinue.ara.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import os.retinue.ara.BuildConfig
import os.retinue.ara.R
import os.retinue.ara.data.AraStore
import os.retinue.ara.wake.WakeRuntime

/** Where the updater is, for the settings screen. */
sealed class UpdateState {
    /** Not checked in this process (or the channel was just changed). */
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpToDate(val checkedAt: Long, val latest: ReleaseInfo) : UpdateState()
    data class NoBuilds(val checkedAt: Long) : UpdateState()

    /** A newer build; [waitingForUnmetered] when the automatic install holds off until the network is unmetered. */
    data class Available(val checkedAt: Long, val release: ReleaseInfo, val apk: ReleaseAsset, val waitingForUnmetered: Boolean) : UpdateState()
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState()

    /** Downloaded; an automatic install holds off while the web app holds a voice conversation. */
    data class Waiting(val release: ReleaseInfo) : UpdateState()

    /** Handed to the package installer; the outcome comes back through [UpdateReceiver]. */
    data class Installing(val release: ReleaseInfo) : UpdateState()

    /**
     * The installer wants the user's confirmation: [confirm] opens its dialog.
     * The shell shows it once ([shown]) when it is in front; the settings
     * offer it again in case it was dismissed, or to cancel. [release] is null
     * when the process was restarted in between.
     */
    data class AwaitingConfirmation(val release: ReleaseInfo?, val confirm: Intent, val shown: Boolean) : UpdateState()

    /**
     * The attempt this process made succeeded. Normally never seen: Android
     * restarts the app with the new build, and the report reaching that build
     * is not shown as this.
     */
    data object Installed : UpdateState()
    data class Failed(val message: String) : UpdateState()

    /** A check, download or install is in progress. */
    val busy: Boolean get() = this is Checking || this is Downloading || this is Waiting || this is Installing
}

/**
 * Process-wide owner of updates: checks the chosen [UpdateChannel] (the GitHub
 * releases of [UpdateSource.REPO]), downloads the build it offers and hands it
 * to the package installer as an update of this app. With auto-update on it
 * does all of that by itself when the shell comes to the front, at most every
 * [CHECK_INTERVAL_MS] and over unmetered networks only; otherwise only the settings' buttons
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

    /** After a check that failed (offline, rate-limited), the next automatic one waits this long rather than for the next resume. */
    private val FAILED_RETRY_MS: Long = TimeUnit.MINUTES.toMillis(30)

    /** An automatic install waits for the end of a voice conversation at most this long (the flag can outlive a destroyed page). */
    private val VOICE_WAIT_MS: Long = TimeUnit.MINUTES.toMillis(30)

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

    /** No automatic check before this (epoch ms), after one failed. */
    private var retryAt = 0L

    /** The one check or download running. */
    private var job: Job? = null

    /** Gives up on an install the installer never reports on. */
    private var watchdog: Job? = null

    /** The running job was started by the runtime, not from the settings. */
    private var automatic = false
    private var downloaded: File? = null

    /** The installer session committed last, until it has reported; 0 for none. */
    private var pendingSession = 0

    /** Sessions given up on: the installer reports them aborted, which is not a failure to show. */
    private val ignoredSessions = mutableSetOf<Int>()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // github.com sends the APK download on to another https host; never to plain http.
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }

    private val userAgent: String get() = "Ara/${BuildConfig.VERSION_NAME} (Android)"

    /** The build whose check the stored check time belongs to: a new build checks afresh. */
    private val buildId: String get() = "${installed.tag}@${installed.sha}"

    fun init(context: Context) {
        app = context.applicationContext
        store = AraStore(app)
        scope.launch {
            cleanUp()
            lastCheckAt = store.updateCheckedAt(buildId).first()
            store.settings
                .map { it.autoUpdate to it.updateChannel }
                .distinctUntilChanged()
                .collect { (auto, ch) ->
                    configure(auto, ch)
                    if (!loaded.isCompleted) loaded.complete(Unit)
                }
        }
    }

    /**
     * Leftovers of an earlier process: a downloaded APK, and installer sessions
     * never committed (a process died while writing one; each stages a copy of
     * the APK). A committed one is in the installer's hands, its dialog possibly
     * still up for the user to finish, and is left alone until the next attempt
     * replaces it ([retireSessions]); what it reports is shown.
     */
    private suspend fun cleanUp() {
        val installer = app.packageManager.packageInstaller
        val orphans = withContext(Dispatchers.IO) {
            dir().listFiles()?.forEach { it.delete() }
            installer.mySessions.filter { !it.isSealed }.map { it.sessionId }
        }
        ignoredSessions += orphans
        orphans.forEach { runCatching { installer.abandonSession(it) } }
    }

    /** Sessions of earlier attempts, still in the installer's hands: the new attempt replaces them. */
    private fun retireSessions() {
        val installer = app.packageManager.packageInstaller
        installer.mySessions.forEach {
            ignoredSessions += it.sessionId
            runCatching { installer.abandonSession(it.sessionId) }
        }
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
            val s = _state.value
            if (!s.busy && s !is UpdateState.AwaitingConfirmation) _state.value = UpdateState.Idle
            lastCheckAt = 0L
            scope.launch { store.setUpdateCheckedAt(0L, buildId) }
        } else if (turnedOff) {
            if (automatic) cancel()
            val s = _state.value
            if (s is UpdateState.Available && s.waitingForUnmetered) _state.value = s.copy(waitingForUnmetered = false)
        }
        if (auto && (channelChanged || turnedOn)) check(automatic = true)
    }

    /** Stop a check or download in progress (an install already handed over runs on). */
    private fun cancel() {
        val j = job ?: return
        if (j.isActive && _state.value.busy && _state.value !is UpdateState.Installing) j.cancel()
    }

    /** The shell came to the front: with auto-update on, check when due, or finish a build that waited for an unmetered network. */
    fun onShellResumed() {
        scope.launch {
            loaded.await()
            if (!autoUpdate) return@launch
            val s = _state.value
            if (s is UpdateState.Available && s.waitingForUnmetered && unmetered()) {
                start(automatic = true) { downloadAndInstall(s.release, s.apk) }
            } else {
                val now = System.currentTimeMillis()
                if (now >= retryAt && UpdateLogic.checkDue(now, lastCheckAt, CHECK_INTERVAL_MS)) check(automatic = true)
            }
        }
    }

    /** The settings' "Check for updates". With auto-update on, what it finds is installed too. */
    fun checkNow() = check(automatic = false)

    /** The settings' "Install": the build the last check found, whatever the network. */
    fun installNow() {
        val s = _state.value as? UpdateState.Available ?: return
        start(automatic = false) { downloadAndInstall(s.release, s.apk) }
    }

    /**
     * The settings' "Cancel" on a confirmation the user does not want to give:
     * the session is abandoned (its abort report ignored), so a new attempt can start.
     */
    fun cancelConfirmation() {
        if (_state.value !is UpdateState.AwaitingConfirmation) return
        val id = pendingSession
        if (id != 0) {
            ignoredSessions += id
            runCatching { app.packageManager.packageInstaller.abandonSession(id) }
        }
        finish()
        _state.value = UpdateState.Idle
    }

    private fun check(automatic: Boolean) = start(automatic) {
        _state.value = UpdateState.Checking
        val ch = channel
        val releases = try {
            withContext(Dispatchers.IO) { fetchReleases(ch) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or rate-limited: not again at the very next resume, but soon.
            retryAt = System.currentTimeMillis() + FAILED_RETRY_MS
            throw e
        }
        retryAt = 0L
        val now = System.currentTimeMillis()
        lastCheckAt = now
        store.setUpdateCheckedAt(now, buildId)
        when (val d = UpdateLogic.decide(installed, releases, ch)) {
            is UpdateDecision.NoBuilds -> _state.value = UpdateState.NoBuilds(now)
            is UpdateDecision.UpToDate -> _state.value = UpdateState.UpToDate(now, d.latest)
            is UpdateDecision.Available -> {
                val wait = autoUpdate && !unmetered()
                _state.value = UpdateState.Available(now, d.release, d.apk, waitingForUnmetered = wait)
                if (autoUpdate && !wait) downloadAndInstall(d.release, d.apk)
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
                // cancel(): the download may have reported progress since; nothing is in
                // progress now, and a file downloaded for an install that will not happen goes.
                if (_state.value.busy && _state.value !is UpdateState.Installing) {
                    _state.value = UpdateState.Idle
                    downloaded?.delete()
                    downloaded = null
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun fetchReleases(channel: UpdateChannel): List<ReleaseInfo> {
        val endpoint = UpdateSource.endpoint(channel)
        val request = Request.Builder()
            .url(endpoint.url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 429 || (resp.code == 403 && resp.header("X-RateLimit-Remaining") == "0")) {
                throw IOException(app.getString(R.string.update_rate_limited))
            }
            // "No latest release" is an answer, not an error.
            if (resp.code == 404 && endpoint.single) return emptyList()
            if (!resp.isSuccessful) throw IOException(app.getString(R.string.update_http_error, resp.code))
            val body = resp.body?.string() ?: throw IOException(app.getString(R.string.update_http_error, resp.code))
            return if (endpoint.single) listOf(UpdateLogic.parseRelease(body)) else UpdateLogic.parseReleases(body)
        }
    }

    private suspend fun downloadAndInstall(release: ReleaseInfo, apk: ReleaseAsset) {
        _state.value = UpdateState.Downloading(release, 0f)
        val file = withContext(Dispatchers.IO) {
            download(apk) { progress -> _state.value = UpdateState.Downloading(release, progress) }
        }
        downloaded = file
        if (automatic && WakeRuntime.voiceActive.value) {
            // Installing restarts Ara: an automatic install waits for the end of a voice conversation.
            _state.value = UpdateState.Waiting(release)
            withTimeoutOrNull(VOICE_WAIT_MS) { WakeRuntime.voiceActive.first { !it } }
        }
        _state.value = UpdateState.Installing(release)
        Toast.makeText(app, app.getString(R.string.update_installing_toast, release.tag), Toast.LENGTH_LONG).show()
        retireSessions()
        val sessionId = try {
            withContext(Dispatchers.IO) { UpdateInstaller.install(app, file) }
        } catch (e: Exception) {
            file.delete()
            downloaded = null
            throw e
        }
        pendingSession = sessionId
        // From here the outcome arrives in UpdateReceiver: installed (Android
        // restarts Ara), a confirmation to show, or a failure. Should none come,
        // the attempt is given up rather than blocking the next.
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(INSTALL_TIMEOUT_MS)
            val s = _state.value
            if (s is UpdateState.Installing && s.release == release && pendingSession == sessionId) {
                // Whatever the installer still does with the session is no longer this attempt's.
                ignoredSessions += sessionId
                runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }
                finish()
                _state.value = UpdateState.Failed(app.getString(R.string.update_install_no_report))
            }
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

    /**
     * Whether a report is about the attempt in progress, or, with none in
     * progress, about a session an earlier process committed (its dialog was
     * still up, or the process did not live to see the report).
     */
    private fun concerns(sessionId: Int): Boolean =
        sessionId !in ignoredSessions && (pendingSession == 0 || sessionId == pendingSession)

    /** The installer wants the user to confirm (first install through Ara, or "Install unknown apps" still off). */
    fun onUserActionNeeded(sessionId: Int, confirm: Intent?) {
        if (!concerns(sessionId)) return
        if (confirm == null) {
            onInstallFailed(sessionId, PackageInstaller.STATUS_FAILURE, app.getString(R.string.update_no_confirmation))
            return
        }
        val release = (_state.value as? UpdateState.Installing)?.release
        pendingSession = sessionId
        _state.value = UpdateState.AwaitingConfirmation(release, confirm, shown = false)
    }

    fun onInstalled(sessionId: Int) {
        if (!concerns(sessionId)) return
        // The report of the update that produced this very build reaches it here: nothing is pending then.
        val own = sessionId == pendingSession
        finish()
        _state.value = if (own) UpdateState.Installed else UpdateState.Idle
    }

    fun onInstallFailed(sessionId: Int, status: Int, message: String?) {
        if (!concerns(sessionId)) return
        finish()
        _state.value = UpdateState.Failed(installFailure(status, message))
    }

    /** The install attempt is over, one way or the other. */
    private fun finish() {
        watchdog?.cancel()
        watchdog = null
        pendingSession = 0
        downloaded?.delete()
        downloaded = null
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

    /**
     * The active network is unmetered (Wi-Fi, typically; a metered hotspot is
     * not): where an 85 MB download may go unasked. No network is not unmetered.
     */
    private fun unmetered(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun dir(): File = File(app.cacheDir, DIR).apply { mkdirs() }
}
