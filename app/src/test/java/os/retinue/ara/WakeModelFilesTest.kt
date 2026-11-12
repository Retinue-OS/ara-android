package os.retinue.ara

import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import os.retinue.ara.wake.WakeModelCatalog
import os.retinue.ara.wake.WakeModelFiles

/**
 * The phone's phrase model files where two windows meet: a removal and a
 * choice of the same model. On a folder of the test's own; the files
 * directory is all that WakeModelFiles asks of its Context here.
 */
class WakeModelFilesTest {

    private class Phone(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private val dir = Files.createTempDirectory("ara-wake").toFile()
    private val phone = Phone(dir)
    private val jarvis = WakeModelCatalog.builtins[0].id
    private val mycroft = WakeModelCatalog.builtins[1].id

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** The catalog model [id] as downloaded (what is in the file does not matter here). */
    private fun download(id: String) = WakeModelFiles.phraseFile(phone, id).writeText("a model")

    private fun onPhone() = WakeModelFiles.library(phone).filter { it.downloaded }.map { it.model.id }

    /** The downloaded models of the list every window shows. */
    private fun shown() = WakeModelFiles.state.library.value.filter { it.downloaded }.map { it.model.id }

    @Test
    fun aRemovalAsksWhoUsesTheModelWithTheLibraryHeldAsItsLastStepBeforeTheDelete() = runBlocking {
        download(jarvis)
        download(mycroft)
        // One removal holds the library while it asks who uses its model.
        val asking = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<List<String>>()
        val first = async {
            WakeModelFiles.remove(phone, jarvis) {
                asking.complete(Unit)
                answer.await()
            }
        }
        asking.await()
        // Another, begun meanwhile, waits for it: it has asked nothing yet, so no endpoint has let go of its model.
        val asked = CopyOnWriteArrayList<String>()
        val second = async {
            WakeModelFiles.remove(phone, mycroft) {
                asked += "asked with the first model on the phone: ${WakeModelFiles.phraseFileFor(phone, jarvis) != null}"
                listOf("Work")
            }
        }
        delay(100)
        assertEquals(emptyList<String>(), asked.toList())
        assertEquals(listOf(jarvis, mycroft), onPhone())
        answer.complete(emptyList())
        // Nobody uses the first: it is gone. The second asked only then, and is told of a user: it stays.
        assertEquals(emptyList(), first.await())
        assertEquals(listOf("Work"), second.await())
        assertEquals(listOf("asked with the first model on the phone: false"), asked.toList())
        assertEquals(listOf(mycroft), onPhone())
        // A model that is not on the list: nobody is asked to let go of it.
        var askedOfUnknown = false
        assertFailsWith<IOException> {
            WakeModelFiles.remove(phone, "user:unknown") {
                askedOfUnknown = true
                emptyList()
            }
        }
        assertFalse(askedOfUnknown)
    }

    @Test
    fun aModelIsChosenOnlyWhileItsFileIsOnThePhoneAndNoJobWorksOnIt() = runBlocking {
        download(jarvis)
        WakeModelFiles.refreshState(phone)
        assertEquals(listOf(jarvis), shown())
        assertTrue(WakeModelFiles.mayUse(phone, jarvis))
        // Not downloaded, or not on the list at all.
        assertFalse(WakeModelFiles.mayUse(phone, mycroft))
        assertFalse(WakeModelFiles.mayUse(phone, "user:unknown"))

        // A removal is the phone's one job from before it asks who uses the model until the file is gone.
        assertTrue(WakeModelFiles.beginJob(jarvis))
        try {
            assertFalse(WakeModelFiles.beginJob(null), "the other window's job waits its turn")
            assertFalse(WakeModelFiles.mayUse(phone, jarvis))
            val users = WakeModelFiles.remove(phone, jarvis) {
                assertFalse(WakeModelFiles.mayUse(phone, jarvis), "not chosen between the question and the delete")
                emptyList()
            }
            assertEquals(emptyList(), users)
            // Every window shows the list of before until the job is over, then the files as they are.
            assertEquals(listOf(jarvis), shown())
        } finally {
            WakeModelFiles.endJob(phone)
        }
        assertEquals(emptyList(), shown())
        assertFalse(WakeModelFiles.state.busy.value)
        // A list that still showed it would offer a model that is gone: the files decide.
        assertFalse(WakeModelFiles.mayUse(phone, jarvis))
    }
}
