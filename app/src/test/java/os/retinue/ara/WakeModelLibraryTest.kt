package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.wake.WakeModelCatalog
import os.retinue.ara.wake.WakeModelIndex
import os.retinue.ara.wake.WakeModelNames
import os.retinue.ara.wake.WakeModelSource
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
    fun theListHasTheCatalogFirstThenTheUsersOwn() {
        val mine = WakePhraseModel("user:1", "hey_retinue.onnx", WakeModelSource.File)
        val list = wakeModelList(listOf(mine)) { it.id == mine.id }
        assertEquals(WakeModelCatalog.builtins.map { it.id } + mine.id, list.map { it.model.id })
        assertEquals(listOf(mine.id), list.filter { it.downloaded }.map { it.model.id })
    }
}
