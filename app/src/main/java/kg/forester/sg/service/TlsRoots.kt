package kg.forester.sg.service

import android.content.Context
import android.os.Build
import kg.forester.sg.R
import kg.forester.sg.ssm.SsmTrace
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 6 and 7.0 (API 23–24) don't know ISRG Root X1, the root of Let's Encrypt, nor its newer roots: every HTTPS
 * site on such a certificate fails there with "Trust anchor for certification path not found". Seen on a budget
 * Android 6 head unit: the internet radio's catalogue (radio-browser.info) never loaded; many stations' streams and logos
 * are on Let's Encrypt too. The roots ship in res/raw (taken from letsencrypt.org, fingerprints checked by TlsRootsTest)
 * and are trusted next to the system's own for every HttpsURLConnection of the app (the radio, the player's streams,
 * covers, the updater, the log upload). Trust is only added: a chain still has to verify up to a root and the host name
 * still has to match. Android 7.1.1 and later have these roots themselves, nothing is changed there.
 */
object TlsRoots {
    private val RAW = intArrayOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2, R.raw.isrg_root_yr, R.raw.isrg_root_ye)

    fun install(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 25) return
        runCatching {
            val cf = CertificateFactory.getInstance("X.509")
            val roots = RAW.map { id -> ctx.resources.openRawResource(id).use { cf.generateCertificate(it) as X509Certificate } }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(Combined(trustManager(null), trustManager(keyStore(roots)))), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            SsmTrace.log("tls: ${roots.size} Let's Encrypt roots added (sdk ${Build.VERSION.SDK_INT})", keep = true)
        }.onFailure { SsmTrace.log("tls: roots not added: $it", keep = true) }
    }

    fun keyStore(roots: List<X509Certificate>): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        roots.forEachIndexed { i, c -> setCertificateEntry("isrg$i", c) }
    }

    /** The platform's X.509 trust manager over [ks]; null = the system's own roots. */
    fun trustManager(ks: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** The system decides first; only a chain it refuses is tried against the bundled roots. Its refusal is what is thrown. */
    class Combined(private val system: X509TrustManager, private val extra: X509TrustManager) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                try {
                    extra.checkServerTrusted(chain, authType)
                } catch (_: CertificateException) {
                    throw e
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
    }
}
