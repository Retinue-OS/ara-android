package os.retinue.ara.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import os.retinue.ara.R
import os.retinue.ara.keyboard.KeyboardLogic
import os.retinue.ara.keyboard.KeyboardRuntime
import os.retinue.ara.keyboard.PageRequest
import os.retinue.ara.keyboard.PendingConfirmation

/**
 * The phone's own question before a page types something that acts on the
 * other device — or anything, as the device's mode says (KeyboardLogic.decide):
 * a small window of the app's, outside the WebView, that the page can neither
 * see nor answer. Modelled on [SecretTypeActivity], without its field.
 *
 * It names the device (by the name it gave itself, cleaned) and the host of
 * the server that asked, as the app's own settings have it — never the name
 * the server gives itself; shows a text exactly, in a box that scrolls, with
 * line breaks, tabs and the blanks at its ends made visible, its length, and
 * whether Enter follows and line breaks go as Shift+Enter; or the key; and how
 * many characters the dashboard typed on that device without asking since
 * the last approval.
 *
 * *Type* takes no tap for [ARM_MS] after the window appears and after every
 * touch on it that is not on the button — a tap aimed at the page cannot land
 * on it — nor, for a text longer than its box, before the box was scrolled to
 * its end; nor while another window covers it.
 *
 * The page hears exactly one answer, however the window ends: the runtime
 * holds the request ([KeyboardRuntime.confirmation]) and settles it once —
 * typed (exactly this request, only into the device it names) or `cancelled`
 * (declined, Back, left, timed out after [KeyboardLogic.confirmTimeoutMs], the
 * page's Stop). Recreated by Android, or after a restart of the process, it
 * finds its request answered and closes. FLAG_SECURE, as the password dialog.
 */
class ConfirmTypeActivity : Activity() {

    private var token: String? = null
    private var dialog: AlertDialog? = null
    private var typeButton: Button? = null
    private var settled = false
    private val timer = Handler(Looper.getMainLooper())

    /** Type takes a tap from then on (elapsedRealtime). */
    private var armedAt = Long.MAX_VALUE

    /** The text's box was scrolled to its end (or never needed it). */
    private var seenEnd = true

    private val refresh = Runnable { refreshType() }

    /** The page's Stop closes this window, as Don't type would. */
    private val closer: () -> Unit = { runOnUiThread { declineAndFinish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        token = intent.getStringExtra(EXTRA_TOKEN)
        if (savedInstanceState != null && lastNonConfigurationInstance == null) {
            // Brought back by Android: its request was answered when the old
            // window went (or its process, and the page with it, is gone).
            token?.let(KeyboardRuntime::confirmDeclined)
            settled = true
            finish()
            return
        }
        val t = token
        val request = KeyboardRuntime.confirmation(t)
        if (t == null || request == null || !KeyboardRuntime.confirmationShown(t, closer)) {
            // Answered already (the page's Stop, the runtime's own timeout).
            settled = true
            finish()
            return
        }
        // Nobody answers: it closes itself, before the page stops waiting.
        timer.postDelayed({ declineAndFinish() }, KeyboardLogic.confirmTimeoutMs(request.request))
        show(request)
    }

    private fun show(p: PendingConfirmation) {
        val device = p.hostName ?: getString(R.string.keyboard_secret_unknown_host)
        val server = p.serverHost ?: getString(R.string.keyboard_confirm_unknown_server)
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun line(text: String, style: Int = android.R.style.TextAppearance_Material_Body1) =
            TextView(this).apply {
                setTextAppearance(style)
                this.text = text
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }.also(box::addView)

        when (val r = p.request) {
            is PageRequest.Type -> {
                line(getString(R.string.keyboard_confirm_asks_text, server, device))
                val shown = TextView(this).apply {
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(false)
                    text = shownText(r.text)
                    val inner = (8 * density).toInt()
                    setPadding(inner, inner, inner, inner)
                }
                val scroll = BoundedScrollView(this, (180 * density).toInt()).apply {
                    addView(shown)
                    setBackgroundColor(themeColor(android.R.attr.colorControlHighlight))
                    // Long enough to scroll: Type waits until its end was seen.
                    viewTreeObserver.addOnGlobalLayoutListener { checkEnd(this) }
                    setOnScrollChangeListener { v, _, _, _, _ -> checkEnd(v as ScrollView) }
                }
                seenEnd = false
                box.addView(scroll)
                val chars = KeyboardLogic.charCount(r.text)
                line(resources.getQuantityString(R.plurals.keyboard_confirm_chars, chars, chars), android.R.style.TextAppearance_Material_Body2)
                if (r.enter) line(getString(R.string.keyboard_confirm_enter), android.R.style.TextAppearance_Material_Body2)
                if (KeyboardLogic.hasLineBreak(r.text)) line(getString(R.string.keyboard_confirm_line_breaks), android.R.style.TextAppearance_Material_Body2)
            }
            is PageRequest.Key -> {
                line(getString(R.string.keyboard_confirm_asks_key, server, device))
                line(getString(R.string.keyboard_confirm_press, keyName(r.name)), android.R.style.TextAppearance_Material_Headline)
            }
            else -> Unit
        }
        if (p.unconfirmed > 0) {
            line(
                resources.getQuantityString(R.plurals.keyboard_confirm_unasked, p.unconfirmed, p.unconfirmed, device),
                android.R.style.TextAppearance_Material_Body2,
            )
        }

        val dialog = GuardedDialog()
        dialog.setTitle(getString(R.string.keyboard_confirm_title, device))
        dialog.setView(box)
        // The listener is replaced once shown: a tap Type does not take must not close the dialog.
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.keyboard_confirm_type)) { _, _ -> }
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.keyboard_confirm_decline)) { _, _ -> declineAndFinish() }
        dialog.setOnCancelListener { declineAndFinish() }
        // A tap aimed at the page and landing beside the window declines nothing: it holds Type.
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            typeButton = button
            // A window laid over this one gets no tap through to it.
            button.filterTouchesWhenObscured = true
            button.setOnClickListener { if (typeTakesTap()) approveAndFinish() }
            hold()
        }
        this.dialog = dialog
        dialog.show()
    }

    /** The dialog that sees every touch on it first, to hold Type for one that is not on the button. */
    private inner class GuardedDialog : AlertDialog(this@ConfirmTypeActivity) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (!onTypeButton(ev)) hold()
            return super.dispatchTouchEvent(ev)
        }
    }

    /** A touch on this activity's own window, beside the dialog, holds Type too. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        hold()
        return super.dispatchTouchEvent(ev)
    }

    private fun onTypeButton(ev: MotionEvent): Boolean {
        val button = typeButton ?: return false
        val at = IntArray(2)
        button.getLocationOnScreen(at)
        val rect = Rect(at[0], at[1], at[0] + button.width, at[1] + button.height)
        return rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    /** Type takes no tap for [ARM_MS] from now. */
    private fun hold() {
        armedAt = SystemClock.elapsedRealtime() + ARM_MS
        refreshType()
        timer.removeCallbacks(refresh)
        timer.postDelayed(refresh, ARM_MS)
    }

    private fun typeTakesTap(): Boolean = !settled && seenEnd && SystemClock.elapsedRealtime() >= armedAt

    private fun refreshType() {
        typeButton?.isEnabled = typeTakesTap()
    }

    private fun checkEnd(scroll: ScrollView) {
        if (!seenEnd && !scroll.canScrollVertically(1)) {
            seenEnd = true
            refreshType()
        }
    }

    private fun approveAndFinish() {
        if (settled) return
        settled = true
        // Exactly this request, once; the runtime types it only into the device it named.
        token?.let(KeyboardRuntime::confirmApproved)
        finish()
    }

    private fun declineAndFinish() {
        if (settled) return
        settled = true
        token?.let(KeyboardRuntime::confirmDeclined)
        finish()
    }

    /** The text with its marks drawn apart (KeyboardLogic.shownText). */
    private fun shownText(text: String): CharSequence {
        val out = SpannableStringBuilder()
        val markColor = themeColor(android.R.attr.colorAccent)
        val markBackground = themeColor(android.R.attr.colorControlHighlight)
        for (piece in KeyboardLogic.shownText(text)) {
            val start = out.length
            out.append(piece.text)
            if (piece.mark) {
                out.setSpan(ForegroundColorSpan(markColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(markBackground), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun keyName(name: String): String = getString(
        when (name) {
            "enter" -> R.string.key_name_enter
            "tab" -> R.string.key_name_tab
            "backspace" -> R.string.key_name_backspace
            "escape" -> R.string.key_name_escape
            "up" -> R.string.key_name_up
            "down" -> R.string.key_name_down
            "left" -> R.string.key_name_left
            "right" -> R.string.key_name_right
            "home" -> R.string.key_name_home
            "end" -> R.string.key_name_end
            "delete" -> R.string.key_name_delete
            "space" -> R.string.key_name_space
            else -> R.string.key_name_other
        },
    )

    /** A scroll view no taller than [maxHeight]: a long text scrolls inside the dialog. */
    private class BoundedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        }
    }

    /** Marks a recreation for a configuration change, whose request is still open (see onCreate). */
    @Deprecated("Deprecated in Java")
    override fun onRetainNonConfigurationInstance(): Any = true

    override fun onStop() {
        super.onStop()
        // Left (Home, another app, the screen off): nothing typed, and the page hears so.
        if (!isChangingConfigurations) declineAndFinish()
    }

    override fun onDestroy() {
        if (!settled && !isChangingConfigurations) declineAndFinish()
        timer.removeCallbacksAndMessages(null)
        KeyboardRuntime.windowClosed(closer)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TOKEN = "token"

        /** How long Type takes no tap after the window appears, and after a touch elsewhere on it. */
        const val ARM_MS = 600L

        /** The window for the confirmation [token] (KeyboardRuntime.fromPage reserved it). */
        fun intent(context: Context, token: String): Intent =
            Intent(context, ConfirmTypeActivity::class.java).putExtra(EXTRA_TOKEN, token)
    }
}
