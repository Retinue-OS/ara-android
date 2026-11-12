package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointCodec
import os.retinue.ara.data.EndpointWake
import os.retinue.ara.ui.EndpointDraft
import os.retinue.ara.ui.EndpointDraft.Problem
import os.retinue.ara.wake.WakeMode

/**
 * What the app saves of the settings page's editor (see [EndpointDraft.problem]
 * and [EndpointDraft.toEndpoint]). What closing the editor does is the page's
 * (assets/settings/logic.mjs, tested in app/src/test/js/settings-page.test.mjs).
 */
class EndpointDraftTest {

    /** A stored endpoint, as the app checks it: user "reto" with a stored password. */
    private val home = EndpointDraft(
        id = "home", isNew = false, baseUrl = "https://agents.example.com", certificateAlias = "ara-phone",
        username = "reto", password = "", hasStoredPassword = true, forgetPassword = false, healthUpload = true, name = "Retinue",
    )

    /** The endpoint [home] is the draft of, as it is stored: its call switch on, its name fetched. */
    private val stored = Endpoint(
        "home", "https://agents.example.com", certificateAlias = "ara-phone", username = "reto", secret = "sealed",
        healthUpload = true, directCalls = true, name = "Retinue", nameFetchedAt = 1_000L,
    )

    private val fresh = EndpointDraft(
        id = "new", isNew = true, baseUrl = "", certificateAlias = null, username = "", password = "",
        hasStoredPassword = false, forgetPassword = false, healthUpload = false, name = null,
    )

    @Test
    fun anHttpsAddressIsNeeded() {
        assertEquals(Problem.Address, fresh.problem)
        assertEquals(Problem.Address, home.copy(baseUrl = "http://plain.example").problem)
        assertEquals(Problem.Address, home.copy(baseUrl = "  ").problem)
        // https:// is assumed when left out; the path is kept.
        assertNull(fresh.copy(baseUrl = "agents.example.com/retinue").problem)
        assertEquals("https://agents.example.com/retinue", fresh.copy(baseUrl = "agents.example.com/retinue/").base)
    }

    @Test
    fun aUserNameAndAPasswordGoTogether() {
        // The stored password counts until it is forgotten.
        assertNull(home.problem)
        assertTrue(home.hasPassword)
        assertEquals(Problem.Password, home.copy(forgetPassword = true).problem)
        // A new password for the stored user; or the sign-in dropped altogether.
        assertNull(home.copy(forgetPassword = true, password = "new secret").problem)
        assertNull(home.copy(username = "", forgetPassword = true).problem)
        // A user name with no password anywhere.
        assertEquals(Problem.Password, fresh.copy(baseUrl = "https://a.example", username = "reto").problem)
        // A password without a user name: saving would drop it, so it is refused.
        val noUser = fresh.copy(baseUrl = "https://a.example", password = "pw")
        assertEquals(Problem.UserName, noUser.problem)
        assertFalse(noUser.canSave)
    }

    @Test
    fun canSaveIsNoProblem() {
        assertTrue(home.canSave)
        assertFalse(fresh.canSave)
        assertTrue(fresh.copy(baseUrl = "https://admin.example.com").canSave)
    }

    @Test
    fun savingLeavesTheCallSwitchAsItIsStored() {
        // Untouched, the endpoint is stored again as it was: its call switch, its password, its name.
        assertEquals(stored, home.toEndpoint(stored, sealed = null))
        // Edited (no certificate, health data off, a new password): the switch is still as the user set it.
        val edited = home.copy(certificateAlias = null, healthUpload = false, password = "new").toEndpoint(stored, sealed = "sealed-2")
        assertEquals(stored.copy(certificateAlias = null, healthUpload = false, secret = "sealed-2"), edited)
        // Off stays off just the same: a save never switches it.
        assertFalse(home.toEndpoint(stored.copy(directCalls = false), sealed = null).directCalls)
        // Another address: the fetched name was the old server's; the switch is the endpoint's and stays.
        val moved = home.copy(baseUrl = "new.example.com").toEndpoint(stored, sealed = null)
        assertEquals(stored.copy(baseUrl = "new.example.com", name = null, nameFetchedAt = 0L), moved)
        // A new endpoint starts with it off, and with its wake phrase off.
        val added = fresh.copy(baseUrl = " https://admin.example.com ", healthUpload = true).toEndpoint(null, sealed = null)
        assertEquals(Endpoint("new", "https://admin.example.com", healthUpload = true, wake = EndpointWake.OFF), added)
    }

    @Test
    fun theBackgroundSwitchIsStoredAsTheEditorHasIt() {
        // On: the endpoint keeps working while another is shown. Nothing else of it changes.
        val working = home.copy(background = true).toEndpoint(stored, sealed = null)
        assertEquals(stored.copy(background = true), working)
        // Left on, it stays on; switched off over a stored on, the endpoint rests again.
        assertEquals(working, home.copy(background = true).toEndpoint(working, sealed = null))
        assertEquals(stored, home.toEndpoint(working, sealed = null))
        // A new endpoint is stored as its editor has the switch.
        val added = fresh.copy(baseUrl = "https://admin.example.com")
        assertFalse(added.toEndpoint(null, sealed = null).background)
        assertTrue(added.copy(background = true).toEndpoint(null, sealed = null).background)
    }

    @Test
    fun savingLeavesTheWakePhraseAsItIsStored() {
        // Mode, threshold and phrase model are set on the wake phrase's card, not in the editor.
        val listening = stored.copy(wake = EndpointWake.of(WakeMode.Always, 0.35f, "user:hey-retinue"))
        assertEquals(listening, home.toEndpoint(listening, sealed = null))
        // Edited, and at another address: like the call switch, the phrase is the endpoint's on this phone.
        val moved = home.copy(baseUrl = "new.example.com", healthUpload = false, background = true).toEndpoint(listening, sealed = null)
        assertEquals(
            listening.copy(baseUrl = "new.example.com", healthUpload = false, name = null, nameFetchedAt = 0L, background = true),
            moved,
        )
        // A new endpoint starts with the phrase off and without a model. Stored without a wake of its
        // own, it would read the one setting of earlier builds as its own on a phone that still holds it.
        val added = fresh.copy(baseUrl = "https://admin.example.com").toEndpoint(null, sealed = null)
        assertEquals(EndpointWake.OFF, added.wake)
        val earlier = EndpointCodec.legacyWake(WakeMode.Always.key, 0.7f, "oww:hey_jarvis_v0.1")
        val read = EndpointCodec.withLegacyWake(EndpointCodec.decode(EndpointCodec.encode(listOf(added)))!!, earlier)
        assertEquals(listOf(added), read)
    }

    @Test
    fun theStoredPasswordStaysUnlessTheDraftSaysOtherwise() {
        assertEquals("sealed", home.toEndpoint(stored, sealed = null).secret)
        // A typed password, sealed, replaces it; forgetting it or dropping the user name removes it.
        assertEquals("sealed-2", home.copy(password = "new").toEndpoint(stored, sealed = "sealed-2").secret)
        assertNull(home.copy(forgetPassword = true).toEndpoint(stored, sealed = null).secret)
        val noUser = home.copy(username = "  ").toEndpoint(stored, sealed = null)
        assertNull(noUser.username)
        assertNull(noUser.secret)
        assertEquals("reto", home.copy(username = " reto ").toEndpoint(stored, sealed = null).username)
    }
}
