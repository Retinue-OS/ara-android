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
        assertEquals("http://10.0.0.2:8080", ShellUrls.normalizeBase("http://10.0.0.2:8080"))
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
