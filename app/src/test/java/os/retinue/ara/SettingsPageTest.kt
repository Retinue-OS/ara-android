package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.net.CertificateImport
import os.retinue.ara.settings.AppPage
import os.retinue.ara.settings.CurrentEndpoint
import os.retinue.ara.settings.PageDraft
import os.retinue.ara.settings.SettingsAction
import os.retinue.ara.settings.SettingsMessages
import os.retinue.ara.settings.SettingsPageState
import os.retinue.ara.settings.UpdatesInfo
import os.retinue.ara.settings.WakeInfo
import os.retinue.ara.ui.EndpointDraft
import os.retinue.ara.update.UpdateChannel
import os.retinue.ara.wake.WakeMode

/** The app's settings page: its address and files, its messages and its state. */
class SettingsPageTest {

    @Test
    fun theReservedAddressServesThePageFolderOnly() {
        assertEquals("https://appassets.androidplatform.net", AppPage.ORIGIN)
        assertTrue(AppPage.isAppPage("https://appassets.androidplatform.net/settings/index.html"))
        assertTrue(AppPage.isAppPage("https://APPASSETS.androidplatform.net/settings/"))
        assertFalse(AppPage.isAppPage("http://appassets.androidplatform.net/settings/index.html"))
        assertFalse(AppPage.isAppPage("https://appassets.androidplatform.net:8443/settings/index.html"))
        assertFalse(AppPage.isAppPage("https://appassets.androidplatform.net/other/"))
        assertFalse(AppPage.isAppPage("https://agents.example.com/settings/"))
        assertTrue(AppPage.isAppHost("http://appassets.androidplatform.net/anything"))
        assertFalse(AppPage.isAppHost("https://agents.example.com/"))
        assertFalse(AppPage.isAppHost(null))

        assertEquals("settings/index.html", AppPage.assetFor("/settings/"))
        assertEquals("settings/page.mjs", AppPage.assetFor("/settings/page.mjs"))
        assertEquals("settings/settings.css", AppPage.assetFor("/settings/settings.css"))
        for (bad in listOf(null, "/", "/settings", "/retinue-shell.js", "/settings/../retinue-shell.js",
            "/settings/sub/x.js", "/settings/.hidden.js", "/settings/x.json", "/settings/a%2Fb.js")) {
            assertNull(AppPage.assetFor(bad), bad.toString())
        }
        assertEquals("text/javascript", AppPage.mimeFor("settings/logic.mjs"))
        assertEquals("text/html", AppPage.mimeFor("settings/index.html"))
        assertEquals("utf-8", AppPage.encodingFor("text/css"))
        assertNull(AppPage.encodingFor("image/png"))
    }

    @Test
    fun thePolicyAllowsTheEndpointsFrameAndNothingElse() {
        val csp = AppPage.contentSecurityPolicy("https://agents.example.com")
        assertTrue(csp.contains("frame-src https://agents.example.com;"), csp)
        assertTrue(csp.contains("frame-ancestors 'none'"))
        assertTrue(csp.contains("script-src 'self';") && csp.contains("connect-src 'none'"))
        // No endpoint, or anything that is not a plain origin: no frames at all.
        assertTrue(AppPage.contentSecurityPolicy(null).contains("frame-src 'none';"))
        assertTrue(AppPage.contentSecurityPolicy("https://a.example; script-src *").contains("frame-src 'none';"))
        // A policy cannot name an IPv6 address (Chromium ignores the source), so none is written.
        assertNull(AppPage.frameSource("https://[::1]:8443"))
        assertTrue(AppPage.contentSecurityPolicy("https://[2001:db8::1]").contains("frame-src 'none';"))
        assertEquals("https://agents.example.com:8443", AppPage.frameSource("https://agents.example.com:8443"))
        assertEquals("https://appassets.androidplatform.net/settings/index.html#add", AppPage.startUrl(add = true))
        assertEquals("https://appassets.androidplatform.net/settings/index.html#edit=endpoint-1", AppPage.startUrl(editId = "endpoint-1"))
    }

    @Test
    fun parsesWhatThePageSends() {
        val p = SettingsMessages::parse
        assertEquals(SettingsAction.Hello, p("""{"type":"hello"}"""))
        assertEquals(SettingsAction.SelectEndpoint("admin"), p("""{"type":"endpoint.select","id":"admin"}"""))
        assertEquals(SettingsAction.HealthUpload(false), p("""{"type":"health.upload","on":false}"""))
        assertEquals(SettingsAction.HealthInterval(15), p("""{"type":"health.interval","minutes":15}"""))
        assertEquals(SettingsAction.WakeModeSet(WakeMode.Always), p("""{"type":"wake.mode","mode":"always"}"""))
        assertEquals(SettingsAction.WakeThreshold(0.35f), p("""{"type":"wake.threshold","value":0.35}"""))
        assertEquals(SettingsAction.UpdateChannelSet(UpdateChannel.Release), p("""{"type":"update.channel","channel":"release"}"""))
        assertEquals(SettingsAction.DeviceSet("retinue.shelf.size", "9"), p("""{"type":"device.set","key":"retinue.shelf.size","value":"9"}"""))
        assertEquals(SettingsAction.LauncherEdit, p("""{"type":"launcher.edit"}"""))
        assertEquals(SettingsAction.LauncherUsageAccess, p("""{"type":"launcher.usageAccess"}"""))
        assertEquals(SettingsAction.ChooseCertificate("r3", "https://agents.example.com", null), p("""{"type":"endpoint.certificate","request":"r3","baseUrl":"https://agents.example.com","current":null}"""))
        assertEquals(SettingsAction.ImportCertificate("r4", "https://agents.example.com"), p("""{"type":"endpoint.certificateImport","request":"r4","baseUrl":"https://agents.example.com"}"""))
        assertEquals(SettingsAction.ImportCertificate("r5", ""), p("""{"type":"endpoint.certificateImport","request":"r5"}"""), "a new endpoint without an address yet")
        assertEquals(
            SettingsAction.SaveEndpoint("r1", PageDraft("ep-1", true, "admin.example.com", null, "reto", "pw", false, true)),
            p("""{"type":"endpoint.save","request":"r1","draft":{"id":"ep-1","isNew":true,"baseUrl":"admin.example.com","certificateAlias":null,"username":"reto","password":"pw","forgetPassword":false,"healthUpload":true}}"""),
        )
        // Malformed, unknown or out of range: ignored.
        for (bad in listOf(
            null, "", "nope", "[]", """{"type":"unknown"}""", """{"type":"endpoint.select","id":"a b"}""",
            """{"type":"health.interval","minutes":0}""", """{"type":"health.interval","minutes":"5"}""",
            """{"type":"health.upload","on":"true"}""", """{"type":"wake.mode","mode":"sometimes"}""",
            """{"type":"update.channel","channel":"beta"}""", """{"type":"device.set","key":"k","value":3}""",
            """{"type":"endpoint.save","request":"r 1","draft":{"id":"x"}}""", """{"type":"endpoint.save","request":"r1"}""",
            """{"type":"endpoint.certificateImport"}""", """{"type":"endpoint.certificateImport","request":"r 4"}""",
        )) {
            assertNull(p(bad), bad.toString())
        }
    }

    @Test
    fun aSavedDraftTakesWhatIsStoredFromTheAppNotThePage() {
        val stored = Endpoint("admin", "https://admin.example.com", username = "reto", secret = "sealed", name = "Admin")
        val page = PageDraft("admin", isNew = true, baseUrl = "https://admin.example.com", certificateAlias = null,
            username = "reto", password = "", forgetPassword = false, healthUpload = false)
        val draft = SettingsMessages.toEndpointDraft(page, stored)
        assertFalse(draft.isNew, "the app knows it exists")
        assertTrue(draft.hasStoredPassword)
        assertEquals("Admin", draft.name)
        assertNull(draft.problem, "the stored password counts")
        // The same draft for an endpoint the app does not know: a user name without a password.
        assertEquals(EndpointDraft.Problem.Password, SettingsMessages.toEndpointDraft(page, null).problem)
        assertEquals("""{"type":"saved","request":"r1","ok":true}""", SettingsMessages.saved("r1", null))
        assertEquals("""{"type":"saved","request":"r2","ok":false,"problem":"user"}""", SettingsMessages.saved("r2", EndpointDraft.Problem.UserName))
        assertEquals("""{"type":"certificate","request":"r3","alias":null}""", SettingsMessages.certificate("r3", null))
        assertEquals(
            """{"type":"certificateImport","request":"r4","outcome":"not-pkcs12"}""",
            SettingsMessages.certificateImport("r4", CertificateImport.Outcome.NotPkcs12),
        )
    }

    @Test
    fun theStateListsEndpointsUnderTheirNamesWithoutSecrets() {
        val home = Endpoint("home", "https://agents.example.com", certificateAlias = "ara-phone", healthUpload = true, name = "Retinue")
        val admin = Endpoint("admin", "https://admin.example.com/", username = "reto", secret = "sealed", name = "Admin")
        val settings = ShellSettings(endpoints = listOf(home, admin), currentEndpointId = "home", deviceWebSettings = mapOf("retinue.shelf.size" to "7"))
        val rows = SettingsPageState.endpointRows(settings)
        assertEquals(listOf("Retinue", "Admin"), rows.map { it.label })
        assertEquals(listOf(true, false), rows.map { it.current })
        assertTrue(rows[1].hasPassword)
        assertEquals(
            CurrentEndpoint(
                "home", "Retinue", "https://agents.example.com", "https://agents.example.com/settings-instance.html",
                "https://agents.example.com/settings.html", SettingsPageState.frameKey(home),
            ),
            SettingsPageState.currentEndpoint(settings),
        )
        assertNull(SettingsPageState.currentEndpoint(ShellSettings()))
        // An endpoint at an IPv6 address: no frame (the page offers the dashboard), its pages as usual.
        val v6 = Endpoint("v6", "https://[::1]:8443")
        val v6Current = SettingsPageState.currentEndpoint(ShellSettings(endpoints = listOf(v6), currentEndpointId = "v6"))!!
        assertNull(v6Current.instanceUrl)
        assertEquals("https://[::1]:8443/settings.html", v6Current.settingsUrl)
        val state = SettingsPageState(
            version = "2.0+abc",
            endpoints = rows,
            current = SettingsPageState.currentEndpoint(settings),
            failure = null,
            health = JsonObject(emptyMap()),
            isDefaultHome = true,
            splitLaunch = false,
            keepSplitInRecents = true,
            wake = WakeInfo("off", 0.5f, "Not listening.", true, false, true, true, true, false, false, false, "https://x/hey.onnx", false, null),
            updates = UpdatesInfo("Installed: a local build.", false, "nightly", "Not checked yet.", false, null, null, false, true, true),
            device = settings.deviceWebSettings,
            usageAccess = true,
        )
        val json = Json.parseToJsonElement(state.toJson().toString()).jsonObject
        val text = json.toString()
        assertFalse(text.contains("sealed"), "no secret reaches the page")
        assertEquals("7", json["device"]!!.jsonObject["retinue.shelf.size"]!!.jsonPrimitive.content)
        assertEquals("Admin", json["endpoints"]!!.jsonArray[1].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("null", json["failure"].toString())
        assertEquals("true", json["home"]!!.jsonObject["isDefault"].toString())
        assertEquals("true", json["launcher"]!!.jsonObject["usageAccess"].toString())
        assertEquals("false", state.copy(usageAccess = false).toJson()["launcher"]!!.jsonObject["usageAccess"].toString())
        assertEquals("""{"type":"state","state":{}}""", SettingsMessages.state(JsonObject(emptyMap())))
        assertEquals(SettingsPageState.frameKey(home), json["current"]!!.jsonObject["frameKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSectionLoadsAfreshWhenWhatSignsItInChanges() {
        val home = Endpoint("home", "https://agents.example.com", certificateAlias = "ara-phone", username = "reto", secret = "sealed-1", name = "Retinue")
        val key = SettingsPageState.frameKey(home)
        for (changed in listOf(
            home.copy(id = "other"),
            home.copy(baseUrl = "https://agents.example.com/retinue"),
            home.copy(certificateAlias = null),
            home.copy(username = "anna"),
            home.copy(secret = "sealed-2"),
        )) {
            assertNotEquals(key, SettingsPageState.frameKey(changed), changed.toString())
        }
        // What does not sign it in changes nothing: the health switch, the fetched name.
        assertEquals(key, SettingsPageState.frameKey(home.copy(healthUpload = true, name = "Home")))
        assertFalse(key.contains("sealed"), "nothing of the password")
    }
}
