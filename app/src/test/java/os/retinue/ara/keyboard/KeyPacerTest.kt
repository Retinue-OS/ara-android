package os.retinue.ara.keyboard

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
    fun descriptorIsABootKeyboardWithReportId() {
        val d = HidKeys.DESCRIPTOR
        assertContentEquals(byteArrayOf(0x05, 0x01, 0x09, 0x06), d.copyOfRange(0, 4))
        assertEquals(0xC0.toByte(), d.last())
        assertEquals(HidKeys.REPORT_ID.toByte(), d[7])
    }
}
