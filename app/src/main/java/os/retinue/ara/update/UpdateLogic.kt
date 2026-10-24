package os.retinue.ara.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A release as the GitHub API lists it (the fields the updater reads). */
@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tag: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    /** The commit the release was made from: a SHA for the nightly workflow, possibly a branch name for a hand-made release. */
    @SerialName("target_commitish") val commit: String = "",
    /** ISO 8601 (`2026-10-20T07:52:21Z`), so the strings sort chronologically. */
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val url: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
) {
    /** The APK the release ships, or null for a release without one. */
    val apk: ReleaseAsset? get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

@Serializable
data class ReleaseAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("content_type") val contentType: String = "",
)

/**
 * What this APK was built from: the release tag and the commit that CI baked
 * in (see `ARA_BUILD_TAG` / `ARA_BUILD_SHA` in app/build.gradle.kts). Both are
 * empty in a local build; a branch build from CI has the commit only.
 */
data class InstalledBuild(val tag: String, val sha: String) {
    val shortSha: String get() = sha.take(7)
    val fromChannel: Boolean get() = tag.isNotEmpty()
}

sealed class UpdateDecision {
    /** The channel has no release with an APK. */
    data object NoBuilds : UpdateDecision()

    /** The channel's latest build is the installed one. */
    data class UpToDate(val latest: ReleaseInfo) : UpdateDecision()

    /** The channel's latest build is another one than the installed. */
    data class Available(val release: ReleaseInfo, val apk: ReleaseAsset) : UpdateDecision()
}

/**
 * The updater's rules, framework-free so they are unit-tested without an
 * emulator: which release a channel offers, and whether it is the installed one.
 */
object UpdateLogic {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse the GitHub releases list. Throws on malformed JSON. */
    fun parseReleases(body: String): List<ReleaseInfo> = json.decodeFromString<List<ReleaseInfo>>(body)

    /** Parse one release (the latest-release endpoint). Throws on malformed JSON. */
    fun parseRelease(body: String): ReleaseInfo = json.decodeFromString<ReleaseInfo>(body)

    /**
     * The newest published release of [channel] that ships an APK, or null.
     * Drafts and anything without a publication time never count. Newest by
     * publication time (the list is normally in that order already, but the
     * API sorts by creation time).
     */
    fun latestOn(channel: UpdateChannel, releases: List<ReleaseInfo>): ReleaseInfo? =
        releases.asSequence()
            .filter { !it.draft && it.publishedAt != null && channel.accepts(it) && it.apk != null }
            .maxByOrNull { it.publishedAt!! }

    /**
     * Whether [release] is the installed build: the same tag, or (a tag the
     * build does not know, or another tag of the same commit) the same commit.
     * A build that knows neither its tag nor its commit matches nothing.
     */
    fun isInstalled(installed: InstalledBuild, release: ReleaseInfo): Boolean {
        if (installed.tag.isNotEmpty() && installed.tag == release.tag) return true
        return sameCommit(installed.sha, release.commit)
    }

    /**
     * What the channel offers to this build. An update is whatever the channel's
     * latest build is, when it is not the installed one: the order of two
     * commits is not known here, so a branch build from CI is offered the
     * latest nightly too (an auto-update that is unwanted there is turned off).
     */
    fun decide(installed: InstalledBuild, releases: List<ReleaseInfo>, channel: UpdateChannel): UpdateDecision {
        val latest = latestOn(channel, releases) ?: return UpdateDecision.NoBuilds
        if (isInstalled(installed, latest)) return UpdateDecision.UpToDate(latest)
        return UpdateDecision.Available(latest, latest.apk ?: return UpdateDecision.NoBuilds)
    }

    /** An automatic check is due once [intervalMs] have passed since the last (or the clock went backwards). */
    fun checkDue(nowMs: Long, lastCheckMs: Long, intervalMs: Long): Boolean =
        lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs

    /** Two commit identifiers name the same commit when both are hex and one is a prefix of the other (7 characters at least). */
    fun sameCommit(a: String, b: String): Boolean {
        val x = a.trim().lowercase()
        val y = b.trim().lowercase()
        if (x.length < 7 || y.length < 7 || !isHex(x) || !isHex(y)) return false
        return x.startsWith(y) || y.startsWith(x)
    }

    private fun isHex(s: String) = s.all { it in '0'..'9' || it in 'a'..'f' }
}
