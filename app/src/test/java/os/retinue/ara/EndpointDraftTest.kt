package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.ui.EndpointDraft
import os.retinue.ara.ui.EndpointDraft.Problem

/**
 * What the app saves of the settings page's editor (see [EndpointDraft.problem]).
 * What closing the editor does is the page's (assets/settings/logic.mjs, tested
 * in app/src/test/js/settings-page.test.mjs).
 */
class EndpointDraftTest {

    /** A stored endpoint, as the app checks it: user "reto" with a stored password. */
    private val home = EndpointDraft(
        id = "home", isNew = false, baseUrl = "https://agents.example.com", certificateAlias = "ara-phone",
        username = "reto", password = "", hasStoredPassword = true, forgetPassword = false, healthUpload = true, name = "Retinue",
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
}
