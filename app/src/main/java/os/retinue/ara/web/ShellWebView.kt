package os.retinue.ara.web

import android.content.Context
import android.view.WindowInsets
import android.webkit.WebView
import os.retinue.ara.shell.ShellDecisions

/**
 * The shell's WebView: the soft keyboard stays up while one of its own popups
 * (the language list, the input-method picker) has the window focus. See
 * [ShellDecisions.passWindowFocusChange] for the Chromium bug this works around.
 */
class ShellWebView(context: Context) : WebView(context) {

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        val keyboardUp = rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        if (ShellDecisions.passWindowFocusChange(hasWindowFocus, keyboardUp)) {
            super.onWindowFocusChanged(hasWindowFocus)
        }
    }
}
