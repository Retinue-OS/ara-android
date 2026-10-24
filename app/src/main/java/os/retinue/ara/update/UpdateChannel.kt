package os.retinue.ara.update

/**
 * Where updates come from. Every channel is a view of the GitHub releases of
 * [UpdateSource.REPO]; the channel decides which of them count.
 */
enum class UpdateChannel(val key: String) {
    /** The nightly pre-releases the nightly workflow publishes (`nightly-YYYY-MM-DD[-Xn]`). */
    Nightly("nightly"),

    /**
     * Proper releases (not pre-releases). None has been published yet, so the
     * channel is shown but cannot be chosen ([selectable]); the rule below is
     * what will serve it once there are some.
     */
    Release("release");

    /** Whether the settings let the user pick this channel. */
    val selectable: Boolean get() = this == Nightly

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

    /** The releases, newest first; drafts the token cannot see are not listed anyway. */
    const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"
}
