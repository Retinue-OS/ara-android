package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.data.Endpoint
import os.retinue.ara.ui.EndpointDraft
import os.retinue.ara.ui.EndpointDraft.Close
import os.retinue.ara.ui.EndpointDraft.Problem

/** What closing the endpoint editor does with what was typed (see [EndpointDraft.onClose]). */
class EndpointDraftTest {

    private val home = Endpoint(
        "home", "https://agents.example.com", certificateAlias = "ara-phone",
        username = "reto", secret = "c2VhbGVk", healthUpload = true, name = "Retinue",
    )

    @Test
    fun anUntouchedDraftJustCloses() {
        assertEquals(Close.Unchanged, EndpointDraft.of(home).onClose())
        assertEquals(Close.Unchanged, EndpointDraft.forNew("new").onClose())
        // Changed and changed back is no change; spaces around the address and the user name do not count.
        val back = EndpointDraft.of(home).copy(baseUrl = "https://else.example").copy(baseUrl = " https://agents.example.com ", username = "reto ")
        assertFalse(back.changed)
        assertEquals(Close.Unchanged, back.onClose())
    }

    @Test
    fun changesThatCanBeSavedAreSavedOnTheWayOut() {
        val d = EndpointDraft.of(home)
        assertEquals(Close.Save, d.copy(healthUpload = false).onClose())
        assertEquals(Close.Save, d.copy(certificateAlias = null).onClose())
        assertEquals(Close.Save, d.copy(baseUrl = "agents.example.com/retinue").onClose())
        // A new password for the stored user; or the sign-in dropped altogether.
        assertEquals(Close.Save, d.copy(password = "new secret").onClose())
        assertEquals(Close.Save, d.copy(username = "", forgetPassword = true).onClose())
        // A new endpoint with an address is created on the way out.
        assertEquals(Close.Save, EndpointDraft.forNew("new").copy(baseUrl = "https://admin.example.com").onClose())
    }

    @Test
    fun changesThatCannotBeSavedAreAskedAboutNeverDropped() {
        // No address yet, or not an https one.
        assertEquals(Close.Ask(Problem.Address), EndpointDraft.forNew("new").copy(healthUpload = true).onClose())
        assertEquals(Close.Ask(Problem.Address), EndpointDraft.of(home).copy(baseUrl = "http://plain.example").onClose())
        assertEquals(Close.Ask(Problem.Address), EndpointDraft.of(home).copy(baseUrl = "").onClose())
        // The stored password forgotten while the user name stays.
        assertEquals(Close.Ask(Problem.Password), EndpointDraft.of(home).copy(forgetPassword = true).onClose())
        assertEquals(Close.Ask(Problem.Password), EndpointDraft.forNew("new").copy(baseUrl = "https://a.example", username = "reto").onClose())
        // A password typed without a user name: Save would drop it, so it is not saved silently.
        val noUser = EndpointDraft.forNew("new").copy(baseUrl = "https://a.example", password = "pw")
        assertEquals(Close.Ask(Problem.UserName), noUser.onClose())
        assertFalse(noUser.canSave)
    }

    @Test
    fun theSaveButtonFollowsTheSameRule() {
        assertTrue(EndpointDraft.of(home).canSave)
        assertFalse(EndpointDraft.forNew("new").canSave)
        assertTrue(EndpointDraft.forNew("new").copy(baseUrl = "agents.example.com").canSave)
        // A stored password counts until it is forgotten.
        assertTrue(EndpointDraft.of(home).copy(password = "").canSave)
        assertFalse(EndpointDraft.of(home).copy(forgetPassword = true).canSave)
    }
}
