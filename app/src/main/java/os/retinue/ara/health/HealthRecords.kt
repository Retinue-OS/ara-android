package os.retinue.ara.health

import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A Health Connect record type the shell reads, by the name it has on the wire. */
data class HealthType(val name: String, val recordClass: KClass<out Record>) {
    /** The read permission Health Connect grants for it. */
    val readPermission: String get() = HealthPermission.getReadPermission(recordClass)
}

/**
 * The record types the shell syncs and their JSON form (contract §3). The app
 * is a thin pipe: a record is written out field by field as Health Connect
 * has it, with units in the key names, instants as ISO-8601 UTC and zone
 * offsets in seconds; nothing is corrected, merged or dropped. Enumerations
 * are sent as Health Connect's int with its name beside it (`…_name`), so a
 * value a later Health Connect adds still arrives (its name is "unknown").
 */
object HealthRecords {

    val TYPES: List<HealthType> = listOf(
        HealthType("HeartRateRecord", HeartRateRecord::class),
        HealthType("RestingHeartRateRecord", RestingHeartRateRecord::class),
        HealthType("HeartRateVariabilityRmssdRecord", HeartRateVariabilityRmssdRecord::class),
        HealthType("SleepSessionRecord", SleepSessionRecord::class),
        HealthType("BloodPressureRecord", BloodPressureRecord::class),
        HealthType("BloodGlucoseRecord", BloodGlucoseRecord::class),
        HealthType("OxygenSaturationRecord", OxygenSaturationRecord::class),
        HealthType("StepsRecord", StepsRecord::class),
    )

    /** The read permissions of every synced type (what the shell asks for). */
    val READ_PERMISSIONS: Set<String> get() = TYPES.map { it.readPermission }.toSet()

    /** Lets a sync that runs while Ara is not in front read too (where Health Connect has the feature). */
    val BACKGROUND_PERMISSION: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** The types whose read permission is among [granted]. */
    fun grantedTypes(granted: Set<String>): List<HealthType> = TYPES.filter { it.readPermission in granted }

    fun typeOf(record: Record): HealthType? = TYPES.firstOrNull { it.recordClass.isInstance(record) }

    /**
     * One entry of `changes`: an upsert carries the full record, a deletion
     * only the id. Null for a record of a type the shell does not sync (the
     * Changes API returns only the requested types, so this is a safeguard).
     */
    fun change(change: Change): JsonObject? = when (change) {
        is UpsertionChange -> upsert(change.record)
        is DeletionChange -> delete(change.recordId)
        else -> null
    }

    fun upsert(record: Record): JsonObject? {
        val type = typeOf(record) ?: return null
        val body = record(record) ?: return null
        return buildJsonObject {
            put("op", "upsert")
            put("type", type.name)
            put("record", body)
        }
    }

    fun delete(id: String): JsonObject = buildJsonObject {
        put("op", "delete")
        put("id", id)
    }

    /** The record as JSON, or null for a type the shell does not sync. */
    fun record(record: Record): JsonObject? = when (record) {
        is HeartRateRecord -> buildJsonObject {
            metadata(record.metadata)
            interval(record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset)
            put(
                "samples",
                buildJsonArray {
                    record.samples.forEach { s ->
                        add(buildJsonObject { put("time", iso(s.time)); put("bpm", s.beatsPerMinute) })
                    }
                },
            )
        }
        is RestingHeartRateRecord -> buildJsonObject {
            metadata(record.metadata)
            instant(record.time, record.zoneOffset)
            put("bpm", record.beatsPerMinute)
        }
        is HeartRateVariabilityRmssdRecord -> buildJsonObject {
            metadata(record.metadata)
            instant(record.time, record.zoneOffset)
            put("heart_rate_variability_millis", record.heartRateVariabilityMillis)
        }
        is SleepSessionRecord -> buildJsonObject {
            metadata(record.metadata)
            interval(record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset)
            record.title?.let { put("title", it) }
            record.notes?.let { put("notes", it) }
            put(
                "stages",
                buildJsonArray {
                    record.stages.forEach { st ->
                        add(
                            buildJsonObject {
                                put("start_time", iso(st.startTime))
                                put("end_time", iso(st.endTime))
                                put("stage", st.stage)
                                put("stage_name", name(SLEEP_STAGES, st.stage))
                            },
                        )
                    }
                },
            )
        }
        is BloodPressureRecord -> buildJsonObject {
            metadata(record.metadata)
            instant(record.time, record.zoneOffset)
            put("systolic_mmhg", record.systolic.inMillimetersOfMercury)
            put("diastolic_mmhg", record.diastolic.inMillimetersOfMercury)
            enum("body_position", record.bodyPosition, BODY_POSITIONS)
            enum("measurement_location", record.measurementLocation, MEASUREMENT_LOCATIONS)
        }
        is BloodGlucoseRecord -> buildJsonObject {
            metadata(record.metadata)
            instant(record.time, record.zoneOffset)
            put("level_mmol_per_l", record.level.inMillimolesPerLiter)
            enum("specimen_source", record.specimenSource, SPECIMEN_SOURCES)
            enum("meal_type", record.mealType, MEAL_TYPES)
            enum("relation_to_meal", record.relationToMeal, RELATIONS_TO_MEAL)
        }
        is OxygenSaturationRecord -> buildJsonObject {
            metadata(record.metadata)
            instant(record.time, record.zoneOffset)
            put("percentage", record.percentage.value)
        }
        is StepsRecord -> buildJsonObject {
            metadata(record.metadata)
            interval(record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset)
            put("count", record.count)
        }
        else -> null
    }

    /** ISO-8601 in UTC (`…Z`), as [Instant.toString] writes it. */
    fun iso(instant: Instant): String = instant.toString()

    private fun JsonObjectBuilder.metadata(m: Metadata) {
        put(
            "metadata",
            buildJsonObject {
                put("id", m.id)
                put("data_origin", m.dataOrigin.packageName)
                put("last_modified_time", iso(m.lastModifiedTime))
                m.clientRecordId?.let { put("client_record_id", it) }
                put("client_record_version", m.clientRecordVersion)
                enum("recording_method", m.recordingMethod, RECORDING_METHODS)
                m.device?.let { d ->
                    put(
                        "device",
                        buildJsonObject {
                            d.manufacturer?.let { put("manufacturer", it) }
                            d.model?.let { put("model", it) }
                            enum("type", d.type, DEVICE_TYPES)
                        },
                    )
                }
            },
        )
    }

    private fun JsonObjectBuilder.instant(time: Instant, offset: ZoneOffset?) {
        put("time", iso(time))
        offset?.let { put("zone_offset_seconds", it.totalSeconds) }
    }

    private fun JsonObjectBuilder.interval(start: Instant, startOffset: ZoneOffset?, end: Instant, endOffset: ZoneOffset?) {
        put("start_time", iso(start))
        startOffset?.let { put("start_zone_offset_seconds", it.totalSeconds) }
        put("end_time", iso(end))
        endOffset?.let { put("end_zone_offset_seconds", it.totalSeconds) }
    }

    private fun JsonObjectBuilder.enum(key: String, value: Int, names: Map<Int, String>) {
        put(key, value)
        put("${key}_name", name(names, value))
    }

    private fun name(names: Map<Int, String>, value: Int): String = names[value] ?: "unknown"

    // Health Connect's own names for its constants (lower case, as in its string maps).

    val RECORDING_METHODS = mapOf(
        Metadata.RECORDING_METHOD_UNKNOWN to "unknown",
        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED to "actively_recorded",
        Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED to "automatically_recorded",
        Metadata.RECORDING_METHOD_MANUAL_ENTRY to "manual_entry",
    )

    val DEVICE_TYPES = mapOf(
        Device.TYPE_UNKNOWN to "unknown",
        Device.TYPE_WATCH to "watch",
        Device.TYPE_PHONE to "phone",
        Device.TYPE_SCALE to "scale",
        Device.TYPE_RING to "ring",
        Device.TYPE_HEAD_MOUNTED to "head_mounted",
        Device.TYPE_FITNESS_BAND to "fitness_band",
        Device.TYPE_CHEST_STRAP to "chest_strap",
        Device.TYPE_SMART_DISPLAY to "smart_display",
    )

    val SLEEP_STAGES = mapOf(
        SleepSessionRecord.STAGE_TYPE_UNKNOWN to "unknown",
        SleepSessionRecord.STAGE_TYPE_AWAKE to "awake",
        SleepSessionRecord.STAGE_TYPE_SLEEPING to "sleeping",
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "out_of_bed",
        SleepSessionRecord.STAGE_TYPE_LIGHT to "light",
        SleepSessionRecord.STAGE_TYPE_DEEP to "deep",
        SleepSessionRecord.STAGE_TYPE_REM to "rem",
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "awake_in_bed",
    )

    val BODY_POSITIONS = mapOf(
        BloodPressureRecord.BODY_POSITION_UNKNOWN to "unknown",
        BloodPressureRecord.BODY_POSITION_STANDING_UP to "standing_up",
        BloodPressureRecord.BODY_POSITION_SITTING_DOWN to "sitting_down",
        BloodPressureRecord.BODY_POSITION_LYING_DOWN to "lying_down",
        BloodPressureRecord.BODY_POSITION_RECLINING to "reclining",
    )

    val MEASUREMENT_LOCATIONS = mapOf(
        BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN to "unknown",
        BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_WRIST to "left_wrist",
        BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST to "right_wrist",
        BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM to "left_upper_arm",
        BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM to "right_upper_arm",
    )

    val SPECIMEN_SOURCES = mapOf(
        BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN to "unknown",
        BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID to "interstitial_fluid",
        BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD to "capillary_blood",
        BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA to "plasma",
        BloodGlucoseRecord.SPECIMEN_SOURCE_SERUM to "serum",
        BloodGlucoseRecord.SPECIMEN_SOURCE_TEARS to "tears",
        BloodGlucoseRecord.SPECIMEN_SOURCE_WHOLE_BLOOD to "whole_blood",
    )

    val MEAL_TYPES = mapOf(
        MealType.MEAL_TYPE_UNKNOWN to "unknown",
        MealType.MEAL_TYPE_BREAKFAST to "breakfast",
        MealType.MEAL_TYPE_LUNCH to "lunch",
        MealType.MEAL_TYPE_DINNER to "dinner",
        MealType.MEAL_TYPE_SNACK to "snack",
    )

    val RELATIONS_TO_MEAL = mapOf(
        BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN to "unknown",
        BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL to "general",
        BloodGlucoseRecord.RELATION_TO_MEAL_FASTING to "fasting",
        BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL to "before_meal",
        BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL to "after_meal",
    )
}
