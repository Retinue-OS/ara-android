package os.retinue.ara.settings

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import os.retinue.ara.shell.ShellUrls

/**
 * The settings page's line to the app: `window.AraSettingsNative`, injected by
 * `addWebMessageListener` into the page's own origin ([AppPage.ORIGIN]) in its
 * main frame only. The instance section it embeds is another origin and gets
 * nothing. Unlike the dashboard's bridge, this page is the app's own, so it
 * may change what a dashboard page may only ask for: endpoints, their
 * certificates and passwords, their health and call switches.
 *
 * The page says hello once loaded; the app answers through that hello's
 * reply proxy from then on: the whole state on every change ([pushState]),
 * and the answers to its requests ([send]). Main thread.
 */
class SettingsBridge(private val webView: WebView, private val onAction: (SettingsAction) -> Unit) {

    private var reply: JavaScriptReplyProxy? = null
    private var lastState: String? = null

    /** False on a WebView without message listeners: the page then says it needs the app. */
    val supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    @SuppressLint("RequiresFeature")
    fun install() {
        if (!supported) {
            Log.w(TAG, "WebView lacks WEB_MESSAGE_LISTENER: the settings page has no line to the app")
            return
        }
        try {
            WebViewCompat.addWebMessageListener(webView, NATIVE, setOf(AppPage.ORIGIN)) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                if (!isMainFrame || ShellUrls.origin(sourceOrigin.toString()) != PAGE_ORIGIN) return@addWebMessageListener
                val action = SettingsMessages.parse(message.data) ?: return@addWebMessageListener
                if (action is SettingsAction.Hello) {
                    reply = replyProxy
                    lastState?.let { post(it) }
                }
                onAction(action)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "origin rule refused", e)
        }
    }

    /** The page's state (a [SettingsMessages.state] message): sent when it differs from the last. */
    fun pushState(message: String) {
        if (message == lastState) return
        lastState = message
        post(message)
    }

    /** An answer to one of the page's requests. */
    fun send(message: String) = post(message)

    /**
     * The phone's Back: the page takes it first (its dialog, its editor);
     * [onUnhandled] runs when it does not.
     */
    fun back(onUnhandled: () -> Unit) {
        webView.evaluateJavascript(BACK_SCRIPT) { result -> if (result?.trim() != "true") onUnhandled() }
    }

    @SuppressLint("RequiresFeature")
    private fun post(message: String) {
        try {
            reply?.postMessage(message)
        } catch (e: Exception) {
            // The page went away (reloaded, destroyed): its next hello brings a new proxy.
            Log.w(TAG, "settings page not reached", e)
        }
    }

    private companion object {
        const val TAG = "SettingsBridge"
        const val NATIVE = "AraSettingsNative"
        const val BACK_SCRIPT = "(function(){try{return !!(window.__araSettingsBack&&window.__araSettingsBack());}catch(e){return false;}})()"
        val PAGE_ORIGIN = ShellUrls.origin(AppPage.ORIGIN)
    }
}
