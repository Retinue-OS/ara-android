package os.retinue.ara.launcher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * When apps were last used, for the tray's order (most recent first). Two
 * sources: the launches Ara made itself (an app or web app started from the
 * bar, a folder, All apps or the tray), stored by [LauncherEntry.Launchable.key],
 * and — once the user grants Ara usage access — Android's own usage stats,
 * by package, which also see apps opened from a notification or Recents.
 */
object AppRecency {

    /** How many launches are kept: the most recent ones (the tray needs no more). */
    const val MAX_LAUNCHES = 300

    /**
     * The last use of an item: the later of Ara's own launch of [key] and
     * Android's last sight of [packageName] (null for a web app, whose package
     * is the browser that installed it: its use says nothing about the web
     * app). 0 for never.
     */
    fun lastUsed(key: String, packageName: String?, launches: Map<String, Long>, usage: Map<String, Long>): Long =
        maxOf(launches[key] ?: 0L, packageName?.let { usage[it] } ?: 0L)

    /**
     * [items] with the most recently used first; those never used keep their
     * order (by name, as listed) after them.
     */
    fun <T> recentFirst(items: List<T>, lastUsed: (T) -> Long): List<T> =
        // sortedByDescending is stable: equal times (and all the never-used) keep their order.
        items.sortedByDescending { lastUsed(it).coerceAtLeast(0L) }

    /** [launches] with [key] launched at [at], trimmed to the [limit] most recent. */
    fun recorded(launches: Map<String, Long>, key: String, at: Long, limit: Int = MAX_LAUNCHES): Map<String, Long> {
        val updated = launches + (key to at)
        if (updated.size <= limit) return updated
        return updated.entries.sortedByDescending { it.value }.take(limit).associate { it.key to it.value }
    }

    /** The stored form: a JSON object of key → epoch ms. */
    fun encode(launches: Map<String, Long>): String =
        JsonObject(launches.mapValues { JsonPrimitive(it.value) }).toString()

    /** The stored form read back; anything unreadable counts as never launched. */
    fun decode(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: IllegalArgumentException) {
            // SerializationException is one.
            null
        } ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let { k to it } }.toMap()
    }
}

/**
 * The tray's height logic (pure, for the tests). The tray is the launcher bar
 * pulled up: below the bar it shows more lines of apps, one [row] high each,
 * up to [max] (below the web app's top menu, or where the apps end).
 */
object TrayLogic {

    /**
     * Where the tray comes to rest when let go at [expansion] (px above the
     * bar) with [velocity] (px/s, positive = upwards). A fling past
     * [flingVelocity] goes all the way, up or down; otherwise the tray settles
     * on the nearest whole line, or on [max].
     */
    fun settle(expansion: Float, velocity: Float, row: Float, max: Float, flingVelocity: Float): Float {
        if (max <= 0f) return 0f
        val e = expansion.coerceIn(0f, max)
        return when {
            velocity > flingVelocity -> max
            velocity < -flingVelocity -> 0f
            row <= 0f -> if (e * 2 >= max) max else 0f
            else -> {
                val lower = kotlin.math.floor(e / row) * row
                val upper = minOf(lower + row, max)
                if (e - lower <= upper - e) lower else upper
            }
        }
    }

    /** How many lines [items] take in [columns] columns. */
    fun lines(items: Int, columns: Int): Int =
        if (items <= 0) 0 else (items + columns.coerceAtLeast(1) - 1) / columns.coerceAtLeast(1)

    /**
     * How far the tray goes up: to [available] (px between the bar at rest and
     * the web app's top menu), but no further than its [lines] of [row] px.
     */
    fun maxExpansion(available: Float, lines: Int, row: Float): Float =
        minOf(available, lines * row).coerceAtLeast(0f)

    /**
     * JavaScript that finds the bottom of the web app's top menu (its
     * navigation row `retinue-nav`, or a page's own `header`) while it is at
     * the top of the window, in device pixels; 0 when there is none.
     */
    const val TOP_MENU_SCRIPT =
        "(function(){var b=0,h=window.innerHeight||0,l=document.querySelectorAll('retinue-nav,header');" +
            "for(var i=0;i<l.length;i++){var r=l[i].getBoundingClientRect();" +
            "if(r.height>0&&r.top<=1&&r.bottom>b&&r.bottom<h/2)b=r.bottom;}" +
            "return Math.round(b*(window.devicePixelRatio||1));})();"

    /** The result of [TOP_MENU_SCRIPT] as `evaluateJavascript` reports it (JSON); null when unusable. */
    fun topMenuPx(result: String?): Float? =
        result?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
}
