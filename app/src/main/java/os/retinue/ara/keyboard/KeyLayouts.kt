package os.retinue.ara.keyboard

import java.text.Normalizer
import java.util.Locale
import os.retinue.ara.keyboard.HidKeys.KEY_102ND
import os.retinue.ara.keyboard.HidKeys.KEY_A
import os.retinue.ara.keyboard.HidKeys.KEY_APOSTROPHE
import os.retinue.ara.keyboard.HidKeys.KEY_BACKSLASH
import os.retinue.ara.keyboard.HidKeys.KEY_COMMA
import os.retinue.ara.keyboard.HidKeys.KEY_DOT
import os.retinue.ara.keyboard.HidKeys.KEY_ENTER
import os.retinue.ara.keyboard.HidKeys.KEY_EQUAL
import os.retinue.ara.keyboard.HidKeys.KEY_GRAVE
import os.retinue.ara.keyboard.HidKeys.KEY_LEFTBRACE
import os.retinue.ara.keyboard.HidKeys.KEY_MINUS
import os.retinue.ara.keyboard.HidKeys.KEY_NONUS_HASH
import os.retinue.ara.keyboard.HidKeys.KEY_RIGHTBRACE
import os.retinue.ara.keyboard.HidKeys.KEY_SEMICOLON
import os.retinue.ara.keyboard.HidKeys.KEY_SLASH
import os.retinue.ara.keyboard.HidKeys.KEY_SPACE
import os.retinue.ara.keyboard.HidKeys.KEY_TAB
import os.retinue.ara.keyboard.HidKeys.MOD_LSHIFT
import os.retinue.ara.keyboard.HidKeys.MOD_NONE
import os.retinue.ara.keyboard.HidKeys.MOD_RALT
import os.retinue.ara.keyboard.HidKeys.Stroke

/**
 * What a character is on a keyboard layout: the key presses that produce it.
 *
 * A HID keyboard sends key *positions*; the host turns them into characters
 * with the layout it is set to. So Ara has to type for the layout the other
 * device uses — which the user picks once per device ([KeyboardPrefs]). Most
 * characters are one press; a character a layout makes with a dead key (`^`
 * on a German layout, `É` on a Swiss one) is the dead key and then the base
 * letter, or a space for the accent alone.
 *
 * The tables follow the layouts as Windows, Linux, ChromeOS and Android
 * (the Quest included) define them. macOS puts many AltGr characters
 * elsewhere on its German and Swiss layouts, so those come out wrong there.
 *
 * Pure, so the tables are pinned down by unit tests.
 */
class KeyLayout internal constructor(
    val id: String,
    val label: String,
    private val direct: Map<Char, Stroke>,
    /** Dead keys by combining mark (U+0301 acute, …): press it, then the base letter. */
    private val dead: Map<Char, Stroke>,
) {
    /** The presses for [c], or null when this layout cannot type it. */
    fun strokesFor(c: Char): List<Stroke>? {
        direct[c]?.let { return listOf(it) }
        // An accent alone: the dead key, then a space.
        SPACING_ACCENTS[c]?.let { mark -> dead[mark]?.let { return listOf(it, SPACE) } }
        // An accented letter: the dead key, then the letter — only for the
        // letters a dead key composes on every host (Windows composes far
        // fewer than Linux): "ń" would come out as "´n", so it is refused.
        val nfd = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        if (nfd.length == 2 && COMPOSABLE[nfd[1]]?.contains(nfd[0]) == true) {
            val base = direct[nfd[0]] ?: return null
            val accent = dead[nfd[1]] ?: return null
            return listOf(accent, base)
        }
        return null
    }

    companion object {
        private val SPACE = Stroke(MOD_NONE, KEY_SPACE)

        /** The letters each dead key composes with, by its combining mark. */
        private val COMPOSABLE = mapOf(
            '\u0301' to "aeiouyAEIOUY", // ´
            '\u0300' to "aeiouAEIOU", // `
            '\u0302' to "aeiouAEIOU", // ^
            '\u0308' to "aeiouyAEIOU", // ¨
            '\u0303' to "anoANO", // ~
        )

        /**
         * Accents typed alone (the dead key, then a space), by the combining
         * mark their dead key carries. Not ´ and ¨: on Linux hosts the dead
         * key and a space make ' and " instead, and no sequence types them
         * alone on both — so they count as missing.
         */
        private val SPACING_ACCENTS = mapOf(
            '^' to '̂',
            '`' to '̀',
            '~' to '̃',
        )
    }
}

/** The result of mapping a whole text: every press, or the first character that cannot be typed. */
sealed class Mapped {
    data class Ok(val strokes: List<Stroke>) : Mapped()
    data class Unsupported(val index: Int, val char: Char) : Mapped()
}

object KeyLayouts {

    const val US = "us"
    const val DE_CH = "de-CH"
    const val FR_CH = "fr-CH"
    const val DE_DE = "de-DE"

    /** The layouts in the order the settings list them. */
    val ALL: List<KeyLayout> by lazy { listOf(us(), deCh(), frCh(), deDe()) }

    fun byId(id: String?): KeyLayout? = ALL.firstOrNull { it.id == id }

    /** A first guess for a new device: the phone's own language and country. */
    fun defaultFor(locale: Locale): String = when {
        locale.language == "de" && locale.country in setOf("CH", "LI") -> DE_CH
        locale.language == "fr" && locale.country == "CH" -> FR_CH
        locale.language == "de" -> DE_DE
        else -> US
    }

    /**
     * Every press for [text] on [layout], checked whole before anything is
     * typed, so a character the layout lacks never leaves half a text on the
     * other device. Never copies [text] into a String (a secret arrives as a
     * wrapped char array). `\r\n` and `\r` count as one line break; a line break is
     * Shift+Enter (a new line in a text area and in chat apps, where a plain
     * Enter would send), a tab is Tab.
     */
    fun map(text: CharSequence, layout: KeyLayout): Mapped {
        val out = ArrayList<Stroke>(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            out += charStrokes(c, layout) ?: return Mapped.Unsupported(i, c)
            i += step(text, i)
        }
        return Mapped.Ok(out)
    }

    /** Whether [layout] can type [c]; a line break and a tab always. */
    fun typeable(c: Char, layout: KeyLayout): Boolean = charStrokes(c, layout) != null

    /**
     * The presses for [text], leaving out what [layout] cannot type. For the
     * live field, which takes only typeable characters ([typeable]) but may
     * meet a layout changed under it; everything else uses [map], which
     * refuses the whole text instead.
     */
    fun typeableStrokes(text: CharSequence, layout: KeyLayout): List<Stroke> {
        val out = ArrayList<Stroke>(text.length + 8)
        var i = 0
        while (i < text.length) {
            charStrokes(text[i], layout)?.let { out += it }
            i += step(text, i)
        }
        return out
    }

    private fun charStrokes(c: Char, layout: KeyLayout): List<Stroke>? = when (c) {
        '\r', '\n' -> listOf(Stroke(MOD_LSHIFT, KEY_ENTER))
        '\t' -> listOf(Stroke(MOD_NONE, KEY_TAB))
        else -> layout.strokesFor(c)
    }

    /** \r\n is one line break. */
    private fun step(text: CharSequence, i: Int): Int =
        if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') 2 else 1

    // ── Tables ─────────────────────────────────────────────────────────────

    private class Builder {
        val direct = HashMap<Char, Stroke>()
        val dead = HashMap<Char, Stroke>()
        fun put(c: Char, key: Int, mod: Int = MOD_NONE) { direct.putIfAbsent(c, Stroke(mod, key)) }
        fun shift(c: Char, key: Int) = put(c, key, MOD_LSHIFT)
        fun altGr(c: Char, key: Int) = put(c, key, MOD_RALT)
        fun dead(mark: Char, key: Int, mod: Int = MOD_NONE) { dead[mark] = Stroke(mod, key).also { it.joinsNext = true } }

        /** Letters a–z at their US positions, with [swapYZ] for QWERTZ. */
        fun letters(swapYZ: Boolean) {
            for (i in 0 until 26) {
                var c = 'a' + i
                if (swapYZ && c == 'y') c = 'z' else if (swapYZ && c == 'z') c = 'y'
                put(c, KEY_A + i)
                shift(c.uppercaseChar(), KEY_A + i)
            }
        }

        fun digits() {
            for (d in 1..9) put('0' + d, HidKeys.KEY_1 + d - 1)
            put('0', HidKeys.KEY_0)
            put(' ', KEY_SPACE)
        }

        /** [shifted] in the order of the keys 1…9, 0. */
        fun shiftedDigits(shifted: String) {
            shifted.forEachIndexed { i, c -> if (c != ' ') shift(c, HidKeys.KEY_1 + i) }
        }

        fun build(id: String, label: String) = KeyLayout(id, label, direct, dead)
    }

    private fun us() = Builder().apply {
        letters(swapYZ = false)
        digits()
        shiftedDigits("!@#\$%^&*()")
        put('-', KEY_MINUS); shift('_', KEY_MINUS)
        put('=', KEY_EQUAL); shift('+', KEY_EQUAL)
        put('[', KEY_LEFTBRACE); shift('{', KEY_LEFTBRACE)
        put(']', KEY_RIGHTBRACE); shift('}', KEY_RIGHTBRACE)
        put('\\', KEY_BACKSLASH); shift('|', KEY_BACKSLASH)
        put(';', KEY_SEMICOLON); shift(':', KEY_SEMICOLON)
        put('\'', KEY_APOSTROPHE); shift('"', KEY_APOSTROPHE)
        put('`', KEY_GRAVE); shift('~', KEY_GRAVE)
        put(',', KEY_COMMA); shift('<', KEY_COMMA)
        put('.', KEY_DOT); shift('>', KEY_DOT)
        put('/', KEY_SLASH); shift('?', KEY_SLASH)
    }.build(US, "English (US)")

    /** German (Switzerland), QWERTZ. Capital umlauts come from the ¨ dead key. */
    private fun deCh() = Builder().apply {
        swiss()
        put('ü', KEY_LEFTBRACE); shift('è', KEY_LEFTBRACE)
        put('ö', KEY_SEMICOLON); shift('é', KEY_SEMICOLON)
        put('ä', KEY_APOSTROPHE); shift('à', KEY_APOSTROPHE)
    }.build(DE_CH, "German (Switzerland)")

    /**
     * French (Switzerland): the Swiss layout with the three keys right of
     * the letters the other way round — è/ü, é/ö, à/ä.
     */
    private fun frCh() = Builder().apply {
        swiss()
        put('è', KEY_LEFTBRACE); shift('ü', KEY_LEFTBRACE)
        put('é', KEY_SEMICOLON); shift('ö', KEY_SEMICOLON)
        put('à', KEY_APOSTROPHE); shift('ä', KEY_APOSTROPHE)
    }.build(FR_CH, "French (Switzerland)")

    /**
     * What the Swiss layouts share. AltGr+1 (`¦` on Windows, `|` on Linux)
     * is left out: it differs between hosts.
     */
    private fun Builder.swiss() {
        letters(swapYZ = true)
        digits()
        shiftedDigits("+\"*ç%&/()=")
        altGr('@', HidKeys.KEY_1 + 1)
        altGr('#', HidKeys.KEY_1 + 2)
        altGr('¬', HidKeys.KEY_1 + 5)
        altGr('|', HidKeys.KEY_1 + 6)
        altGr('¢', HidKeys.KEY_1 + 7)
        put('\'', KEY_MINUS); shift('?', KEY_MINUS)
        dead('́', KEY_MINUS, MOD_RALT) // ´
        dead('̂', KEY_EQUAL) // ^
        dead('̀', KEY_EQUAL, MOD_LSHIFT) // `
        dead('̃', KEY_EQUAL, MOD_RALT) // ~
        altGr('[', KEY_LEFTBRACE)
        dead('̈', KEY_RIGHTBRACE) // ¨
        shift('!', KEY_RIGHTBRACE); altGr(']', KEY_RIGHTBRACE)
        put('$', KEY_NONUS_HASH); shift('£', KEY_NONUS_HASH); altGr('}', KEY_NONUS_HASH)
        altGr('{', KEY_APOSTROPHE)
        put('§', KEY_GRAVE); shift('°', KEY_GRAVE)
        put(',', KEY_COMMA); shift(';', KEY_COMMA)
        put('.', KEY_DOT); shift(':', KEY_DOT)
        put('-', KEY_SLASH); shift('_', KEY_SLASH)
        put('<', KEY_102ND); shift('>', KEY_102ND); altGr('\\', KEY_102ND)
        altGr('€', KEY_A + ('e' - 'a'))
    }

    /** German (Germany), QWERTZ. */
    private fun deDe() = Builder().apply {
        letters(swapYZ = true)
        digits()
        shiftedDigits("!\"§\$%&/()=")
        altGr('²', HidKeys.KEY_1 + 1)
        altGr('³', HidKeys.KEY_1 + 2)
        altGr('{', HidKeys.KEY_1 + 6)
        altGr('[', HidKeys.KEY_1 + 7)
        altGr(']', HidKeys.KEY_1 + 8)
        altGr('}', HidKeys.KEY_0)
        put('ß', KEY_MINUS); shift('?', KEY_MINUS); altGr('\\', KEY_MINUS)
        dead('́', KEY_EQUAL) // ´
        dead('̀', KEY_EQUAL, MOD_LSHIFT) // `
        put('ü', KEY_LEFTBRACE); shift('Ü', KEY_LEFTBRACE)
        put('+', KEY_RIGHTBRACE); shift('*', KEY_RIGHTBRACE); altGr('~', KEY_RIGHTBRACE)
        put('#', KEY_NONUS_HASH); shift('\'', KEY_NONUS_HASH)
        put('ö', KEY_SEMICOLON); shift('Ö', KEY_SEMICOLON)
        put('ä', KEY_APOSTROPHE); shift('Ä', KEY_APOSTROPHE)
        dead('̂', KEY_GRAVE) // ^
        shift('°', KEY_GRAVE)
        put(',', KEY_COMMA); shift(';', KEY_COMMA)
        put('.', KEY_DOT); shift(':', KEY_DOT)
        put('-', KEY_SLASH); shift('_', KEY_SLASH)
        put('<', KEY_102ND); shift('>', KEY_102ND); altGr('|', KEY_102ND)
        altGr('@', KEY_A + ('q' - 'a'))
        altGr('€', KEY_A + ('e' - 'a'))
        altGr('µ', KEY_A + ('m' - 'a'))
    }.build(DE_DE, "German (Germany)")
}
