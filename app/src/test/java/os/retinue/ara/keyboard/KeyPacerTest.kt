package os.retinue.ara.keyboard

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import os.retinue.ara.keyboard.HidKeys.MOD_LSHIFT
import os.retinue.ara.keyboard.HidKeys.MOD_NONE
import os.retinue.ara.keyboard.HidKeys.Stroke

class KeyPacerTest {

    @Test
    fun everyPressIsReleasedAndModifiersGoFirst() {
        val reports = KeyPacer.reports(listOf(Stroke(MOD_NONE, 0x0F), Stroke(MOD_NONE, 0x0F), Stroke(MOD_LSHIFT, 0x04)))
        val expected = listOf(
            byteArrayOf(0, 0, 0x0F, 0, 0, 0, 0, 0), ByteArray(8),
            byteArrayOf(0, 0, 0x0F, 0, 0, 0, 0, 0), ByteArray(8),
            byteArrayOf(MOD_LSHIFT.toByte(), 0, 0, 0, 0, 0, 0, 0),
            byteArrayOf(MOD_LSHIFT.toByte(), 0, 0x04, 0, 0, 0, 0, 0), ByteArray(8),
        )
        assertEquals(expected.size, reports.size)
        expected.zip(reports).forEach { (e, r) -> assertContentEquals(e, r) }
    }

    @Test
    fun pausesAfterEveryReport() = runBlocking {
        val pauses = mutableListOf<Long>()
        var sent = 0
        val done = KeyPacer.send(listOf(Stroke(MOD_NONE, 4), Stroke(MOD_LSHIFT, 5)), delayMs = 12, pause = { pauses += it }) { sent++; true }
        assertEquals(2, done)
        assertEquals(5, sent)
        assertEquals(List(5) { 12L }, pauses)
    }

    @Test
    fun stopsWhenTheHostGoesAndReleasesKeys() = runBlocking {
        val seen = mutableListOf<ByteArray>()
        var n = 0
        val done = KeyPacer.send(listOf(Stroke(MOD_NONE, 4), Stroke(MOD_NONE, 5), Stroke(MOD_NONE, 6)), pause = {}) { r ->
            seen += r
            n++ != 2 // the third report (second press) is refused
        }
        assertEquals(1, done)
        assertContentEquals(ByteArray(8), seen.last())
    }

    @Test
    fun stopsWhenCancelledAndReleasesKeys() = runBlocking {
        val seen = mutableListOf<ByteArray>()
        var presses = 0
        val done = KeyPacer.send(
            listOf(Stroke(MOD_NONE, 4), Stroke(MOD_NONE, 5), Stroke(MOD_NONE, 6)),
            pause = {},
            cancelled = { presses >= 1 },
        ) { r ->
            seen += r.copyOf()
            if (r.any { it != 0.toByte() }) presses++
            true
        }
        assertEquals(1, done)
        assertContentEquals(ByteArray(8), seen.last())
    }

    @Test
    fun neverStopsBetweenADeadKeyAndItsLetter() = runBlocking {
        val de = KeyLayouts.byId(KeyLayouts.DE_DE)!!
        val strokes = (KeyLayouts.map("éa", de) as Mapped.Ok).strokes // ´, e, a
        var presses = 0
        val done = KeyPacer.send(strokes, pause = {}, cancelled = { presses >= 1 }) { r ->
            if (r.any { it != 0.toByte() } && r[2] != 0.toByte()) presses++
            true
        }
        // Stop asked after the dead key: its letter still goes, then it stops.
        assertEquals(2, done)
    }

    @Test
    fun reportsAreOverwrittenOnceSent() = runBlocking {
        val kept = mutableListOf<ByteArray>()
        KeyPacer.send(listOf(Stroke(MOD_LSHIFT, 4)), pause = {}) { r -> kept += r; true }
        // The arrays handed out are zeroed afterwards: a secret's reports do not linger.
        assertTrue(kept.all { r -> r.all { it == 0.toByte() } })
    }

    @Test
    fun capsLockIsTypedAround() {
        val text = listOf(Stroke(MOD_NONE, 4))
        assertEquals(text, HidKeys.aroundCapsLock(text, capsOn = false))
        val caps = Stroke(MOD_NONE, HidKeys.KEY_CAPSLOCK)
        assertEquals(listOf(caps) + text + caps, HidKeys.aroundCapsLock(text, capsOn = true))
        assertEquals(0x02.toByte(), HidKeys.ledByte(byteArrayOf(0x02)))
        assertEquals(0x02.toByte(), HidKeys.ledByte(byteArrayOf(HidKeys.REPORT_ID.toByte(), 0x02)))
        assertNull(HidKeys.ledByte(ByteArray(0)))
        assertTrue(HidKeys.capsLockOn(0x02))
        assertTrue(HidKeys.capsLockOn(0x03))
        assertFalse(HidKeys.capsLockOn(0x01))
    }

    @Test
    fun descriptorIsABootKeyboardWithReportId() {
        // Pinned whole: a changed byte changes what every host makes of Ara.
        val expected = intArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, HidKeys.REPORT_ID,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
        ).map { it.toByte() }.toByteArray()
        assertContentEquals(expected, HidKeys.DESCRIPTOR)
    }
}
