package os.retinue.ara.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.provider.OpenableColumns
import android.security.KeyChain
import android.util.Log
import os.retinue.ara.net.CertificateImport.Outcome

/**
 * Importing a client certificate into the Android KeyChain, where the endpoint
 * editor's "Select certificate" finds it: a PKCS#12 file the user picks is
 * handed to Android's own certificate installer
 * ([KeyChain.createInstallIntent] with [KeyChain.EXTRA_PKCS12]), which asks for
 * its password and keeps the private key in the system's credential store.
 * Ara keeps nothing of it: the file is read into memory only, checked
 * ([CertificateImport]), handed over in the intent and wiped. Installing does
 * not give Ara the key: Android's chooser does, which is why the import ends
 * there (MainActivity).
 */
object CertificateInstaller {

    /** A picked file that may go to the installer. [bytes] is wiped once handed over. */
    class File(val bytes: ByteArray, val displayName: String?)

    /** What reading the picked file gave: the file, or why it does not go to the installer. */
    sealed class Read {
        class Picked(val file: File) : Read()
        data class Refused(val outcome: Outcome) : Read()
    }

    /**
     * A device policy forbids the user to install credentials (a work profile,
     * a managed or restricted device): the installer would close at once.
     */
    fun blockedByPolicy(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)
            ?.hasUserRestriction(UserManager.DISALLOW_CONFIG_CREDENTIALS) == true
    } catch (e: Exception) {
        Log.w(TAG, "user restrictions unreadable", e)
        false
    }

    /** Read and check the file at [uri]. Blocks: off the main thread. */
    fun read(context: Context, uri: Uri): Read {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { CertificateImport.readBounded(it) ?: return Read.Refused(Outcome.TooLarge) }
        } catch (e: Exception) {
            // FileNotFoundException, IOException, a SecurityException from the provider.
            Log.w(TAG, "certificate file unreadable", e)
            null
        } ?: return Read.Refused(Outcome.Unreadable)
        if (!CertificateImport.isPkcs12(bytes)) {
            bytes.fill(0)
            return Read.Refused(Outcome.NotPkcs12)
        }
        return Read.Picked(File(bytes, displayName(context, uri)))
    }

    /**
     * Android's installer for [file], suggesting [name] for it. Started for a
     * result: RESULT_OK once it is installed.
     */
    fun installIntent(file: File, name: String): Intent = KeyChain.createInstallIntent().apply {
        putExtra(KeyChain.EXTRA_PKCS12, file.bytes)
        putExtra(KeyChain.EXTRA_NAME, name)
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "certificate file name unreadable", e)
        null
    }

    private const val TAG = "CertificateInstaller"
}
