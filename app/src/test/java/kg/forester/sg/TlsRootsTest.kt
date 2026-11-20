package kg.forester.sg

import kg.forester.sg.service.RadioLogos
import kg.forester.sg.service.TlsRoots
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

/** The Let's Encrypt roots added on Android 6–7.0 (see TlsRoots): the right certificates, and trust only widened. */
class TlsRootsTest {
    private val cf = CertificateFactory.getInstance("X.509")
    private fun cert(path: String) = File(path).inputStream().use { cf.generateCertificate(it) as X509Certificate }
    private fun sha256(c: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

    /** SHA-256 of each bundled root, as letsencrypt.org publishes it (X1 and X2 also match macOS's own root store). */
    private val roots = mapOf(
        "isrg_root_x1" to "96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6",
        "isrg_root_x2" to "69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70",
        "isrg_root_yr" to "E5:7B:7E:6F:15:0C:41:91:02:E8:D5:C0:55:72:9F:F9:67:B9:D1:A8:29:BF:00:CE:C8:9C:A6:04:EB:F4:A8:6F",
        "isrg_root_ye" to "E1:4F:FC:AD:5B:00:25:73:10:06:CA:A4:3A:12:1A:22:D8:E9:70:0F:4F:B9:CF:85:2F:02:A7:08:AA:5D:56:66",
    )

    private fun raw(name: String) = cert("src/main/res/raw/$name.pem")

    @Test fun bundledRootsAreTheRealOnes() {
        roots.forEach { (name, fp) ->
            val c = raw(name)
            assertEquals(name, fp, sha256(c))
            // a self-signed CA
            assertEquals(c.subjectX500Principal, c.issuerX500Principal)
            c.verify(c.publicKey)
            assertTrue(name, c.basicConstraints >= 0)
        }
        // nothing else ships next to them
        val pems = File("src/main/res/raw").listFiles()!!.filter { it.name.endsWith(".pem") }.map { it.name.removeSuffix(".pem") }.toSet()
        assertEquals(roots.keys, pems)
    }

    /** radio-browser.info sends leaf ← YR2 ← Root YR signed by ISRG Root X1 (2026-11): X1 alone anchors it. */
    @Test fun radioBrowserChainEndsAtBundledX1() {
        val cross = cert("src/test/resources/tls/root-yr-by-x1.pem")
        val x1 = raw("isrg_root_x1")
        assertEquals(x1.subjectX500Principal, cross.issuerX500Principal)
        cross.verify(x1.publicKey)
        // the same key as the bundled self-signed Root YR, so a chain that stops before the cross-sign is anchored too
        assertArrayEquals(raw("isrg_root_yr").publicKey.encoded, cross.publicKey.encoded)
    }

    private class Fake(val ok: Boolean, val issuer: X509Certificate? = null) : X509TrustManager {
        var asked = 0
        val refusal = CertificateException("refused")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { if (!ok) throw refusal }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { asked++; if (!ok) throw refusal }
        override fun getAcceptedIssuers(): Array<X509Certificate> = listOfNotNull(issuer).toTypedArray()
    }

    @Test fun systemFirstThenBundled() {
        val chain = arrayOf(raw("isrg_root_x1"))
        // the system trusts it: the bundled roots are not even asked
        Fake(true).let { sys -> val extra = Fake(true); TlsRoots.Combined(sys, extra).checkServerTrusted(chain, "RSA"); assertEquals(0, extra.asked) }
        // the system refuses, a bundled root vouches for it
        TlsRoots.Combined(Fake(false), Fake(true)).checkServerTrusted(chain, "RSA")
        // both refuse: the system's own refusal comes out
        val sys = Fake(false)
        try {
            TlsRoots.Combined(sys, Fake(false)).checkServerTrusted(chain, "RSA")
            fail("trusted with nothing vouching for it")
        } catch (e: CertificateException) {
            assertSame(sys.refusal, e)
        }
    }

    @Test fun acceptedIssuersAreBoth() {
        val a = raw("isrg_root_x1")
        val b = raw("isrg_root_x2")
        assertEquals(listOf(a, b), TlsRoots.Combined(Fake(true, a), Fake(true, b)).acceptedIssuers.toList())
    }

    /** The real JVM trust manager over the bundled roots alone: a certificate none of them signed is still refused. */
    @Test fun bundledTrustIsNotOpen() {
        val tm = TlsRoots.trustManager(TlsRoots.keyStore(roots.keys.map(::raw)))
        assertEquals(roots.size, tm.acceptedIssuers.size)
        try {
            tm.checkServerTrusted(arrayOf(cert("src/test/resources/tls/unrelated-self-signed.pem")), "RSA")
            fail("an unknown self-signed certificate was trusted")
        } catch (_: CertificateException) {
        }
    }

    @Test fun reasonNamesTheExceptionAndItsCause() {
        val e = SSLHandshakeException("Handshake failed").apply {
            initCause(java.security.cert.CertPathValidatorException("Trust anchor for certification path not found."))
        }
        val r = RadioLogos.reason(e)
        assertTrue(r, r.startsWith("SSLHandshakeException: Handshake failed"))
        assertTrue(r, r.contains("CertPathValidatorException: Trust anchor for certification path not found."))
    }
}
