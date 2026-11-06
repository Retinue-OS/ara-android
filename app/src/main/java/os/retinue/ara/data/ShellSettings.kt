package os.retinue.ara.data

import os.retinue.ara.update.UpdateChannel
import os.retinue.ara.wake.WakeMode

/**
 * The shell's device-local configuration. The one secret in it, an endpoint's
 * password, is stored sealed (see [Endpoint.secret] and [CredentialVault]);
 * the client certificate is referenced by its KeyChain alias only.
 */
data class ShellSettings(
    /** The Retinue servers this phone can show (see [Endpoint]). */
    val endpoints: List<Endpoint> = emptyList(),
    /** The endpoint the shell shows (see [EndpointSet.current]). */
    val currentEndpointId: String? = null,
    val wakeMode: WakeMode = WakeMode.Off,
    /** Score at or above which the phrase counts as heard (openWakeWord's default 0.5). */
    val wakeThreshold: Float = DEFAULT_WAKE_THRESHOLD,
    /** Where "Download phrase model" fetches the keyword model from. */
    val wakeModelUrl: String = DEFAULT_WAKE_MODEL_URL,
    /** Check the update channel on its own and install what it offers (see UpdateRuntime). Off until the user turns it on. */
    val autoUpdate: Boolean = false,
    /** Which builds count as updates. */
    val updateChannel: UpdateChannel = UpdateChannel.Nightly,
    /**
     * A tap on the home screen opens the app in split screen, beside the
     * split-screen window (see SplitShellActivity), rather than full screen.
     */
    val splitLaunch: Boolean = false,
    /** The home screen keeps the split-screen window among the recent apps, ready to pair. */
    val keepSplitInRecents: Boolean = false,
) {
    val endpointSet: EndpointSet get() = EndpointSet(endpoints, currentEndpointId)

    /** The endpoint the shell shows, or null when none is set up. */
    val current: Endpoint? get() = endpointSet.current

    /** The current endpoint's base URL as the user typed it ("" for none). */
    val baseUrl: String get() = current?.baseUrl ?: ""

    /** The current endpoint's client certificate alias. */
    val certificateAlias: String? get() = current?.certificateAlias

    /** The normalized base URL of the current endpoint, or null when none is configured (or it is invalid). */
    val base: String? get() = current?.base

    companion object {
        const val DEFAULT_WAKE_THRESHOLD = 0.5f
        const val OWW_RELEASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"

        /** A pre-trained phrase for testing until "Hey Retinue" is trained (plan §5). */
        const val DEFAULT_WAKE_MODEL_URL = "$OWW_RELEASE/hey_jarvis_v0.1.onnx"
        const val MELSPECTROGRAM_URL = "$OWW_RELEASE/melspectrogram.onnx"
        const val EMBEDDING_URL = "$OWW_RELEASE/embedding_model.onnx"
    }
}
