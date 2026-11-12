package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointCodec
import os.retinue.ara.data.EndpointLabels
import os.retinue.ara.data.EndpointSet
import os.retinue.ara.net.EndpointNames

/** The endpoint list: its stored form, the migration from one base URL, the set operations and the labels. */
class EndpointTest {

    private val home = Endpoint("home", "https://agents.example.com", certificateAlias = "ara-phone", healthUpload = true, name = "Retinue")
    private val admin = Endpoint("admin", "https://admin.example.com", username = "reto", secret = "c2VhbGVk", name = "Admin")

    @Test
    fun roundTripsThroughJson() {
        val raw = EndpointCodec.encode(listOf(home, admin))
        assertTrue(raw.startsWith("""{"version":1,"endpoints":["""), raw)
        assertEquals(listOf(home, admin), EndpointCodec.decode(raw))
        // The sealed password and the user name are stored as they are; nothing else is derived.
        assertTrue(raw.contains("\"secret\":\"c2VhbGVk\""))
        assertTrue(raw.contains("\"healthUpload\":true"))
    }

    @Test
    fun decodeIsForgiving() {
        // No document: the legacy keys decide (null); a broken one: nothing.
        assertNull(EndpointCodec.decode(null))
        assertEquals(emptyList(), EndpointCodec.decode("nonsense"))
        assertEquals(emptyList(), EndpointCodec.decode("""{"version":1}"""))
        // Unknown fields are ignored, entries without an id or without a URL dropped, duplicate ids keep the first.
        val raw = """{"version":1,"endpoints":[
            {"id":"a","baseUrl":"https://a.example","future":1},
            {"id":"","baseUrl":"https://nobody.example"},
            {"baseUrl":"https://no-id.example"},
            {"id":"a","baseUrl":"https://again.example"}]}"""
        val decoded = EndpointCodec.decode(raw)!!
        assertEquals(listOf("a"), decoded.map { it.id })
        assertEquals("https://a.example", decoded[0].baseUrl)
        assertFalse(decoded[0].healthUpload)
    }

    @Test
    fun theOldBaseUrlBecomesTheFirstEndpointWithHealthOn() {
        val migrated = EndpointCodec.legacy(" https://agents.example.com ", "ara-phone")
        assertEquals(1, migrated.size)
        assertEquals(EndpointCodec.LEGACY_ID, migrated[0].id)
        assertEquals("https://agents.example.com", migrated[0].baseUrl)
        assertEquals("ara-phone", migrated[0].certificateAlias)
        // The phone synced health data to this server until now: it goes on doing so.
        assertTrue(migrated[0].healthUpload)
        assertNull(migrated[0].username)
        assertEquals(emptyList(), EndpointCodec.legacy(null, "alias"))
        assertEquals(emptyList(), EndpointCodec.legacy("  ", null))
    }

    @Test
    fun theCarriedOverEndpointStaysShownWhenAnotherIsAdded() {
        // The store of an older build as AraStore reads it: its one server, shown without ever having been chosen.
        val carried = EndpointSet(EndpointCodec.legacy("https://agents.example.com", "ara-phone"), null)
        assertEquals(EndpointCodec.LEGACY_ID, carried.current?.id)
        // A second endpoint does not take over, and the endpoint shown is the stored choice from then on.
        val added = carried.upsert(admin)
        assertEquals(EndpointCodec.LEGACY_ID, added.currentId)
        assertEquals(EndpointCodec.LEGACY_ID, added.current?.id)
        assertEquals(listOf(EndpointCodec.LEGACY_ID, "admin"), added.endpoints.map { it.id })
        // Nor after the writes that choose nothing (a fetched name, a switch), which leave the choice unset.
        val named = carried.update(EndpointCodec.LEGACY_ID) { it.copy(name = "Retinue") }
        assertNull(named.currentId)
        assertEquals(EndpointCodec.LEGACY_ID, named.upsert(admin).currentId)
        // Saving the carried-over endpoint itself keeps it shown, as before.
        assertEquals(EndpointCodec.LEGACY_ID, carried.upsert(carried.endpoints[0].copy(name = "Home")).currentId)
        // A carried-over address the shell cannot use shows nothing: there the endpoint added takes over, as before.
        val unusable = EndpointSet(EndpointCodec.legacy("http://10.0.0.2:8080", null), null)
        assertNull(unusable.current?.base)
        assertEquals("admin", unusable.upsert(admin).currentId)
        // And once that address is put right in the editor, the carried-over endpoint is the choice.
        assertEquals(
            EndpointCodec.LEGACY_ID,
            unusable.upsert(unusable.endpoints[0].copy(baseUrl = "https://agents.example.com")).currentId,
        )
    }

    @Test
    fun setOperations() {
        var set = EndpointSet.EMPTY
        assertNull(set.current)
        // The first endpoint becomes the current one by itself; later ones do not.
        set = set.upsert(home)
        assertEquals("home", set.currentId)
        set = set.upsert(admin)
        assertEquals("home", set.currentId)
        assertEquals(listOf("home", "admin"), set.endpoints.map { it.id })
        // Replacing keeps the position; selecting an unknown id changes nothing.
        set = set.upsert(home.copy(baseUrl = "https://agents.example.com/retinue"))
        assertEquals("https://agents.example.com/retinue", set.endpoints[0].baseUrl)
        assertEquals(set, set.select("nobody"))
        set = set.select("admin")
        assertEquals("admin", set.current?.id)
        // Removing the current one hands over to the first that is left; a stale current id falls back to the first.
        set = set.remove("admin")
        assertEquals("home", set.current?.id)
        assertEquals("home", EndpointSet(listOf(home, admin), "gone").current?.id)
        set = set.update("home") { it.copy(name = "Home") }
        assertEquals("Home", set.endpoints[0].name)
        assertNull(set.remove("home").current)
    }

    @Test
    fun endpointProperties() {
        assertEquals("https://agents.example.com", home.base)
        assertEquals("agents.example.com", home.hostLabel)
        assertEquals("admin.example.com:8443", Endpoint("x", "admin.example.com:8443").hostLabel)
        assertEquals("not a url", Endpoint("x", "not a url").hostLabel)
        assertNull(Endpoint("x", "http://plain.example").base)
        assertTrue(admin.hasCredentials)
        assertFalse(home.hasCredentials)
        assertFalse(Endpoint("x", "https://a.example", username = "u").hasCredentials)
        assertNull(Endpoint("x", "https://a.example", name = "  ").knownName)
    }

    @Test
    fun labelsAreTheInstancesOwnNamesWithTheHostOnlyToTellThemApart() {
        // A name alone while it is unique.
        assertEquals(mapOf("home" to "Retinue", "admin" to "Admin"), EndpointLabels.labels(listOf(home, admin)))
        // Two unnamed instances both say "Retinue": the host is appended.
        val other = Endpoint("other", "https://other.example.com", name = "Retinue")
        assertEquals(
            mapOf("home" to "Retinue (agents.example.com)", "other" to "Retinue (other.example.com)", "admin" to "Admin"),
            EndpointLabels.labels(listOf(home, other, admin)),
        )
        // Until the name was fetched, the host stands in (and the raw URL when it does not parse).
        assertEquals("new.example.com", EndpointLabels.label(Endpoint("n", "new.example.com"), emptyList()))
        assertEquals("garbage", EndpointLabels.label(Endpoint("n", "garbage"), emptyList()))
    }

    @Test
    fun manifestNames() {
        assertEquals("Admin", EndpointNames.parse("""{"name":"Retinue (Admin)","short_name":"Admin"}"""))
        assertEquals("Retinue (Admin)", EndpointNames.parse("""{"name":"Retinue (Admin)"}"""))
        assertEquals("Retinue", EndpointNames.parse("""{"short_name":"  Retinue  ","name":""}"""))
        assertNull(EndpointNames.parse("""{"short_name":"","name":"   "}"""))
        assertNull(EndpointNames.parse("""{"short_name":12}"""))
        assertNull(EndpointNames.parse("<html>"))
        assertNull(EndpointNames.parse(null))
        assertEquals(60, EndpointNames.parse("""{"short_name":"${"x".repeat(100)}"}""")!!.length)
        assertEquals("https://agents.example.com/manifest.webmanifest", EndpointNames.manifestUrl("https://agents.example.com"))
        // Due: never fetched, an hour old, or forced; a clock set back counts as due too.
        val now = 10_000_000L
        assertTrue(EndpointNames.due(Endpoint("x", "https://a.example"), now))
        assertFalse(EndpointNames.due(Endpoint("x", "https://a.example", name = "A", nameFetchedAt = now - 1), now))
        assertTrue(EndpointNames.due(Endpoint("x", "https://a.example", name = "A", nameFetchedAt = now - EndpointNames.REFRESH_MS), now))
        assertTrue(EndpointNames.due(Endpoint("x", "https://a.example", name = "A", nameFetchedAt = now - 1), now, force = true))
        assertTrue(EndpointNames.due(Endpoint("x", "https://a.example", name = "A", nameFetchedAt = now + 1), now))
    }
}
