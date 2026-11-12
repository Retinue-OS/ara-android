package os.retinue.ara.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import os.retinue.ara.R
import os.retinue.ara.keyboard.KeyboardEvent
import os.retinue.ara.keyboard.KeyboardLogic
import os.retinue.ara.keyboard.KeyboardRuntime

/**
 * The secret path of the keyboard (`RetinueShell.keyboardSecret(opts)`): a
 * native dialog, outside the WebView, where the user's password manager fills
 * — or the user types — a password that Ara then types into the connected
 * device.
 *
 * What it guarantees: the secret is never written to a log, never stored by
 * Ara, never handed to the page, the server or any agent; the page learns
 * `secret-typed` or `secret-cancelled` (or an error that says nothing about
 * the secret), and the typing is padded so its duration says little of the
 * length (KeyboardRuntime.typeSecret). The dialog names the device it types
 * into, and nothing is typed if another one is connected by then. The window
 * is FLAG_SECURE (no screenshots, blank in Recents), and the field is a
 * password field with the autofill hint "password", so password managers
 * offer to fill it and keyboards do not learn from it. Ara's own copies — the
 * char buffer and the key reports — are overwritten once typed, the autofill
 * session is cancelled (no "save password?" prompt), and the field is
 * cleared. What Ara cannot wipe: the text the IME or the autofill service
 * handed over is a string in Android's own code, the field's own buffer keeps
 * the old characters until reused (clearing only moves its gap), and an
 * autofill service sees what the user types into a field it serves. One dialog
 * at a time; the page's Stop closes it; it closes itself after ten minutes.
 *
 * A plain [Activity] with a framework [EditText], not Compose: autofill
 * services fill framework views most reliably.
 */
class SecretTypeActivity : Activity() {

    private var field: EditText? = null
    private var dialog: AlertDialog? = null
    private var requestId: String? = null
    private var host: String? = null
    private var settled = false
    private val timer = Handler(Looper.getMainLooper())

    /** The page's Stop (`keyboardCancel()`) closes this dialog, as the user's Cancel would. */
    private val closer: () -> Unit = { runOnUiThread { cancelAndFinish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState != null && lastNonConfigurationInstance == null) {
            // Brought back by Android (the process, or the activity, was
            // destroyed in the background): its request was answered as
            // cancelled then, or its page is gone. Nothing asks for it now.
            settled = true
            finish()
            return
        }
        requestId = KeyboardLogic.cleanId(intent.getStringExtra(EXTRA_ID))
        host = intent.getStringExtra(EXTRA_HOST)
        // The name the other device gave itself: shown cleaned, like the label.
        val hostName = KeyboardRuntime.status.value.host?.takeIf { it.address == host }?.name
            ?.let(KeyboardLogic::cleanLabel)
            ?: getString(R.string.keyboard_secret_unknown_host)
        KeyboardRuntime.windowOpened(closer)
        // Nobody answers: it closes itself, so the page hears an end.
        timer.postDelayed({ cancelAndFinish() }, TIMEOUT_MS)
        val label = KeyboardLogic.cleanLabel(intent.getStringExtra(EXTRA_LABEL))
            ?: getString(R.string.keyboard_secret_default_label)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            hint = getString(R.string.keyboard_secret_hint)
            filters = arrayOf(android.text.InputFilter.LengthFilter(KeyboardLogic.MAX_SECRET))
            isLongClickable = false // no copying the password out
        }
        field = edit
        val enter = CheckBox(this).apply {
            text = getString(R.string.keyboard_secret_enter)
            isChecked = intent.getBooleanExtra(EXTRA_ENTER, false)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
            addView(enter)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(getString(R.string.keyboard_secret_message, hostName))
            .setView(box)
            .setPositiveButton(R.string.keyboard_secret_type) { _, _ -> typeAndFinish(enter.isChecked) }
            .setNegativeButton(R.string.keyboard_secret_cancel) { _, _ -> cancelAndFinish() }
            .setOnCancelListener { cancelAndFinish() }
            .create()
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        this.dialog = dialog
        dialog.setOnShowListener {
            edit.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun typeAndFinish(enter: Boolean) {
        if (settled) return
        settled = true
        val edit = field
        val editable = edit?.text
        val chars = CharArray(editable?.length ?: 0)
        editable?.getChars(0, chars.size, chars, 0)
        endAutofill()
        editable?.clear()
        if (chars.isEmpty()) {
            KeyboardRuntime.listener?.onKeyboardEvent(KeyboardEvent(KeyboardEvent.SECRET_CANCELLED, requestId))
        } else {
            // The runtime overwrites the buffer once sent, and types only into [host].
            KeyboardRuntime.typeSecret(chars, enter, requestId, host)
        }
        finish()
    }

    private fun cancelAndFinish() {
        if (settled) return
        settled = true
        endAutofill()
        field?.text?.clear()
        KeyboardRuntime.listener?.onKeyboardEvent(KeyboardEvent(KeyboardEvent.SECRET_CANCELLED, requestId))
        finish()
    }

    /** End the autofill session without a "save password?" prompt. */
    private fun endAutofill() {
        try {
            getSystemService(AutofillManager::class.java)?.cancel()
        } catch (e: Exception) {
            // No autofill on this phone.
        }
    }

    /** Marks a recreation for a configuration change, whose request is still open (see onCreate). */
    @Deprecated("Deprecated in Java")
    override fun onRetainNonConfigurationInstance(): Any = true

    override fun onDestroy() {
        // Left by any other way (the task removed): nothing typed, and the page hears so.
        // Covered alone it stays: a password manager's unlock screen returns to it.
        if (!settled && !isChangingConfigurations) cancelAndFinish()
        timer.removeCallbacksAndMessages(null)
        KeyboardRuntime.windowClosed(closer)
        field?.text?.clear()
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_ENTER = "enter"
        private const val EXTRA_HOST = "host"

        /** How long the dialog waits for the user before it closes as cancelled. */
        private const val TIMEOUT_MS = 10 * 60 * 1000L

        /** The dialog for typing into [host] (the connected device's address, the app's own). */
        fun intent(context: Context, id: String?, label: String?, enter: Boolean, host: String?): Intent =
            Intent(context, SecretTypeActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_ENTER, enter)
                .putExtra(EXTRA_HOST, host)
    }
}
