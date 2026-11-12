package os.retinue.ara

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import os.retinue.ara.data.Endpoint
import os.retinue.ara.data.EndpointCodec
import os.retinue.ara.data.EndpointSet
import os.retinue.ara.data.EndpointWake
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
    fun aModelThatLeavesTheLibraryIsLetGoByItsEndpoints() {
        val set = EndpointSet(
            listOf(own("a", WakeMode.Always, 0.5f, mine), own("b", WakeMode.Off, 0.6f, mine), own("c", WakeMode.Always, 0.5f, jarvis), bare("d")),
            "a",
        )
        val after = set.withoutWakeModel(mine)
        assertEquals(listOf(null, null, jarvis, null), after.endpoints.map { it.wake?.modelId })
        // Nothing else of them changes.
        assertEquals(EndpointWake.of(WakeMode.Off, 0.6f), after.endpoints[1].wake)
        assertEquals("a", after.currentId)
        assertEquals(set, set.withoutWakeModel("user:unknown"))
    }
}
