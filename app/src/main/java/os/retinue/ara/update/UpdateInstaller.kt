package os.retinue.ara.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import java.io.File

/**
 * Hands a downloaded APK to Android's package installer as an update of this
 * app. The installer checks that it is signed like the installed app (so a
 * nightly installs over a nightly or a CI build, never over an APK signed by
 * someone else); on Android 12+ an app updating itself, holding
 * `UPDATE_PACKAGES_WITHOUT_USER_ACTION` and allowed to install unknown apps,
 * is updated without a dialog. The outcome reaches [UpdateReceiver].
 */
object UpdateInstaller {

    /** The status broadcast's action (the receiver's PendingIntent is Ara's own). */
    const val ACTION_STATUS = "os.retinue.ara.action.UPDATE_STATUS"

    /** Returns the session's id (the status broadcast names it). Blocks while the APK is copied in: call off the main thread. */
    fun install(context: Context, apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("ara.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS)
                // Mutable: the installer fills in the status extras. Explicit, so Android 14 allows that.
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    status,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        return sessionId
    }
}
