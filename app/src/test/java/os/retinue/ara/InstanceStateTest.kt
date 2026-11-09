package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointCodec
import os.retinue.ara.data.EndpointSet
import os.retinue.ara.data.EndpointWake
import os.retinue.ara.data.InstanceState
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.settings.SettingsAction
import os.retinue.ara.settings.SettingsMessages
import os.retinue.ara.settings.SettingsPageState
import os.retinue.ara.settings.UpdatesInfo
import os.retinue.ara.settings.WakeInfo
import os.retinue.ara.shell.ShellDecisions
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeTarget
import os.retinue.ara.wake.WakeTargets
import os.retinue.ara.wake.WakeTicket

/**
 * Instances on the phone: shown (foreground), working in the background, or
 * disabled; each with its own wake phrase, and the wake that brings a
 * background instance to the front.
 */
class InstanceStateTest {

    private val always = EndpointWake.of(WakeMode.Always, 0.5f, "oww:hey_jarvis_v0.1")
    private val home = Endpoint("home", "https://home.example", wake = always)
    private val work = Endpoint("work", "https://work.example", background = true, wake = EndpointWake.of(WakeMode.WhileVisible, 0.6f, "user:work"))
    private val club = Endpoint("club", "https://club.example", wake = always)

    @Test
    fun theShownEndpointIsForegroundTheOthersAsTheirSwitchSays() {
        val set = EndpointSet(listOf(home, work, club), "home")
        assertEquals(InstanceState.Foreground, set.stateOf(home))
        assertEquals(InstanceState.Background, set.stateOf(work))
        assertEquals(InstanceState.Disabled, set.stateOf(club))
        assertEquals(listOf(work), set.backgroundEndpoints)
        // Shown, an endpoint is in the foreground whatever its switch says.
        val switched = set.select("work")
        assertEquals(InstanceState.Foreground, switched.stateOf(work))
        assertEquals(InstanceState.Disabled, switched.stateOf(home))
        assertEquals(emptyList(), switched.backgroundEndpoints)
        // No current id: the first is shown.
        assertEquals(InstanceState.Foreground, EndpointSet(listOf(home, work), null).stateOf(home))
    }

    @Test
    fun theStateAndTheWakePhraseAreStoredWithTheEndpoint() {
        val raw = EndpointCodec.encode(listOf(home, work))
        assertEquals(listOf(home, work), EndpointCodec.decode(raw))
        assertTrue(raw.contains("\"background\":true"), raw)
        assertTrue(raw.contains("\"modelId\":\"user:work\""), raw)
        // A list of an older build has neither: off in the background, no wake of its own.
        val old = EndpointCodec.decode("""{"version":1,"endpoints":[{"id":"a","baseUrl":"https://a.example"}]}""")!!.single()
        assertFalse(old.background)
        assertNull(old.wake)
    }

    @Test
    fun theOneWakeSettingOfOlderBuildsGoesToEveryEndpointWithoutItsOwn() {
        val legacy = EndpointWake.of(WakeMode.Always, 0.7f)
        val bare = Endpoint("a", "https://a.example")
        val migrated = EndpointCodec.withLegacyWake(listOf(bare, home), legacy)
        assertEquals(legacy, migrated[0].wake)
        // An endpoint with its own keeps it.
        assertEquals(always, migrated[1].wake)
        val done = listOf(home, work)
        assertTrue(EndpointCodec.withLegacyWake(done, legacy) === done)
        // Thresholds stay in the detector's range.
        assertEquals(EndpointWake.MAX_THRESHOLD, EndpointWake.of(WakeMode.Always, 5f).threshold)
        assertEquals(EndpointWake.MIN_THRESHOLD, EndpointWake.of(WakeMode.Always, 0f).threshold)
        assertEquals(WakeMode.Off, EndpointWake(mode = "nonsense").wakeMode)
    }

    @Test
    fun theShownAndTheBackgroundInstancesAreListenedForTheShownFirst() {
        val settings = ShellSettings(endpoints = listOf(work, home, club), currentEndpointId = "home")
        assertEquals(
            listOf(
                WakeTarget("home", WakeMode.Always, 0.5f, "oww:hey_jarvis_v0.1", foreground = true),
                WakeTarget("work", WakeMode.WhileVisible, 0.6f, "user:work", foreground = false),
            ),
            WakeTargets.of(settings),
        )
        // Mode off, or no wake setting at all: not listened for, even in the background.
        val quiet = ShellSettings(
            endpoints = listOf(home.copy(wake = EndpointWake.OFF), work.copy(wake = null)),
            currentEndpointId = "home",
        )
        assertEquals(emptyList(), WakeTargets.of(quiet))
        assertEquals(emptyList(), WakeTargets.of(ShellSettings()))
    }

    @Test
    fun aPhraseHeardForSeveralInstancesWakesTheFirstOfThem() {
        val targets = WakeTargets.of(ShellSettings(endpoints = listOf(work, home), currentEndpointId = "home"))
        assertEquals("home", WakeTargets.pick(targets, setOf("work", "home")))
        assertEquals("work", WakeTargets.pick(targets, listOf("work")))
        assertNull(WakeTargets.pick(targets, emptyList()))
        assertNull(WakeTargets.pick(targets, listOf("club")))
    }

    @Test
    fun theWakeTicketKeepsTheInstanceItWasIssuedFor() {
        var n = 0
        val ticket = WakeTicket(maxAgeMs = 1_000) { "t${++n}" }
        val token = ticket.issue(nowMs = 0, endpoint = "work")
        assertNull(ticket.take("forged", nowMs = 10))
        assertEquals(WakeTicket.Taken("work"), ticket.take(token, nowMs = 10))
        // Used up.
        assertNull(ticket.take(token, nowMs = 20))
        // Without an instance: the one shown.
        val plain = ticket.issue(nowMs = 0)
        assertEquals(WakeTicket.Taken(null), ticket.take(plain, nowMs = 10))
        // Too late.
        val late = ticket.issue(nowMs = 0, endpoint = "work")
        assertNull(ticket.take(late, nowMs = 1_001))
    }

    @Test
    fun aWakeForAnotherInstanceWaitsUntilTheSwitchHasArrived() {
        assertTrue(ShellDecisions.wakeEndpointShown(null, "home"))
        assertTrue(ShellDecisions.wakeEndpointShown("work", "work"))
        assertFalse(ShellDecisions.wakeEndpointShown("work", "home"))
        assertFalse(ShellDecisions.wakeEndpointShown("work", null))
    }

    @Test
    fun theSettingsPageListsEachEndpointsStateAndSavesTheSwitch() {
        val settings = ShellSettings(endpoints = listOf(home, work, club), currentEndpointId = "home")
        val rows = SettingsPageState.endpointRows(settings)
        assertEquals(listOf("foreground", "background", "disabled"), rows.map { it.state })
        assertEquals(listOf(false, true, false), rows.map { it.background })

        val parsed = SettingsMessages.parse(
            """{"type":"endpoint.save","request":"r1","draft":{"id":"club","isNew":false,"baseUrl":"https://club.example","healthUpload":false,"background":true}}""",
        ) as SettingsAction.SaveEndpoint
        assertTrue(parsed.draft.background)
        assertTrue(SettingsMessages.toEndpointDraft(parsed.draft, club).background)
        // A page that does not know the switch sends none: off.
        val older = SettingsMessages.parse(
            """{"type":"endpoint.save","request":"r1","draft":{"id":"club","isNew":false,"baseUrl":"https://club.example"}}""",
        ) as SettingsAction.SaveEndpoint
        assertFalse(older.draft.background)
    }

    @Test
    fun theStateGoesToThePageInTheEndpointList() {
        val settings = ShellSettings(endpoints = listOf(home, work), currentEndpointId = "home")
        val rows = SettingsPageState.endpointRows(settings)
        val json = SettingsPageState(
            version = "2.0",
            endpoints = rows,
            current = SettingsPageState.currentEndpoint(settings),
            failure = null,
            health = JsonObject(emptyMap()),
            isDefaultHome = false,
            splitLaunch = false,
            keepSplitInRecents = false,
            wake = WakeInfo("always", 0.5f, "Listening.", true, true, true, true, true, true, true, true, "https://x/hey.onnx", false, null),
            updates = UpdatesInfo("Installed: a local build.", false, "nightly", "Not checked yet.", false, null, null, false, true, true),
            device = emptyMap(),
        ).toJson()
        val list = json["endpoints"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("foreground", "background"), list.map { it["state"]!!.jsonPrimitive.content })
        assertEquals(listOf("false", "true"), list.map { it["background"]!!.jsonPrimitive.content })
        assertEquals(0, json["wake"]!!.jsonObject["alsoListening"]!!.jsonArray.size)
    }

    @Test
    fun theWakeCardNamesTheOtherInstancesListenedFor() {
        val wake = WakeInfo("always", 0.5f, "Listening.", true, true, true, true, true, true, true, true, "u", false, null, alsoListening = listOf("Work"))
        val settings = ShellSettings(endpoints = listOf(home), currentEndpointId = "home")
        val json = SettingsPageState(
            "2.0", SettingsPageState.endpointRows(settings), null, null, JsonObject(emptyMap()), false, false, false, wake,
            UpdatesInfo("i", false, "nightly", "s", false, null, null, false, true, true), emptyMap(),
        ).toJson()
        assertEquals("Work", json["wake"]!!.jsonObject["alsoListening"]!!.jsonArray.single().jsonPrimitive.content)
    }
}
