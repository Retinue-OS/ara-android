package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.shell.FileNames

class FileNamesTest {

    @Test
    fun keepsPlainNames() {
        assertEquals("report.pdf", FileNames.sanitize("report.pdf"))
        assertEquals("Grüezi mitenand.txt", FileNames.sanitize(" Grüezi mitenand.txt "))
    }

    @Test
    fun dropsDirectoriesAndTraversal() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("x.txt", FileNames.sanitize("..\\..\\x.txt"))
        assertEquals("hidden", FileNames.sanitize(".hidden"))
        assertEquals("download", FileNames.sanitize(".."))
        assertEquals("download", FileNames.sanitize("dir/"))
    }

    @Test
    fun replacesReservedAndControlCharacters() {
        assertEquals("a_b_c_d_.txt", FileNames.sanitize("a:b*c?d\".txt"))
        assertEquals("a_b", FileNames.sanitize("a\u0000b"))
        assertEquals("x_y_z", FileNames.sanitize("x<y>z"))
        assertEquals("download", FileNames.sanitize("|||"))
        assertEquals("download", FileNames.sanitize(null))
        assertEquals("download", FileNames.sanitize("   "))
    }

    @Test
    fun cutsLongNamesKeepingTheExtension() {
        val long = "a".repeat(300) + ".pdf"
        val cut = FileNames.sanitize(long)
        assertEquals(FileNames.MAX_LENGTH, cut.length)
        assertTrue(cut.endsWith(".pdf"))
        assertEquals(FileNames.MAX_LENGTH, FileNames.sanitize("b".repeat(500)).length)
    }
}
