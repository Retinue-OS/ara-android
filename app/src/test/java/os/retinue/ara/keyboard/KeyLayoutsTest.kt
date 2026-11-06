package os.retinue.ara.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Locale
import org.junit.Test
import os.retinue.ara.keyboard.HidKeys.KEY_A
import os.retinue.ara.keyboard.HidKeys.KEY_ENTER
import os.retinue.ara.keyboard.HidKeys.MOD_LSHIFT
import os.retinue.ara.keyboard.HidKeys.MOD_NONE
import os.retinue.ara.keyboard.HidKeys.MOD_RALT
import os.retinue.ara.keyboard.HidKeys.Stroke

class KeyLayoutsTest {

    private val us = KeyLayouts.byId(KeyLayouts.US)!!
    private val ch = KeyLayouts.byId(KeyLayouts.DE_CH)!!
    private val de = KeyLayouts.byId(KeyLayouts.DE_DE)!!

    private fun key(c: Char) = KEY_A + (c - 'a')
    private fun one(layout: KeyLayout, c: Char) = layout.strokesFor(c)!!.single()

    @Test
    fun usLettersDigitsAndSymbols() {
        assertEquals(Stroke(MOD_NONE, key('a')), one(us, 'a'))
        assertEquals(Stroke(MOD_LSHIFT, key('z')), one(us, 'Z'))
        assertEquals(Stroke(MOD_NONE, HidKeys.KEY_1), one(us, '1'))
        assertEquals(Stroke(MOD_LSHIFT, HidKeys.KEY_1 + 1), one(us, '@'))
        assertEquals(Stroke(MOD_NONE, HidKeys.KEY_0), one(us, '0'))
        assertNull(us.strokesFor('ü'))
    }

    @Test
    fun qwertzSwapsYAndZ() {
        assertEquals(Stroke(MOD_NONE, key('y')), one(ch, 'z'))
        assertEquals(Stroke(MOD_NONE, key('z')), one(ch, 'y'))
        assertEquals(Stroke(MOD_LSHIFT, key('y')), one(de, 'Z'))
    }

    @Test
    fun swissGerman() {
        assertEquals(Stroke(MOD_RALT, HidKeys.KEY_1 + 1), one(ch, '@'))
        assertEquals(Stroke(MOD_NONE, HidKeys.KEY_LEFTBRACE), one(ch, 'ü'))
        assertEquals(Stroke(MOD_NONE, HidKeys.KEY_SEMICOLON), one(ch, 'ö'))
        assertEquals(Stroke(MOD_NONE, HidKeys.KEY_APOSTROPHE), one(ch, 'ä'))
        assertEquals(Stroke(MOD_LSHIFT, HidKeys.KEY_SEMICOLON), one(ch, 'é'))
        assertEquals(Stroke(MOD_RALT, key('e')), one(ch, '€'))
        assertEquals(Stroke(MOD_LSHIFT, HidKeys.KEY_1 + 2), one(ch, '*'))
        // Capital umlauts: the ¨ dead key, then the capital letter.
        assertEquals(listOf(Stroke(MOD_NONE, HidKeys.KEY_RIGHTBRACE), Stroke(MOD_LSHIFT, key('u'))), ch.strokesFor('Ü'))
        // An accented letter without a key of its own: dead key, then the letter.
        assertEquals(listOf(Stroke(MOD_NONE, HidKeys.KEY_EQUAL), Stroke(MOD_NONE, key('o'))), ch.strokesFor('ô'))
        // The accent alone: dead key, then space.
        assertEquals(listOf(Stroke(MOD_NONE, HidKeys.KEY_EQUAL), Stroke(MOD_NONE, HidKeys.KEY_SPACE)), ch.strokesFor('^'))
    }

    @Test
    fun germanGermany() {
        assertEquals(Stroke(MOD_RALT, key('q')), one(de, '@'))
        assertEquals(Stroke(MOD_NONE, HidKeys.KEY_MINUS), one(de, 'ß'))
        assertEquals(Stroke(MOD_LSHIFT, HidKeys.KEY_APOSTROPHE), one(de, 'Ä'))
        assertEquals(Stroke(MOD_NONE, HidKeys.KEY_NONUS_HASH), one(de, '#'))
        assertEquals(listOf(Stroke(MOD_NONE, HidKeys.KEY_EQUAL), Stroke(MOD_NONE, key('e'))), de.strokesFor('é'))
    }

    @Test
    fun everyPrintableAsciiOnEveryLayout() {
        for (layout in KeyLayouts.ALL) {
            for (c in ' '..'~') {
                assertTrue(layout.strokesFor(c) != null, "${layout.id} lacks '$c'")
            }
        }
    }

    @Test
    fun mapChecksTheWholeTextFirst() {
        val ok = KeyLayouts.map("Hi\r\nyou\t!", us) as Mapped.Ok
        // H i ⇧Enter y o u Tab !
        assertEquals(8, ok.strokes.size)
        assertEquals(Stroke(MOD_LSHIFT, KEY_ENTER), ok.strokes[2])
        val bad = KeyLayouts.map("ok ✓ no", us)
        assertEquals(Mapped.Unsupported(3, '✓'), bad)
    }

    @Test
    fun defaultLayoutFollowsThePhone() {
        assertEquals(KeyLayouts.DE_CH, KeyLayouts.defaultFor(Locale("de", "CH")))
        assertEquals(KeyLayouts.DE_DE, KeyLayouts.defaultFor(Locale("de", "AT")))
        assertEquals(KeyLayouts.US, KeyLayouts.defaultFor(Locale("fr", "CH")))
    }
}
