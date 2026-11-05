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
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import os.retinue.ara.shell.Origin

/** The selected certificate could not be read (removed, expired, or inaccessible). */
class CertificateUnavailableException(cause: Throwable?) :
    Exception("Selected client certificate is unavailable", cause)

/** A user name and password for HTTP basic authentication at a Retinue edge. */
data class BasicCredentials(val username: String, val password: String)

/**
 * Builds the OkHttp client the shell uses to reach a Retinue endpoint as the
 * WebView does: with the client certificate the user selected from the Android
 * KeyChain (mutual TLS), the stored user name and password where the edge asks
 * for one, or both — the edge accepts either, and with both the certificate
 * decides the handshake and the password is never asked for. The health upload
 * and the model and manifest downloads from the endpoint use it; the WebView
 * presents the same certificate through [clientKey].
 *
 * Security posture (per spec):
 *  - the private key and chain are fetched from KeyChain by alias — never
 *    bundled in the APK, never persisted by the app;
 *  - server-certificate validation uses the platform default trust managers
 *    (no trust-all manager);
 *  - hostname verification is left at OkHttp's default (not disabled);
 *  - the client follows no redirects, so neither the certificate nor the
 *    password reaches another host; the password goes only to [origin]'s host.
 */
object AraHttp {

    /**
     * A client for the endpoint at [origin]. [alias] null: no client certificate;
     * [credentials] null: no password. Must be called off the main thread when
     * [alias] is set — [KeyChain.getPrivateKey] blocks.
     *
     * @throws CertificateUnavailableException when the alias no longer resolves
     *   to a usable key/chain (e.g. the user removed it or it expired).
     */
    fun build(
        context: Context,
        alias: String?,
        requestTimeoutMs: Long,
        credentials: BasicCredentials? = null,
        origin: Origin? = null,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            // The certificate and the password are for the configured origin
            // only: never follow a redirect (callers follow same-origin ones themselves).
            .followRedirects(false)
            .followSslRedirects(false)
        if (alias != null) {
            val (key, chain) = clientKey(context, alias)
            val keyManager = FixedClientKeyManager(alias, chain, key)
            val trustManager = defaultTrustManager()
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        }
        if (credentials != null) builder.authenticator(BasicAuthenticator(credentials, origin))
        return builder.build()
    }

    /**
     * The private key and certificate chain stored under [alias] in the Android
     * KeyChain — what both this OkHttp client and the WebView's client-certificate
     * callback present. Blocks: never call on the main thread.
     *
     * @throws CertificateUnavailableException when the alias no longer resolves.
     */
    fun clientKey(context: Context, alias: String): Pair<PrivateKey, Array<X509Certificate>> {
        try {
            val chain = KeyChain.getCertificateChain(context, alias)
                ?: throw CertificateUnavailableException(null)
            val key = KeyChain.getPrivateKey(context, alias)
                ?: throw CertificateUnavailableException(null)
            return key to chain
        } catch (e: CertificateUnavailableException) {
            throw e
        } catch (e: Exception) {
            // KeyChainException / InterruptedException / IllegalStateException
            throw CertificateUnavailableException(e)
        }
    }

    private fun defaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /**
     * Answers one 401 of the endpoint's own host with the stored user name and
     * password, once: a challenge that follows an answered one means the
     * password is wrong, and the request fails rather than loops. A challenge
     * from any other host (or port) goes unanswered.
     */
    private class BasicAuthenticator(private val credentials: BasicCredentials, private val origin: Origin?) : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.header("Authorization") != null) return null
            if (origin != null) {
                val url = response.request.url
                if (!url.host.equals(origin.host, ignoreCase = true) || url.port != origin.port) return null
            }
            return response.request.newBuilder()
                .header("Authorization", Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8))
                .build()
        }
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
