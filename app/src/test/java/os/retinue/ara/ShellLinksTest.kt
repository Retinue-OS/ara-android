package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.shell.LinkTarget
import os.retinue.ara.shell.ShellLinks

class ShellLinksTest {

    private val base = "https://agents.example.com"

    @Test
    fun opensUrlsOfTheConfiguredOrigin() {
        // What Chrome delivers for the /sync-health intent link (contract §1), fragment included.
        assertEquals(
            LinkTarget.Open("https://agents.example.com/sync-health#sync"),
            ShellLinks.target("retinue-shell://open?url=https%3A%2F%2Fagents.example.com%2Fsync-health%23sync", base),
        )
        // The opaque form, and a trailing slash after the action.
        assertEquals(
            LinkTarget.Open("https://agents.example.com/sync-health#sync"),
            ShellLinks.target("retinue-shell:open?url=https%3A%2F%2Fagents.example.com%2Fsync-health%23sync", base),
        )
        assertEquals(
            LinkTarget.Open("https://agents.example.com/#threads"),
            ShellLinks.target("RETINUE-SHELL://open/?url=https%3A%2F%2Fagents.example.com%2F%23threads&x=1", base),
        )
        // Default port spelled out is the same origin; the base's path does not matter.
        assertEquals(
            LinkTarget.Open("https://agents.example.com:443/a"),
            ShellLinks.target("retinue-shell://open?url=https%3A%2F%2Fagents.example.com%3A443%2Fa", "$base/retinue"),
        )
    }

    @Test
    fun otherOriginsAreOnlyNamed() {
        assertEquals(
            LinkTarget.OtherOrigin("https://other.example"),
            ShellLinks.target("retinue-shell://open?url=https%3A%2F%2Fother.example%2Fsync-health%23sync", base),
        )
        // Never upgraded from http to https, nor matched across ports.
        assertEquals(
            LinkTarget.OtherOrigin("http://agents.example.com"),
            ShellLinks.target("retinue-shell://open?url=http%3A%2F%2Fagents.example.com%2Fsync-health", base),
        )
        assertEquals(
            LinkTarget.OtherOrigin("https://agents.example.com:8443"),
            ShellLinks.target("retinue-shell://open?url=https%3A%2F%2Fagents.example.com%3A8443%2F", base),
        )
        // A userinfo trick names the real host.
        assertEquals(
            LinkTarget.OtherOrigin("https://evil.example"),
            ShellLinks.target("retinue-shell://open?url=https%3A%2F%2Fagents.example.com%40evil.example%2F", base),
        )
        // Nothing configured: every link is for an origin that is not set up.
        assertEquals(
            LinkTarget.OtherOrigin("https://agents.example.com"),
            ShellLinks.target("retinue-shell://open?url=https%3A%2F%2Fagents.example.com%2F", null),
        )
    }

    @Test
    fun malformedLinksAreIgnored() {
        listOf(
            null,
            "",
            "https://agents.example.com/",
            "retinue-shell://open",
            "retinue-shell://close?url=https%3A%2F%2Fagents.example.com%2F",
            "retinue-shell://open?u=https%3A%2F%2Fagents.example.com%2F",
            "retinue-shell://open?url=",
            "retinue-shell://open?url=a&url=b",
            "retinue-shell://open?url=javascript%3Aalert(1)",
            "retinue-shell://open?url=intent%3A%2F%2Fx%23Intent%3Bend",
            "retinue-shell://open?url=https%3Aagents.example.com",
            "retinue-shell://open?url=https%3A%2F%2Fagents.example.com%2F%20x",
            "retinue-shell://open?url=https%3A%2F%2Fagents.example.com%2F%0A",
            "retinue-shell://open?url=https%3A%2F%2Fagents.example.com%2F%ZZ",
            "retinue-shell://open?url=https%3A%2F%2Fagents.example.com%2F%C3",
        ).forEach { assertEquals(LinkTarget.Malformed, ShellLinks.target(it, base), "for $it") }
    }

    @Test
    fun recognisesShellLinks() {
        assertTrue(ShellLinks.isShellLink("retinue-shell://open?url=x"))
        assertTrue(ShellLinks.isShellLink("Retinue-Shell:open"))
        assertFalse(ShellLinks.isShellLink("https://agents.example.com/"))
        assertFalse(ShellLinks.isShellLink(null))
    }

    @Test
    fun percentDecoding() {
        assertEquals("a b+c/ü", ShellLinks.percentDecode("a%20b+c%2F%C3%BC"))
        assertEquals("ü", ShellLinks.percentDecode("ü"))
        assertNull(ShellLinks.percentDecode("%4"))
        assertNull(ShellLinks.percentDecode("%G0"))
        assertNull(ShellLinks.percentDecode("%FF"))
    }
}
