package os.retinue.ara.update

/**
 * Where updates come from. Every channel is a view of the GitHub releases of
 * [UpdateSource.REPO]; the channel decides which of them count.
 */
enum class UpdateChannel(val key: String) {
    /** The nightly pre-releases the nightly workflow publishes (`nightly-YYYY-MM-DD[-Xn]`). */
    Nightly("nightly"),

    /**
     * Proper releases (not pre-releases): GitHub's "latest release". None has
     * been published yet, so the channel offers nothing until the first one;
     * the settings say so after a check.
     */
    Release("release");

    /** Whether a published (non-draft) release belongs to this channel. */
    fun accepts(release: ReleaseInfo): Boolean = when (this) {
        Nightly -> release.prerelease && release.tag.startsWith(NIGHTLY_PREFIX)
        Release -> !release.prerelease
    }

    companion object {
        const val NIGHTLY_PREFIX = "nightly-"

        fun fromKey(key: String?): UpdateChannel = entries.firstOrNull { it.key == key } ?: Nightly
    }
}

/** The GitHub repository whose releases carry the shell's builds. */
object UpdateSource {
    const val REPO = "Retinue-OS/ara-android"
    private const val API = "https://api.github.com/repos/$REPO"

    /** What the API answers with at [url]: a list of releases, or a [single] release. */
    data class Endpoint(val url: String, val single: Boolean)

    /**
     * Where a channel's builds are read from. The nightlies are pre-releases,
     * which GitHub's "latest release" never is, so they come from the list of
     * recent releases (newest first); the release channel is that latest
     * release itself, which is 404 while there is none.
     */
    fun endpoint(channel: UpdateChannel): Endpoint = when (channel) {
        UpdateChannel.Nightly -> Endpoint("$API/releases?per_page=30", single = false)
        UpdateChannel.Release -> Endpoint("$API/releases/latest", single = true)
    }
}
