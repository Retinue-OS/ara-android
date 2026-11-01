package os.retinue.ara

import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Pressure
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import os.retinue.ara.health.HealthRecords

class HealthRecordsTest {

    private val t = Instant.parse("2026-11-01T06:30:00Z")
    private val cet = ZoneOffset.ofHours(1)
    private val watch = Device(type = Device.TYPE_WATCH, manufacturer = "Garmin", model = "fenix 8")

    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun bloodPressure() {
        val r = BloodPressureRecord(
            time = t,
            zoneOffset = cet,
            metadata = Metadata.manualEntryWithId("bp-1"),
            systolic = Pressure.millimetersOfMercury(128.0),
            diastolic = Pressure.millimetersOfMercury(82.0),
            bodyPosition = BloodPressureRecord.BODY_POSITION_SITTING_DOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM,
        )
        val change = HealthRecords.change(UpsertionChange(r))!!
        assertEquals("upsert", change["op"].toString().trim('"'))
        assertEquals("BloodPressureRecord", change["type"].toString().trim('"'))
        val record = change["record"]!!.jsonObject
        val expected = json(
            """{"time":"2026-11-01T06:30:00Z","zone_offset_seconds":3600,
            "systolic_mmhg":128.0,"diastolic_mmhg":82.0,
            "body_position":${BloodPressureRecord.BODY_POSITION_SITTING_DOWN},"body_position_name":"sitting_down",
            "measurement_location":${BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM},
            "measurement_location_name":"left_upper_arm"}""",
        ).jsonObject
        assertEquals(expected, JsonObject(record - "metadata"))
        val metadata = record["metadata"]!!.jsonObject
        assertEquals("\"bp-1\"", metadata["id"].toString())
        assertEquals("\"manual_entry\"", metadata["recording_method_name"].toString())
        assertEquals("${Metadata.RECORDING_METHOD_MANUAL_ENTRY}", metadata["recording_method"].toString())
        assertFalse("device" in metadata)
        assertFalse("client_record_id" in metadata)
    }

    @Test
    fun heartRateWithDevice() {
        val r = HeartRateRecord(
            startTime = t,
            startZoneOffset = cet,
            endTime = t.plusSeconds(60),
            endZoneOffset = null,
            samples = listOf(HeartRateRecord.Sample(t, 58), HeartRateRecord.Sample(t.plusSeconds(30), 61)),
            metadata = Metadata.autoRecorded(watch, "garmin-1", 3),
        )
        val record = HealthRecords.record(r)!!
        assertEquals(
            json(
                """{"start_time":"2026-11-01T06:30:00Z","start_zone_offset_seconds":3600,"end_time":"2026-11-01T06:31:00Z",
                "samples":[{"time":"2026-11-01T06:30:00Z","bpm":58},{"time":"2026-11-01T06:30:30Z","bpm":61}]}""",
            ),
            JsonObject(record - "metadata"),
        )
        val metadata = record["metadata"]!!.jsonObject
        assertEquals("\"garmin-1\"", metadata["client_record_id"].toString())
        assertEquals("3", metadata["client_record_version"].toString())
        assertEquals(
            json("""{"manufacturer":"Garmin","model":"fenix 8","type":${Device.TYPE_WATCH},"type_name":"watch"}"""),
            metadata["device"],
        )
    }

    @Test
    fun sleepGlucoseHrvSteps() {
        val sleep = HealthRecords.record(
            SleepSessionRecord(
                startTime = t,
                startZoneOffset = cet,
                endTime = t.plusSeconds(3600),
                endZoneOffset = cet,
                metadata = Metadata.autoRecorded(watch),
                title = "Night",
                notes = null,
                stages = listOf(SleepSessionRecord.Stage(t, t.plusSeconds(1800), SleepSessionRecord.STAGE_TYPE_DEEP)),
            ),
        )!!
        assertEquals("\"Night\"", sleep["title"].toString())
        assertFalse("notes" in sleep)
        assertEquals(
            json(
                """[{"start_time":"2026-11-01T06:30:00Z","end_time":"2026-11-01T07:00:00Z",
                "stage":${SleepSessionRecord.STAGE_TYPE_DEEP},"stage_name":"deep"}]""",
            ),
            sleep["stages"],
        )

        val glucose = HealthRecords.record(
            BloodGlucoseRecord(
                time = t,
                zoneOffset = cet,
                metadata = Metadata.autoRecorded(watch),
                level = BloodGlucose.millimolesPerLiter(5.4),
                specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
                mealType = MealType.MEAL_TYPE_BREAKFAST,
                relationToMeal = BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL,
            ),
        )!!
        assertEquals("5.4", glucose["level_mmol_per_l"].toString())
        assertEquals("\"interstitial_fluid\"", glucose["specimen_source_name"].toString())
        assertEquals("\"breakfast\"", glucose["meal_type_name"].toString())
        assertEquals("\"after_meal\"", glucose["relation_to_meal_name"].toString())

        val hrv = HealthRecords.record(HeartRateVariabilityRmssdRecord(t, null, 42.5, Metadata.autoRecorded(watch)))!!
        assertEquals("42.5", hrv["heart_rate_variability_millis"].toString())
        assertFalse("zone_offset_seconds" in hrv)

        val steps = HealthRecords.record(StepsRecord(t, cet, t.plusSeconds(900), cet, 1234, Metadata.autoRecorded(watch)))!!
        assertEquals("1234", steps["count"].toString())
    }

    @Test
    fun deletionsCarryOnlyTheId() {
        assertEquals<Any?>(json("""{"op":"delete","id":"hc-9"}"""), HealthRecords.change(DeletionChange("hc-9")))
    }
}
