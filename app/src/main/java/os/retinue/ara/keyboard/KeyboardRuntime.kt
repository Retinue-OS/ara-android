package os.retinue.ara.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.nio.CharBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import os.retinue.ara.R
import os.retinue.ara.keyboard.HidKeys.Stroke

/** The page side of the keyboard: [os.retinue.ara.ui.ShellPage] forwards events to the page. */
fun interface KeyboardListener {
    fun onKeyboardEvent(event: KeyboardEvent)
}

/**
 * Process-wide owner of "Keyboard for other devices": the phone as a
 * Bluetooth keyboard for a device that cannot run Retinue itself (a headset,
 * a TV, someone else's computer).
 *
 * While the user has it on, and Bluetooth is on, Ara registers with Android's
 * HID device profile ([BluetoothHidDevice]) as a boot keyboard
 * ([HidKeys.DESCRIPTOR]) and holds a foreground service ([KeyboardService])
 * so the registration survives Ara going to the background. Off, it
 * unregisters and the service leaves.
 *
 * **Which device.** Ara types only into the device the user chose: the one
 * they tapped Connect for (while switching, only that one), or one paired
 * just now — here or in Android's own settings — and afterwards the one it
 * last typed into ([KeyboardLogic.acceptsHost]). Another paired device that
 * connects by itself is disconnected again. Ara reconnects to the chosen one
 * when it shows up (its own ACL or bond broadcast), when Bluetooth comes on,
 * and at start. A Disconnect holds: no device but a newly paired one, until
 * the user connects one again.
 *
 * Typing goes one request at a time: the whole text is mapped for the
 * device's layout first ([KeyLayouts.map]), so a character the layout lacks
 * is reported before anything is sent, then sent at a steady pace
 * ([KeyPacer]) on a background thread, with the host's Caps Lock out of the
 * way ([HidKeys.aroundCapsLock]). [cancelTyping] stops it. Nothing typed is
 * logged or kept; a secret ([typeSecret]) and its reports are overwritten
 * once sent.
 */
@SuppressLint("MissingPermission") // every call goes through [permitted]
object KeyboardRuntime {

    private const val TAG = "KeyboardRuntime"

    /** How long after it bonded a new device may connect and become the chosen one. */
    private const val BOND_WINDOW_MS = 3 * 60 * 1000L

    /** How long a Connect waits for its device before it no longer shuts the others out. */
    private const val PENDING_MS = 30 * 1000L

    /** How long the HID profile has to answer before the phone counts as without it. */
    private const val PROXY_TIMEOUT_MS = 10 * 1000L

    /** A secret's typing is held to a multiple of this ([KeyboardLogic.secretPad]). */
    const val SECRET_BUCKET_MS = 3000L

    private lateinit var app: Context
    private lateinit var prefs: KeyboardPrefs
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val typing = Mutex()

    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var proxyRequested = false
    private var hidUnsupported = false
    private var registered = false
    private var refused = false
    private var connected: BluetoothDevice? = null
    private var pendingConnect: String? = null
    private var pendingSince = 0L
    private val recentBonds = HashMap<String, Long>()
    private var receiverRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null

    /** The host's LED report (Caps Lock among them), as it last sent it. */
    @Volatile
    private var leds: Byte = 0

    /** Set to stop typing; the paced sender checks it before every character. */
    @Volatile
    private var cancelRequested = false

    /** Whether the stop was the user's (`cancelled`) or the device going (`failed`). */
    @Volatile
    private var cancelByUser = false

    /** A secret is being typed: the page's cancel does not reach it. */
    @Volatile
    private var secretTyping = false

    /**
     * The one window of the keyboard's own — the confirmation or the
     * password dialog — open or on its way: the page types nothing
     * meanwhile, and a request is `busy`. Main thread, as everything the
     * page's requests touch here.
     */
    private val windows = KeyboardWindows { SystemClock.elapsedRealtime() }

    /** Confirmations not approved in a row, and the pause they bring. */
    private val streak = ConfirmStreak { SystemClock.elapsedRealtime() }

    /** Characters typed for a page without a confirmation, per device, since the last approval. */
    private val unconfirmed = UnconfirmedCounts()

    /** The strip's Stop was pressed: the next page request asks first, whatever the device's mode. */
    private var stopPending = false

    /** The request waiting for the user's approval, while there is one. */
    private var pending: PendingConfirmation? = null

    /** The window that registered ([windowOpened]) is the pending confirmation's. */
    private var pendingShown = false

    /** ConfirmTypeActivity and SecretTypeActivity register while open ([closer] cancels it). */
    fun windowOpened(closer: () -> Unit) = windows.opened(closer)

    fun windowClosed(closer: () -> Unit) = windows.closed(closer)

    /** The window reserved for a request could not be started: free it again. */
    fun windowFailed() = windows.abandon()

    private val _status = MutableStateFlow(KeyboardStatus())
    val status: StateFlow<KeyboardStatus> = _status.asStateFlow()

    /**
     * Bumped when live typing failed or was stopped: the keyboard screen's
     * field no longer matches what the device has, and starts over.
     */
    private val _liveResets = MutableStateFlow(0)
    val liveResets: StateFlow<Int> = _liveResets.asStateFlow()

    /**
     * A page typing on a device, for the strip over the dashboard
     * (MainActivity): which device, whether it is typing now, and when the
     * last key the page caused went out — a single key is over in 24 ms and
     * must still show. Null until a page first types.
     */
    data class PageTyping(val device: String, val typing: Boolean, val at: Long)

    private val _pageTyping = MutableStateFlow<PageTyping?>(null)
    val pageTyping: StateFlow<PageTyping?> = _pageTyping.asStateFlow()

    /** The page showing (see ShellPage); events with nobody to take them are dropped. */
    @Volatile
    var listener: KeyboardListener? = null

    fun init(context: Context) {
        app = context.applicationContext
        prefs = KeyboardPrefs(app)
        adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (prefs.enabled) start() else publish()
    }

    /** Android's Bluetooth permissions (Android 12+: connect, and advertise for discoverability). */
    fun permitted(): Boolean = PERMISSIONS.all(::granted)

    /** What making the phone discoverable needs on top (asked for only then). */
    fun mayAdvertise(): Boolean = granted(Manifest.permission.BLUETOOTH_ADVERTISE)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** What switching the keyboard on needs: connecting (registering, typing). */
    val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)

    val ADVERTISE_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE)

    // ── On and off ──────────────────────────────────────────────────────────

    /** The user's switch. On needs the permissions; the caller asks for them first. */
    fun setEnabled(on: Boolean) {
        prefs.enabled = on
        if (on) {
            prefs.holdConnections = false
            start()
        } else {
            stop()
        }
        emit(KeyboardEvent.status)
    }

    /** Permissions were granted (or the adapter came on): try again. */
    fun retry() {
        if (prefs.enabled) start()
        publish()
    }

    private fun start() {
        val a = adapter
        if (a == null || !permitted()) return publish()
        watchBluetooth()
        // With Bluetooth off there is nothing to hold: no service, no
        // notification, until it comes on (btReceiver).
        if (!a.isEnabled) return publish()
        KeyboardService.start(app)
        if (hid == null && !proxyRequested) {
            proxyRequested = a.getProfileProxy(app, profileListener, BluetoothProfile.HID_DEVICE)
            // Refused, or never answered: this phone has no HID device profile
            // (getProfileProxy itself says yes for any known profile).
            hidUnsupported = !proxyRequested
            if (proxyRequested) {
                main.postDelayed({
                    if (hid == null && proxyRequested) {
                        hidUnsupported = true
                        publish()
                        emit(KeyboardEvent.status)
                    }
                }, PROXY_TIMEOUT_MS)
            }
        } else {
            hid?.let(::register)
        }
        publish()
    }

    private fun stop() {
        cancelTyping()
        unconfirmed.clear()
        hid?.let { h ->
            try {
                connected?.let { h.disconnect(it) }
                h.unregisterApp()
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed", e)
            }
        }
        dropProxy()
        connected = null
        pendingConnect = null
        registered = false
        refused = false
        KeyboardService.stop(app)
        publish()
    }

    /** Give the profile proxy back — on every way out, so none leaks. */
    private fun dropProxy() {
        val h = hid
        hid = null
        proxyRequested = false
        if (h != null) {
            try {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
            } catch (e: Exception) {
                Log.w(TAG, "closeProfileProxy failed", e)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            if (hid != null && hid !== h) {
                // A second binding of the same profile: keep the first.
                try {
                    adapter?.closeProfileProxy(profile, h)
                } catch (e: Exception) {
                    Log.w(TAG, "closeProfileProxy failed", e)
                }
                return
            }
            hid = h
            hidUnsupported = false
            if (prefs.enabled) register(h) else dropProxy()
            publish()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            dropProxy()
            registered = false
            connected = null
            publish()
            emit(KeyboardEvent.status)
        }
    }

    private fun register(h: BluetoothHidDevice) {
        if (registered || !permitted()) return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Ara keyboard",
            "Retinue, typing for you",
            "Retinue",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HidKeys.DESCRIPTOR,
        )
        try {
            if (!h.registerApp(sdp, null, null, mainExecutor, callback)) {
                // Only one app at a time may act as a HID device.
                refused = true
                Log.w(TAG, "registerApp refused")
            }
        } catch (e: Exception) {
            refused = true
            Log.w(TAG, "registerApp failed", e)
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@KeyboardRuntime.registered = registered
            if (!registered && connected != null) {
                // No keyboard, no connection — whether or not the stack still
                // says so: a device left standing would keep reconnect() off.
                connected?.let { unconfirmed.reset(it.address) }
                connected = null
                leds = 0
                abortTyping()
            }
            // Unregistered while on, with the proxy still there: someone else has the role.
            refused = !registered && prefs.enabled && hid != null
            if (registered) reconnect()
            publish()
            emit(KeyboardEvent.status)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val bondedAt = recentBonds[device.address]
                    val recentlyBonded = bondedAt != null &&
                        SystemClock.elapsedRealtime() - bondedAt < BOND_WINDOW_MS
                    if (KeyboardLogic.acceptsHost(device.address, pending(), prefs.holdConnections,
                            recentlyBonded, prefs.lastHost)) {
                        connected = device
                        pendingConnect = null
                        recentBonds.remove(device.address)
                        // A new host: its Caps Lock is its own, and unknown until it says.
                        leds = 0
                        prefs.lastHost = device.address
                        prefs.holdConnections = false
                        prefs.rememberLayout(device.address, KeyLayouts.defaultFor(Locale.getDefault()))
                    } else {
                        // A device the user did not choose connected by itself.
                        try {
                            hid?.disconnect(device)
                        } catch (e: Exception) {
                            Log.w(TAG, "disconnect failed", e)
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // A disconnect ends what the page typed there unasked: the count starts over.
                    unconfirmed.reset(device.address)
                    if (connected?.address == device.address) {
                        connected = null
                        leds = 0
                        abortTyping()
                    }
                    // Switching hosts: the connect follows the disconnection.
                    pending()?.takeIf { it != device.address && connected == null }?.let(::connectNow)
                }
            }
            publish()
            emit(KeyboardEvent.status)
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            try {
                when (type) {
                    BluetoothHidDevice.REPORT_TYPE_INPUT -> h.replyReport(device, type, id, HidKeys.release())
                    BluetoothHidDevice.REPORT_TYPE_OUTPUT -> h.replyReport(device, type, id, byteArrayOf(leds))
                    else -> h.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                }
            } catch (e: Exception) {
                Log.w(TAG, "replyReport failed", e)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            val h = hid ?: return
            try {
                if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) {
                    HidKeys.ledByte(data, id.toInt())?.let { leds = it }
                    h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
                } else {
                    h.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                }
            } catch (e: Exception) {
                Log.w(TAG, "reportError failed", e)
            }
        }

        // The LED output report over the interrupt channel.
        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            HidKeys.ledByte(data, reportId.toInt())?.let { leds = it }
        }
    }

    /**
     * Connect to the device the user chose — not while they hold Ara off,
     * nor while connected, nor while they are switching to another one.
     */
    private fun reconnect() {
        if (connected != null || prefs.holdConnections || pending() != null) return
        prefs.lastHost?.let(::connectNow)
    }

    /** The device a Connect is waiting for, or null once it has waited [PENDING_MS]. */
    private fun pending(): String? = pendingConnect?.takeIf {
        SystemClock.elapsedRealtime() - pendingSince < PENDING_MS
    }

    private fun connectNow(address: String) {
        val h = hid ?: return
        val device = bonded().firstOrNull { it.address == address } ?: return
        try {
            h.connect(device)
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
        }
    }

    /** The user connects the paired device [address] (the keyboard screen's list). */
    fun connect(address: String) {
        val h = hid ?: return
        if (bonded().none { it.address == address }) return
        prefs.holdConnections = false
        pendingConnect = address
        pendingSince = SystemClock.elapsedRealtime()
        val current = connected
        try {
            when {
                current == null -> connectNow(address)
                current.address != address -> h.disconnect(current) // connectNow follows (STATE_DISCONNECTED)
                else -> pendingConnect = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
        }
        publish()
    }

    /** The user disconnects: Ara stays off every device until they connect one again. */
    fun disconnect() {
        prefs.holdConnections = true
        pendingConnect = null
        cancelTyping()
        val h = hid
        try {
            connected?.let { h?.disconnect(it) }
        } catch (e: Exception) {
            Log.w(TAG, "disconnect failed", e)
        }
        publish()
        emit(KeyboardEvent.status)
    }

    /**
     * Ask Android to make the phone discoverable for a while, so a new device
     * can pair; once it has bonded it may connect and become the chosen one
     * ([BOND_WINDOW_MS]) — whether it paired this way or in Android's own
     * settings.
     */
    fun discoverableIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)

    fun setLayout(address: String, layout: String) {
        prefs.setLayout(address, layout)
        publish()
        emit(KeyboardEvent.status)
    }

    /** What the phone asks before a page types on [address] (the keyboard screen). */
    fun setConfirmMode(address: String, mode: ConfirmMode) {
        prefs.setConfirmMode(address, mode)
        publish()
    }

    private fun bonded(): List<BluetoothDevice> = try {
        if (permitted()) adapter?.bondedDevices?.toList().orEmpty() else emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    // ── Bluetooth coming and going ─────────────────────────────────────────

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (s == BluetoothAdapter.STATE_ON && prefs.enabled) start()
                    if (s == BluetoothAdapter.STATE_OFF) {
                        abortTyping()
                        unconfirmed.clear()
                        dropProxy()
                        registered = false
                        connected = null
                        pendingConnect = null
                        KeyboardService.stop(app)
                    }
                }
                // Only the chosen device coming back in range (or bonded anew) brings a reconnect.
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = deviceExtra(intent)
                    val bondChange = intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED
                    val bonded = !bondChange ||
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE) ==
                        BluetoothDevice.BOND_BONDED
                    // A device paired just now may connect and be chosen for a while.
                    if (device != null && bondChange && bonded) {
                        recentBonds[device.address] = SystemClock.elapsedRealtime()
                    }
                    if (device != null && bonded && device.address == prefs.lastHost) reconnect()
                }
            }
            publish()
            emit(KeyboardEvent.status)
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceExtra(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun watchBluetooth() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Exported: these broadcasts come from the Bluetooth stack's own
        // process (its uid is not the system's), which a not-exported
        // receiver does not hear. All three are protected broadcasts that
        // only the system may send, so exporting opens nothing.
        ContextCompat.registerReceiver(app, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    // ── A page's requests ───────────────────────────────────────────────────

    /**
     * Every keyboard request of a page comes here, and only here
     * (MainActivity gathers [facts]): [KeyboardLogic.decide] says what
     * becomes of it, and this carries it out — types at once, reserves the
     * window for a confirmation or the password dialog, or answers why not.
     * What the window showing the page still has to do (start an activity)
     * is the [PageOutcome]. Main thread.
     */
    fun fromPage(request: PageRequest, facts: PageFacts): PageOutcome {
        val status = _status.value
        val decision = KeyboardLogic.decide(
            request = request,
            serverAllows = facts.serverAllows,
            status = status,
            inFront = facts.inFront,
            windowOpen = windows.open,
            mode = status.confirmMode,
            stopPending = stopPending,
            confirmPaused = streak.paused,
        )
        return when (decision) {
            is PageDecision.Refuse -> {
                emit(KeyboardEvent.error(request.id, decision.error))
                PageOutcome.Done
            }
            PageDecision.Cancel -> {
                cancelFromPage()
                PageOutcome.Done
            }
            PageDecision.OpenScreen -> PageOutcome.OpenScreen
            PageDecision.TypeNow -> {
                typeForPage(request, expectedHost = null)
                PageOutcome.Done
            }
            PageDecision.OpenSecret -> {
                windows.ask()
                // The dialog asks: that is the one question a Stop called for.
                stopPending = false
                PageOutcome.OpenSecret(status.host?.address, facts.serverHost, unconfirmed.of(status.host?.address))
            }
            PageDecision.Confirm -> confirm(request, status, facts)
        }
    }

    /** Hold [request] for the user's approval; the window is reserved now, and answers once whatever happens. */
    private fun confirm(request: PageRequest, status: KeyboardStatus, facts: PageFacts): PageOutcome {
        val host = status.host ?: return PageOutcome.Done.also { emit(KeyboardEvent.error(request.id, KeyboardError.NotConnected)) }
        windows.ask()
        stopPending = false
        val token = UUID.randomUUID().toString()
        pending = PendingConfirmation(
            token = token,
            request = request,
            hostAddress = host.address,
            hostName = KeyboardLogic.cleanLabel(host.name),
            serverHost = facts.serverHost,
            unconfirmed = unconfirmed.of(host.address),
        )
        pendingShown = false
        // Its window never came up, or never ended: the page still hears an end.
        main.postDelayed({ if (pending?.token == token && !pendingShown) settle(token, approved = false) }, KeyboardLogic.WINDOW_OPENING_MS)
        main.postDelayed({ settle(token, approved = false) }, KeyboardLogic.confirmTimeoutMs(request) + CONFIRM_GRACE_MS)
        return PageOutcome.Confirm(token)
    }

    /** The confirmation [token] while it waits for an answer; null once answered (or after a restart). */
    fun confirmation(token: String?): PendingConfirmation? = pending?.takeIf { it.token == token }

    /** ConfirmTypeActivity is up for [token] ([closer] closes it, as Don't type would). */
    fun confirmationShown(token: String, closer: () -> Unit): Boolean {
        if (confirmation(token) == null) return false
        pendingShown = true
        windows.opened(closer)
        return true
    }

    /** The user approved [token]: exactly that request, once, and only into the device it named. */
    fun confirmApproved(token: String) = settle(token, approved = true)

    /** Declined, left, timed out or closed: the page hears `cancelled`. Nothing is typed. */
    fun confirmDeclined(token: String) = settle(token, approved = false)

    private fun settle(token: String, approved: Boolean) {
        val p = confirmation(token) ?: return
        pending = null
        if (!pendingShown) windows.abandon()
        if (approved) {
            streak.approved()
            unconfirmed.reset(p.hostAddress)
            typeForPage(p.request, expectedHost = p.hostAddress)
        } else {
            streak.notApproved()
            emit(KeyboardEvent.error(p.request.id, KeyboardError.Cancelled))
        }
    }

    /** The strip's Stop: what is being typed stops, and the next request of the page asks first. */
    fun stopFromStrip() {
        stopPending = true
        cancelTyping()
    }

    // ── Typing ──────────────────────────────────────────────────────────────

    /**
     * Type a page's text or key into the connected device; the outcome comes
     * as a `keyboard` event with its id. Approved ([expectedHost], the device
     * the confirmation named): only into that device. Otherwise unasked, and
     * counted for the next confirmation ([UnconfirmedCounts]).
     */
    private fun typeForPage(request: PageRequest, expectedHost: String?) {
        val unasked = if (expectedHost == null) KeyboardLogic.unconfirmedChars(request) else 0
        when (request) {
            is PageRequest.Type -> scope.launch {
                val error = typeNow(request.text, request.enter, expectedHost, unasked)
                emit(
                    if (error == null) KeyboardEvent.typed(request.id, KeyboardLogic.charCount(request.text))
                    else KeyboardEvent.error(request.id, error),
                )
            }
            is PageRequest.Key -> {
                val code = HidKeys.NAMED[request.name] ?: return
                scope.launch {
                    val error = if (expectedHost != null && connected?.address != expectedHost) KeyboardError.NotConnected
                    else send(listOf(Stroke(HidKeys.MOD_NONE, code)), 0, PageSend(unasked))
                    emit(if (error == null) KeyboardEvent.typed(request.id, 1) else KeyboardEvent.error(request.id, error))
                }
            }
            else -> Unit
        }
    }

    /** Sent for a page: shown on the strip; [unasked] characters go to the device's count once handed over. */
    private class PageSend(val unasked: Int)

    /** How much longer than its own window's timeout the runtime waits before it answers a confirmation itself. */
    private const val CONFIRM_GRACE_MS = 2000L

    /**
     * The phone's own Stop (the keyboard screen): whatever is being typed —
     * a secret too — stops before its next character, every key is released,
     * and the request ends as `cancelled`. Queued live edits are dropped.
     */
    fun cancelTyping() = requestCancel(byUser = true)

    /**
     * The page's `keyboardCancel()`: closes an open secret dialog
     * (`secret-cancelled`) and stops a text being typed — but never a secret
     * being typed: the page cutting it short would learn its length, and
     * leave part of the password on the device.
     */
    fun cancelFromPage() {
        windows.close()
        pending?.let { settle(it.token, approved = false) }
        if (!secretTyping) requestCancel(byUser = true)
    }

    /** The device went (or Bluetooth): typing stops, and ends as `failed`. */
    private fun abortTyping() = requestCancel(byUser = false)

    private fun requestCancel(byUser: Boolean) {
        if (typing.isLocked) {
            cancelByUser = byUser
            cancelRequested = true
        }
        var dropped = false
        while (liveEdits.tryReceive().isSuccess) dropped = true
        // The live field no longer matches the device: it starts over.
        if (dropped) _liveResets.value = _liveResets.value + 1
    }

    /**
     * Type a secret from the native dialog into the device [expectedHost] —
     * the one the dialog named; if another is connected by now, nothing is
     * typed. [secret] is the dialog's own buffer: it is overwritten here once
     * sent (or refused). The page hears only `secret-typed` or an error that
     * says nothing about the secret ([KeyboardLogic.secretErrorForPage]); the
     * user sees on the phone why it failed.
     */
    fun typeSecret(secret: CharArray, enter: Boolean, id: String?, expectedHost: String?) {
        // Typed with the user's own say: what the page typed there unasked is behind them.
        expectedHost?.let(unconfirmed::reset)
        scope.launch {
            val error = try {
                typeSecretNow(secret, enter, expectedHost)
            } finally {
                secret.fill('\u0000')
            }
            if (error == KeyboardError.UnsupportedChar || error == KeyboardError.TooLong) {
                Toast.makeText(app, R.string.keyboard_secret_unsupported, Toast.LENGTH_LONG).show()
            }
            emit(
                if (error == null) KeyboardEvent(KeyboardEvent.SECRET_TYPED, id)
                else KeyboardEvent.error(id, KeyboardLogic.secretErrorForPage(error)),
            )
        }
    }

    private suspend fun typeSecretNow(secret: CharArray, enter: Boolean, expectedHost: String?): KeyboardError? {
        if (!typing.tryLock()) return KeyboardError.Busy
        val started = SystemClock.elapsedRealtime()
        secretTyping = true
        try {
            KeyboardLogic.precheck(_status.value.copy(busy = false), secret.size, KeyboardLogic.MAX_SECRET)?.let { return it }
            if (expectedHost != null && connected?.address != expectedHost) return KeyboardError.NotConnected
            val layout = KeyLayouts.byId(_status.value.layout) ?: return KeyboardError.Failed
            val mapped = KeyLayouts.map(CharBuffer.wrap(secret), layout)
            // The characters are not needed past this point: the key presses are.
            secret.fill('\u0000')
            val strokes = when (mapped) {
                is Mapped.Unsupported -> return KeyboardError.UnsupportedChar
                is Mapped.Ok -> ArrayList(mapped.strokes).also { (mapped.strokes as? MutableList)?.clear() }
            }
            if (enter) strokes += Stroke(HidKeys.MOD_NONE, HidKeys.KEY_ENTER)
            return try {
                transmit(strokes, PageSend(0))
            } finally {
                strokes.clear()
            }
        } finally {
            secretTyping = false
            // Held — busy to every other request — to the next multiple of
            // SECRET_BUCKET_MS, whatever happened, stopped or not: the time a
            // page can measure says little about the secret's length or content.
            delay(KeyboardLogic.secretPad(SystemClock.elapsedRealtime() - started, SECRET_BUCKET_MS))
            typing.unlock()
        }
    }

    private sealed class LiveEdit {
        data class Diff(val backspaces: Int, val text: String) : LiveEdit()
        data class Key(val code: Int) : LiveEdit()
    }

    private val liveEdits = Channel<LiveEdit>(Channel.UNLIMITED)
    private var liveConsumer: Job? = null

    /**
     * Live mode: [backspaces] deletions, then [text]. Edits — and the screen's
     * key buttons ([liveKey]) — go through one queue, sent one after the
     * other, each waiting for the one before (and for any typing going on)
     * rather than being refused as busy: the live field assumes the device has
     * everything it sent. When an edit fails anyway (no device, stopped),
     * the queue is dropped and [liveResets] tells the field to start over.
     */
    fun live(backspaces: Int, text: String) = enqueueLive(LiveEdit.Diff(backspaces, text))

    /** A key button on the keyboard screen, in order with the live field's edits. */
    fun liveKey(name: String) {
        HidKeys.NAMED[name]?.let { enqueueLive(LiveEdit.Key(it)) }
    }

    private fun enqueueLive(edit: LiveEdit) {
        if (liveConsumer == null) {
            liveConsumer = scope.launch { for (e in liveEdits) sendLive(e) }
        }
        liveEdits.trySend(edit)
    }

    private suspend fun sendLive(edit: LiveEdit) {
        val layout = KeyLayouts.byId(_status.value.layout) ?: return
        val strokes = when (edit) {
            is LiveEdit.Diff -> buildList {
                repeat(edit.backspaces.coerceAtMost(KeyboardLogic.MAX_TEXT)) { add(Stroke(HidKeys.MOD_NONE, HidKeys.KEY_BACKSPACE)) }
                addAll(KeyLayouts.typeableStrokes(edit.text.take(KeyboardLogic.MAX_TEXT), layout))
            }
            is LiveEdit.Key -> listOf(Stroke(HidKeys.MOD_NONE, edit.code))
        }
        if (strokes.isEmpty()) return
        val error = typing.withLock {
            KeyboardLogic.precheck(_status.value.copy(busy = false), 0) ?: transmit(strokes)
        }
        if (error != null && edit is LiveEdit.Diff) {
            while (liveEdits.tryReceive().isSuccess) { /* dropped: the field starts over */ }
            _liveResets.value = _liveResets.value + 1
        }
    }

    private suspend fun typeNow(text: CharSequence, enter: Boolean, expectedHost: String?, unasked: Int): KeyboardError? {
        KeyboardLogic.precheck(_status.value, text.length)?.let { return it }
        if (expectedHost != null && connected?.address != expectedHost) return KeyboardError.NotConnected
        val layout = KeyLayouts.byId(_status.value.layout) ?: return KeyboardError.Failed
        val strokes = when (val mapped = KeyLayouts.map(text, layout)) {
            is Mapped.Unsupported -> return KeyboardError.UnsupportedChar
            is Mapped.Ok -> mapped.strokes
        }
        val all = if (enter) strokes + Stroke(HidKeys.MOD_NONE, HidKeys.KEY_ENTER) else strokes
        return send(all, text.length, PageSend(unasked))
    }

    /** Send [strokes] with the typing lock held; refuses at once when typing is going on. */
    private suspend fun send(strokes: List<Stroke>, length: Int, page: PageSend?): KeyboardError? {
        KeyboardLogic.precheck(_status.value, length)?.let { return it }
        if (!typing.tryLock()) return KeyboardError.Busy
        try {
            return transmit(strokes, page)
        } finally {
            typing.unlock()
        }
    }

    /**
     * Send [strokes] to the connected device; the caller holds [typing]. The
     * phone is kept awake meanwhile: a press whose release came only after
     * the phone slept would repeat on the other device. For a [page], the
     * strip shows it, and its unasked characters are counted as handed over
     * (a text stopped halfway counts whole).
     */
    private suspend fun transmit(strokes: List<Stroke>, page: PageSend? = null): KeyboardError? {
        cancelRequested = false
        cancelByUser = false
        val lock = typingWakeLock()
        var shown: PageTyping? = null
        try {
            lock?.acquire(WAKE_LOCK_MAX_MS)
            setBusy(true)
            val h = hid ?: return KeyboardError.NotConnected
            val device = connected ?: return KeyboardError.NotConnected
            if (page != null) {
                unconfirmed.add(device.address, page.unasked)
                shown = PageTyping(KeyboardLogic.cleanLabel(name(device)) ?: device.address, typing = true, at = SystemClock.elapsedRealtime())
                _pageTyping.value = shown
            }
            val capsOn = HidKeys.capsLockOn(leds)
            val all = HidKeys.aroundCapsLock(strokes, capsOn)
            val report: (ByteArray) -> Boolean = { r ->
                try {
                    h.sendReport(device, HidKeys.REPORT_ID, r)
                } catch (e: Exception) {
                    false
                }
            }
            val sent = withContext(Dispatchers.IO) {
                KeyPacer.send(all, cancelled = { cancelRequested }, send = report)
            }
            if (capsOn && sent in 1 until all.size) {
                // Stopped halfway: Caps Lock back as the host had it.
                withContext(Dispatchers.IO) { KeyPacer.send(listOf(all.last()), send = report) }
            }
            val complete = sent == all.size
            if (all !== strokes) (all as? MutableList)?.clear()
            return when {
                complete -> null
                cancelRequested && cancelByUser -> KeyboardError.Cancelled
                else -> KeyboardError.Failed
            }
        } finally {
            setBusy(false)
            shown?.let { _pageTyping.value = it.copy(typing = false, at = SystemClock.elapsedRealtime()) }
            if (lock?.isHeld == true) lock.release()
        }
    }

    private const val WAKE_LOCK_MAX_MS = 10 * 60 * 1000L

    private fun typingWakeLock(): PowerManager.WakeLock? {
        wakeLock?.let { return it }
        return app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ara:keyboard")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
    }

    private fun setBusy(on: Boolean) {
        _status.value = _status.value.copy(busy = on)
    }

    // ── Status ──────────────────────────────────────────────────────────────

    private fun publish() {
        val a = adapter
        val ok = permitted()
        val c = connected
        val paired = bonded().map { KeyboardHost(name(it), it.address) }.sortedBy { it.name.lowercase() }
        val chosen = c?.address ?: prefs.lastHost.takeUnless { prefs.holdConnections }
        val host = when {
            c != null -> KeyboardHost(name(c), c.address)
            chosen != null -> paired.firstOrNull { it.address == chosen }
            else -> null
        }
        _status.value = KeyboardStatus(
            available = a != null && !hidUnsupported,
            enabled = prefs.enabled,
            registered = registered,
            connected = c != null && registered,
            host = host,
            layout = prefs.layoutFor(host?.address),
            busy = _status.value.busy,
            permitted = ok,
            paired = paired,
            bluetoothOn = a?.isEnabled == true,
            refused = refused,
            held = prefs.holdConnections,
            confirmMode = prefs.confirmModeFor(host?.address),
        )
    }

    private fun name(d: BluetoothDevice): String = try {
        d.alias ?: d.name ?: d.address
    } catch (e: SecurityException) {
        d.address
    }

    private fun emit(event: KeyboardEvent) {
        main.post { listener?.onKeyboardEvent(event) }
    }
}
