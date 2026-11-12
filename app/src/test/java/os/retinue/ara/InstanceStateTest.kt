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
import os.retinue.ara.ui.WakeConditions
import os.retinue.ara.ui.shownWakeStatus
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeModelStatus
import os.retinue.ara.wake.WakePhraseScorers
import os.retinue.ara.wake.WakeStatus
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

    /** Scores past the scorers' warm-up, then [frames], each one frame's scores (one per model); what each frame wakes. */
    private fun wakes(scorers: WakePhraseScorers, models: Int, vararg frames: FloatArray): List<Int?> {
        var now = 0L
        repeat(5) { scorers.onScores(FloatArray(models), now++) }
        return frames.map { scorers.onScores(it, now++) }
    }

    @Test
    fun aPhraseTwoInstancesShareWakesTheFirstOfThemWhicheverThresholdIsReached() {
        // The shown one (first) is less sensitive than the background one: a
        // score rising through the lower threshold must not bring that one to the front.
        assertEquals(
            listOf(null, 0),
            wakes(WakePhraseScorers(listOf(0.5f, 0.3f), listOf(0, 0)), 1, floatArrayOf(0.1f), floatArrayOf(0.4f)),
        )
        assertEquals(listOf<Int?>(0), wakes(WakePhraseScorers(listOf(0.5f, 0.5f), listOf(0, 0)), 1, floatArrayOf(0.9f)))
    }

    @Test
    fun aPhraseOfItsOwnWakesItsInstanceAtItsOwnThreshold() {
        val scorers = WakePhraseScorers(listOf(0.5f, 0.6f, 0.3f), listOf(0, 1, 0))
        assertEquals(
            listOf(null, 1),
            wakes(scorers, 2, floatArrayOf(0.2f, 0.55f), floatArrayOf(0.2f, 0.7f)),
        )
        // Both phrases in one frame: the first in order.
        assertEquals(listOf<Int?>(0), wakes(WakePhraseScorers(listOf(0.5f, 0.5f), listOf(0, 1)), 2, floatArrayOf(0.9f, 0.9f)))
        assertEquals(listOf<Int?>(null), wakes(WakePhraseScorers(listOf(0.5f), listOf(0)), 1, floatArrayOf(Float.NaN)))
    }

    @Test
    fun ofInstancesThatShareAPhraseModelOnlyTheOneItWakesCountsAsListenedFor() {
        // Home (shown) and Work listen with the same model file, Club with its own: Work's phrase wakes Home.
        assertEquals(listOf(0, 0, 2), WakePhraseScorers.takers(listOf("jarvis.onnx", "jarvis.onnx", "mine.onnx")))
        // The instance shown does not listen, and two in the background share a model: the first of them takes it.
        assertEquals(listOf(0, 0), WakePhraseScorers.takers(listOf("mine.onnx", "mine.onnx")))
        // A model each: each is woken by its own.
        assertEquals(listOf(0, 1), WakePhraseScorers.takers(listOf("jarvis.onnx", "mine.onnx")))
        assertEquals(emptyList(), WakePhraseScorers.takers(emptyList<String>()))
        // These are the phrases the scorers hand a wake to, whichever of the sharing thresholds is reached.
        val modelOf = listOf(0, 0, 1)
        val heard = wakes(WakePhraseScorers(listOf(0.5f, 0.3f, 0.5f), modelOf), 2, floatArrayOf(0.4f, 0f), floatArrayOf(0f, 0.9f))
        assertEquals(listOf<Int?>(0, 2), heard)
        assertEquals<Set<Int?>>(WakePhraseScorers.takers(modelOf).toSet(), heard.toSet())
    }

    @Test
    fun aStoppedListenerStartsAfreshOnlyWhenThePhraseModelsListenedWithChange() {
        val jarvis = "oww:hey_jarvis_v0.1"
        val settings = ShellSettings(endpoints = listOf(home.copy(background = true), work, club), currentEndpointId = "home")
        fun models(s: ShellSettings) = WakeTargets.models(WakeTargets.of(s))
        fun having(id: String, change: (Endpoint) -> Endpoint) =
            settings.copy(endpoints = settings.endpoints.map { if (it.id == id) change(it) else it })
        val before = models(settings)
        assertEquals(setOf(jarvis, "user:work"), before)
        // Another instance shown, another mode or threshold: the same models, and an error keeps its backoff.
        assertEquals(before, models(settings.copy(currentEndpointId = "work")))
        assertEquals(before, models(having("work") { it.copy(wake = EndpointWake.of(WakeMode.Always, 0.3f, "user:work")) }))
        // Another model chosen for an instance, or none: the one that stopped the listener may be gone.
        assertEquals(setOf(jarvis), models(having("work") { it.copy(wake = it.wake!!.copy(modelId = jarvis)) }))
        assertEquals(setOf(jarvis), models(having("work") { it.copy(wake = it.wake!!.copy(modelId = null)) }))
        // So it may when its instance is no longer listened for: its mode off, at rest, or removed.
        assertEquals(setOf(jarvis), models(having("work") { it.copy(wake = it.wake!!.copy(mode = WakeMode.Off.key)) }))
        assertEquals(setOf(jarvis), models(having("work") { it.copy(background = false) }))
        assertEquals(setOf(jarvis), models(settings.copy(endpoints = settings.endpoints.filter { it.id != "work" })))
        // An instance that joins without a model, or with one already listened with, brings no new model.
        assertEquals(before, models(having("club") { it.copy(background = true, wake = EndpointWake.of(WakeMode.Always, 0.5f)) }))
        assertEquals(before, models(having("club") { it.copy(background = true) }))
        assertEquals(emptySet(), models(ShellSettings()))
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
    fun aWakeIsDroppedForARemovedInstanceButNotBeforeTheSettingsAreRead() {
        val known = listOf("home", "work")
        assertTrue(ShellDecisions.wakeEndpointGone("club", settingsLoaded = true, endpoints = known))
        assertFalse(ShellDecisions.wakeEndpointGone("work", settingsLoaded = true, endpoints = known))
        assertFalse(ShellDecisions.wakeEndpointGone(null, settingsLoaded = true, endpoints = known))
        // A window the wake brought up knows no endpoint yet: the wake waits for the settings.
        assertFalse(ShellDecisions.wakeEndpointGone("work", settingsLoaded = false, endpoints = emptyList()))
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
            wake = WakeInfo("always", 0.5f, "Listening.", true, true, true, true, true, true, true, emptyList(), false, null, null),
            updates = UpdatesInfo("Installed: a local build.", false, "nightly", "Not checked yet.", false, null, null, false, true, true),
            device = emptyMap(),
        ).toJson()
        val list = json["endpoints"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("foreground", "background"), list.map { it["state"]!!.jsonPrimitive.content })
        assertEquals(listOf("false", "true"), list.map { it["background"]!!.jsonPrimitive.content })
        assertEquals(0, json["wake"]!!.jsonObject["alsoListening"]!!.jsonArray.size)
    }

    @Test
    fun theWakeCardsStatusIsTheShownInstancesNotTheDetectors() {
        fun conditions(status: WakeStatus, listening: Set<String>) =
            WakeConditions(status, true, true, true, true, WakeModelStatus(true, true, true), emptyList(), false, null, null, listening)
        assertEquals(WakeStatus.Listening, shownWakeStatus(conditions(WakeStatus.Listening, setOf("home", "work")), "home", WakeMode.Always))
        // Only a background instance is listened for: the shown one is not.
        assertEquals(WakeStatus.Idle, shownWakeStatus(conditions(WakeStatus.Listening, setOf("work")), "home", WakeMode.WhileVisible))
        assertEquals(WakeStatus.Idle, shownWakeStatus(conditions(WakeStatus.Listening, setOf("work")), "home", WakeMode.Off))
        // What holds the detector back holds the shown one back too, unless its phrase is off.
        assertEquals(WakeStatus.PausedForVoice, shownWakeStatus(conditions(WakeStatus.PausedForVoice, emptySet()), "home", WakeMode.Always))
        assertEquals(WakeStatus.Error("mic"), shownWakeStatus(conditions(WakeStatus.Error("mic"), emptySet()), "home", WakeMode.Always))
        assertEquals(WakeStatus.Idle, shownWakeStatus(conditions(WakeStatus.Error("mic"), emptySet()), "home", WakeMode.Off))
        assertEquals(WakeStatus.Idle, shownWakeStatus(conditions(WakeStatus.Listening, setOf("work")), null, WakeMode.Always))
    }

    @Test
    fun theWakeCardNamesTheOtherInstancesListenedFor() {
        val wake = WakeInfo("always", 0.5f, "Listening.", true, true, true, true, true, true, true, emptyList(), false, null, null, alsoListening = listOf("Work"))
        val settings = ShellSettings(endpoints = listOf(home), currentEndpointId = "home")
        val json = SettingsPageState(
            "2.0", SettingsPageState.endpointRows(settings), null, null, JsonObject(emptyMap()), false, false, false, wake,
            UpdatesInfo("i", false, "nightly", "s", false, null, null, false, true, true), emptyMap(),
        ).toJson()
        assertEquals("Work", json["wake"]!!.jsonObject["alsoListening"]!!.jsonArray.single().jsonPrimitive.content)
    }
}
