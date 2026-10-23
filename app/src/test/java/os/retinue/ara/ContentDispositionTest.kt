package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.shell.ContentDisposition

class ContentDispositionTest {

    @Test
    fun readsTheHeadersRetinueSends() {
        // The web gateway's view and save links: an ASCII fallback, then RFC 6266's filename*.
        val view = ContentDisposition.parse(
            "inline; filename=\"Rechnung M?rz.pdf\"; filename*=UTF-8''Rechnung%20M%C3%A4rz.pdf",
        )!!
        assertTrue(view.inline)
        assertEquals("Rechnung März.pdf", view.filename)
        val save = ContentDisposition.parse(
            "attachment; filename=\"invoice.pdf\"; filename*=UTF-8''invoice.pdf",
        )!!
        assertFalse(save.inline)
        assertEquals("invoice.pdf", save.filename)
    }

    @Test
    fun readsPlainFilenames() {
        assertEquals("report.pdf", ContentDisposition.parse("attachment; filename=\"report.pdf\"")!!.filename)
        assertEquals("report.pdf", ContentDisposition.parse("attachment; filename=report.pdf")!!.filename)
        assertEquals("a \"b\" c.txt", ContentDisposition.parse("attachment; filename=\"a \\\"b\\\" c.txt\"")!!.filename)
        assertEquals("x; y.txt", ContentDisposition.parse("attachment; filename=\"x; y.txt\"; size=3")!!.filename)
    }

    @Test
    fun prefersFilenameStarWhicheverComesFirst() {
        val header = "attachment; filename*=UTF-8''%E2%82%AC%20rates.txt; filename=\"EUR rates.txt\""
        assertEquals("€ rates.txt", ContentDisposition.parse(header)!!.filename)
        assertEquals("café.txt", ContentDisposition.parse("attachment; filename*=iso-8859-1'fr'caf%E9.txt")!!.filename)
    }

    @Test
    fun fallsBackToFilenameWhenFilenameStarDoesNotDecode() {
        for (star in listOf("UTF-16''x.txt", "UTF-8''bad%zz.txt", "UTF-8''cut%4", "UTF-8''%C3", "no-quotes.txt")) {
            assertEquals("plain.txt", ContentDisposition.parse("attachment; filename=plain.txt; filename*=$star")!!.filename, star)
        }
    }

    @Test
    fun typesAndCase() {
        assertTrue(ContentDisposition.parse("INLINE; FileName*=UTF-8''a.pdf")!!.inline)
        assertEquals("a.pdf", ContentDisposition.parse("INLINE; FileName*=UTF-8''a.pdf")!!.filename)
        // An unknown type is an attachment; so is a header that starts with a parameter.
        assertFalse(ContentDisposition.parse("form-data; filename=a.pdf")!!.inline)
        assertEquals("a.pdf", ContentDisposition.parse("filename=a.pdf")!!.filename)
        val bare = ContentDisposition.parse("inline")!!
        assertTrue(bare.inline)
        assertNull(bare.filename)
    }

    @Test
    fun nothingToRead() {
        assertNull(ContentDisposition.parse(null))
        assertNull(ContentDisposition.parse("  "))
        assertNull(ContentDisposition.parse("attachment; filename=\"\"")!!.filename)
    }
}
