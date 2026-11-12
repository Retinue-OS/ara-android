package os.retinue.ara

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import os.retinue.ara.wake.WakeModelCatalog
import os.retinue.ara.wake.WakeModelIndex
import os.retinue.ara.wake.WakeModelNames
import os.retinue.ara.wake.WakeModelSource
import os.retinue.ara.wake.WakeModelState
import os.retinue.ara.wake.WakeModelStatus
import os.retinue.ara.wake.WakePhraseModel
import os.retinue.ara.wake.wakeModelList

class WakeModelLibraryTest {

    @Test
    fun theCatalogStartsWithHeyJarvis() {
        val first = WakeModelCatalog.builtins.first()
        assertEquals(WakeModelCatalog.HEY_JARVIS, first.id)
        assertEquals("Hey Jarvis", first.name)
        assertEquals("${WakeModelCatalog.OWW_RELEASE}/hey_jarvis_v0.1.onnx", first.url)
        assertFalse(first.removable)
        assertEquals(first, WakeModelCatalog.bySha256(first.sha256!!.uppercase()))
        assertNull(WakeModelCatalog.bySha256("00"))
        assertEquals(WakeModelCatalog.builtins.size, WakeModelCatalog.builtins.map { it.id }.toSet().size)
    }

    @Test
    fun fileNamesAreSafeAndDistinct() {
        assertEquals("oww_hey_jarvis_v0.1.onnx", WakeModelNames.fileName(WakeModelCatalog.HEY_JARVIS))
        assertEquals("user_..__etc_passwd.onnx", WakeModelNames.fileName("user:../\u00e9etc/passwd"))
        val name = WakeModelNames.fileName("user:../../x")
        assertFalse(name.contains('/'))
        assertTrue(name.endsWith(".onnx"))
        val ids = WakeModelCatalog.builtins.map { it.id } + "user:0b6f3c1e-7c1d-4f7e-9c51-3d1f0a8c2b11"
        assertEquals(ids.size, ids.map(WakeModelNames::fileName).toSet().size)
    }

    @Test
    fun modelsAreNamedAfterTheirFileOrUrl() {
        assertEquals("hey_retinue.onnx", WakeModelNames.ofFile("hey_retinue.onnx"))
        assertEquals("Uploaded model", WakeModelNames.ofFile("  "))
        assertEquals("Uploaded model", WakeModelNames.ofFile(null))
        assertEquals("hey retinue.onnx", WakeModelNames.ofUrl("https://agents.example.com/models/hey%20retinue.onnx?v=2"))
        assertEquals("agents.example.com", WakeModelNames.ofUrl("https://agents.example.com/"))
        assertEquals(80, WakeModelNames.ofFile("x".repeat(200)).length)
    }

    @Test
    fun theUsersOwnModelsRoundTripAndBadEntriesAreDropped() {
        val mine = listOf(
            WakePhraseModel("user:1", "hey_retinue.onnx", WakeModelSource.File),
            WakePhraseModel("user:2", "ok_ara.onnx", WakeModelSource.Url, "https://x/ok_ara.onnx"),
        )
        assertEquals(mine, WakeModelIndex.decode(WakeModelIndex.encode(mine)))
        // The catalog is never stored with them.
        assertEquals(mine, WakeModelIndex.decode(WakeModelIndex.encode(WakeModelCatalog.builtins + mine)))
        val raw = """[{"id":"user:1","name":"a","source":"file"},{"id":"oww:hey_jarvis_v0.1","name":"b","source":"builtin"},
            {"id":"x","name":"c","source":"file"},{"id":"user:3","source":"nope"},7,{"id":"user:1","name":"dup","source":"file"}]"""
        assertEquals(listOf(WakePhraseModel("user:1", "a", WakeModelSource.File)), WakeModelIndex.decode(raw))
        assertEquals(emptyList(), WakeModelIndex.decode("not json"))
        assertEquals(emptyList(), WakeModelIndex.decode(null))
    }

    @Test
    fun aModelKnownByItsContentKeepsItsId() {
        val hash = "ab".repeat(32)
        val id = WakeModelIndex.idOfContent(hash)
        assertEquals(id, WakeModelIndex.idOfContent(hash.uppercase()), "a repeated migration finds the same entry")
        assertTrue(id != WakeModelIndex.idOfContent("cd".repeat(32)))
        // It is an id the index keeps.
        val model = WakePhraseModel(id, "Earlier model", WakeModelSource.File)
        assertEquals(listOf(model), WakeModelIndex.decode(WakeModelIndex.encode(listOf(model))))
    }

    @Test
    fun theListHasTheCatalogFirstThenTheUsersOwn() {
        val mine = WakePhraseModel("user:1", "hey_retinue.onnx", WakeModelSource.File)
        val list = wakeModelList(listOf(mine)) { it.id == mine.id }
        assertEquals(WakeModelCatalog.builtins.map { it.id } + mine.id, list.map { it.model.id })
        assertEquals(listOf(mine.id), list.filter { it.downloaded }.map { it.model.id })
    }

    private val features = WakeModelStatus(melspectrogram = true, embedding = true, phrase = false)

    @Test
    fun oneModelJobAtATimeForThePhoneAndItsEndShowsTheFilesAfresh() {
        // One state for both windows: what the one does, the other sees.
        val state = WakeModelState()
        assertFalse(state.features.value.featuresReady)
        assertEquals(emptyList(), state.library.value)
        val mine = WakePhraseModel("user:1", "hey_retinue.onnx", WakeModelSource.File)
        val before = wakeModelList(listOf(mine)) { it.id == mine.id }
        state.show(features, before)
        assertTrue(state.features.value.featuresReady)
        assertEquals(before, state.library.value)

        // A removal begins in one window: no job of the other begins meanwhile, whatever it would work on.
        assertTrue(state.begin(mine.id))
        assertFalse(state.begin(null))
        assertFalse(state.begin(mine.id))
        assertFalse(state.begin("oww:alexa_v0.1"))
        assertTrue(state.busy.value)
        assertEquals(mine.id, state.busyId.value, "a job that has not begun changes nothing")
        assertEquals(before, state.library.value)

        // Over: the list is the files as the job left them, and the next job may begin.
        val after = wakeModelList(emptyList()) { false }
        state.end(features, after)
        assertFalse(state.busy.value)
        assertNull(state.busyId.value)
        assertEquals(after, state.library.value)
        assertTrue(state.begin(null))
        assertTrue(state.busy.value)
        assertNull(state.busyId.value)
    }

    @Test
    fun aJobAskedForWhileAnotherRunsWaitsItsTurn() = runBlocking<Unit> {
        val after = wakeModelList(emptyList()) { false }
        // Unconfined: a waiting job goes on inside the end of the one before it, as in the windows' own
        // scopes (Dispatchers.Main.immediate). Else it goes on later, when this thread is free.
        for (resumed in listOf(Dispatchers.Unconfined, EmptyCoroutineContext)) {
            val state = WakeModelState()
            assertTrue(state.begin("user:1"))
            // Two more are asked for meanwhile: a removal confirmed in a dialog that was open, say, and another.
            val waiting = listOf("user:2", "user:3").associateWith { id -> launch(resumed) { state.beginInTurn(id) } }
            yield()
            assertTrue(waiting.values.none { it.isCompleted }, "$resumed")
            assertEquals("user:1", state.busyId.value, "a job that waits has begun nothing")
            // Nor is its model held back yet: a removal asks who uses it once it has begun.
            assertTrue(state.mayUse("user:2", onPhone = true))

            // The first is over: one of them begins, as the job on its own model, and the other goes on waiting.
            state.end(features, after)
            yield()
            val second = waiting.entries.single { it.value.isCompleted }.key
            assertTrue(state.busy.value, "$resumed")
            assertEquals(second, state.busyId.value, "$resumed")
            assertFalse(state.mayUse(second, onPhone = true))
            assertEquals(after, state.library.value)
            // Then the other, and after it nothing runs.
            state.end(features, after)
            yield()
            assertTrue(waiting.values.all { it.isCompleted }, "$resumed")
            assertTrue(state.busy.value, "$resumed")
            assertEquals(waiting.keys.single { it != second }, state.busyId.value, "$resumed")
            state.end(features, after)
            yield()
            assertFalse(state.busy.value)
            assertNull(state.busyId.value)

            // With none running a job begins without waiting.
            state.beginInTurn(null)
            assertTrue(state.busy.value)
            // A window that closes while its job waits has begun none.
            val closed = launch(resumed) { state.beginInTurn("user:4") }
            yield()
            closed.cancelAndJoin()
            state.end(features, after)
            yield()
            assertFalse(state.busy.value, "$resumed")
            assertNull(state.busyId.value)
        }
    }

    @Test
    fun aModelIsChosenOnlyWhileItsFileIsThereAndNoJobWorksOnIt() {
        val state = WakeModelState()
        assertTrue(state.mayUse("user:1", onPhone = true))
        // Its file is gone (removed in the other window), whatever list a window still shows.
        assertFalse(state.mayUse("user:1", onPhone = false))
        // A job works on it, a removal for one: not chosen until that is over. Any other model is.
        state.begin("user:1")
        assertFalse(state.mayUse("user:1", onPhone = true))
        assertTrue(state.mayUse("user:2", onPhone = true))
        assertFalse(state.mayUse("user:2", onPhone = false))
        state.end(features, emptyList())
        assertTrue(state.mayUse("user:1", onPhone = true))
        // A job that names no model (an upload, the feature models) holds none back.
        state.begin(null)
        assertTrue(state.mayUse("user:1", onPhone = true))
    }
}
