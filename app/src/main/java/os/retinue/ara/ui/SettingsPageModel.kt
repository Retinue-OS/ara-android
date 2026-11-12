package os.retinue.ara.ui

import android.content.Context
import android.text.format.DateUtils
import os.retinue.ara.BuildConfig
import os.retinue.ara.R
import os.retinue.ara.data.EndpointLabels
import os.retinue.ara.data.EndpointWake
import os.retinue.ara.data.ShellSettings
import os.retinue.ara.health.HealthSyncStatus
import os.retinue.ara.settings.FailureInfo
import os.retinue.ara.settings.SettingsPageState
import os.retinue.ara.settings.UpdatesInfo
import os.retinue.ara.settings.WakeInfo
import os.retinue.ara.settings.wakeModelRows
import os.retinue.ara.shell.PageState
import os.retinue.ara.shell.PageStatus
import os.retinue.ara.update.InstalledBuild
import os.retinue.ara.update.UpdateRuntime
import os.retinue.ara.update.UpdateState
import os.retinue.ara.wake.WakeMode
import os.retinue.ara.wake.WakeModelEntry
import os.retinue.ara.wake.WakeModelStatus
import os.retinue.ara.wake.WakeStatus

/** What the wake phrase's card shows, besides the stored settings. */
internal data class WakeConditions(
    val status: WakeStatus,
    val micGranted: Boolean,
    val notificationsAllowed: Boolean,
    val fullScreenAllowed: Boolean,
    val overlayAllowed: Boolean,
    /** The feature models' files. */
    val models: WakeModelStatus,
    /** The phone's phrase models, the same for every instance. */
    val library: List<WakeModelEntry>,
    val modelBusy: Boolean,
    val modelBusyId: String?,
    val modelMessage: String?,
    /** The instances listened for now (WakeRuntime.listening). */
    val listening: Set<String> = emptySet(),
)

/**
 * The settings page's state ([SettingsPageState]) from what the app knows. The
 * texts that depend on the phone's language or on the moment (statuses, "3
 * hours ago", why the dashboard did not load) are built here; the page's own
 * labels are its own.
 */
internal fun settingsPageState(
    context: Context,
    settings: ShellSettings,
    page: PageState,
    health: HealthSyncStatus,
    isDefaultHome: Boolean,
    wake: WakeConditions,
    update: UpdateState,
    installAllowed: Boolean,
    usageAccess: Boolean,
): SettingsPageState {
    val shown = settings.current
    val shownWake = shown?.wake ?: EndpointWake.OFF
    val labels = EndpointLabels.labels(settings.endpoints)
    return SettingsPageState(
        version = BuildConfig.VERSION_NAME,
        endpoints = SettingsPageState.endpointRows(settings),
        current = SettingsPageState.currentEndpoint(settings),
        failure = (page.status as? PageStatus.Failed)?.let { failed ->
            FailureInfo(
                reason = failureText(context, failed.failure),
                url = failed.failure.url ?: page.url,
                consoleErrors = page.consoleErrors.map { consoleErrorText(context, it) },
            )
        },
        health = health.toJson(),
        isDefaultHome = isDefaultHome,
        splitLaunch = settings.splitLaunch,
        keepSplitInRecents = settings.keepSplitInRecents,
        wake = WakeInfo(
            mode = shownWake.mode,
            threshold = shownWake.threshold,
            status = wakeStatusText(context, shownWakeStatus(wake, shown?.id, shownWake.wakeMode)),
            micGranted = wake.micGranted,
            // The shown instance's own model: one of the list, and on the phone.
            modelsReady = wake.models.featuresReady &&
                wake.library.any { it.downloaded && it.model.id == shownWake.modelId },
            notificationsAllowed = wake.notificationsAllowed,
            fullScreenAllowed = wake.fullScreenAllowed,
            overlayAllowed = wake.overlayAllowed,
            melspectrogram = wake.models.melspectrogram,
            embedding = wake.models.embedding,
            models = wakeModelRows(wake.library, settings),
            busy = wake.modelBusy,
            busyId = wake.modelBusyId,
            message = wake.modelMessage,
            alsoListening = settings.endpoints.filter { it.id != shown?.id && it.id in wake.listening }.map { labels[it.id] ?: it.hostLabel },
        ),
        updates = UpdatesInfo(
            installed = installedText(context, UpdateRuntime.installed),
            auto = settings.autoUpdate,
            channel = settings.updateChannel.key,
            status = updateStatusText(context, update),
            busy = update.busy,
            progress = (update as? UpdateState.Downloading)?.progress,
            installTag = (update as? UpdateState.Available)?.release?.tag,
            awaitingConfirmation = update is UpdateState.AwaitingConfirmation,
            canCheck = !update.busy && update !is UpdateState.AwaitingConfirmation,
            installAllowed = installAllowed,
        ),
        device = settings.deviceWebSettings,
        usageAccess = usageAccess,
    )
}

/**
 * The detector's status as the shown instance sees it: listening when its
 * own phrase is listened for, idle when its mode is off (the detector may
 * still listen for an instance in the background).
 */
internal fun shownWakeStatus(wake: WakeConditions, shownId: String?, mode: WakeMode): WakeStatus = when {
    mode == WakeMode.Off -> WakeStatus.Idle
    shownId != null && shownId in wake.listening -> WakeStatus.Listening
    wake.status == WakeStatus.Listening -> WakeStatus.Idle
    else -> wake.status
}

private fun wakeStatusText(context: Context, status: WakeStatus): String = when (status) {
    WakeStatus.Idle -> context.getString(R.string.wake_status_idle)
    WakeStatus.Listening -> context.getString(R.string.wake_status_listening)
    WakeStatus.PausedForVoice -> context.getString(R.string.wake_status_paused)
    WakeStatus.PausedForOtherApp -> context.getString(R.string.wake_status_paused_other_app)
    is WakeStatus.Error -> context.getString(R.string.wake_status_error, status.message)
}

private fun installedText(context: Context, installed: InstalledBuild): String = when {
    installed.fromChannel -> context.getString(R.string.update_installed_build, installed.tag, installed.shortSha)
    installed.sha.isNotEmpty() -> context.getString(R.string.update_installed_branch_build, installed.shortSha)
    else -> context.getString(R.string.update_installed_local_build)
}

private fun updateStatusText(context: Context, update: UpdateState): String = when (update) {
    UpdateState.Idle -> context.getString(R.string.update_status_idle)
    UpdateState.Checking -> context.getString(R.string.update_status_checking)
    is UpdateState.UpToDate -> context.getString(R.string.update_status_up_to_date, update.latest.tag, ago(update.checkedAt))
    is UpdateState.NoBuilds -> context.getString(R.string.update_status_no_builds, ago(update.checkedAt))
    is UpdateState.Available -> context.getString(
        if (update.waitingForUnmetered) R.string.update_status_available_unmetered else R.string.update_status_available,
        update.release.tag,
    )
    is UpdateState.Downloading -> context.getString(R.string.update_status_downloading, update.release.tag, (update.progress * 100).toInt())
    is UpdateState.Waiting -> context.getString(R.string.update_status_waiting_voice, update.release.tag)
    is UpdateState.Installing -> context.getString(R.string.update_status_installing, update.release.tag)
    is UpdateState.AwaitingConfirmation -> context.getString(
        R.string.update_status_confirm,
        update.release?.tag ?: context.getString(R.string.update_the_update),
    )
    UpdateState.Installed -> context.getString(R.string.update_status_installed)
    is UpdateState.Failed -> context.getString(R.string.update_status_failed, update.message)
}

/** "3 hours ago", in the phone's language. */
private fun ago(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
