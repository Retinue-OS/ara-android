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
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The page side of the keyboard: [os.retinue.ara.ui.ShellPage] forwards events to the page. */
fun interface KeyboardListener {
    fun onKeyboardEvent(event: KeyboardEvent)
}

/**
 * Process-wide owner of "Keyboard for other devices": the phone as a
 * Bluetooth keyboard for a device that cannot run Retinue itself (a headset,
 * a TV, someone else's computer).
 *
 * While the user has it on, Ara registers with Android's HID device profile
 * ([BluetoothHidDevice]) as a boot keyboard ([HidKeys.DESCRIPTOR]) and holds
 * a foreground service ([KeyboardService]) so the registration survives Ara
 * going to the background. Once registered it reconnects to the device it
 * last typed into, and again whenever that device comes back. Off, it
 * unregisters and the service leaves.
 *
 * Typing goes one request at a time ([busy]): the whole text is mapped for
 * the device's layout first ([KeyLayouts.map]), so a character the layout
 * lacks is reported before anything is sent, then sent at a steady pace
 * ([KeyPacer]) on a background thread. Nothing typed is logged or kept; a
 * secret ([typeSecret]) is cleared from memory once sent.
 */
@SuppressLint("MissingPermission") // every call goes through [permitted]
object KeyboardRuntime {

    private const val TAG = "KeyboardRuntime"

    private lateinit var app: Context
    private lateinit var prefs: KeyboardPrefs
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val typing = Mutex()

    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var proxyRequested = false
    private var connected: BluetoothDevice? = null
    private var receiverRegistered = false

    private val _status = MutableStateFlow(KeyboardStatus())
    val status: StateFlow<KeyboardStatus> = _status.asStateFlow()

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
        if (on) start() else stop()
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
        KeyboardService.start(app)
        if (hid == null && !proxyRequested && a.isEnabled) {
            proxyRequested = a.getProfileProxy(app, profileListener, BluetoothProfile.HID_DEVICE)
        } else {
            hid?.let(::register)
        }
        publish()
    }

    private fun stop() {
        hid?.let { h ->
            try {
                connected?.let { h.disconnect(it) }
                h.unregisterApp()
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed", e)
            }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null
        proxyRequested = false
        connected = null
        registered = false
        KeyboardService.stop(app)
        publish()
    }

    private var registered = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            hid = h
            if (prefs.enabled) register(h) else adapter?.closeProfileProxy(profile, h)
            publish()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            proxyRequested = false
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
                Log.w(TAG, "registerApp refused")
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerApp failed", e)
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@KeyboardRuntime.registered = registered
            if (registered) reconnect(pluggedDevice)
            publish()
            emit(KeyboardEvent.status)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = device
                    prefs.lastHost = device.address
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (connected?.address == device.address) connected = null
            }
            publish()
            emit(KeyboardEvent.status)
        }

        // Ara does not answer report requests beyond an empty report: a boot
        // keyboard has nothing the host needs to read.
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            try {
                hid?.replyReport(device, type, id, HidKeys.release())
            } catch (e: Exception) {
                Log.w(TAG, "replyReport failed", e)
            }
        }
    }

    /** Connect to [plugged] (a host that asked), else to the device Ara last typed into, if it is paired. */
    private fun reconnect(plugged: BluetoothDevice?) {
        val h = hid ?: return
        if (connected != null) return
        val target = plugged ?: prefs.lastHost?.let { addr -> bonded().firstOrNull { it.address == addr } } ?: return
        try {
            h.connect(target)
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
        }
    }

    /** Connect to the paired device [address] (the keyboard screen's list). */
    fun connect(address: String) {
        val h = hid ?: return
        val device = bonded().firstOrNull { it.address == address } ?: return
        try {
            connected?.takeIf { it.address != address }?.let { h.disconnect(it) }
            h.connect(device)
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
        }
    }

    fun disconnect() {
        val h = hid ?: return
        try {
            connected?.let { h.disconnect(it) }
        } catch (e: Exception) {
            Log.w(TAG, "disconnect failed", e)
        }
    }

    /** Ask Android to make the phone discoverable for a while, so a new device can pair. */
    fun discoverableIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)

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
                        hid = null; proxyRequested = false; registered = false; connected = null
                    }
                }
                // A device Ara typed into came back in range (it connected for something else, or bonded anew).
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> reconnect(null)
            }
            publish()
            emit(KeyboardEvent.status)
        }
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
            val error = send(listOf(HidKeys.Stroke(HidKeys.MOD_NONE, code)), 0)
            emit(if (error == null) KeyboardEvent.typed(id, 1) else KeyboardEvent.error(id, error))
        }
    }

    /**
     * Type a secret from the native dialog. [secret] is the dialog's own
     * buffer: it is overwritten here once sent (or refused), and nothing of
     * it — not even its length — leaves this function except as key presses.
     */
    fun typeSecret(secret: CharArray, enter: Boolean, id: String?) {
        scope.launch {
            val error = try {
                typeNow(java.nio.CharBuffer.wrap(secret), enter)
            } finally {
                secret.fill('\u0000')
            }
            emit(
                if (error == null) KeyboardEvent(KeyboardEvent.SECRET_TYPED, id)
                else KeyboardEvent.error(id, error),
            )
        }
    }

    /**
     * Live mode: [backspaces] deletions, then [text]. Edits are queued and
     * sent one after the other, each waiting for the one before (and for any
     * typing going on) rather than being refused as busy: the live field
     * assumes the device has everything it sent, so none may be dropped. The
     * field only takes characters the layout can type ([KeyLayouts.typeable]).
     */
    fun live(backspaces: Int, text: String) {
        if (liveConsumer == null) {
            liveConsumer = scope.launch { for (edit in liveEdits) sendLive(edit.first, edit.second) }
        }
        liveEdits.trySend(backspaces to text)
    }

    private val liveEdits = Channel<Pair<Int, String>>(Channel.UNLIMITED)
    private var liveConsumer: kotlinx.coroutines.Job? = null

    private suspend fun sendLive(backspaces: Int, text: String) {
        val layout = KeyLayouts.byId(_status.value.layout) ?: return
        val strokes = ArrayList<HidKeys.Stroke>()
        repeat(backspaces.coerceAtMost(KeyboardLogic.MAX_TEXT)) { strokes += HidKeys.Stroke(HidKeys.MOD_NONE, HidKeys.KEY_BACKSPACE) }
        strokes += KeyLayouts.typeableStrokes(text, layout)
        if (strokes.isEmpty()) return
        typing.withLock {
            if (KeyboardLogic.precheck(_status.value.copy(busy = false), 0) != null) return
            transmit(strokes)
        }
    }

    private suspend fun typeNow(text: CharSequence, enter: Boolean): KeyboardError? {
        KeyboardLogic.precheck(_status.value, text.length)?.let { return it }
        val layout = KeyLayouts.byId(_status.value.layout) ?: return KeyboardError.Failed
        val mapped = KeyLayouts.map(text, layout)
        val strokes = when (mapped) {
            is Mapped.Unsupported -> return KeyboardError.UnsupportedChar
            is Mapped.Ok -> mapped.strokes
        }
        val all = if (enter) strokes + HidKeys.Stroke(HidKeys.MOD_NONE, HidKeys.KEY_ENTER) else strokes
        return send(all, text.length)
    }

    /** Send [strokes] with the typing lock held; refuses at once when typing is going on. */
    private suspend fun send(strokes: List<HidKeys.Stroke>, length: Int): KeyboardError? {
        KeyboardLogic.precheck(_status.value, length)?.let { return it }
        if (!typing.tryLock()) return KeyboardError.Busy
        try {
            return transmit(strokes)
        } finally {
            typing.unlock()
        }
    }

    /** Send [strokes] to the connected device; the caller holds [typing]. */
    private suspend fun transmit(strokes: List<HidKeys.Stroke>): KeyboardError? {
        try {
            setBusy(true)
            val h = hid ?: return KeyboardError.NotConnected
            val device = connected ?: return KeyboardError.NotConnected
            val sent = withContext(Dispatchers.IO) {
                KeyPacer.send(strokes) { report ->
                    try {
                        h.sendReport(device, HidKeys.REPORT_ID, report)
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            return if (sent == strokes.size) null else KeyboardError.Failed
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
        val hostAddr = c?.address ?: prefs.lastHost
        val paired = bonded().map { KeyboardHost(name(it), it.address) }.sortedBy { it.name.lowercase() }
        val host = when {
            c != null -> KeyboardHost(name(c), c.address)
            hostAddr != null -> paired.firstOrNull { it.address == hostAddr }
            else -> null
        }
        _status.value = KeyboardStatus(
            available = a != null,
            enabled = prefs.enabled,
            registered = registered,
            connected = c != null && registered,
            host = host,
            layout = prefs.layoutFor(host?.address),
            busy = _status.value.busy,
            permitted = ok,
            paired = paired,
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
