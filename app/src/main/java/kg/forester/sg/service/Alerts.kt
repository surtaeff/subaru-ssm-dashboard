package kg.forester.sg.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import kg.forester.sg.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * Warnings for the driver (IAM drop, overheating, cold engine revved, maintenance due, low tyre…): a banner on
 * every screen and, if the head unit has a Russian voice, spoken; otherwise a beep. Each warning has an id and
 * is repeated no more often than its cooldown.
 */
object Alerts {
    enum class Level { INFO, WARN, DANGER }

    data class Alert(val id: String, val text: String, val level: Level, val timeMs: Long)

    /** The banner shown now (the latest one), null = none. */
    val current = MutableStateFlow<Alert?>(null)
    /** Recent warnings, newest first, for the «Мониторинг» screen. */
    val history = MutableStateFlow<List<Alert>>(emptyList())

    private val lastShown = HashMap<String, Long>()
    private val lastLevel = HashMap<String, Level>()
    private var prefs: android.content.SharedPreferences? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    fun init(ctx: Context) {
        if (Repo.settings.voiceAlerts) Speech.prepare(ctx)
        if (prefs == null) {
            // the list survives a restart, so a warning heard while driving can be read later
            val p = ctx.applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE).also { prefs = it }
            val saved = runCatching {
                val a = org.json.JSONArray(p.getString("history", "[]"))
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Alert(o.getString("id"), o.getString("text"), Level.valueOf(o.getString("level")), o.getLong("time"))
                }
            }.getOrDefault(emptyList())
            history.value = (history.value + saved).take(50)
            save()
        }
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            ttsReady = status == TextToSpeech.SUCCESS &&
                t.setLanguage(Speech.locale()).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
            t.setOnUtteranceProgressListener(progress)
            // like a navigator's voice: over the music, not at the notification volume
            runCatching {
                t.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
        }
    }

    /**
     * Shows (and says) [text] unless the same [id] was raised less than [cooldownMs] ago; a more serious [level]
     * than last time gets through all the same (a warning must not hold back the danger that follows it).
     */
    fun raise(id: String, text: String, level: Level = Level.WARN, cooldownMs: Long = 10 * 60_000L, speak: Boolean = true, banner: Boolean = true) {
        val now = System.currentTimeMillis()
        synchronized(lastShown) {
            val last = lastShown[id]
            val worse = lastLevel[id]?.let { level > it } ?: false
            if (last != null && now - last < cooldownMs && !worse) return
            lastShown[id] = now
            lastLevel[id] = level
        }
        val a = Alert(id, text, level, now)
        if (banner) current.value = a
        history.value = (listOf(a) + history.value).take(50)
        save()
        if (!speak || !Repo.settings.voiceAlerts || Repo.settings.demo) return
        // the app's own voice first, then the head unit's TTS, then a beep
        if (Speech.say(text, level = level)) return
        val beepIfNeeded = { if (level != Level.INFO) beep() }
        if (!systemSay(Speech.spoken(text), level == Level.DANGER, onFail = beepIfNeeded)) beepIfNeeded()
    }

    /** A short beep; the tone generator (a native audio session) is released when it has sounded. */
    fun beep(volume: Int = 80, ms: Int = 300) {
        runCatching {
            val g = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
            g.startTone(ToneGenerator.TONE_PROP_BEEP2, ms)
            main.postDelayed({ runCatching { g.release() } }, ms + 100L)
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val utterances = java.util.concurrent.ConcurrentHashMap<String, Utterance>()

    private val progress = object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(id: String) { utterances[id]?.start() }
        override fun onDone(id: String) { utterances.remove(id)?.done() }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String) { utterances.remove(id)?.fail() }
        override fun onError(id: String, errorCode: Int) { utterances.remove(id)?.fail() }
        // pushed out by a danger: said in part is said, never begun is not
        override fun onStop(id: String, interrupted: Boolean) {
            utterances.remove(id)?.let { if (it.startedOk) it.done() else it.fail() }
        }
    }

    /** The engine doesn't always say what became of a phrase; this looks. */
    private fun watch(id: String, u: Utterance, t0: Long) {
        main.postDelayed({
            if (utterances[id] === u) when (u.look(systemSpeaking(), android.os.SystemClock.elapsedRealtime() - t0)) {
                UtteranceStep.WAIT -> watch(id, u, t0)
                UtteranceStep.FAIL -> { utterances.remove(id); u.fail() }
                UtteranceStep.END -> { utterances.remove(id); u.done() }
            }
        }, 500)
    }

    /** The language was changed: the head unit's voice follows, whether it can or not. */
    fun languageChanged(ctx: Context) {
        val t = tts
        if (t != null) ttsReady = runCatching { t.setLanguage(Speech.locale()) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            .let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
        if (Repo.settings.voiceAlerts) Speech.prepare(ctx)
    }

    /** The head unit's own voice can say the chosen language. */
    val systemReady get() = ttsReady

    /**
     * [spoken] (already through [SpeechText]) by the head unit's own voice; false if it can't. True only means queued:
     * [onFail] comes if it isn't said after all, [onEnd] when it is over either way (only if true was returned).
     * [urgent]: ahead of the ordinary speech it is saying or has queued (those not begun get their [onFail]).
     */
    fun systemSay(spoken: String, urgent: Boolean = false, onEnd: () -> Unit = {}, onFail: () -> Unit = {}): Boolean {
        if (!ttsReady) return false
        val loud = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val id = "alert${System.nanoTime()}"
        val flush = systemFlushes(urgent, utterances.values.any { it.urgent })
        val u = Utterance(onEnd, onFail, urgent)
        utterances[id] = u
        val queued = runCatching {
            tts?.speak(spoken, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, loud, id) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!queued) { utterances.remove(id); return false }
        watch(id, u, android.os.SystemClock.elapsedRealtime())
        return true
    }

    fun systemSpeaking() = ttsReady && runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    private fun save() {
        val p = prefs ?: return
        val a = org.json.JSONArray()
        history.value.forEach {
            a.put(org.json.JSONObject().put("id", it.id).put("text", it.text).put("level", it.level.name).put("time", it.timeMs))
        }
        p.edit().putString("history", a.toString()).apply()
    }

    /** The banner goes away when the condition is over (e.g. the turbo has cooled down). */
    fun clear(id: String) {
        if (current.value?.id == id) current.value = null
    }

    fun dismiss() {
        current.value = null
    }
}
