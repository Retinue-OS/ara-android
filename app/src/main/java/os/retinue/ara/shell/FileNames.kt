package os.retinue.ara.shell

/**
 * File names for downloads saved by the shell, framework-free so they are
 * unit-tested. A name comes from the server (Content-Disposition) or the URL,
 * so it is reduced to one plain path segment before it reaches MediaStore.
 */
object FileNames {

    /** Longer names are cut (keeping the extension); file systems allow 255 bytes. */
    const val MAX_LENGTH = 120

    private const val FALLBACK = "download"
    private val forbidden = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /**
     * One safe file name: no directory parts, no path or reserved characters,
     * no control characters, no leading dots (no hidden files, no `..`), and at
     * most [MAX_LENGTH] characters.
     */
    fun sanitize(raw: String?): String {
        val last = raw.orEmpty().replace('\\', '/').substringAfterLast('/')
        var name = forbidden.replace(last, "_").trim().trimStart('.').trimEnd('.', ' ')
        if (name.isEmpty() || name.all { it == '_' }) return FALLBACK
        if (name.length > MAX_LENGTH) {
            val dot = name.lastIndexOf('.')
            val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
            name = name.take(MAX_LENGTH - ext.length).trimEnd('.', ' ') + ext
        }
        return name
    }
}
