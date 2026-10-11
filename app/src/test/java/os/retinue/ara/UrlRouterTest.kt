package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.shell.Origin
import os.retinue.ara.shell.Route
import os.retinue.ara.shell.ShellUrls
import os.retinue.ara.shell.UrlRouter

class ShellUrlsTest {

    @Test
    fun normalizesBase() {
        assertEquals("https://agents.example.com", ShellUrls.normalizeBase(" https://agents.example.com/ "))
        assertEquals("https://agents.example.com", ShellUrls.normalizeBase("agents.example.com"))
        assertEquals("https://agents.example.com", ShellUrls.normalizeBase("HTTPS://Agents.Example.com:443/"))
        assertEquals("https://h.example:8443/retinue", ShellUrls.normalizeBase("https://h.example:8443/retinue/?x=1#y"))
        // Cleartext is off in the shell: an http base is not configured.
        assertNull(ShellUrls.normalizeBase("http://10.0.0.2:8080"))
        assertTrue(ShellUrls.isCleartextBase(" HTTP://10.0.0.2:8080"))
        assertFalse(ShellUrls.isCleartextBase("https://h.example"))
        assertFalse(ShellUrls.isCleartextBase("h.example"))
        assertEquals("https://[::1]:8443", ShellUrls.normalizeBase("https://[::1]:8443/"))
        assertNull(ShellUrls.normalizeBase(""))
        assertNull(ShellUrls.normalizeBase(null))
        assertNull(ShellUrls.normalizeBase("ftp://h.example"))
        assertNull(ShellUrls.normalizeBase("https://"))
    }

    @Test
    fun originFillsDefaultPorts() {
        assertEquals(Origin("https", "h.example", 443), ShellUrls.origin("https://h.example/x"))
        assertEquals(Origin("http", "h.example", 80), ShellUrls.origin("http://H.example"))
        assertEquals("https://h.example", Origin("https", "h.example", 443).toString())
        assertEquals("https://h.example:8443", Origin("https", "h.example", 8443).toString())
        assertNull(ShellUrls.origin("mailto:a@b.c"))
        assertNull(ShellUrls.origin("not a url"))
    }

    @Test
    fun originToleratesCharactersChromiumLeavesUnescaped() {
        val o = Origin("https", "h.example", 443)
        assertEquals(o, ShellUrls.origin("https://h.example/a|b"))
        assertEquals(o, ShellUrls.origin("https://h.example/a{b}^c[d]"))
        assertEquals(o, ShellUrls.origin("https://h.example/?q=a|b&r={x}"))
        assertEquals(o, ShellUrls.origin("https://h.example/#thread|1{2}"))
        assertEquals(o, ShellUrls.origin("https://h.example/a b`c\"d"))
        // A backslash ends the authority, as in a browser.
        assertEquals(Origin("https", "evil.example", 443), ShellUrls.origin("https://evil.example\\@h.example/"))
        assertEquals(Origin("https", "h.example", 443), ShellUrls.origin("https://user:pw@h.example/x"))
        assertNull(ShellUrls.origin("https://h.example:99999/"))
        assertTrue(ShellUrls.sameOrigin("https://h.example", "https://h.example/x?y=|"))
        assertTrue(UrlRouter("https://h.example").isConfiguredOrigin("https://h.example/{a}#|"))
    }

    @Test
    fun shellPages() {
        val base = "https://h.example"
        assertEquals("https://h.example/", ShellUrls.startUrl(base))
        assertEquals("https://h.example/?wake=1", ShellUrls.wakeUrl(base))
        assertEquals("https://h.example/contacts", ShellUrls.webPathUrl(base, "/contacts"))
        assertEquals("https://h.example/contacts", ShellUrls.webPathUrl(base, "contacts"))
        assertEquals("https://h.example/#sends", ShellUrls.webPathUrl(base, "#sends"))
        assertEquals("https://h.example/", ShellUrls.webPathUrl(base, ""))
        assertEquals("https://h.example/a?b=1", ShellUrls.webPathUrl(base, "https://h.example/a?b=1"))
        // Another origin is never smuggled in through a web-page entry.
        assertEquals("https://h.example/", ShellUrls.webPathUrl(base, "https://evil.example/a"))
    }

    @Test
    fun startPage() {
        val base = "https://h.example"
        assertTrue(ShellUrls.isStartPage(base, "https://h.example/"))
        assertTrue(ShellUrls.isStartPage(base, "https://h.example"))
        assertTrue(ShellUrls.isStartPage(base, "https://h.example/?wake=1"))
        assertTrue(ShellUrls.isStartPage(base, "https://h.example/#threads"))
        assertTrue(ShellUrls.isStartPage(base, "https://h.example/index.html"))
        assertFalse(ShellUrls.isStartPage(base, "https://h.example/sends"))
        assertFalse(ShellUrls.isStartPage(base, "https://h.example/claude-auth?x=1"))
        assertFalse(ShellUrls.isStartPage(base, "https://other.example/"))
        assertFalse(ShellUrls.isStartPage(base, null))
        val sub = "https://h.example/retinue"
        assertTrue(ShellUrls.isStartPage(sub, "https://h.example/retinue/"))
        assertTrue(ShellUrls.isStartPage(sub, "https://h.example/retinue/index.html"))
        assertFalse(ShellUrls.isStartPage(sub, "https://h.example/"))
        // Unescaped characters in the query, fragment or path do not hide the page.
        assertTrue(ShellUrls.isStartPage(base, "https://h.example/?q={a}|b"))
        assertTrue(ShellUrls.isStartPage(base, "https://h.example/#t|{1}"))
        assertTrue(ShellUrls.isStartPage(sub, "https://h.example/retinue/?x=^"))
        assertFalse(ShellUrls.isStartPage(base, "https://h.example/a|b"))
        assertTrue(ShellUrls.isStartPage("https://h.example/a{b}", "https://h.example/a{b}/"))
        assertEquals("https://h.example/a%7Bb%7D", ShellUrls.normalizeBase("https://h.example/a{b}/"))
    }
}

class UrlRouterTest {

    private val router = UrlRouter("https://agents.example.com")

    @Test
    fun sameOriginStays() {
        assertEquals(Route.Stay, router.route("https://agents.example.com/#thread/1"))
        assertEquals(Route.Stay, router.route("https://AGENTS.example.com:443/x"))
    }

    @Test
    fun otherOriginsGoToTheBrowser() {
        assertEquals(Route.Browser("https://example.org/a"), router.route("https://example.org/a"))
        // Same host, other scheme or port, is another origin.
        assertEquals(Route.Browser("http://agents.example.com/"), router.route("http://agents.example.com/"))
        assertEquals(Route.Browser("https://agents.example.com:8443/"), router.route("https://agents.example.com:8443/"))
        assertEquals(Route.Browser("https://agents.example.com.evil.org/"), router.route("https://agents.example.com.evil.org/"))
    }

    @Test
    fun framesMayEmbedOtherOrigins() {
        assertEquals(Route.Stay, router.route("https://maps.example.org/embed", isMainFrame = false))
        assertEquals(Route.Block, router.route("tel:123", isMainFrame = false))
        assertEquals(Route.Block, router.route("intent://x#Intent;end", isMainFrame = false))
    }

    @Test
    fun otherSchemesGoToAndroid() {
        assertEquals(Route.External("tel:+41441234567"), router.route("tel:+41441234567"))
        assertEquals(Route.External("sms:+41"), router.route("sms:+41"))
        assertEquals(Route.External("mailto:a@b.c"), router.route("mailto:a@b.c"))
        assertEquals(Route.External("geo:0,0?q=Zurich"), router.route("geo:0,0?q=Zurich"))
        assertEquals(
            Route.AndroidIntent("intent://scan/#Intent;scheme=zxing;package=x;end"),
            router.route("intent://scan/#Intent;scheme=zxing;package=x;end"),
        )
    }

    @Test
    fun unsafeSchemesAreBlocked() {
        assertEquals(Route.Block, router.route("javascript:alert(1)"))
        assertEquals(Route.Block, router.route("file:///sdcard/x"))
        assertEquals(Route.Block, router.route("content://x/y"))
        assertEquals(Route.Block, router.route("data:text/html,hi"))
        assertEquals(Route.Block, router.route("no scheme"))
        assertEquals(Route.Stay, router.route("about:blank"))
        assertEquals(Route.Stay, router.route("blob:https://agents.example.com/123"))
    }

    @Test
    fun intentFallbacks() {
        assertEquals(Route.Browser("https://example.org/app"), router.fallback("https://example.org/app"))
        assertEquals(Route.Load("https://agents.example.com/x"), router.fallback("https://agents.example.com/x"))
        assertEquals(Route.Block, router.fallback("javascript:alert(1)"))
        assertEquals(Route.Block, router.fallback(null))
    }

    @Test
    fun hostChecksForCertificates() {
        assertTrue(router.isConfiguredHost("agents.example.com", 443))
        assertTrue(router.isConfiguredHost("Agents.Example.com", -1))
        assertFalse(router.isConfiguredHost("agents.example.com", 8443))
        assertFalse(router.isConfiguredHost("example.org", 443))
        assertFalse(UrlRouter(null).isConfiguredHost("agents.example.com", 443))
    }

    @Test
    fun unconfiguredRouterKeepsNothing() {
        val none = UrlRouter("")
        assertEquals(Route.Browser("https://agents.example.com/"), none.route("https://agents.example.com/"))
        assertFalse(none.isConfiguredOrigin("https://agents.example.com/"))
    }

    @Test
    fun schemeParsing() {
        assertEquals("https", UrlRouter.schemeOf("HTTPS://x"))
        assertEquals("tel", UrlRouter.schemeOf("tel:1"))
        assertNull(UrlRouter.schemeOf(":x"))
        assertNull(UrlRouter.schemeOf("1abc:x"))
        assertNull(UrlRouter.schemeOf("a b:x"))
    }

    @Test
    fun refusedIntentData() {
        assertTrue(UrlRouter.isRefusedIntentData("file"))
        assertTrue(UrlRouter.isRefusedIntentData("CONTENT"))
        assertFalse(UrlRouter.isRefusedIntentData("https"))
        assertFalse(UrlRouter.isRefusedIntentData("zxing"))
        assertFalse(UrlRouter.isRefusedIntentData(null))
    }
}
