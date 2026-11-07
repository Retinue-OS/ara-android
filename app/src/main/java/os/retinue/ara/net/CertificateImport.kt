package os.retinue.ara.net

import java.io.InputStream

/**
 * Importing a client certificate, without Android: what a picked file must be
 * to go to Android's certificate installer (a PKCS#12 key store, `.p12` or
 * `.pfx`, of a certificate file's size), the name the installer suggests for
 * it, and how an import ends. Pure, so the rules are pinned down by unit
 * tests; [CertificateInstaller] carries them out.
 */
object CertificateImport {

    /**
     * The largest file taken. A PKCS#12 file with a key and a chain of a few
     * certificates is a few kilobytes; the bytes travel to the installer in an
     * intent, which Android's binder limits to well under a megabyte.
     */
    const val MAX_BYTES = 256 * 1024

    /** The longest name suggested for the installed certificate (its alias). */
    const val MAX_NAME = 64

    /**
     * The file picker's types. PKCS#12 first; anything else is offered too,
     * since a file synced to the device is often typed by a provider that does
     * not know the extension: [isPkcs12] decides, not the type.
     */
    val PICKER_TYPES = arrayOf("application/x-pkcs12", "application/pkcs12", "*/*")

    /** How an import ends, as the settings page names it. */
    enum class Outcome(val key: String) {
        /** Android's installer reports the certificate installed; the chooser follows, so Ara may use it. */
        Installed("installed"),

        /** No file was picked. */
        NoFile("none"),

        /** The installer came back without installing: the user left it, or the device refused it. */
        NotInstalled("cancelled"),

        /** The file is not a PKCS#12 key store. */
        NotPkcs12("not-pkcs12"),

        /** The file is larger than [MAX_BYTES]. */
        TooLarge("too-large"),

        /** The file could not be read. */
        Unreadable("unreadable"),

        /** The device has no certificate installer Ara may open. */
        NoInstaller("unavailable"),

        /** A device policy forbids installing credentials (`DISALLOW_CONFIG_CREDENTIALS`). */
        Blocked("blocked"),

        /** The device has no file picker. */
        NoPicker("no-picker"),
    }

    /**
     * Everything [input] holds, when that is at most [max] bytes; null when it
     * holds more. Read into one buffer that is wiped afterwards, so no stray
     * copy of the key store stays behind in memory.
     */
    fun readBounded(input: InputStream, max: Int = MAX_BYTES): ByteArray? {
        val buffer = ByteArray(max + 1)
        var n = 0
        try {
            while (n < buffer.size) {
                val read = input.read(buffer, n, buffer.size - n)
                if (read < 0) break
                n += read
            }
            return if (n > max) null else buffer.copyOf(n)
        } finally {
            buffer.fill(0)
        }
    }

    /**
     * Whether [bytes] begin as a PKCS#12 key store does (RFC 7292):
     * `PFX ::= SEQUENCE { version INTEGER (3), authSafe ContentInfo, … }` —
     * a SEQUENCE that holds version 3 and then the start of the ContentInfo,
     * a SEQUENCE. In DER the SEQUENCE's length must fit the file (not cut
     * short) and hold both; BER of indefinite length, as some tools write it,
     * is taken as far as both. A quick look at the kind of file, not a
     * validation: the installer, which has the password, reads the whole
     * store. It tells a key store from a certificate (`.crt`, `.pem`,
     * `.der`), a Java key store or any other file.
     */
    fun isPkcs12(bytes: ByteArray): Boolean {
        if (bytes.size < 8 || bytes[0] != SEQUENCE) return false
        var i = 1
        val first = bytes[i++].toInt() and 0xff
        val length: Long? = when {
            first < 0x80 -> first.toLong()
            first == 0x80 -> null // indefinite (BER): ends with an end-of-contents marker
            first <= 0x84 -> {
                val count = first and 0x7f
                if (i + count > bytes.size) return false
                var len = 0L
                repeat(count) { len = (len shl 8) or (bytes[i++].toLong() and 0xff) }
                len
            }
            else -> return false
        }
        // Where the SEQUENCE's contents end: the version and the ContentInfo's tag and length start inside.
        val end = if (length == null) bytes.size.toLong() else i + length
        if (end > bytes.size || i + 5 > end) return false
        return bytes[i] == INTEGER && bytes[i + 1] == 1.toByte() && bytes[i + 2] == 3.toByte() &&
            bytes[i + 3] == SEQUENCE
    }

    /**
     * The name the installer suggests for the certificate (the user may change
     * it there): the file's name without `.p12` or `.pfx`, else the
     * endpoint's host, else "Retinue". Control characters go, white space is
     * collapsed, and it is at most [MAX_NAME] characters.
     */
    fun suggestedName(fileName: String?, host: String?): String {
        val stem = clean(fileName?.replace(PKCS12_EXTENSION, ""))
        return stem ?: clean(host) ?: "Retinue"
    }

    private fun clean(raw: String?): String? {
        if (raw == null) return null
        val text = raw.filterNot { it.isISOControl() }.replace(WHITE_SPACE, " ").trim()
        return text.take(MAX_NAME).trimEnd().takeIf { it.isNotEmpty() }
    }

    private const val SEQUENCE: Byte = 0x30
    private const val INTEGER: Byte = 0x02
    private val PKCS12_EXTENSION = Regex("""\.(p12|pfx)\s*$""", RegexOption.IGNORE_CASE)
    private val WHITE_SPACE = Regex("""\s+""")
}
