package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.apps.AppsLogic
import os.retinue.ara.apps.ReminderOccurrence
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.WebMessage

class AppsLogicTest {

    private val base = "https://agents.example.com"
    private val now = AppsLogic.parseKey("2026-11-24T09:00:00Z")!!

    @Test
    fun theIndexKeepsOnlyAppPagesOfTheEndpoint() {
        val doc = """{"apps": [
            {"chamber": "routines", "id": "routine", "name": "Routine timer", "description": "d",
             "sphere": "health", "url": "/apps/routines/routine/", "start_url": "/apps/routines/routine/"},
            {"chamber": "x", "id": "elsewhere", "name": "E", "url": "https://evil.example/apps/x/elsewhere/"},
            {"chamber": "x", "id": "sends", "name": "S", "url": "/sends"},
            {"chamber": "../x", "id": "y", "url": "/apps/../x/y/"},
            {"chamber": "x", "id": "noname", "url": "/apps/x/noname/"},
            "junk"]}"""
        val apps = AppsLogic.parseIndex(doc, base)
        assertEquals(listOf("routine", "noname"), apps.map { it.id })
        assertEquals("https://agents.example.com/apps/routines/routine/", apps[0].url)
        assertEquals("Routine timer", apps[0].name)
        assertEquals("health", apps[0].sphere)
        assertEquals("noname", apps[1].name)
        assertEquals(emptyList(), AppsLogic.parseIndex("not json", base))
        assertEquals(emptyList(), AppsLogic.parseIndex("""{"apps": 3}""", base))
    }

    private fun schedule(occurrences: String, delivered: String = "[]", open: String = "null") = """{
        "now": "2026-11-24T09:00:00Z", "snooze_minutes": 10, "reminders": [
        {"id": "app:routines:routine:hand-1", "chamber": "routines", "app": "routine", "job": "hand-1",
         "title": "Hand session", "body": "Imagine, then try",
         "url": "/apps/routines/routine/?retinue-reminder=hand-1#session/hand-1",
         "occurrences": $occurrences, "delivered": $delivered, "open": $open},
        {"id": "app:routines:routine:other", "chamber": "routines", "app": "routine", "job": "hand-1",
         "title": "Mismatched id", "url": "/apps/routines/routine/", "occurrences": [{"at": "2026-11-24T10:00:00Z"}]},
        {"id": "app:routines:routine:far", "chamber": "routines", "app": "routine", "job": "far",
         "title": "Elsewhere", "url": "https://evil.example/", "occurrences": [{"at": "2026-11-24T10:00:00Z"}]},
        {"id": "app:routines:routine:page", "chamber": "routines", "app": "routine", "job": "page",
         "title": "Another page", "url": "/sends", "occurrences": [{"at": "2026-11-24T10:00:00Z"}]}
        ]}"""

    @Test
    fun theScheduleGivesTheOccurrencesToArm() {
        val doc = schedule(
            """[{"at": "2026-11-24T09:30:00Z", "ring": true}, {"at": "2026-11-25T09:30:00Z", "ring": false},
                {"at": "2026-11-24T07:00:00Z"}, {"at": "2026-11-24T08:55:00Z"}, {"at": "soon"}]""",
            delivered = """["2026-11-25T09:30:00Z"]""",
        )
        val occ = AppsLogic.parseSchedule(doc, base, "e1", now)
        // Two hours late is dropped, five minutes late still fires; delivered is left out; junk is ignored.
        assertEquals(listOf("2026-11-24T08:55:00Z", "2026-11-24T09:30:00Z"), occ.map { it.key })
        val first = occ[1]
        assertEquals("app:routines:routine:hand-1", first.reminderId)
        assertEquals("https://agents.example.com/apps/routines/routine/?retinue-reminder=hand-1#session/hand-1", first.url)
        assertTrue(first.ring)
        assertEquals("e1|app:routines:routine:hand-1|2026-11-24T09:30:00Z", first.armKey)
        assertEquals(10, AppsLogic.snoozeMinutes(doc))
        assertEquals(AppsLogic.DEFAULT_SNOOZE_MINUTES, AppsLogic.snoozeMinutes("{}"))
        val quiet = AppsLogic.parseSchedule(schedule("""[{"at": "2026-11-24T09:30:00Z", "ring": false}]"""), base, "e1", now)
        assertEquals(false, quiet.single().ring)
        val opened = AppsLogic.parseSchedule(schedule("""[{"at": "2026-11-24T08:58:00Z"}]""", open = "\"2026-11-24T08:58:00Z\""), base, "e1", now)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun nothingBeyondTheHorizonIsArmed() {
        val doc = schedule("""[{"at": "2026-11-26T08:59:00Z"}, {"at": "2026-11-26T09:30:00Z"}, {"at": "2027-01-01T09:00:00Z"}]""")
        // 48 hours from 2026-11-24T09:00Z (and the same slack as for a late one).
        assertEquals(listOf("2026-11-26T08:59:00Z"), AppsLogic.parseSchedule(doc, base, "e1", now).map { it.key })
    }

    @Test
    fun aRepeatedAppIsListedOnce() {
        val doc = """{"apps": [
            {"chamber": "c", "id": "a", "name": "First", "url": "/apps/c/a/"},
            {"chamber": "c", "id": "a", "name": "Again", "url": "/apps/c/a/"}]}"""
        assertEquals(listOf("First"), AppsLogic.parseIndex(doc, base).map { it.name })
    }

    @Test
    fun occurrenceKeysAreTheServersSpelling() {
        assertEquals("2026-11-24T09:30:00Z", AppsLogic.occurrenceKey(AppsLogic.parseKey("2026-11-24T09:30:00.750Z")!!))
        assertEquals(AppsLogic.parseKey("2026-11-24T10:30:00+01:00"), AppsLogic.parseKey("2026-11-24T09:30:00Z"))
        assertNull(AppsLogic.parseKey("tomorrow"))
    }

    @Test
    fun actionPathsAreOnlyAReminderOwn() {
        assertEquals("/apps/api/schedule/routines/routine/hand-1/delivered",
            AppsLogic.actionPath("routines", "routine", "hand-1", "delivered"))
        assertNull(AppsLogic.actionPath("..", "routine", "hand-1", "skip"))
        assertNull(AppsLogic.actionPath("routines", "routine", "../x", "skip"))
        assertNull(AppsLogic.actionPath("routines", "routine", "hand-1", "approve"))
        assertEquals("""{"occurrence":"2026-11-24T09:30:00Z"}""", AppsLogic.deliveredBody("2026-11-24T09:30:00Z"))
        assertEquals("""{"at":"2026-11-24T09:15:00Z"}""", AppsLogic.snoozeBody(now + 15 * 60_000L))
    }

    private fun occ(endpoint: String, key: String, title: String = "t") = ReminderOccurrence(
        endpoint, "app:c:a:j", "c", "a", "j", title, "", "$base/apps/c/a/", AppsLogic.parseKey(key)!!, key, true,
    )

    @Test
    fun thePlanCancelsWhatWentAndArmsWhatIsNew() {
        val kept = occ("e1", "2026-11-24T10:00:00Z")
        val gone = occ("e1", "2026-11-24T11:00:00Z")
        val changed = occ("e1", "2026-11-24T12:00:00Z")
        val other = occ("e2", "2026-11-24T10:00:00Z")
        val snooze = occ("e1", "2026-11-24T09:15:00Z")
        val armed = listOf(kept, gone, changed, other, snooze).associateBy { it.armKey }
        val fresh = occ("e1", "2026-11-25T10:00:00Z")
        val retitled = changed.copy(title = "new title")
        val plan = AppsLogic.plan("e1", armed, listOf(kept, retitled, fresh), setOf(snooze.armKey))
        assertEquals(setOf(gone.armKey), plan.cancel)          // another endpoint's and the local snooze stay
        assertEquals(listOf(retitled, fresh), plan.arm)       // unchanged ones are not armed twice
    }

    @Test
    fun skipTodayCancelsThatDaysAlarmsOfThatReminderOnly() {
        val zone = java.time.ZoneId.of("Europe/Zurich")
        val tapped = occ("e1", "2026-11-24T09:30:00Z")
        val later = occ("e1", "2026-11-24T16:00:00Z")
        val tomorrow = occ("e1", "2026-11-25T09:30:00Z")
        val otherEndpoint = occ("e2", "2026-11-24T16:00:00Z")
        val otherReminder = later.copy(reminderId = "app:c:a:k")
        val armed = listOf(later, tomorrow, otherEndpoint).associateBy { it.armKey } +
            (otherReminder.armKey + "x" to otherReminder)
        assertEquals(setOf(later.armKey), AppsLogic.sameDayArmed(armed, tapped, zone))
        assertEquals("""{"occurrence":"2026-11-24T09:30:00Z"}""", AppsLogic.skipBody(tapped.key))
    }

    @Test
    fun aSnoozeIsArmedOnThePhoneAtTheInstantSentToTheServer() {
        val o = occ("e1", "2026-11-24T09:00:00Z").copy(ring = false)
        val s = AppsLogic.snoozed(o, now + 450, 15)
        assertEquals("2026-11-24T09:15:00Z", s.key)
        assertEquals(AppsLogic.parseKey("2026-11-24T09:15:00Z"), s.atMs)
        assertTrue(s.ring)
    }

    @Test
    fun anOccurrenceSurvivesItsStorage() {
        val o = occ("e1", "2026-11-24T09:30:00Z", title = "Hand \"1\"")
        assertEquals(o, AppsLogic.decode(AppsLogic.encode(o)))
        assertNull(AppsLogic.decode("""{"endpoint":"e1"}"""))
        assertNull(AppsLogic.decode("""{"endpoint":"e1","id":"x","chamber":"..","app":"a","job":"j","title":"t","url":"u","at":1,"key":"k"}"""))
    }

    @Test
    fun theBridgeMessagesOfChamberApps() {
        assertEquals(WebMessage.AppNotify("Next set", "in 10 s"),
            ShellMessages.parse("""{"type":"app.notify","title":" Next set ","body":"in 10 s"}"""))
        assertNull(ShellMessages.parse("""{"type":"app.notify","title":"  ","body":"x"}"""))
        assertEquals(120, (ShellMessages.parse("""{"type":"app.notify","title":"${"x".repeat(300)}"}""") as WebMessage.AppNotify).title.length)
        assertEquals(WebMessage.RemindersChanged, ShellMessages.parse("""{"type":"reminders.changed"}"""))
        // speak() rides the stand-in's own wire.
        val speak = ShellMessages.parse("""{"type":"tts.speak","id":2000000001,"text":"Next set","lang":"en"}""") as WebMessage.Speak
        assertEquals(2000000001L, speak.id)
        assertEquals("en", speak.lang)
    }
}
