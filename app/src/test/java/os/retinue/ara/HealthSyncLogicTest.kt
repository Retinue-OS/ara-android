package os.retinue.ara

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import os.retinue.ara.health.HealthRecords
import os.retinue.ara.health.HealthSyncEvent
import os.retinue.ara.health.HealthSyncLogic
import os.retinue.ara.health.HealthSyncStatus
import os.retinue.ara.health.HealthTrigger
import os.retinue.ara.health.PermissionState
import os.retinue.ara.health.SkipReason
import os.retinue.ara.health.SyncDecision
import os.retinue.ara.health.SyncKind
import os.retinue.ara.health.SyncResult
import os.retinue.ara.health.UploadResult
import os.retinue.ara.shell.ShellMessages
import os.retinue.ara.shell.WebMessage

class HealthSyncLogicTest {

    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L

    private fun decide(
        trigger: HealthTrigger,
        now: Long = t0,
        running: Boolean = false,
        lastAttemptAt: Long = 0L,
        lastDeliberateAt: Long = 0L,
        interval: Int = 5,
        auto: Boolean = true,
    ) = HealthSyncLogic.decide(trigger, now, running, lastAttemptAt, lastDeliberateAt, interval, auto)

    @Test
    fun automaticSyncsWaitForTheInterval() {
        assertEquals(SyncDecision.Run, decide(HealthTrigger.Resume))
        assertEquals(SyncDecision.Skip(SkipReason.Interval), decide(HealthTrigger.Resume, lastAttemptAt = t0 - 4 * minute))
        assertEquals(SyncDecision.Run, decide(HealthTrigger.Resume, lastAttemptAt = t0 - 5 * minute))
        assertEquals(SyncDecision.Run, decide(HealthTrigger.Resume, lastAttemptAt = t0 - 11 * minute, interval = 10))
        assertEquals(SyncDecision.Skip(SkipReason.Interval), decide(HealthTrigger.Resume, lastAttemptAt = t0 - 9 * minute, interval = 10))
        // A clock set back does not block syncing.
        assertEquals(SyncDecision.Run, decide(HealthTrigger.Resume, lastAttemptAt = t0 + 60 * minute))
        assertEquals(SyncDecision.Ignore, decide(HealthTrigger.Resume, auto = false))
        assertEquals(SyncDecision.Ignore, decide(HealthTrigger.Resume, running = true))
    }

    @Test
    fun deliberateSyncsBypassTheIntervalAndCollapse() {
        assertEquals(SyncDecision.Run, decide(HealthTrigger.Manual, lastAttemptAt = t0 - minute))
        assertEquals(SyncDecision.Run, decide(HealthTrigger.Link, lastAttemptAt = t0 - minute, auto = false))
        assertEquals(SyncDecision.Ignore, decide(HealthTrigger.Manual, lastDeliberateAt = t0 - 4_999))
        assertEquals(SyncDecision.Run, decide(HealthTrigger.Manual, lastDeliberateAt = t0 - 5_000))
        assertEquals(SyncDecision.Ignore, decide(HealthTrigger.Link, running = true))
        assertTrue(HealthTrigger.Manual.deliberate)
        assertFalse(HealthTrigger.Resume.deliberate)
    }

    @Test
    fun permissions() {
        val wanted = setOf("a", "b")
        assertEquals(PermissionState.Granted, HealthSyncLogic.permissionState(setOf("a", "b", "x"), wanted))
        assertEquals(PermissionState.Partial, HealthSyncLogic.permissionState(setOf("b"), wanted))
        assertEquals(PermissionState.None, HealthSyncLogic.permissionState(setOf("x"), wanted))
        assertTrue(HealthSyncLogic.shouldRequestPermissions(true, PermissionState.None, askedBefore = true))
        assertTrue(HealthSyncLogic.shouldRequestPermissions(true, PermissionState.Partial, askedBefore = false))
        assertFalse(HealthSyncLogic.shouldRequestPermissions(true, PermissionState.Partial, askedBefore = true))
        assertFalse(HealthSyncLogic.shouldRequestPermissions(true, PermissionState.Granted, askedBefore = false))
        assertFalse(HealthSyncLogic.shouldRequestPermissions(false, PermissionState.None, askedBefore = false))
    }

    @Test
    fun typesAndTokenKey() {
        assertEquals(8, HealthRecords.TYPES.size)
        val granted = HealthRecords.grantedTypes(setOf(HealthRecords.TYPES[3].readPermission, HealthRecords.TYPES[0].readPermission))
        assertEquals(listOf("HeartRateRecord", "SleepSessionRecord"), granted.map { it.name })
        assertEquals("HeartRateRecord,SleepSessionRecord", HealthSyncLogic.typeKey(granted.reversed()))
        assertTrue(HealthRecords.READ_PERMISSIONS.all { it.startsWith("android.permission.health.READ_") })
    }

    @Test
    fun partsHoldAtMost5000Changes() {
        assertEquals(emptyList(), HealthSyncLogic.parts(emptyList<Int>()))
        assertEquals(listOf(5000), HealthSyncLogic.parts(List(5000) { it }).map { it.size })
        assertEquals(listOf(5000, 5000, 1), HealthSyncLogic.parts(List(10_001) { it }).map { it.size })
    }

    @Test
    fun envelopeMatchesTheContract() {
        val change = buildJsonObject { put("op", "delete"); put("id", "x1") }
        val body = HealthSyncLogic.envelope(
            batchId = "b-1",
            kind = SyncKind.Initial,
            trigger = HealthTrigger.Link,
            requestedTypes = listOf("HeartRateRecord", "StepsRecord"),
            windowStart = Instant.parse("2026-10-02T17:15:00Z"),
            part = 1,
            parts = 3,
            app = "os.retinue.ara",
            version = "2.0+abc1234",
            androidSdk = 35,
            sentAt = Instant.parse("2026-11-01T17:15:00Z"),
            changes = listOf(change),
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"schema":"retinue-health-connect/1","batch_id":"b-1",
                "sync":{"kind":"initial","trigger":"link","requested_types":["HeartRateRecord","StepsRecord"],
                "window_start":"2026-10-02T17:15:00Z","part":1,"parts":3},
                "client":{"app":"os.retinue.ara","version":"2.0+abc1234","android_sdk":35},
                "sent_at":"2026-11-01T17:15:00Z","changes":[{"op":"delete","id":"x1"}]}""",
            ),
            body,
        )
        // A changes sync has no window.
        val changes = HealthSyncLogic.envelope(
            "b-2", SyncKind.Changes, HealthTrigger.Resume, emptyList(), Instant.EPOCH, 1, 1,
            "os.retinue.ara", "2.0", 35, Instant.EPOCH, emptyList(),
        )
        assertFalse("window_start" in changes["sync"]!!.jsonObject)
    }

    @Test
    fun letterboxFileNames() {
        val name = HealthSyncLogic.fileName(Instant.parse("2026-11-01T17:15:00.123Z"), "0f1e", 2, 3)
        assertEquals("hc-20261101T171500Z-0f1e-p2of3.health-connect.json", name)
        assertEquals(
            "https://agents.example.com/inbox/$name",
            HealthSyncLogic.uploadUrl("https://agents.example.com", name),
        )
    }

    @Test
    fun readsTheLetterboxAnswer() {
        assertEquals(UploadResult.Stored, HealthSyncLogic.parseResponse(201))
        assertEquals(UploadResult.Stored, HealthSyncLogic.parseResponse(204))
        assertEquals(UploadResult.NoBackend, HealthSyncLogic.parseResponse(404))
        assertEquals(UploadResult.NoBackend, HealthSyncLogic.parseResponse(405))
        assertEquals(UploadResult.Failed("HTTP 302 (redirected; signed out?)"), HealthSyncLogic.parseResponse(302))
        assertEquals(UploadResult.Failed("HTTP 507 Insufficient Storage"), HealthSyncLogic.parseResponse(507, "Insufficient Storage"))
        assertEquals(UploadResult.Failed("HTTP 401"), HealthSyncLogic.parseResponse(401, ""))
    }

    @Test
    fun linkTriggerFromTheSyncPage() {
        val base = "https://agents.example.com"
        assertEquals(HealthTrigger.Link, HealthSyncLogic.triggerFor("$base/sync-health#sync", base))
        assertEquals(HealthTrigger.Link, HealthSyncLogic.triggerFor("$base/sync-health/?x=1", base))
        assertEquals(HealthTrigger.Manual, HealthSyncLogic.triggerFor("$base/#settings", base))
        assertEquals(HealthTrigger.Manual, HealthSyncLogic.triggerFor(null, base))
    }

    @Test
    fun statusJson() {
        assertEquals(
            Json.parseToJsonElement(
                """{"available":false,"permissions":"none","last_sync_at":null,"last_result":null,
                "last_error":null,"last_count":null,"min_interval_minutes":5,"auto_on_resume":true}""",
            ),
            HealthSyncStatus().toJson(),
        )
        val s = HealthSyncStatus(
            available = true, permissions = PermissionState.Partial, lastSyncAt = Instant.parse("2026-11-01T17:15:00Z").toEpochMilli(),
            lastResult = SyncResult.Ok, lastCount = 12, minIntervalMinutes = 10, autoOnResume = false,
        ).toJson()
        assertEquals("\"2026-11-01T17:15:00Z\"", s["last_sync_at"].toString())
        assertEquals("\"partial\"", s["permissions"].toString())
        assertEquals("12", s["last_count"].toString())
    }

    @Test
    fun bridgeMessagesAndEvents() {
        assertEquals(WebMessage.HealthSync, ShellMessages.parse("""{"type":"health.sync"}"""))
        assertEquals(WebMessage.HealthSyncInterval(15), ShellMessages.parse("""{"type":"health.interval","minutes":15}"""))
        assertEquals(null, ShellMessages.parse("""{"type":"health.interval","minutes":0}"""))
        assertEquals(null, ShellMessages.parse("""{"type":"health.interval"}"""))
        assertEquals(WebMessage.HealthAutoSync(false), ShellMessages.parse("""{"type":"health.auto","on":false}"""))
        assertEquals(null, ShellMessages.parse("""{"type":"health.auto"}"""))

        val done = ShellMessages.eventScript(ShellMessages.HEALTH_SYNC_EVENT, HealthSyncEvent.Done(42).detail())
        assertTrue(done.contains("{detail:{\"type\":\"health-sync\",\"state\":\"done\",\"count\":42},cancelable:true}"), done)
        val skipped = HealthSyncEvent.Skipped(SkipReason.NoBackendSupport).detail()
        assertEquals("""{"state":"skipped","reason":"no-backend-support"}""", skipped.toString())
        assertEquals("""{"state":"error","error":"HTTP 500"}""", HealthSyncEvent.Error("HTTP 500").detail().toString())
        assertEquals("""{"state":"started","trigger":"manual"}""", HealthSyncEvent.Started(HealthTrigger.Manual).detail().toString())
        val status = ShellMessages.healthStatus(HealthSyncStatus())
        assertTrue(status.startsWith("window.__retinueShellReceive&&window.__retinueShellReceive({\"type\":\"health.status\",\"status\":{"))
        // An extra "type" never overrides the event's.
        val sneaky: JsonObject = buildJsonObject { put("type", "wake") }
        assertTrue(ShellMessages.eventScript("health-sync", sneaky).contains("{\"type\":\"health-sync\"}"))
    }
}
