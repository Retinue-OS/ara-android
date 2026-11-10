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
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.nio.CharBuffer
import java.util.Locale
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
 * they tapped Connect for, or paired just now, and afterwards the one it
 * last typed into ([KeyboardLogic.acceptsHost]). Another paired device that
 * connects by itself is disconnected again. Ara reconnects to the chosen one
 * when it shows up (its own ACL or bond broadcast), when Bluetooth comes on,
 * and at start. A Disconnect holds: no device, chosen or not, until the user
 * connects one again.
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

    /** How long after "Make this phone discoverable" a new device may connect and be chosen. */
    private const val PAIRING_WINDOW_MS = 3 * 60 * 1000L

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
    private var pairingUntil = 0L
    private var receiverRegistered = false

    /** The host's LED report (Caps Lock among them), as it last sent it. */
    @Volatile
    private var leds: Byte = 0

    /** Set by [cancelTyping]; the paced sender checks it before every press. */
    @Volatile
    private var cancelRequested = false

    private val _status = MutableStateFlow(KeyboardStatus())
    val status: StateFlow<KeyboardStatus> = _status.asStateFlow()

    /**
     * Bumped when live typing failed or was stopped: the keyboard screen's
     * field no longer matches what the device has, and starts over.
     */
    private val _liveResets = MutableStateFlow(0)
    val liveResets: StateFlow<Int> = _liveResets.asStateFlow()

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
    fun permitted(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)

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
            // Refused with Bluetooth on: this phone has no HID device profile.
            hidUnsupported = !proxyRequested
        } else {
            hid?.let(::register)
        }
        publish()
    }

    private fun stop() {
        cancelTyping()
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
            // Unregistered while on, with the proxy still there: someone else has the role.
            refused = !registered && prefs.enabled && hid != null
            if (registered) reconnect()
            publish()
            emit(KeyboardEvent.status)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val pairing = SystemClock.elapsedRealtime() < pairingUntil
                    if (KeyboardLogic.acceptsHost(device.address, pendingConnect, pairing,
                            prefs.holdConnections, prefs.lastHost)) {
                        connected = device
                        pendingConnect = null
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
                    if (connected?.address == device.address) {
                        connected = null
                        cancelTyping()
                    }
                    // Switching hosts: the connect follows the disconnection.
                    pendingConnect?.takeIf { it != device.address && connected == null }?.let(::connectNow)
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

    /** Connect to the device the user chose — not while they hold Ara off, nor while connected. */
    private fun reconnect() {
        if (connected != null || prefs.holdConnections) return
        prefs.lastHost?.let(::connectNow)
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
     * can pair — and, for [PAIRING_WINDOW_MS], connect and become the chosen one.
     */
    fun discoverableIntent(): Intent {
        pairingUntil = SystemClock.elapsedRealtime() + PAIRING_WINDOW_MS
        prefs.holdConnections = false
        return Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
    }

    fun setLayout(address: String, layout: String) {
        prefs.setLayout(address, layout)
        publish()
        emit(KeyboardEvent.status)
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
                        cancelTyping()
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
                    val bonded = intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED ||
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE) ==
                        BluetoothDevice.BOND_BONDED
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

    // ── Typing ──────────────────────────────────────────────────────────────

    /** Type [text] into the connected device; the outcome comes as a `keyboard` event with [id]. */
    fun type(text: String, enter: Boolean, id: String?) {
        scope.launch {
            val error = typeNow(text, enter)
            emit(if (error == null) KeyboardEvent.typed(id, text.length) else KeyboardEvent.error(id, error))
        }
    }

    /** Press one named key (HidKeys.NAMED); the outcome comes as a `keyboard` event with [id]. */
    fun key(name: String, id: String? = null) {
        val code = HidKeys.NAMED[name] ?: return
        scope.launch {
            val error = send(listOf(Stroke(HidKeys.MOD_NONE, code)), 0)
            emit(if (error == null) KeyboardEvent.typed(id, 1) else KeyboardEvent.error(id, error))
        }
    }

    /**
     * Stop what is being typed (`keyboardCancel()`, the keyboard screen's
     * Stop, a disconnection): the paced sender stops before its next press
     * and releases every key; the request ends as `cancelled`. Queued live
     * edits are dropped.
     */
    fun cancelTyping() {
        if (typing.isLocked) cancelRequested = true
        while (liveEdits.tryReceive().isSuccess) { /* dropped */ }
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
        cancelRequested = false // a stale Stop must not skip the padding below
        try {
            KeyboardLogic.precheck(_status.value.copy(busy = false), secret.size, KeyboardLogic.MAX_SECRET)?.let { return it }
            if (expectedHost != null && connected?.address != expectedHost) return KeyboardError.NotConnected
            val layout = KeyLayouts.byId(_status.value.layout) ?: return KeyboardError.Failed
            val strokes = when (val mapped = KeyLayouts.map(CharBuffer.wrap(secret), layout)) {
                is Mapped.Unsupported -> return KeyboardError.UnsupportedChar
                is Mapped.Ok -> ArrayList(mapped.strokes)
            }
            if (enter) strokes += Stroke(HidKeys.MOD_NONE, HidKeys.KEY_ENTER)
            return try {
                transmit(strokes)
            } finally {
                strokes.clear()
            }
        } finally {
            // Held — busy to every other request — to the next multiple of
            // SECRET_BUCKET_MS, whatever happened: the time a page can
            // measure says little about the secret's length or content.
            if (!cancelRequested) {
                delay(KeyboardLogic.secretPad(SystemClock.elapsedRealtime() - started, SECRET_BUCKET_MS))
            }
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

    private suspend fun typeNow(text: CharSequence, enter: Boolean): KeyboardError? {
        KeyboardLogic.precheck(_status.value, text.length)?.let { return it }
        val layout = KeyLayouts.byId(_status.value.layout) ?: return KeyboardError.Failed
        val strokes = when (val mapped = KeyLayouts.map(text, layout)) {
            is Mapped.Unsupported -> return KeyboardError.UnsupportedChar
            is Mapped.Ok -> mapped.strokes
        }
        val all = if (enter) strokes + Stroke(HidKeys.MOD_NONE, HidKeys.KEY_ENTER) else strokes
        return send(all, text.length)
    }

    /** Send [strokes] with the typing lock held; refuses at once when typing is going on. */
    private suspend fun send(strokes: List<Stroke>, length: Int): KeyboardError? {
        KeyboardLogic.precheck(_status.value, length)?.let { return it }
        if (!typing.tryLock()) return KeyboardError.Busy
        try {
            return transmit(strokes)
        } finally {
            typing.unlock()
        }
    }

    /** Send [strokes] to the connected device; the caller holds [typing]. */
    private suspend fun transmit(strokes: List<Stroke>): KeyboardError? {
        cancelRequested = false
        try {
            setBusy(true)
            val h = hid ?: return KeyboardError.NotConnected
            val device = connected ?: return KeyboardError.NotConnected
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
            return when {
                sent == all.size -> null
                cancelRequested -> KeyboardError.Cancelled
                else -> KeyboardError.Failed
            }
        } finally {
            setBusy(false)
        }
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
