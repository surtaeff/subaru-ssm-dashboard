package kg.forester.sg

import kg.forester.sg.service.yandex.YaDevice
import kg.forester.sg.service.yandex.YaDevice.Flow
import kg.forester.sg.service.yandex.YaDevice.Poll
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Signing in from the phone (ya.ru/device): the answers of oauth.yandex.ru and the panel's steps between them. */
class YaDeviceTest {
    private val now = 1_000_000L
    private fun code(deadline: Long = now + 300_000) = YaDevice.Code("dev", "ABC1234", "https://ya.ru/device", 5_000, deadline)

    @Test fun theCodeAnswer() {
        val c = YaDevice.parseCode(JSONObject("""{"device_code":"d1","user_code":"7GH2K9P","verification_url":"https://ya.ru/device","interval":5,"expires_in":300}"""), now)!!
        assertEquals("d1", c.deviceCode)
        assertEquals("7GH2K9P", c.userCode)
        assertEquals("https://ya.ru/device", c.url)
        assertEquals(5_000L, c.intervalMs)
        assertEquals(now + 300_000, c.deadline)
        // what is missing gets the usual; too fast a pace and too short a life are not taken
        val d = YaDevice.parseCode(JSONObject("""{"device_code":"d2","user_code":"X","interval":1,"expires_in":5}"""), now)!!
        assertEquals(YaDevice.DEFAULT_URL, d.url)
        assertEquals(YaDevice.MIN_INTERVAL_MS, d.intervalMs)
        assertEquals(now + 60_000, d.deadline)
        // a refusal has no code
        assertNull(YaDevice.parseCode(JSONObject("""{"error":"invalid_client","error_description":"Client not found"}"""), now))
        assertNull(YaDevice.parseCode(JSONObject("""{"device_code":"d3"}"""), now))
        assertNull(YaDevice.parseCode(JSONObject(), now))
    }

    @Test fun theCheckAnswer() {
        assertEquals(Poll.Token("y0_tok", 31_536_000), YaDevice.parsePoll(JSONObject("""{"access_token":"y0_tok","expires_in":31536000,"token_type":"bearer"}""")))
        assertEquals(Poll.Pending, YaDevice.parsePoll(JSONObject("""{"error":"authorization_pending"}""")))
        assertEquals("an empty answer is no answer yet", Poll.Pending, YaDevice.parsePoll(JSONObject()))
        assertEquals(Poll.SlowDown, YaDevice.parsePoll(JSONObject("""{"error":"slow_down"}""")))
        assertEquals(Poll.Denied, YaDevice.parsePoll(JSONObject("""{"error":"access_denied"}""")))
        assertEquals(Poll.Expired, YaDevice.parsePoll(JSONObject("""{"error":"expired_token"}""")))
        assertEquals(Poll.Expired, YaDevice.parsePoll(JSONObject("""{"error":"bad_verification_code"}""")))
        assertEquals(Poll.Failed("invalid_client"), YaDevice.parsePoll(JSONObject("""{"error":"invalid_client"}""")))
    }

    @Test fun theSteps() {
        assertEquals("no code: the service is down", Flow.Failed(YaDevice.Reason.UNAVAILABLE), YaDevice.start(null))
        val w = YaDevice.start(code()) as Flow.Waiting
        assertEquals(5_000L, w.intervalMs)
        // not yet, or no internet for a moment: go on waiting at the same pace
        assertEquals(w, YaDevice.next(w, Poll.Pending, now + 5_000))
        assertEquals(w, YaDevice.next(w, null, now + 5_000))
        // slow_down: further apart, and it adds up
        val slow = YaDevice.next(w, Poll.SlowDown, now + 5_000) as Flow.Waiting
        assertEquals(10_000L, slow.intervalMs)
        assertEquals(15_000L, (YaDevice.next(slow, Poll.SlowDown, now + 15_000) as Flow.Waiting).intervalMs)
        // confirmed on the phone
        assertEquals(Flow.Confirming("y0_tok", 100), YaDevice.next(w, Poll.Token("y0_tok", 100), now + 5_000))
        // refused, or something else wrong: stop, the panel offers a new code
        assertEquals(Flow.Failed(YaDevice.Reason.DENIED), YaDevice.next(w, Poll.Denied, now + 5_000))
        assertEquals(Flow.Failed(YaDevice.Reason.REJECTED, "invalid_grant"), YaDevice.next(w, Poll.Failed("invalid_grant"), now + 5_000))
        assertEquals(Flow.Expired, YaDevice.next(w, Poll.Expired, now + 5_000))
    }

    @Test fun theDeadline() {
        val w = Flow.Waiting(code(deadline = now + 10_000), 5_000)
        assertEquals(Flow.Expired, YaDevice.next(w, Poll.Pending, now + 10_000))
        assertEquals("a lost request past the deadline too", Flow.Expired, YaDevice.next(w, null, now + 12_000))
        assertEquals("a token that made it in time still counts", Flow.Confirming("t", 0), YaDevice.next(w, Poll.Token("t", 0), now + 12_000))
        assertEquals(10L, YaDevice.secondsLeft(w.code, now))
        assertEquals("a part of a second still shows", 1L, YaDevice.secondsLeft(w.code, now + 9_500))
        assertEquals(0L, YaDevice.secondsLeft(w.code, now + 20_000))
        assertEquals("4:05", YaDevice.clock(245))
        assertEquals("0:00", YaDevice.clock(0))
        assertEquals("ya.ru/device", YaDevice.shortUrl("https://ya.ru/device/"))
    }

    @Test fun noSecretInTheSource() {
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val auth = File(root, "kg/forester/sg/service/yandex/YaAuth.kt").readText()
        // the secret is the build machine's (gradle property → BuildConfig), the code login only with it
        assertTrue(auth.contains("BuildConfig.YANDEX_CLIENT_SECRET"))
        assertTrue(auth.contains("val codeLogin: Boolean get() = clientSecret.isNotEmpty()"))
        assertFalse(Regex("client_secret\"\\s*,\\s*\"[0-9a-f]{16,}").containsMatchIn(auth))
        val gradle = (if (root.path == "src/main/java") File("build.gradle.kts") else File("app/build.gradle.kts")).readText()
        assertTrue(gradle.contains("findProperty(\"yandexClientSecret\")"))
        // signed out, the panel itself signs in: the phone code only where the build can, Yandex's page and a token always
        val ui = File(root, "kg/forester/sg/ui/MediaYandex.kt").readText()
        assertTrue(ui.contains("if (YaAuth.codeLogin) {") && ui.contains("DeviceLogin()"))
        assertTrue(ui.contains("SignIn.WEB -> WebLogin(done)") && ui.contains("SignIn.PASTE -> PasteToken(done)"))
        assertFalse("no window over the player for signing in", ui.contains("WebLoginDialog") || ui.contains("CodeLoginDialog"))
    }
}
