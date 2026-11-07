package os.retinue.ara

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.net.CertificateImport

/** What a picked file must be to go to Android's certificate installer, and the name it suggests. */
class CertificateImportTest {

    /** A PKCS#12 key store as the JDK writes it (DER, a MAC over the contents). */
    private fun pkcs12(withEntry: Boolean = true): ByteArray {
        val password = "secret".toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        if (withEntry) {
            store.setEntry(
                "ara",
                KeyStore.SecretKeyEntry(SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")),
                KeyStore.PasswordProtection(password),
            )
        }
        return ByteArrayOutputStream().also { store.store(it, password) }.toByteArray()
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun aPkcs12KeyStoreIsTaken() {
        assertTrue(CertificateImport.isPkcs12(pkcs12()))
        assertTrue(CertificateImport.isPkcs12(pkcs12(withEntry = false)))
        // With bytes after it (padding a tool left): the store itself is whole.
        assertTrue(CertificateImport.isPkcs12(pkcs12() + bytes(0, 0)))
        // BER with an indefinite length, as some tools write it.
        assertTrue(CertificateImport.isPkcs12(bytes(0x30, 0x80, 0x02, 0x01, 0x03, 0x30, 0x80, 0x06, 0x09, 0, 0, 0, 0)))
        // A short (one-byte) length.
        assertTrue(CertificateImport.isPkcs12(bytes(0x30, 0x06, 0x02, 0x01, 0x03, 0x30, 0x01, 0x00)))
    }

    @Test
    fun anythingElseIsNot() {
        val p12 = pkcs12()
        // An X.509 certificate (DER): a SEQUENCE that opens with another SEQUENCE, not version 3.
        val certificate = bytes(0x30, 0x82, 0x01, 0x0a, 0x30, 0x81, 0xf2, 0xa0, 0x03, 0x02, 0x01, 0x02) + ByteArray(262)
        val notPkcs12 = listOf(
            ByteArray(0),
            bytes(0x30),
            "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----\n".toByteArray(),
            "-----BEGIN PRIVATE KEY-----\n".toByteArray(),
            certificate,
            bytes(0xfe, 0xed, 0xfe, 0xed, 0, 0, 0, 2, 0, 0, 0, 1), // a Java key store
            bytes(0x50, 0x4b, 0x03, 0x04, 0x14, 0, 0, 0, 8, 0), // a zip
            // Version 2, and a version that is not one byte long.
            bytes(0x30, 0x06, 0x02, 0x01, 0x02, 0x30, 0x01, 0x00),
            bytes(0x30, 0x07, 0x02, 0x02, 0x00, 0x03, 0x30, 0x01, 0x00),
            // A SEQUENCE too short to hold the version and the ContentInfo that follow it.
            bytes(0x30, 0x01, 0x02, 0x01, 0x03, 0x30, 0x01, 0x00),
            bytes(0x30, 0x04, 0x02, 0x01, 0x03, 0x30, 0x01, 0x00),
            // A length longer than four bytes, and one cut short.
            bytes(0x30, 0x85, 1, 0, 0, 0, 0, 0x02, 0x01, 0x03, 0x30),
            bytes(0x30, 0x84, 0x7f),
        )
        for (b in notPkcs12) assertFalse(CertificateImport.isPkcs12(b), b.joinToString(" ") { "%02x".format(it) })
        // A store cut short (an interrupted copy or sync) is refused here, not by the installer.
        assertFalse(CertificateImport.isPkcs12(p12.copyOf(p12.size - 1)))
        assertFalse(CertificateImport.isPkcs12(p12.copyOf(7)))
    }

    @Test
    fun theFileIsReadWhole_upToTheLimit() {
        val p12 = pkcs12()
        assertContentEquals(p12, CertificateImport.readBounded(ByteArrayInputStream(p12)))
        assertContentEquals(ByteArray(0), CertificateImport.readBounded(ByteArrayInputStream(ByteArray(0))))
        assertContentEquals(ByteArray(10) { 7 }, CertificateImport.readBounded(ByteArrayInputStream(ByteArray(10) { 7 }), max = 10))
        assertNull(CertificateImport.readBounded(ByteArrayInputStream(ByteArray(11)), max = 10))
        assertNull(CertificateImport.readBounded(ByteArrayInputStream(ByteArray(CertificateImport.MAX_BYTES + 1))))
        // A stream that hands out a few bytes at a time (a provider's pipe) is read to its end.
        val trickle = object : InputStream() {
            private val source = ByteArrayInputStream(p12)
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 3))
        }
        assertContentEquals(p12, CertificateImport.readBounded(trickle))
    }

    @Test
    fun theSuggestedNameIsTheFilesWithoutItsExtension() {
        val s = CertificateImport::suggestedName
        assertEquals("reto-quest", s("reto-quest.p12", "agents.example.com"))
        assertEquals("Client Cert", s("Client  Cert.PFX", null))
        assertEquals("ara.phone", s("ara.phone.p12", null))
        assertEquals("backup.p12.zip", s("backup.p12.zip", null), "only a last .p12 or .pfx goes")
        assertEquals("agents.example.com", s(".p12", "agents.example.com"))
        assertEquals("agents.example.com", s("  ", "agents.example.com"))
        assertEquals("agents.example.com", s(null, "agents.example.com"))
        assertEquals("Retinue", s(null, null))
        assertEquals("Retinue", s("\u0000\n.pfx", ""))
        assertEquals("ab", s("a\u0007b.p12", null), "no control characters")
        val long = s("x".repeat(100) + ".p12", null)
        assertEquals(CertificateImport.MAX_NAME, long.length)
        assertEquals("x".repeat(63), s("x".repeat(63) + " yyyy.p12", null), "cut to the limit, then trimmed")
    }

    @Test
    fun outcomesHaveTheKeysThePageKnows() {
        assertEquals(
            listOf("installed", "none", "cancelled", "not-pkcs12", "too-large", "unreadable", "unavailable", "blocked", "no-picker"),
            CertificateImport.Outcome.entries.map { it.key },
        )
    }
}
