package kg.forester.sg.service.yandex

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Signing in to Yandex Music (ported from FreeLite's YaMusicAuth). The driver signs in on Yandex's own page
 * (oauth.yandex.ru in a WebView, the OAuth «token» flow with the public client id of the official Android app, as the
 * open clients use it), or confirms a code on the phone (device flow, ya.ru/device; only in builds given the client's
 * secret, see [codeLogin]), or pastes a token. The app never reads the page's fields or the password: it only catches
 * the redirect with `#access_token=…`. The token is checked with account/status, then kept in this unit's private
 * prefs, encrypted with an AES-GCM key of the Android Keystore (the key never leaves it), decrypted once into memory,
 * never logged, never in a backup. «Выйти» deletes the token, the key, the account and the saved queue.
 */
object YaAuth {
    /** The public OAuth client of the official Yandex Music app for Android (the same in MarshalX/yandex-music-api and yamusic-tui). */
    const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
    const val AUTHORIZE = "https://oauth.yandex.ru/authorize?response_type=token&client_id=$CLIENT_ID"
    private const val ALIAS = "carpulse_yamusic"

    /**
     * The client's secret, needed only by the code flow. Not in the sources: a build gets it from the Gradle property
     * `yandexClientSecret` (~/.gradle/gradle.properties); without it «Войти с телефона» isn't offered.
     */
    private val clientSecret: String get() = kg.forester.sg.BuildConfig.YANDEX_CLIENT_SECRET
    val codeLogin: Boolean get() = clientSecret.isNotEmpty()

    data class State(val account: YaApi.Account? = null, val problem: String = "", val expiresAt: Long = 0) {
        val signedIn get() = account != null
    }

    val state = MutableStateFlow(State())

    @Volatile private var token: String? = null
    @Volatile private var loaded = false
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("yamusic", Context.MODE_PRIVATE)

    /** The token or null: decrypted once per process (a Keystore call), from memory afterwards. */
    @Synchronized
    fun token(c: Context): String? {
        if (loaded) return token
        loaded = true
        val p = prefs(c)
        val blob = p.getString("tok", "").orEmpty()
        if (blob.isNotEmpty()) {
            token = runCatching { decrypt(blob) }.getOrNull()
            if (token == null) state.value = state.value.copy(problem = "Сохранённый вход не читается, войдите снова")
        }
        if (token != null) {
            val uid = p.getString("uid", "").orEmpty()
            if (uid.isNotEmpty()) state.value = State(
                YaApi.Account(uid, p.getString("login", "").orEmpty(), p.getString("name", "").orEmpty(), p.getBoolean("plus", false), p.getString("until", "").orEmpty()),
                expiresAt = p.getLong("exp", 0),
            )
        }
        return token
    }

    fun signedIn(c: Context) = token(c) != null && state.value.account != null
    fun uid(c: Context): String { token(c); return state.value.account?.uid.orEmpty() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return kg.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val iv = Base64.decode(blob.substringBefore(':'), Base64.NO_WRAP)
        val ct = Base64.decode(blob.substringAfter(':'), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct))
    }

    /** Checks [tok] with account/status, then keeps it (encrypted) with the account. [done] on the main thread: ok, what to say. */
    fun signIn(ctx: Context, tok: String?, expiresInSec: Long, done: (Boolean, String) -> Unit) {
        val c = ctx.applicationContext
        val t = tok?.trim().orEmpty()
        if (t.length < 20 || t.contains(' ')) { done(false, "Это не похоже на токен Яндекса"); return }
        io.launch {
            val r = runCatching { YaApi.account(t) }
            val blob = r.getOrNull()?.let { runCatching { encrypt(t) }.getOrNull() }
            withContext(Dispatchers.Main) {
                val acc = r.getOrNull()
                if (acc == null) {
                    done(false, if (YaApi.isAuth(r.exceptionOrNull())) "Яндекс не принял вход, попробуйте ещё раз" else YaApi.message(r.exceptionOrNull()))
                    return@withContext
                }
                synchronized(this@YaAuth) { token = t; loaded = true }
                val exp = if (expiresInSec > 0) System.currentTimeMillis() + expiresInSec * 1000 else 0
                val e = prefs(c).edit()
                if (blob != null) e.putString("tok", blob) else e.remove("tok")
                putAccount(e, acc).putLong("exp", exp).apply()
                state.value = State(acc, if (blob == null) "Хранилище ключей недоступно: вход действует до перезапуска" else "", exp)
                YaLibrary.reset()
                SsmTrace.log("yandex: signed in", keep = true)
                done(true, "Вы вошли: ${acc.name}")
            }
        }
    }

    private fun putAccount(e: SharedPreferences.Editor, a: YaApi.Account) = e.putString("uid", a.uid).putString("login", a.login).putString("name", a.name)
        .putBoolean("plus", a.plus).putString("until", a.until).putLong("checked", System.currentTimeMillis())

    /** Reads the account (the subscription) again in the background, at most every 6 h, or now when [force]. */
    fun refresh(ctx: Context, force: Boolean) {
        val c = ctx.applicationContext
        val t = token(c) ?: return
        if (!force && System.currentTimeMillis() - prefs(c).getLong("checked", 0) < 6 * 3600_000L) return
        prefs(c).edit().putLong("checked", System.currentTimeMillis()).apply()
        io.launch {
            val r = runCatching { YaApi.account(t) }
            withContext(Dispatchers.Main) {
                r.getOrNull()?.let { a -> putAccount(prefs(c).edit(), a).apply(); state.value = state.value.copy(account = a) }
                if (YaApi.isAuth(r.exceptionOrNull())) expired(c)
            }
        }
    }

    /** The API answered 401: keep nothing, ask to sign in again. */
    fun expired(c: Context) {
        if (token(c) == null) return
        logout(c)
        state.value = State(problem = "Вход в Яндекс устарел, войдите снова")
    }

    fun logout(ctx: Context) {
        val c = ctx.applicationContext
        YaMusic.signedOut(c)
        synchronized(this) { token = null; loaded = true }
        prefs(c).edit().clear().apply()
        YaMusic.forget(c)
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        clearCookies()
        YaLibrary.reset()
        state.value = State()
        SsmTrace.log("yandex: signed out", keep = true)
    }

    fun clearCookies() {
        runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.CookieManager.getInstance().flush() }
    }

    /** «Плюс активен до 14.12.2026» / no subscription: only 30-second previews. */
    fun plusText(a: YaApi.Account?): String {
        if (a == null) return ""
        if (!a.plus) return "Подписки Плюс нет: доступны только 30-секундные фрагменты"
        val d = date(a.until)
        return if (d.isEmpty()) "Плюс активен" else "Плюс активен до $d"
    }

    fun date(iso: String?): String {
        if (iso == null || iso.length < 10) return ""
        return runCatching {
            java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.ROOT).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(iso.substring(0, 10))!!)
        }.getOrDefault("")
    }

    /** Only Yandex's own pages over https stay in the sign-in window. */
    fun yandexPage(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase().orEmpty()
        val yandex = listOf("yandex.ru", "ya.ru", "yandex.net", "yandex.com", "yandex.by", "yandex.kz").any { host == it || host.endsWith(".$it") }
        return u.scheme == "https" && yandex
    }

    // ---- the code flow (device flow) ----------------------------------------------------------------------

    data class Code(val deviceCode: String, val userCode: String, val url: String, val intervalMs: Long, val deadline: Long)

    /** A code to type on the phone; blocking. */
    fun requestCode(): Code {
        val did = java.lang.Long.toHexString(java.util.Random().nextLong()).padStart(16, '0').take(10)
        val r = oauthPost("device/code", YaApi.form("client_id", CLIENT_ID, "device_id", did, "device_name", "CarPulse"))
        val dc = r.optString("device_code", "")
        if (dc.isEmpty()) throw YaApi.Err(500, "no code")
        return Code(dc, r.optString("user_code", ""), r.optString("verification_url", "https://ya.ru/device"),
            maxOf(3000L, r.optLong("interval", 5) * 1000), System.currentTimeMillis() + maxOf(60L, r.optLong("expires_in", 300)) * 1000)
    }

    /** One look whether the code was confirmed: (token, expires in s) or (null, the OAuth error). Blocking. */
    fun pollCode(code: Code): Pair<String?, String> {
        val r = oauthPost("token", YaApi.form("grant_type", "device_code", "code", code.deviceCode, "client_id", CLIENT_ID, "client_secret", clientSecret))
        val tok = r.optString("access_token", "")
        return if (tok.isNotEmpty()) tok to r.optLong("expires_in", 0).toString() else null to r.optString("error", "")
    }

    private fun oauthPost(path: String, form: String): JSONObject {
        val c = URL("https://oauth.yandex.ru/$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("User-Agent", YaApi.UA)
            val b = form.toByteArray()
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.let { YaApi.read(it, 64 shl 10) } ?: "{}"
            return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        } finally {
            c.disconnect()
        }
    }
}
