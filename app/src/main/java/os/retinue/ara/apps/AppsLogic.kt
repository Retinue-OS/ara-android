package os.retinue.ara.apps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One chamber app of an endpoint, as its `/apps/index.json` lists it
 * (Retinue's docs/chamber-apps.md). [url] is absolute and on the endpoint's
 * origin; an entry pointing anywhere else is dropped when parsed.
 */
data class ChamberApp(
    val chamber: String,
    val id: String,
    val name: String,
    val description: String,
    val sphere: String?,
    val url: String,
)

/**
 * One occurrence of a reminder, as the shell arms it: which endpoint and
 * reminder it belongs to, when it fires ([atMs]), its name ([key], the server's
 * spelling: UTC to the second with a Z), whether it would break through the
 * focus mode at its time ([ring]; a quiet one is shown without sound), and
 * what the notification shows and opens.
 */
data class ReminderOccurrence(
    val endpointId: String,
    val reminderId: String,
    val chamber: String,
    val app: String,
    val job: String,
    val title: String,
    val body: String,
    val url: String,
    val atMs: Long,
    val key: String,
    val ring: Boolean,
) {
    /** The name this occurrence is armed and remembered under on the phone. */
    val armKey: String get() = AppsLogic.armKey(endpointId, reminderId, key)
}

/**
 * The shell's side of chamber apps: parsing what the endpoint answers, the API
 * paths it posts to, and which local alarms to arm or cancel. Framework-free,
 * so it is unit-tested; the Android parts are [ReminderRuntime],
 * [ReminderReceiver] and the Apps sheet.
 */
object AppsLogic {

    private val json = Json { ignoreUnknownKeys = true }

    /** A chamber, app or job name as the server checks it: one path segment. */
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    private val JOB = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /** How far ahead the shell asks for, and arms, occurrences. */
    const val HORIZON_HOURS = 48

    /** At most this many alarms per endpoint, so a runaway plan cannot flood AlarmManager. */
    const val MAX_ALARMS = 60

    /** The snooze the notification's action offers when the server names none. */
    const val DEFAULT_SNOOZE_MINUTES = 15

    /** An occurrence found this late still fires (the phone was off, the alarm was inexact). */
    const val LATE_MS = 10 * 60 * 1000L

    private const val MAX_TITLE = 120
    private const val MAX_BODY = 500

    fun isName(value: String?): Boolean = value != null && NAME.matches(value)

    fun isJob(value: String?): Boolean = value != null && JOB.matches(value)

    /** `/apps/index.json`. */
    const val INDEX_PATH = "/apps/index.json"

    /** The reminders listing, marking this phone as one that arms them. */
    const val SCHEDULE_PATH = "/apps/api/schedule?arm=1&hours=$HORIZON_HOURS"

    /** `POST` target of an action on a reminder: opened, done, skip, snooze, delivered. */
    fun actionPath(chamber: String, app: String, job: String, action: String): String? {
        if (!isName(chamber) || !isName(app) || !isJob(job)) return null
        if (action !in ACTIONS) return null
        return "/apps/api/schedule/$chamber/$app/$job/$action"
    }

    val ACTIONS = setOf("opened", "done", "skip", "snooze", "delivered")

    /** The occurrence key of an instant, as the server spells it. */
    fun occurrenceKey(epochMs: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(epochMs / 1000 * 1000))

    /** An occurrence key back to epoch ms; null when it is not one. */
    fun parseKey(key: String?): Long? = try {
        if (key == null) null else Instant.parse(key).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    fun armKey(endpointId: String, reminderId: String, key: String) = "$endpointId|$reminderId|$key"

    /** A stable request code for the alarm and notification of [armKey]. */
    fun requestCode(armKey: String): Int = armKey.hashCode() and 0x3fffffff

    /** [path] (or an absolute URL) resolved against [base], when the result is on [base]'s origin. */
    fun sameOriginUrl(base: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        val b = base.toHttpUrlOrNull() ?: return null
        val u = b.resolve(path.trim()) ?: return null
        if (u.scheme != b.scheme || u.host != b.host || u.port != b.port) return null
        return u.toString()
    }

    /** The apps of `/apps/index.json`; empty for anything that is not such a document. */
    fun parseIndex(document: String?, base: String): List<ChamberApp> {
        val root = parseObject(document) ?: return emptyList()
        val list = root["apps"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val chamber = o.str("chamber")?.takeIf(::isName) ?: return@mapNotNull null
            val id = o.str("id")?.takeIf(::isName) ?: return@mapNotNull null
            val url = sameOriginUrl(base, o.str("start_url") ?: o.str("url")) ?: return@mapNotNull null
            // Only an app page of this endpoint: the list must not turn into a
            // way of loading another page of the origin by name.
            val path = url.toHttpUrlOrNull()?.encodedPath ?: return@mapNotNull null
            if (!path.startsWith("/apps/$chamber/$id/")) return@mapNotNull null
            ChamberApp(
                chamber = chamber,
                id = id,
                name = o.str("name")?.trim()?.take(80)?.ifEmpty { null } ?: id,
                description = o.str("description")?.trim()?.take(300).orEmpty(),
                sphere = o.str("sphere")?.trim()?.ifEmpty { null },
                url = url,
            )
        }
    }

    /**
     * The occurrences of `GET /apps/api/schedule` still to come after [nowMs]
     * (or at most [LATE_MS] late), soonest first, at most [MAX_ALARMS]. An
     * occurrence the server says this or another shell delivered is left out,
     * and so is one whose open item the server already holds for it.
     */
    fun parseSchedule(document: String?, base: String, endpointId: String, nowMs: Long): List<ReminderOccurrence> {
        val root = parseObject(document) ?: return emptyList()
        val list = root["reminders"] as? JsonArray ?: return emptyList()
        val out = mutableListOf<ReminderOccurrence>()
        for (el in list) {
            val r = el as? JsonObject ?: continue
            val chamber = r.str("chamber")?.takeIf(::isName) ?: continue
            val app = r.str("app")?.takeIf(::isName) ?: continue
            val job = r.str("job")?.takeIf(::isJob) ?: continue
            val id = r.str("id") ?: continue
            if (id != "app:$chamber:$app:$job") continue
            val url = sameOriginUrl(base, r.str("url")) ?: continue
            if (url.toHttpUrlOrNull()?.encodedPath?.startsWith("/apps/$chamber/$app/") != true) continue
            val title = r.str("title")?.trim()?.take(MAX_TITLE)?.ifEmpty { null } ?: continue
            val body = r.str("body")?.trim()?.take(MAX_BODY).orEmpty()
            val delivered = (r["delivered"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty()
            val open = r.str("open")
            for (o in (r["occurrences"] as? JsonArray).orEmpty()) {
                val occ = o as? JsonObject ?: continue
                val at = parseKey(occ.str("at")) ?: continue
                val key = occurrenceKey(at)
                if (key in delivered || key == open) continue
                if (at < nowMs - LATE_MS) continue
                val ring = (occ["ring"] as? JsonPrimitive)?.booleanOrNull ?: true
                out += ReminderOccurrence(endpointId, id, chamber, app, job, title, body, url, at, key, ring)
            }
        }
        return out.sortedBy { it.atMs }.take(MAX_ALARMS)
    }

    /** The snooze length the listing names, else [DEFAULT_SNOOZE_MINUTES]. */
    fun snoozeMinutes(document: String?): Int {
        val v = (parseObject(document)?.get("snooze_minutes") as? JsonPrimitive)?.doubleOrNull ?: return DEFAULT_SNOOZE_MINUTES
        return v.toInt().coerceIn(1, 240)
    }

    /** What changes on the phone: alarms to cancel (by arm key) and occurrences to arm. */
    data class Plan(val cancel: Set<String>, val arm: List<ReminderOccurrence>)

    /**
     * Bring the alarms armed for [endpointId] ([armed]: arm key → occurrence)
     * in line with [wanted]: what is no longer wanted is cancelled, what is new
     * (or changed) is armed. Alarms of other endpoints are left alone, and so
     * is a local snooze ([localSnoozes], arm keys) the server has not listed yet.
     */
    fun plan(
        endpointId: String,
        armed: Map<String, ReminderOccurrence>,
        wanted: List<ReminderOccurrence>,
        localSnoozes: Set<String> = emptySet(),
    ): Plan {
        val mine = armed.filterValues { it.endpointId == endpointId }
        val wantedByKey = wanted.associateBy { it.armKey }
        val cancel = mine.keys.filter { it !in wantedByKey && it !in localSnoozes }.toSet()
        val arm = wanted.filter { mine[it.armKey] != it }
        return Plan(cancel, arm)
    }

    // ---- storage of armed occurrences (SharedPreferences, as JSON) ---------

    fun encode(o: ReminderOccurrence): String = buildJsonObject {
        put("endpoint", o.endpointId); put("id", o.reminderId); put("chamber", o.chamber)
        put("app", o.app); put("job", o.job); put("title", o.title); put("body", o.body)
        put("url", o.url); put("at", o.atMs); put("key", o.key); put("ring", o.ring)
    }.toString()

    fun decode(raw: String?): ReminderOccurrence? {
        val o = parseObject(raw) ?: return null
        return try {
            ReminderOccurrence(
                endpointId = o.str("endpoint") ?: return null,
                reminderId = o.str("id") ?: return null,
                chamber = o.str("chamber")?.takeIf(::isName) ?: return null,
                app = o.str("app")?.takeIf(::isName) ?: return null,
                job = o.str("job")?.takeIf(::isJob) ?: return null,
                title = o.str("title") ?: return null,
                body = o.str("body").orEmpty(),
                url = o.str("url") ?: return null,
                atMs = (o["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null,
                key = o.str("key") ?: return null,
                ring = (o["ring"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** The body of a `delivered` confirmation. */
    fun deliveredBody(key: String): String = buildJsonObject { put("occurrence", key) }.toString()

    /** The body of a `snooze`: the exact instant the phone armed, so both sides name it alike. */
    fun snoozeBody(atMs: Long): String = buildJsonObject { put("at", occurrenceKey(atMs)) }.toString()

    /** The snoozed occurrence the phone arms itself, [minutes] from [nowMs]. */
    fun snoozed(o: ReminderOccurrence, nowMs: Long, minutes: Int): ReminderOccurrence {
        val at = (nowMs + minutes * 60_000L) / 1000 * 1000
        return o.copy(atMs = at, key = occurrenceKey(at), ring = true)
    }

    private fun parseObject(document: String?): JsonObject? {
        if (document.isNullOrBlank()) return null
        return try {
            json.parseToJsonElement(document) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
