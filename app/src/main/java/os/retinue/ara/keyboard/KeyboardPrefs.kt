package os.retinue.ara.keyboard

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * What the keyboard keeps on the phone: whether it is on, the device it last
 * typed into, and the layout for each device (by Bluetooth address). Nothing
 * typed is ever kept — neither text nor secrets.
 */
class KeyboardPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("keyboard", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, false)
        set(v) = prefs.edit().putBoolean(K_ENABLED, v).apply()

    var lastHost: String?
        get() = prefs.getString(K_LAST_HOST, null)
        set(v) = prefs.edit().putString(K_LAST_HOST, v).apply()

    /** The layout Ara types for on [address]: as chosen, or a guess from the phone's language. */
    fun layoutFor(address: String?): String {
        val stored = address?.let { prefs.getString(K_LAYOUT + it, null) }
        return KeyLayouts.byId(stored)?.id ?: KeyLayouts.defaultFor(Locale.getDefault())
    }

    fun setLayout(address: String, layout: String) {
        if (KeyLayouts.byId(layout) == null) return
        prefs.edit().putString(K_LAYOUT + address, layout).apply()
    }

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_LAST_HOST = "last_host"
        const val K_LAYOUT = "layout."
    }
}
