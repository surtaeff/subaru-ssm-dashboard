package kg.forester.sg.service.yandex

import org.json.JSONObject

/**
 * Signing in from the phone (OAuth device flow, ya.ru/device): what oauth.yandex.ru answers, parsed, and the steps the
 * «Яндекс» panel goes through while it shows the code. No network and no Android here, so it is tested on the JVM; the
 * requests are [YaAuth.requestCode] and [YaAuth.pollCode]. The flow needs the client secret, which only a build machine
 * that has `yandexClientSecret` passes in (see [YaAuth.codeLogin]); the source has none.
 */
object YaDevice {
    /** The service asks for no less than this between two checks. */
    const val MIN_INTERVAL_MS = 3_000L

    /** RFC 8628: after «slow_down» the checks go this much further apart. */
    const val SLOW_DOWN_MS = 5_000L

    const val DEFAULT_URL = "https://ya.ru/device"

    data class Code(val deviceCode: String, val userCode: String, val url: String, val intervalMs: Long, val deadline: Long)

    /** The answer of `device/code` at [now]; null without a device code (a refusal, a page of errors). */
    fun parseCode(r: JSONObject, now: Long): Code? {
        val dc = r.optString("device_code", "")
        val uc = r.optString("user_code", "")
        if (dc.isEmpty() || uc.isEmpty()) return null
        val url = r.optString("verification_url", "").ifEmpty { DEFAULT_URL }
        return Code(dc, uc, url, maxOf(MIN_INTERVAL_MS, r.optLong("interval", 5) * 1000), now + maxOf(60L, r.optLong("expires_in", 300)) * 1000)
    }

    /** One check of the code: what the `token` request answered. */
    sealed interface Poll {
        data class Token(val token: String, val expiresInSec: Long) : Poll
        /** Not confirmed on the phone yet. */
        data object Pending : Poll
        data object SlowDown : Poll
        data object Denied : Poll
        /** The code is no longer good: a new one is needed. */
        data object Expired : Poll
        data class Failed(val error: String) : Poll
    }

    fun parsePoll(r: JSONObject): Poll {
        val tok = r.optString("access_token", "")
        if (tok.isNotEmpty()) return Poll.Token(tok, r.optLong("expires_in", 0))
        return when (val e = r.optString("error", "")) {
            "", "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            "access_denied" -> Poll.Denied
            "expired_token", "bad_verification_code" -> Poll.Expired
            else -> Poll.Failed(e)
        }
    }

    /** Why the panel stopped waiting, for its text. */
    enum class Reason { UNAVAILABLE, DENIED, REJECTED, SIGN_IN }

    /** What the panel shows. */
    sealed interface Flow {
        data object Requesting : Flow
        data class Waiting(val code: Code, val intervalMs: Long) : Flow
        /** A token came: the account is being checked. */
        data class Confirming(val token: String, val expiresInSec: Long) : Flow
        data object Expired : Flow
        data class Failed(val reason: Reason, val detail: String = "") : Flow
    }

    /** The first step once `device/code` answered (null: it didn't, or with nothing usable). */
    fun start(code: Code?): Flow = if (code == null) Flow.Failed(Reason.UNAVAILABLE) else Flow.Waiting(code, code.intervalMs)

    /**
     * After one check: [poll] is its answer, null when the request itself failed (no internet for a moment), which keeps
     * waiting. Past the deadline the code is expired whatever else was said, unless a token came.
     */
    fun next(w: Flow.Waiting, poll: Poll?, now: Long): Flow = when {
        poll is Poll.Token -> Flow.Confirming(poll.token, poll.expiresInSec)
        poll == Poll.Expired || now >= w.code.deadline -> Flow.Expired
        poll == Poll.Denied -> Flow.Failed(Reason.DENIED)
        poll is Poll.Failed -> Flow.Failed(Reason.REJECTED, poll.error)
        poll == Poll.SlowDown -> w.copy(intervalMs = w.intervalMs + SLOW_DOWN_MS)
        else -> w
    }

    /** Whole seconds the code is still good for at [now], never below 0. */
    fun secondsLeft(code: Code, now: Long): Long = ((code.deadline - now + 999) / 1000).coerceAtLeast(0)

    /** «4:05». */
    fun clock(sec: Long): String = "%d:%02d".format(java.util.Locale.ROOT, sec / 60, sec % 60)

    /** The address to type, without the scheme: «ya.ru/device». */
    fun shortUrl(url: String): String = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
}
