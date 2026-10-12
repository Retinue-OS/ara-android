package os.retinue.ara.web

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import os.retinue.ara.R
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.LauncherLinks
import os.retinue.ara.launcher.LinkAction
import os.retinue.ara.shell.Route
import os.retinue.ara.shell.UrlRouter

/**
 * Everything the shell hands to Android: web links for the browser, other
 * schemes, sanitized `intent:` URLs, and launcher entries.
 */
object ShellIntents {

    private const val TAG = "ShellIntents"

    /** Open an http(s) URL in the user's default browser. */
    fun openInBrowser(context: Context, url: String): Boolean = start(
        context,
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
    )

    /**
     * A non-http scheme from a page (`tel:`, `mailto:`, `geo:` …). A page's link
     * is held to what a browser may open: browsable activities only.
     */
    fun openExternal(context: Context, url: String): Boolean = startLink(context, url, fromPage = true)

    /**
     * An `intent:` URL, parsed with [Intent.URI_INTENT_SCHEME] and sanitized the
     * way Chrome does: browsable only, no explicit component or selector (a page
     * must not address a non-exported or arbitrary activity), and from a page
     * ([fromPage]) without any launch flags. When nothing handles it,
     * `browser_fallback_url` is honoured through [router].
     * Returns the fallback route the caller should carry out (Stay = load in view).
     */
    fun openIntentUrl(context: Context, url: String, router: UrlRouter, fromPage: Boolean = true): Route {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            // URISyntaxException, but a crafted URL also gets NumberFormatException and others.
            Log.w(TAG, "bad intent URL", e)
            return Route.Block
        }
        val fallback = try {
            intent.getStringExtra("browser_fallback_url")
        } catch (e: Exception) {
            null
        }
        val launch = sanitize(intent, fromPage) && start(context, intent, toastOnFailure = false)
        if (launch) return Route.Block
        val route = router.fallback(fallback)
        if (route == Route.Block) toast(context)
        return route
    }

    /**
     * Make a parsed intent safe to start; false when it must not be started at
     * all (it points at a local file or a content URI).
     */
    private fun sanitize(intent: Intent, fromPage: Boolean): Boolean {
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.clipData = null
        intent.flags = if (fromPage) {
            // A page sets no launch flags at all (task, history, URI grants), as in Chrome.
            0
        } else {
            // Never let a link grant itself access to our content.
            intent.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
        }
        if (UrlRouter.isRefusedIntentData(intent.data?.scheme)) {
            Log.w(TAG, "refused intent data: ${intent.data?.scheme}")
            return false
        }
        return true
    }

    /** Start a launcher entry; WebPage entries are the caller's (they load in the WebView). */
    fun launch(context: Context, entry: LauncherEntry, router: UrlRouter): Route = when (entry) {
        is LauncherEntry.App -> {
            launchApp(context, entry.packageName, entry.activityName)
            Route.Block
        }
        is LauncherEntry.Shortcut -> {
            launchShortcut(context, entry.packageName, entry.shortcutId)
            Route.Block
        }
        is LauncherEntry.Link -> {
            if (LauncherLinks.actionFor(entry.uri) == LinkAction.ParseIntent) {
                openIntentUrl(context, entry.uri, router, fromPage = false)
            } else {
                // The user's own entry: any handler, not only browsable ones.
                startLink(context, entry.uri, fromPage = false)
                Route.Block
            }
        }
        is LauncherEntry.WebPage -> Route.Stay
    }

    fun launchApp(context: Context, packageName: String, activityName: String): Boolean {
        val explicit = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(packageName, activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (start(context, explicit, toastOnFailure = false)) return true
        // The activity moved in an update: fall back to the package's launch intent.
        val byPackage = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (byPackage != null) start(context, byPackage) else {
            toast(context)
            false
        }
    }

    /**
     * Start a pinned shortcut through LauncherApps (the home app's privilege). A
     * shortcut that is gone (app uninstalled, shortcut removed or disabled) only
     * gets a toast: the entry stays on the bar for the user to remove.
     */
    fun launchShortcut(context: Context, packageName: String, shortcutId: String): Boolean {
        val apps = context.getSystemService(LauncherApps::class.java)
        val failure = if (apps == null) {
            R.string.shortcut_unavailable
        } else {
            try {
                apps.startShortcut(packageName, shortcutId, null, null, Process.myUserHandle())
                return true
            } catch (e: ActivityNotFoundException) {
                R.string.shortcut_unavailable
            } catch (e: SecurityException) {
                // Only the default home app may start shortcuts.
                R.string.shortcut_not_home
            } catch (e: IllegalStateException) {
                // Thrown while the user is locked (before the first unlock after a reboot).
                val locked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == false
                Log.w(TAG, "could not start shortcut $packageName/$shortcutId", e)
                if (locked) R.string.shortcut_locked else R.string.shortcut_unavailable
            } catch (e: Exception) {
                Log.w(TAG, "could not start shortcut $packageName/$shortcutId", e)
                R.string.shortcut_unavailable
            }
        }
        Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
        return false
    }

    private fun startLink(context: Context, uri: String, fromPage: Boolean): Boolean {
        val parsed = Uri.parse(uri.trim())
        if (UrlRouter.isRefusedIntentData(parsed.scheme)) {
            toast(context)
            return false
        }
        val intent = when (LauncherLinks.actionFor(uri)) {
            LinkAction.Dial -> Intent(Intent.ACTION_DIAL, parsed)
            LinkAction.SendTo -> Intent(Intent.ACTION_SENDTO, parsed)
            LinkAction.ParseIntent -> return openIntentUrl(context, uri, UrlRouter(null), fromPage) == Route.Block
            LinkAction.View -> Intent(Intent.ACTION_VIEW, parsed).apply {
                if (fromPage) addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        return start(context, intent)
    }

    private fun start(context: Context, intent: Intent, toastOnFailure: Boolean = true): Boolean {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            if (toastOnFailure) toast(context)
            false
        } catch (e: Exception) {
            // SecurityException, FileUriExposedException, a malformed intent …: never crash the shell.
            Log.w(TAG, "could not start: $intent", e)
            if (toastOnFailure) toast(context)
            false
        }
    }

    private fun toast(context: Context) {
        Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
    }
}
