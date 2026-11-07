package os.retinue.ara.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonPrimitive
import os.retinue.ara.BuildConfig
import os.retinue.ara.data.DeviceWebSettings
import os.retinue.ara.health.HealthSyncEvent
import os.retinue.ara.health.HealthSyncStatus
import os.retinue.ara.keyboard.KeyboardEvent
import os.retinue.ara.keyboard.KeyboardStatus
import os.retinue.ara.shell.EndpointLabel
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.UrlRouter
import os.retinue.ara.shell.WebMessage

/**
 * The shell ↔ web bridge (plan §4), restricted to the configured origin.
 *
 * Preferred path (current WebViews): `addWebMessageListener` injects
 * `window.RetinueShellNative` into pages of the configured origin only, and
 * `addDocumentStartJavaScript` runs assets/retinue-shell.js there before any
 * page script, installing `window.RetinueShell` and the speech stand-in.
 *
 * WebViews without `addWebMessageListener` get no bridge at all: the only
 * alternative, `addJavascriptInterface`, is visible to every frame and cannot
 * tell which one called it, so a cross-origin iframe could drive the shell.
 * The web app then behaves as in a browser tab. Without
 * `addDocumentStartJavaScript` alone, the script is injected into the main
 * frame when a page starts and again when it finishes loading (it is idempotent).
 *
 * The page's `healthConnectAvailable()`, `healthSyncStatus()`, `endpoints()`
 * and `deviceSettings()` answer at once, so the script carries the sync
 * status, the endpoint list and the per-device preferences as they were when
 * it was installed ([healthStatus], [endpoints] and [deviceSettings] supply
 * them), and every change is pushed to the page too.
 */
class ShellBridge(
    context: Context,
    private val webView: WebView,
    private val router: () -> UrlRouter,
    private val healthStatus: () -> HealthSyncStatus,
    private val endpoints: () -> List<EndpointLabel>,
    private val deviceSettings: () -> Map<String, String>,
    private val keyboardStatus: () -> KeyboardStatus,
    private val onMessage: (WebMessage) -> Unit,
) {
    private val template: String = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        .replace("__RETINUE_SHELL_VERSION__", BuildConfig.VERSION_NAME)

    /** The page script, with the current health sync status and endpoint list (JSON string literals in place of the placeholders). */
    private val script: String
        get() = template
            .replace(HEALTH_PLACEHOLDER, JsonPrimitive(healthStatus().toJson().toString()).toString())
            .replace(ENDPOINTS_PLACEHOLDER, JsonPrimitive(ShellMessages.endpointsJson(endpoints()).toString()).toString())
            .replace(DEVICE_PLACEHOLDER, JsonPrimitive(DeviceWebSettings.encode(deviceSettings())).toString())
            .replace(KEYBOARD_PLACEHOLDER, JsonPrimitive(keyboardStatus().toJson().toString()).toString())

    private val listenerSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private var scriptHandler: ScriptHandler? = null
    private var listenerInstalled = false

    /**
     * (Re)install for the configured origin. Call before loading the first page
     * and whenever the base URL changes. With no origin configured nothing is
     * exposed.
     */
    @SuppressLint("RequiresFeature", "RestrictedApi")
    fun install() {
        val origin = router().origin?.toString()
        if (listenerSupported) {
            if (listenerInstalled) {
                WebViewCompat.removeWebMessageListener(webView, NATIVE)
                listenerInstalled = false
            }
            if (origin != null) {
                try {
                    WebViewCompat.addWebMessageListener(webView, NATIVE, setOf(origin)) { _, message, sourceOrigin, isMainFrame, _ ->
                        if (isMainFrame && router().isConfiguredOrigin(sourceOrigin.toString())) {
                            ShellMessages.parse(message.data)?.let(onMessage)
                        }
                    }
                    listenerInstalled = true
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "origin rule refused: $origin", e)
                }
            }
        } else {
            Log.w(TAG, "WebView lacks WEB_MESSAGE_LISTENER: no shell bridge")
        }

        installScript(origin)
    }

    @SuppressLint("RequiresFeature")
    private fun installScript(origin: String?) {
        if (documentStartSupported && listenerInstalled) {
            scriptHandler?.remove()
            scriptHandler = null
            if (origin != null) {
                scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
            }
        }
    }

    private val injectsOnLoad: Boolean get() = listenerInstalled && !documentStartSupported

    /** From the WebView client: a main-frame page started loading. */
    fun onPageStarted(url: String?) {
        if (injectsOnLoad && router().isConfiguredOrigin(url)) webView.evaluateJavascript(script, null)
    }

    /** From the WebView client: a main-frame page finished loading. */
    fun onPageFinished(url: String?) {
        if (injectsOnLoad && router().isConfiguredOrigin(url)) webView.evaluateJavascript(script, null)
    }

    /**
     * Shell → web: `retinue-shell` event of [type] (`wake`, `home`).
     * [onUnhandled] runs (on the main thread) when the main frame is not on the
     * configured origin or the page has no listener for the event.
     */
    fun dispatchEvent(type: String, onUnhandled: (() -> Unit)? = null) {
        if (!router().isConfiguredOrigin(webView.url)) {
            onUnhandled?.invoke()
            return
        }
        webView.evaluateJavascript(ShellMessages.eventScript(type)) { result ->
            if (!ShellMessages.eventHandled(result)) onUnhandled?.invoke()
        }
    }

    /**
     * The health sync status changed: pages loaded from now on start with it,
     * and the page on screen is told.
     */
    fun healthStatusChanged(status: HealthSyncStatus) {
        if (scriptHandler != null) installScript(router().origin?.toString())
        run(ShellMessages.healthStatus(status))
    }

    /** Shell → web: a `retinue-shell` event of type `health-sync` (nobody needs to take it). */
    fun healthEvent(event: HealthSyncEvent) {
        run(ShellMessages.eventScript(ShellMessages.HEALTH_SYNC_EVENT, event.detail()))
    }

    /**
     * A keyboard event: the status is refreshed first (pages loaded from now
     * on start with it, the page on screen gets it), then the page on screen
     * gets the `keyboard` event. Nobody needs to take it.
     */
    fun keyboardEvent(event: KeyboardEvent) {
        if (scriptHandler != null) installScript(router().origin?.toString())
        run(ShellMessages.keyboardStatus(keyboardStatus()))
        run(ShellMessages.eventScript(ShellMessages.KEYBOARD_EVENT, event.detail()))
    }

    /**
     * The per-device preferences changed: pages loaded from now on start with
     * them, and the page on screen gets the new copy (its script announces the
     * keys that changed with a `device-settings` event).
     */
    fun deviceSettingsChanged(values: Map<String, String>) {
        if (scriptHandler != null) installScript(router().origin?.toString())
        run(ShellMessages.deviceStatus(values))
    }

    /**
     * The endpoint list (or a label in it) changed: pages loaded from now on
     * start with it, the page on screen is told and gets an `endpoints` event
     * (nobody needs to take it; the Settings page re-renders its menu).
     */
    fun endpointsChanged(list: List<EndpointLabel>) {
        if (scriptHandler != null) installScript(router().origin?.toString())
        run(ShellMessages.endpointsStatus(list))
        run(ShellMessages.eventScript(ShellMessages.ENDPOINTS_EVENT))
    }

    /** Run [js] in the page — only while the main frame is on the configured origin. */
    fun run(js: String) {
        if (router().isConfiguredOrigin(webView.url)) webView.evaluateJavascript(js, null)
    }

    private companion object {
        const val TAG = "ShellBridge"
        const val ASSET = "retinue-shell.js"
        const val NATIVE = "RetinueShellNative"

        /** Where the script takes the health sync status (a quoted string, so the bare asset still parses). */
        const val HEALTH_PLACEHOLDER = "\"__RETINUE_HEALTH_STATUS__\""

        /** Where the script takes the endpoint list, likewise. */
        const val ENDPOINTS_PLACEHOLDER = "\"__RETINUE_ENDPOINTS__\""

        /** Where the script takes the per-device preferences, likewise. */
        const val DEVICE_PLACEHOLDER = "\"__RETINUE_DEVICE_SETTINGS__\""

        /** Where the script takes the keyboard status, likewise. */
        const val KEYBOARD_PLACEHOLDER = "\"__RETINUE_KEYBOARD_STATUS__\""
    }
}
