package os.retinue.ara.net

import android.content.Context
import android.security.KeyChain
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** No client certificate has been selected yet. */
class MissingCertificateException : Exception("No client certificate selected")

/** The selected certificate could not be read (removed, expired, or inaccessible). */
class CertificateUnavailableException(cause: Throwable?) :
    Exception("Selected client certificate is unavailable", cause)

/**
 * Builds the OkHttp client used for every Retinue call, configured for mutual
 * TLS with a certificate the user selected from the Android KeyChain.
 *
 * Security posture (per spec):
 *  - the private key and chain are fetched from KeyChain by alias — never
 *    bundled in the APK, never persisted by the app;
 *  - server-certificate validation uses the platform default trust managers
 *    (no trust-all manager);
 *  - hostname verification is left at OkHttp's default (not disabled).
 */
object AraHttp {

    /**
     * @throws MissingCertificateException when [alias] is null.
     * @throws CertificateUnavailableException when the alias no longer resolves
     *   to a usable key/chain (e.g. the user removed or it expired).
     *
     * Must be called off the main thread — [KeyChain.getPrivateKey] blocks.
     */
    fun build(context: Context, alias: String?, requestTimeoutMs: Long): OkHttpClient {
        if (alias == null) throw MissingCertificateException()

        val chain: Array<X509Certificate>
        val key: PrivateKey
        try {
            chain = KeyChain.getCertificateChain(context, alias)
                ?: throw CertificateUnavailableException(null)
            key = KeyChain.getPrivateKey(context, alias)
                ?: throw CertificateUnavailableException(null)
        } catch (e: CertificateUnavailableException) {
            throw e
        } catch (e: Exception) {
            // KeyChainException / InterruptedException / IllegalStateException
            throw CertificateUnavailableException(e)
        }

        val keyManager = FixedClientKeyManager(alias, chain, key)
        val trustManager = defaultTrustManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // Per-request timeout from settings, applied to each poll/submit call.
            .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun defaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /**
     * A key manager that always presents the single user-selected client
     * certificate. It never acts as a server key manager.
     */
    private class FixedClientKeyManager(
        private val alias: String,
        private val chain: Array<X509Certificate>,
        private val key: PrivateKey,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
            arrayOf(alias)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = alias

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ) = alias

        override fun getCertificateChain(a: String?) = if (a == alias) chain else null
        override fun getPrivateKey(a: String?) = if (a == alias) key else null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = null
    }
}
