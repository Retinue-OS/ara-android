package os.retinue.ara.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The rule of what a page's text may type by itself (KeyboardLogic.onlyWrites)
 * against its twin, `runs_by_itself` in the retinue repository's
 * scripts/shell_actions.py (tests/test_shell_type.py, and needsTap in
 * tests/test_webapp_shell_keyboard.py): the same table of cases. The app's
 * rule must never be stricter, so every case that runs by itself there is
 * plain one-line text here. The table was checked against the twin itself.
 */
class KeyboardRuleTest {

    /** text → whether the twin runs it by itself (without Enter); with Enter it never does. */
    private val twin = listOf(
        "hello" to true,
        "a b" to true,
        // A line break is Shift+Enter, which submits in a terminal or a one-line field.
        "ls\nrm -rf ~" to false,
        "a\rb" to false,
        // A tab moves to a form's button; inside the text as well.
        "me@x.org\t\t " to false,
        "user\tpass" to false,
        // Only blanks, or a blank at an end (a space presses a focused button).
        " " to false,
        "  " to false,
        " a" to false,
        "a " to false,
        " x" to false,
        "" to false,
        // Python's blanks, not Kotlin's: these are str.isspace() there.
        "x " to false,
        "　x" to false,
        "x " to false,
        " x" to false,
        "\u001cx" to false,
        "x\u000b" to false,
        "\u0085x" to false,
        "x " to false,
        "x " to false,
        // And these are not, so the twin types them by itself: so must the app.
        "​x" to true,
        "᠎x" to true,
        "x﻿" to true,
        "Grüezi — 你好" to true,
        "😀" to true,
        "a b" to true,
        "a b" to true,
    )

    @Test
    fun theTwinsTableOfCases() {
        for ((text, byItself) in twin) {
            assertEquals(byItself, KeyboardLogic.onlyWrites(text, enter = false), "\"${text.escaped()}\"")
            // Enter always waits for the phone.
            assertFalse(KeyboardLogic.onlyWrites(text, enter = true), "\"${text.escaped()}\" with Enter")
        }
    }

    @Test
    fun blanksAreExactlyPythonsStrIsspace() {
        // [c for c in range(0x10000) if chr(c).isspace()], Python 3.13; none lies beyond the BMP.
        val python = listOf(
            0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a,
            0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
        )
        assertEquals(python, (0..0xFFFF).filter { KeyboardLogic.isBlank(it.toChar()) })
        // So a blank of Python's at either end acts; any other character there writes.
        for (c in 0..0xFFFF) {
            val ch = c.toChar()
            if (ch.isSurrogate()) continue
            val inner = ch != '\r' && ch != '\n' && ch != '\t'
            assertEquals(c !in python, KeyboardLogic.onlyWrites("${ch}x", enter = false), "U+%04X first".format(c))
            assertEquals(c !in python, KeyboardLogic.onlyWrites("x$ch", enter = false), "U+%04X last".format(c))
            assertEquals(inner, KeyboardLogic.onlyWrites("x${ch}x", enter = false), "U+%04X inside".format(c))
        }
    }

    @Test
    fun everyKeyActs() {
        for (name in HidKeys.NAMED.keys) {
            assertFalse(KeyboardLogic.onlyWrites(PageRequest.Key(name, null)), name)
        }
        assertFalse(KeyboardLogic.onlyWrites(PageRequest.Secret("Wi-Fi", enter = false, id = null)))
        assertTrue(KeyboardLogic.onlyWrites(PageRequest.Type("hello", enter = false, id = null)))
        assertFalse(KeyboardLogic.onlyWrites(PageRequest.Type("hello", enter = true, id = null)))
    }

    private fun String.escaped() = map { if (it.code < 0x20 || it.code > 0x7e) "\\u%04x".format(it.code) else "$it" }.joinToString("")
}
