package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.update.InstalledBuild
import os.retinue.ara.update.ReleaseAsset
import os.retinue.ara.update.ReleaseInfo
import os.retinue.ara.update.UpdateChannel
import os.retinue.ara.update.UpdateDecision
import os.retinue.ara.update.UpdateLogic
import os.retinue.ara.update.UpdateSource

class UpdateLogicTest {

    private fun nightly(tag: String, sha: String, published: String, apk: Boolean = true) = ReleaseInfo(
        tag = tag,
        prerelease = true,
        commit = sha,
        publishedAt = published,
        url = "https://github.com/Retinue-OS/ara-android/releases/tag/$tag",
        assets = if (apk) listOf(ReleaseAsset("ara-$tag.apk", 83_470_800, "https://github.com/Retinue-OS/ara-android/releases/download/$tag/ara-$tag.apk")) else emptyList(),
    )

    private val oct20 = nightly("nightly-2026-10-20", "fe0148561c8fe9b3a107a028a7eb570f0ef80826", "2026-10-20T07:52:21Z")
    private val oct14 = nightly("nightly-2026-10-14", "7578181a9a9ba2b61f8f3c4cd749f1bf62ea55d0", "2026-10-14T07:56:49Z")
    private val oct12x3 = nightly("nightly-2026-10-12-X3", "a29727f31a71f83bfce047f2a2cb18190d8d0d06", "2026-10-12T20:31:39Z")
    private val releases = listOf(oct20, oct14, oct12x3)

    @Test
    fun parsesWhatTheApiReturns() {
        val body = """
            [{"url":"https://api.github.com/repos/Retinue-OS/ara-android/releases/1","id":1,"author":{"login":"github-actions[bot]"},
              "tag_name":"nightly-2026-10-20","target_commitish":"fe0148561c8fe9b3a107a028a7eb570f0ef80826","name":"nightly-2026-10-20",
              "draft":false,"prerelease":true,"created_at":"2026-10-19T16:01:20Z","published_at":"2026-10-20T07:52:21Z",
              "html_url":"https://github.com/Retinue-OS/ara-android/releases/tag/nightly-2026-10-20",
              "assets":[{"id":7,"name":"ara-nightly-2026-10-20.apk","content_type":"application/vnd.android.package-archive","size":83470800,
                         "browser_download_url":"https://github.com/Retinue-OS/ara-android/releases/download/nightly-2026-10-20/ara-nightly-2026-10-20.apk"}],
              "body":"Automated nightly build of Ara at fe0148561c8fe9b3a107a028a7eb570f0ef80826."}]
        """.trimIndent()
        val parsed = UpdateLogic.parseReleases(body)
        assertEquals(1, parsed.size)
        val r = parsed[0]
        assertEquals("nightly-2026-10-20", r.tag)
        assertTrue(r.prerelease)
        assertFalse(r.draft)
        assertEquals("fe0148561c8fe9b3a107a028a7eb570f0ef80826", r.commit)
        assertEquals("ara-nightly-2026-10-20.apk", r.apk?.name)
        assertEquals(83_470_800L, r.apk?.size)
        assertEquals("https://github.com/Retinue-OS/ara-android/releases/download/nightly-2026-10-20/ara-nightly-2026-10-20.apk", r.apk?.downloadUrl)
    }

    @Test
    fun parsesASingleRelease() {
        val body = """{"tag_name":"v1.0","draft":false,"prerelease":false,"target_commitish":"main","published_at":"2026-11-01T10:00:00Z",
            "html_url":"https://github.com/Retinue-OS/ara-android/releases/tag/v1.0",
            "assets":[{"name":"ara-1.0.apk","size":10,"browser_download_url":"https://example.com/ara-1.0.apk"}]}"""
        val r = UpdateLogic.parseRelease(body)
        assertEquals("v1.0", r.tag)
        assertFalse(r.prerelease)
        assertEquals("ara-1.0.apk", r.apk?.name)
        assertTrue(UpdateChannel.Release.accepts(r))
        assertFalse(UpdateChannel.Nightly.accepts(r))
    }

    @Test
    fun endpointsPerChannel() {
        val nightly = UpdateSource.endpoint(UpdateChannel.Nightly)
        assertFalse(nightly.single)
        assertTrue(nightly.url.startsWith("https://api.github.com/repos/Retinue-OS/ara-android/releases?"))
        val release = UpdateSource.endpoint(UpdateChannel.Release)
        assertTrue(release.single)
        assertEquals("https://api.github.com/repos/Retinue-OS/ara-android/releases/latest", release.url)
    }

    @Test
    fun channelsSortTheReleases() {
        val release = ReleaseInfo(tag = "v1.0", prerelease = false, commit = "main", publishedAt = "2026-11-01T10:00:00Z",
            assets = listOf(ReleaseAsset("ara-1.0.apk", 1, "https://example.com/ara-1.0.apk")))
        val otherPre = ReleaseInfo(tag = "beta-1", prerelease = true, publishedAt = "2026-12-01T10:00:00Z",
            assets = listOf(ReleaseAsset("ara-beta.apk", 1, "https://example.com/ara-beta.apk")))
        val all = listOf(oct14, release, oct20, otherPre)
        assertEquals(oct20, UpdateLogic.latestOn(UpdateChannel.Nightly, all))
        assertEquals(release, UpdateLogic.latestOn(UpdateChannel.Release, all))
        assertNull(UpdateLogic.latestOn(UpdateChannel.Release, releases))
    }

    @Test
    fun draftsAndReleasesWithoutApkDoNotCount() {
        val draft = oct20.copy(tag = "nightly-2026-10-21", draft = true, publishedAt = "2026-10-21T07:00:00Z")
        val bare = nightly("nightly-2026-10-22", "0000000a", "2026-10-22T07:00:00Z", apk = false)
        assertEquals(oct20, UpdateLogic.latestOn(UpdateChannel.Nightly, listOf(draft, bare) + releases))
    }

    @Test
    fun newestByPublicationNotByListOrder() {
        assertEquals(oct20, UpdateLogic.latestOn(UpdateChannel.Nightly, listOf(oct12x3, oct14, oct20)))
        // Never published (null) does not count at all.
        val unpublished = oct20.copy(tag = "nightly-2026-10-30", publishedAt = null)
        assertEquals(oct20, UpdateLogic.latestOn(UpdateChannel.Nightly, listOf(unpublished) + releases))
        assertNull(UpdateLogic.latestOn(UpdateChannel.Nightly, listOf(unpublished)))
    }

    @Test
    fun theInstalledNightlyIsUpToDate() {
        val installed = InstalledBuild("nightly-2026-10-20", "fe0148561c8fe9b3a107a028a7eb570f0ef80826")
        assertEquals(UpdateDecision.UpToDate(oct20), UpdateLogic.decide(installed, releases, UpdateChannel.Nightly))
    }

    @Test
    fun anOlderNightlyGetsTheLatest() {
        val installed = InstalledBuild("nightly-2026-10-14", "7578181a9a9ba2b61f8f3c4cd749f1bf62ea55d0")
        val d = UpdateLogic.decide(installed, releases, UpdateChannel.Nightly)
        assertIs<UpdateDecision.Available>(d)
        assertEquals(oct20, d.release)
        assertEquals("ara-nightly-2026-10-20.apk", d.apk.name)
    }

    @Test
    fun aBranchBuildOfTheSameCommitIsUpToDate() {
        // CI's debug APK knows its commit but has no tag.
        val installed = InstalledBuild("", "fe01485")
        assertEquals(UpdateDecision.UpToDate(oct20), UpdateLogic.decide(installed, releases, UpdateChannel.Nightly))
        // Another commit: the latest nightly is offered (the order of commits is unknown).
        assertIs<UpdateDecision.Available>(UpdateLogic.decide(InstalledBuild("", "abcdef0123"), releases, UpdateChannel.Nightly))
    }

    @Test
    fun aLocalBuildMatchesNothing() {
        assertIs<UpdateDecision.Available>(UpdateLogic.decide(InstalledBuild("", ""), releases, UpdateChannel.Nightly))
        assertFalse(UpdateLogic.isInstalled(InstalledBuild("", ""), oct20.copy(commit = "")))
    }

    @Test
    fun aChannelWithoutBuilds() {
        assertEquals(UpdateDecision.NoBuilds, UpdateLogic.decide(InstalledBuild("nightly-2026-10-20", "fe01485"), releases, UpdateChannel.Release))
        assertEquals(UpdateDecision.NoBuilds, UpdateLogic.decide(InstalledBuild("", ""), emptyList(), UpdateChannel.Nightly))
    }

    @Test
    fun commitsCompareAsHexPrefixes() {
        assertTrue(UpdateLogic.sameCommit("fe01485", "fe0148561c8fe9b3a107a028a7eb570f0ef80826"))
        assertTrue(UpdateLogic.sameCommit("FE0148561C8FE9B3A107A028A7EB570F0EF80826", "fe01485"))
        assertFalse(UpdateLogic.sameCommit("fe0148", "fe0148561c8fe9b3a107a028a7eb570f0ef80826")) // too short
        assertFalse(UpdateLogic.sameCommit("main", "main"))
        assertFalse(UpdateLogic.sameCommit("", ""))
        assertFalse(UpdateLogic.sameCommit("7578181a9a9ba2b6", "fe0148561c8fe9b3"))
    }

    @Test
    fun checksAreDueAfterTheInterval() {
        val h = 3_600_000L
        assertTrue(UpdateLogic.checkDue(nowMs = 100 * h, lastCheckMs = 0, intervalMs = 6 * h))
        assertFalse(UpdateLogic.checkDue(nowMs = 100 * h, lastCheckMs = 95 * h, intervalMs = 6 * h))
        assertTrue(UpdateLogic.checkDue(nowMs = 100 * h, lastCheckMs = 94 * h, intervalMs = 6 * h))
        // The clock went backwards: check rather than wait for it to catch up.
        assertTrue(UpdateLogic.checkDue(nowMs = 10 * h, lastCheckMs = 20 * h, intervalMs = 6 * h))
    }

    @Test
    fun channelKeys() {
        assertEquals(UpdateChannel.Nightly, UpdateChannel.fromKey(null))
        assertEquals(UpdateChannel.Nightly, UpdateChannel.fromKey("bogus"))
        assertEquals(UpdateChannel.Release, UpdateChannel.fromKey("release"))
    }
}
