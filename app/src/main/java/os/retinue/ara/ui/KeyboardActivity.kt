package os.retinue.ara.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import android.widget.EditText
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import os.retinue.ara.R
import os.retinue.ara.keyboard.ConfirmMode
import os.retinue.ara.keyboard.KeyLayouts
import os.retinue.ara.keyboard.KeyboardLogic
import os.retinue.ara.keyboard.KeyboardRuntime
import os.retinue.ara.keyboard.KeyboardStatus

/**
 * The keyboard screen (`RetinueShell.keyboardOpen()`, the settings card's
 * "Open keyboard", the keyboard notification): switch the keyboard on, pair
 * and connect a device, pick its layout and what the phone asks before the
 * dashboard types there, and type live — every change in the field goes to
 * the device at once, as key presses (the user's own typing: never asked about).
 *
 * A page can open this screen just before a tap it provoked: the controls
 * that act — the key buttons, Connect and Disconnect, the choice of layout,
 * the choice of what is asked — take no tap for [ConfirmTypeActivity.ARM_MS] after the screen
 * appears and after every touch on it that is not on one of them, nor one
 * that came through another window laid over it; as [ConfirmTypeActivity]'s Type.
 */
class KeyboardActivity : ComponentActivity() {

    /** The controls that act take a tap from then on (elapsedRealtime). */
    private var armedAt = Long.MAX_VALUE

    /** What they show (by the time only); [mayAct] decides. */
    private var armed by mutableStateOf(false)

    /** The touch that started the current gesture came through a window over this one. */
    private var downObscured = false

    /** Where the controls that act are, in window coordinates ([actsHere]). */
    private val acting = HashMap<Any, Rect>()

    private val timer = Handler(Looper.getMainLooper())
    private val arm = Runnable { armed = SystemClock.elapsedRealtime() >= armedAt }

    // An empty result (the request was dismissed) grants nothing: ask the runtime.
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (KeyboardRuntime.permitted()) KeyboardRuntime.setEnabled(true) else KeyboardRuntime.retry()
    }

    // Being discoverable needs one more permission, asked for only when the user wants it.
    private val advertise = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (KeyboardRuntime.mayAdvertise()) discoverable.launch(KeyboardRuntime.discoverableIntent())
    }

    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        KeyboardRuntime.retry()
    }

    private fun makeDiscoverable() {
        if (KeyboardRuntime.mayAdvertise()) discoverable.launch(KeyboardRuntime.discoverableIntent())
        else advertise.launch(KeyboardRuntime.ADVERTISE_PERMISSIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AraTheme {
                val status by KeyboardRuntime.status.collectAsStateWithLifecycle()
                KeyboardScreen(status)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hold()
        KeyboardRuntime.retry()
    }

    override fun onDestroy() {
        timer.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downObscured = ev.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
                if (acting.values.none { it.contains(Offset(ev.x, ev.y)) }) hold()
            }
            MotionEvent.ACTION_POINTER_DOWN -> hold()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** The controls that act take no tap for [ConfirmTypeActivity.ARM_MS] from now. */
    private fun hold() {
        armedAt = SystemClock.elapsedRealtime() + ConfirmTypeActivity.ARM_MS
        armed = false
        timer.removeCallbacks(arm)
        timer.postDelayed(arm, ConfirmTypeActivity.ARM_MS)
    }

    /** Checked again in the click itself: the shown state may lag a frame behind. */
    private fun mayAct(): Boolean = !downObscured && SystemClock.elapsedRealtime() >= armedAt

    /** [action], if the controls that act take a tap now. */
    private fun acts(action: () -> Unit): () -> Unit = { if (mayAct()) action() }

    /** Marks a control that acts: a touch on it does not hold the others. */
    @Composable
    private fun actsHere(): Modifier {
        val key = remember { Any() }
        DisposableEffect(key) { onDispose { acting.remove(key) } }
        return Modifier.onGloballyPositioned { acting[key] = it.boundsInWindow() }
    }

    private fun enable(on: Boolean) {
        if (on && !KeyboardRuntime.permitted()) permissions.launch(KeyboardRuntime.PERMISSIONS)
        else KeyboardRuntime.setEnabled(on)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun KeyboardScreen(status: KeyboardStatus) {
        Scaffold { inner ->
            Column(
                Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.keyboard_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::finish) { Text(stringResource(R.string.keyboard_close)) }
                }
                if (!status.available) {
                    Text(stringResource(R.string.keyboard_unavailable))
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.keyboard_enable), modifier = Modifier.weight(1f))
                    Switch(checked = status.enabled, onCheckedChange = ::enable)
                }
                Text(statusLine(status), style = MaterialTheme.typography.bodyMedium)
                if (!status.enabled) {
                    Text(stringResource(R.string.keyboard_intro), style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                OutlinedButton(onClick = ::makeDiscoverable) {
                    Text(stringResource(R.string.keyboard_discoverable))
                }
                Text(stringResource(R.string.keyboard_pair_hint), style = MaterialTheme.typography.bodySmall)

                if (status.paired.isNotEmpty()) {
                    Text(stringResource(R.string.keyboard_devices), style = MaterialTheme.typography.titleMedium)
                    status.paired.forEach { host ->
                        val isConnected = status.connected && status.host?.address == host.address
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(host.name, modifier = Modifier.weight(1f))
                            if (isConnected) {
                                OutlinedButton(onClick = acts(KeyboardRuntime::disconnect), enabled = armed, modifier = actsHere()) {
                                    Text(stringResource(R.string.keyboard_disconnect))
                                }
                            } else {
                                OutlinedButton(onClick = acts { KeyboardRuntime.connect(host.address) }, enabled = armed, modifier = actsHere()) {
                                    Text(stringResource(R.string.keyboard_connect))
                                }
                            }
                        }
                    }
                }

                status.host?.let { host ->
                    Text(stringResource(R.string.keyboard_layout_for, host.name), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyLayouts.ALL.forEach { l ->
                            // The layout decides which keys a page's text becomes (a wrong
                            // one makes "@" an Alt shortcut): it acts, as the keys do.
                            FilterChip(
                                selected = status.layout == l.id,
                                onClick = acts { KeyboardRuntime.setLayout(host.address, l.id) },
                                label = { Text(l.label) },
                                enabled = armed,
                                modifier = actsHere(),
                            )
                        }
                    }
                    ConfirmModeChoice(host.address, host.name, status.confirmMode)
                }

                if (status.busy) {
                    OutlinedButton(onClick = KeyboardRuntime::cancelTyping) { Text(stringResource(R.string.keyboard_stop)) }
                }
                if (status.connected) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.keyboard_live), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.keyboard_live_hint), style = MaterialTheme.typography.bodySmall)
                    LiveField()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "enter" to R.string.key_enter, "tab" to R.string.key_tab, "backspace" to R.string.key_backspace,
                            "escape" to R.string.key_escape, "left" to R.string.key_left, "right" to R.string.key_right,
                            "up" to R.string.key_up, "down" to R.string.key_down,
                        ).forEach { (name, label) ->
                            // In order with the live field's edits, never dropped as busy.
                            Button(onClick = acts { KeyboardRuntime.liveKey(name) }, enabled = armed, modifier = actsHere()) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * What the phone asks before the dashboard types on this device. "Never
     * ask" is asked about once more here: a page can open this screen, and a
     * tap aimed at it must not end up there. The dialog's Yes, in a window of
     * its own, takes no tap for [ConfirmTypeActivity.ARM_MS] after it appears.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ConfirmModeChoice(address: String, name: String, mode: ConfirmMode) {
        var askNever by remember { mutableStateOf(false) }
        Text(stringResource(R.string.keyboard_confirm_for, name), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ConfirmMode.Always to R.string.keyboard_confirm_always,
                ConfirmMode.OneLine to R.string.keyboard_confirm_one_line,
                ConfirmMode.Never to R.string.keyboard_confirm_never,
            ).forEach { (m, label) ->
                FilterChip(
                    selected = mode == m,
                    onClick = acts {
                        if (m == ConfirmMode.Never && mode != m) askNever = true else KeyboardRuntime.setConfirmMode(address, m)
                    },
                    label = { Text(stringResource(label)) },
                    enabled = armed,
                    modifier = actsHere(),
                )
            }
        }
        Text(stringResource(R.string.keyboard_confirm_hint), style = MaterialTheme.typography.bodySmall)
        if (askNever) {
            val shownAt = remember { SystemClock.elapsedRealtime() }
            var yesArmed by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(ConfirmTypeActivity.ARM_MS)
                yesArmed = true
            }
            AlertDialog(
                onDismissRequest = { askNever = false },
                title = { Text(stringResource(R.string.keyboard_confirm_never_title, name)) },
                text = { Text(stringResource(R.string.keyboard_confirm_never_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (SystemClock.elapsedRealtime() - shownAt >= ConfirmTypeActivity.ARM_MS) {
                                askNever = false
                                KeyboardRuntime.setConfirmMode(address, ConfirmMode.Never)
                            }
                        },
                        enabled = yesArmed,
                    ) { Text(stringResource(R.string.keyboard_confirm_never_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = { askNever = false }) { Text(stringResource(R.string.keyboard_confirm_never_no)) }
                },
            )
        }
    }

    /**
     * The live field: a framework EditText (no suggestions, no personalised
     * learning) whose every change is sent as the difference — Backspace for
     * what went, the new characters typed. Edits are assumed at the end of
     * the text (where typing happens); a change elsewhere in the field is
     * sent as deleting back to it and retyping the rest. The field takes only
     * what the device's layout can type, so it always holds what the device
     * was sent; a line break goes as Shift+Enter, like any typed text.
     */
    @Composable
    private fun LiveField() {
        var last by remember { mutableStateOf("") }
        var view by remember { mutableStateOf<EditText?>(null) }
        // Live typing failed or was stopped: the device no longer has what
        // the field shows, so the field starts over (sending nothing).
        val resets by KeyboardRuntime.liveResets.collectAsStateWithLifecycle()
        var seenResets by remember { mutableStateOf(resets) }
        var wasReset by remember { mutableStateOf(false) }
        LaunchedEffect(resets) {
            if (resets != seenResets) {
                seenResets = resets
                last = ""
                view?.setText("")
                wasReset = true
            }
        }
        if (wasReset) Text(stringResource(R.string.keyboard_live_reset), style = MaterialTheme.typography.bodySmall)
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    view = this
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                    hint = ctx.getString(R.string.keyboard_live_field)
                    minLines = 3
                    // Characters the layout lacks never enter the field: the
                    // device could not get them, and the next diff would be
                    // computed against text it never had.
                    filters = arrayOf(
                        InputFilter.LengthFilter(KeyboardLogic.MAX_TEXT),
                        // A pasted \r\n is one line break on the device but two
                        // characters here: \r is dropped, so a deletion later
                        // never takes one character too many.
                        InputFilter { source, start, end, _, _, _ ->
                            val layout = KeyLayouts.byId(KeyboardRuntime.status.value.layout)
                                ?: return@InputFilter null
                            val keep: (Char) -> Boolean = { it != '\r' && KeyLayouts.typeable(it, layout) }
                            if ((start until end).all { keep(source[it]) }) {
                                null
                            } else {
                                (start until end).map { source[it] }.filter(keep).joinToString("")
                            }
                        },
                    )
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            val now = s?.toString().orEmpty()
                            val (back, add) = KeyboardLogic.liveDiff(last, now)
                            last = now
                            if (back > 0 || add.isNotEmpty()) {
                                wasReset = false
                                KeyboardRuntime.live(back, add)
                            }
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun statusLine(status: KeyboardStatus): String = when {
        !status.enabled -> stringResource(R.string.keyboard_status_off)
        !status.permitted -> stringResource(R.string.keyboard_status_permission)
        !status.bluetoothOn -> stringResource(R.string.keyboard_status_bluetooth_off)
        status.refused -> stringResource(R.string.keyboard_status_refused)
        !status.registered -> stringResource(R.string.keyboard_status_starting)
        status.held && !status.connected -> stringResource(R.string.keyboard_status_held)
        status.connected && status.host != null -> stringResource(R.string.keyboard_status_connected, status.host.name)
        else -> stringResource(R.string.keyboard_status_waiting)
    }
}
