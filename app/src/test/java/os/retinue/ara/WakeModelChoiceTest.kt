package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointCodec
import os.retinue.ara.data.EndpointSet
import os.retinue.ara.data.EndpointWake
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.settings.wakeModelRows
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeModelCatalog
import os.retinue.ara.wake.WakeModelEntry
import os.retinue.ara.wake.WakeModelSource
import os.retinue.ara.wake.WakePhraseModel

/**
 * The phrase model each endpoint listens for, from whatever an earlier build
 * left on the phone: one phrase file for the phone, a library with one choice
 * for the phone, or a wake phrase per endpoint that still had the one file.
 * And what follows from several endpoints choosing from one library: a model
 * says who else uses it, and stays while they do.
 */
class WakeModelChoiceTest {

    private val jarvis = WakeModelCatalog.HEY_JARVIS
    private val mine = "user:0b6f3c1e-7c1d-4f7e-9c51-3d1f0a8c2b11"

    /**
     * What a phone has stored of the wake phrase: the endpoint list as it was
     * written, the one setting for the phone of earlier builds ([mode],
     * [threshold], and [wakeModel], the key `wake_model` of the builds with a
     * library and one choice), and the one phrase file of the builds before
     * the library ([earlierPhrase]: the id it gets in the library, null when
     * there is no such file).
     */
    private data class Phone(
        val endpoints: List<Endpoint>,
        val mode: WakeMode = WakeMode.Off,
        val threshold: Float = EndpointWake.DEFAULT_THRESHOLD,
        val wakeModel: String? = null,
        val earlierPhrase: String? = null,
    ) {
        private val legacy get() = EndpointWake.of(mode, threshold, wakeModel)

        /**
         * A start of the app (AraApp): the earlier file joins the library, its
         * id is stored in one edit (AraStore.adoptLegacyWakeModel), then the
         * file goes. [cutShort]: the process ends before the file is deleted.
         */
        fun started(cutShort: Boolean = false): Phone {
            val id = earlierPhrase ?: return this
            return copy(
                wakeModel = legacy.adopting(id).modelId,
                endpoints = EndpointCodec.adoptingWakeModel(endpoints, id),
                earlierPhrase = if (cutShort) id else null,
            )
        }

        /** The endpoints as the app reads them (AraStore.endpointsOf). */
        fun read(): List<Endpoint> = EndpointCodec.withLegacyWake(endpoints, legacy)

        /** Any write of the list stores what was read. */
        fun written(change: (List<Endpoint>) -> List<Endpoint> = { it }): Phone = copy(endpoints = change(read()))

        fun models(): List<String?> = read().map { it.wake?.modelId }
    }

    private fun bare(id: String) = Endpoint(id, "https://$id.example")
    private fun own(id: String, mode: WakeMode, threshold: Float, modelId: String? = null) =
        Endpoint(id, "https://$id.example", wake = EndpointWake.of(mode, threshold, modelId))

    @Test
    fun onePhraseFileForThePhoneBecomesEveryEndpointsModel() {
        // Main today: one mode and threshold, endpoints without a wake of their own, and wake/phrase.onnx.
        val phone = Phone(listOf(bare("a"), bare("b")), WakeMode.Always, 0.7f, earlierPhrase = jarvis).started()
        val wake = EndpointWake.of(WakeMode.Always, 0.7f, jarvis)
        assertEquals(listOf<EndpointWake?>(wake, wake), phone.read().map { it.wake })
        assertNull(phone.earlierPhrase)
        // A file that is no catalog model comes under an id of its own, all the same.
        assertEquals(listOf<String?>(mine), Phone(listOf(bare("a")), WakeMode.WhileVisible, earlierPhrase = mine).started().models())
    }

    @Test
    fun withoutAPhraseFileNoEndpointHasAModel() {
        val phone = Phone(listOf(bare("a"), bare("b")), WakeMode.WhileVisible, 0.4f).started()
        val wake = EndpointWake.of(WakeMode.WhileVisible, 0.4f)
        assertEquals(listOf<EndpointWake?>(wake, wake), phone.read().map { it.wake })
        assertNull(phone.wakeModel)
    }

    @Test
    fun theOneChoiceOfABuildWithALibraryBecomesEveryEndpointsModel() {
        // The phrase is in the library already, and wake_model holds the choice.
        val chosen = Phone(listOf(bare("a"), bare("b")), WakeMode.Always, 0.6f, wakeModel = mine).started()
        val wake = EndpointWake.of(WakeMode.Always, 0.6f, mine)
        assertEquals(listOf<EndpointWake?>(wake, wake), chosen.read().map { it.wake })
        // No choice stored: none was in use.
        assertEquals(listOf<String?>(null, null), Phone(listOf(bare("a"), bare("b")), WakeMode.Always).started().models())
        // Its own migration cut short before the choice was stored: the earlier file is still there.
        assertEquals(listOf<String?>(jarvis), Phone(listOf(bare("a")), WakeMode.Always, earlierPhrase = jarvis).started().models())
        // Cut short after it: the choice made since stands.
        assertEquals(listOf<String?>(mine), Phone(listOf(bare("a")), WakeMode.Always, wakeModel = mine, earlierPhrase = jarvis).started().models())
    }

    @Test
    fun endpointsWithAWakeOfTheirOwnGetTheModelOfThePhraseFileTheyListenedWith() {
        // A build with a wake phrase per endpoint and still the one file: every endpoint listened with it.
        val phone = Phone(
            listOf(own("a", WakeMode.Always, 0.5f), own("b", WakeMode.WhileVisible, 0.8f), own("c", WakeMode.Off, 0.5f)),
            WakeMode.Always, 0.7f, earlierPhrase = jarvis,
        ).started()
        assertEquals(
            listOf<EndpointWake?>(
                EndpointWake.of(WakeMode.Always, 0.5f, jarvis),
                EndpointWake.of(WakeMode.WhileVisible, 0.8f, jarvis),
                // Off for now: it listens for the same phrase once it is turned on, as before.
                EndpointWake.of(WakeMode.Off, 0.5f, jarvis),
            ),
            phone.read().map { it.wake },
        )
        // An endpoint that has a model keeps it.
        assertEquals(
            listOf<String?>(mine, jarvis),
            EndpointCodec.adoptingWakeModel(listOf(own("a", WakeMode.Always, 0.5f, mine), own("b", WakeMode.Always, 0.5f)), jarvis)
                .map { it.wake?.modelId },
        )
        // Without the file there is nothing to tell them from endpoints set to no model: they stay as they are.
        assertEquals(listOf<String?>(null), Phone(listOf(own("a", WakeMode.Always, 0.5f))).started().models())
    }

    @Test
    fun theListsEarlierBuildsStoredComeThroughTheAdoption() {
        // As main today, or a build with the library and one choice, wrote it: no wake with the endpoint.
        val plain = EndpointCodec.decode(
            """{"version":1,"endpoints":[{"id":"a","baseUrl":"https://a.example","certificateAlias":null,"username":null,""" +
                """"secret":null,"healthUpload":true,"name":"Home","nameFetchedAt":7}]}""",
        )!!
        assertNull(plain.single().wake)
        // Nothing to store with the endpoint: it reads the choice for the phone.
        assertEquals(plain, EndpointCodec.adoptingWakeModel(plain, jarvis))
        assertEquals(listOf<String?>(jarvis), Phone(plain, WakeMode.Always, earlierPhrase = jarvis).started().models())

        // As a build with a wake phrase per endpoint wrote it: a wake of its own, and no model.
        val ofItsOwn = EndpointCodec.decode(
            """{"version":1,"endpoints":[{"id":"a","baseUrl":"https://a.example","healthUpload":true,"name":"Home",""" +
                """"background":true,"wake":{"mode":"always","threshold":0.6,"modelId":null}}]}""",
        )!!
        assertEquals(EndpointWake.of(WakeMode.Always, 0.6f), ofItsOwn.single().wake)
        val adopted = EndpointCodec.adoptingWakeModel(ofItsOwn, jarvis)
        // Only the model changes, and what is written back reads the same.
        assertEquals(ofItsOwn.single().copy(wake = EndpointWake.of(WakeMode.Always, 0.6f, jarvis)), adopted.single())
        val raw = EndpointCodec.encode(adopted)
        assertTrue(raw.contains("\"modelId\":\"$jarvis\""), raw)
        assertEquals(adopted, EndpointCodec.decode(raw))
        assertEquals(adopted, EndpointCodec.adoptingWakeModel(adopted, mine))
    }

    @Test
    fun aStartCutShortRepeatsToTheSameEnd() {
        val phones = listOf(
            Phone(listOf(bare("a"), bare("b")), WakeMode.Always, 0.7f, earlierPhrase = jarvis),
            Phone(listOf(bare("a")), WakeMode.Always, wakeModel = mine, earlierPhrase = jarvis),
            Phone(listOf(own("a", WakeMode.Always, 0.5f), own("b", WakeMode.Off, 0.5f)), earlierPhrase = mine),
            Phone(emptyList(), WakeMode.Always, earlierPhrase = jarvis),
        )
        for (phone in phones) {
            val done = phone.started()
            // Cut short before anything was stored: the phone is as it was. After the edit, before the file went:
            assertEquals(done, phone.started(cutShort = true).started(), phone.toString())
            assertEquals(done, phone.started(cutShort = true).started(cutShort = true).started(), phone.toString())
            // And a start after that changes nothing.
            assertEquals(done, done.started(), phone.toString())
        }
    }

    @Test
    fun laterStartsLeaveAnEndpointSetToNoModelAlone() {
        // The first write of the list stores every endpoint's wake, model included.
        val migrated = Phone(listOf(bare("a"), bare("b")), WakeMode.Always, 0.7f, earlierPhrase = jarvis).started().written()
        assertEquals(listOf<String?>(jarvis, jarvis), migrated.endpoints.map { it.wake?.modelId })
        // "Stop using" for one of them, and a new endpoint: off, without a model.
        val changed = migrated.written { list ->
            list.map { e -> if (e.id == "a") e.copy(wake = e.wake!!.copy(modelId = null)) else e } +
                Endpoint("new", "https://new.example", wake = EndpointWake.OFF)
        }
        assertEquals(listOf(null, jarvis, null), changed.models())
        assertEquals(changed, changed.started())
        assertEquals(listOf(null, jarvis, null), changed.started().models())
        assertEquals(EndpointWake.OFF, changed.started().read().last().wake)
    }

    @Test
    fun aChoiceThatIsNotOnTheListOrNotDownloadedCountsAsNone() {
        val library = listOf(
            WakeModelEntry(WakeModelCatalog.builtins.first(), downloaded = false),
            WakeModelEntry(WakePhraseModel(mine, "hey_retinue.onnx", WakeModelSource.File), downloaded = true),
        )
        assertEquals(listOf(false, true), wakeModelRows(library, mine).map { it.active })
        // Chosen, but its download was deleted; chosen, but removed from the list; none chosen.
        for (none in listOf(jarvis, "user:gone", null)) {
            assertEquals(listOf(false, false), wakeModelRows(library, none).map { it.active }, none.toString())
        }
    }

    @Test
    fun aModelAnotherEndpointUsesIsNotRemoved() {
        val set = EndpointSet(
            listOf(own("a", WakeMode.Always, 0.5f, mine), own("b", WakeMode.Off, 0.6f, mine), own("c", WakeMode.Always, 0.5f, jarvis), bare("d")),
            "a",
        )
        // b has it too, though its mode is off and it is disabled: the model stays, and nothing changes.
        assertEquals(listOf("b"), set.otherUsersOfWakeModel(mine).map { it.id })
        assertNull(set.releasingWakeModel(mine))
        // So does c's model, seen from a's card.
        assertEquals(listOf("c"), set.otherUsersOfWakeModel(jarvis).map { it.id })
        assertNull(set.releasingWakeModel(jarvis))
        // With c shown it is the model of the endpoint shown alone: that one lets go, and nothing else changes.
        val shown = set.select("c")
        assertEquals(emptyList(), shown.otherUsersOfWakeModel(jarvis))
        val after = shown.releasingWakeModel(jarvis)!!
        assertEquals(listOf(mine, mine, null, null), after.endpoints.map { it.wake?.modelId })
        assertEquals(EndpointWake.of(WakeMode.Always, 0.5f), after.endpoints[2].wake)
        assertEquals("c", after.currentId)
        // A model no endpoint has: nothing to let go of, nothing in the way.
        assertEquals(set, set.releasingWakeModel("user:unused"))
        assertEquals(EndpointSet.EMPTY, EndpointSet.EMPTY.releasingWakeModel(mine))
    }

    @Test
    fun aRowNamesTheOtherEndpointsThatUseItsModel() {
        val home = Endpoint("home", "https://home.example", name = "Home", wake = EndpointWake.of(WakeMode.Always, 0.5f, jarvis))
        val work = Endpoint("work", "https://work.example", name = "Work", background = true, wake = EndpointWake.of(WakeMode.Always, 0.6f, jarvis))
        // Disabled, its mode off, and no name fetched yet: it counts, under its host.
        val club = Endpoint("club", "https://club.example", wake = EndpointWake.of(WakeMode.Off, 0.5f, mine))
        val settings = ShellSettings(endpoints = listOf(home, work, club), currentEndpointId = "home")
        val library = listOf(
            WakeModelEntry(WakeModelCatalog.builtins[0], downloaded = true),
            WakeModelEntry(WakeModelCatalog.builtins[1], downloaded = true),
            WakeModelEntry(WakePhraseModel(mine, "hey_retinue.onnx", WakeModelSource.File), downloaded = false),
        )
        val rows = wakeModelRows(library, settings)
        assertEquals(listOf(true, false, false), rows.map { it.active })
        assertEquals(listOf(listOf("Work"), emptyList(), listOf("club.example")), rows.map { it.usedBy })
        // The same list on Work's card: its model, and Home named.
        val onWork = wakeModelRows(library, settings.copy(currentEndpointId = "work"))
        assertEquals(listOf(true, false, false), onWork.map { it.active })
        assertEquals(listOf(listOf("Home"), emptyList(), listOf("club.example")), onWork.map { it.usedBy })
        // On Club's card its own model is not downloaded, so not active, and nobody else's.
        val onClub = wakeModelRows(library, settings.copy(currentEndpointId = "club"))
        assertEquals(listOf(false, false, false), onClub.map { it.active })
        assertEquals(listOf(listOf("Home", "Work"), emptyList(), emptyList()), onClub.map { it.usedBy })
        // The rows name exactly the endpoints that keep a model from being removed.
        for (row in rows) {
            assertEquals(row.usedBy.isEmpty(), settings.endpointSet.releasingWakeModel(row.id) != null, row.id)
        }
        // No endpoint at all: a list, and nothing in use.
        assertEquals(listOf(false, false, false), wakeModelRows(library, ShellSettings()).map { it.active })
    }
}
