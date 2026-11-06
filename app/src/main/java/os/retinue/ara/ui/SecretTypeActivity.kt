package os.retinue.ara.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
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
 * What it guarantees: the secret is in this window and in the key presses
 * only. It is never written to a log, never stored, never handed to the page,
 * the server or any agent; the page learns `secret-typed` or
 * `secret-cancelled`, nothing about the secret, not even its length. The
 * window is FLAG_SECURE (no screenshots, blank in recents), and the field is a
 * password field with the autofill hint "password", so password managers
 * offer to fill it and keyboards do not learn from it. The characters are
 * copied into a buffer that is overwritten once typed, and the field is
 * cleared.
 *
 * A plain [Activity] with a framework [EditText], not Compose: autofill
 * services fill framework views most reliably.
 */
class SecretTypeActivity : Activity() {

    private var field: EditText? = null
    private var requestId: String? = null
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        requestId = KeyboardLogic.cleanId(intent.getStringExtra(EXTRA_ID))
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
            .setMessage(R.string.keyboard_secret_message)
            .setView(box)
            .setPositiveButton(R.string.keyboard_secret_type) { _, _ -> typeAndFinish(enter.isChecked) }
            .setNegativeButton(R.string.keyboard_secret_cancel) { _, _ -> cancelAndFinish() }
            .setOnCancelListener { cancelAndFinish() }
            .create()
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
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
        editable?.clear()
        if (chars.isEmpty()) {
            KeyboardRuntime.listener?.onKeyboardEvent(KeyboardEvent(KeyboardEvent.SECRET_CANCELLED, requestId))
        } else {
            // The runtime overwrites the buffer once sent.
            KeyboardRuntime.typeSecret(chars, enter, requestId)
        }
        finish()
    }

    private fun cancelAndFinish() {
        if (settled) return
        settled = true
        field?.text?.clear()
        KeyboardRuntime.listener?.onKeyboardEvent(KeyboardEvent(KeyboardEvent.SECRET_CANCELLED, requestId))
        finish()
    }

    override fun onDestroy() {
        // Left by any other way (the task removed): nothing typed, and the page hears so.
        if (!settled) cancelAndFinish()
        field?.text?.clear()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_ENTER = "enter"

        fun intent(context: Context, id: String?, label: String?, enter: Boolean): Intent =
            Intent(context, SecretTypeActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_ENTER, enter)
    }
}
