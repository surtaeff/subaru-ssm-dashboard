package kg.forester.sg.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kg.forester.sg.service.audio.ownSources
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.flow.MutableStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app's own Russian voice for the spoken warnings, so they do not depend on the head unit having a Russian
 * TTS: sherpa-onnx with a voice downloaded once when the warnings are on (never shipped in the APK). Piper voices:
 * Irina (female, trained on the RHVoice Irina voice, CC BY-SA 4.0 / GPL-3.0) by default, Dmitri and Denis (male,
 * CC0), Ruslan (male, the RUSLAN corpus, CC BY-NC-SA 4.0), each as int8 (~20 MB) or full precision (~64 MB: no
 * quantisation hiss, and on ARM even faster than int8). Supertonic 3 (OpenRAIL-M, ~123 MB): a newer, more natural
 * model with five female and five male voices, as heavy for the CPU as the int8 Piper ones. Or the head unit's own
 * Android TTS (Google, RHVoice…), if it has a Russian voice. Every text goes through [SpeechText] first.
 */
object Speech {
    enum class Engine { PIPER, SUPERTONIC, SYSTEM }

    /**
     * A voice to choose: [pack] is the sherpa-onnx tts-models archive it comes in (the Supertonic voices share one,
     * a speaker [sid] each), [mb] the download.
     */
    class TtsVoice(val id: String, val name: String, val engine: Engine, val pack: String = "", val mb: Int = 0, val sid: Int = 0)

    /** A server that didn't answer is tried again this often, with no event to go by. */
    private const val RETRY_MS = 120_000L
    /** Never closer than this, whatever a flapping network says. */
    private const val RETRY_GAP_MS = 15_000L
    private const val SUPERTONIC = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    private val SUPERTONIC_FILES = listOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
        "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin")

    private fun piper(id: String, name: String, hq: Boolean) =
        TtsVoice(if (hq) "$id-hq" else id, name, Engine.PIPER, "vits-piper-ru_RU-$id-medium" + if (hq) "" else "-int8", if (hq) 64 else 20)

    /** Piper voices by id (the int8 one; "-hq" after it is the full-precision one) → name. */
    val PIPER = linkedMapOf("irina" to "Ирина", "dmitri" to "Дмитрий", "denis" to "Денис", "ruslan" to "Руслан")

    val ALL: List<TtsVoice> = PIPER.flatMap { (id, name) -> listOf(piper(id, name, false), piper(id, name, true)) } +
        // voice.bin holds the style files sorted by name: F1–F5, then M1–M5
        (1..5).map { TtsVoice("st-f$it", "женский $it", Engine.SUPERTONIC, SUPERTONIC, 123, it - 1) } +
        (1..5).map { TtsVoice("st-m$it", "мужской $it", Engine.SUPERTONIC, SUPERTONIC, 123, it + 4) } +
        TtsVoice("system", "голос Android", Engine.SYSTEM)

    fun voice(id: String) = ALL.firstOrNull { it.id == id } ?: ALL.first()
    val current get() = voice(kg.forester.sg.data.Repo.settings.ttsVoice)

    /** "Ирина", "Ирина, высокое качество", "Supertonic, женский 2", "голос Android". */
    fun title(v: TtsVoice) = when {
        v.engine == Engine.SUPERTONIC -> "Supertonic, ${v.name}"
        v.engine == Engine.PIPER && v.mb > 20 -> "${v.name}, высокое качество"
        else -> v.name
    }

    private fun url(pack: String) = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$pack.tar.bz2"
    @Volatile private var loaded = ""

    /** "", "скачиваю 45 %", "готов", or what went wrong — for the settings. */
    val status = MutableStateFlow("")
    private val worker = Executors.newSingleThreadExecutor()
    private val phrases = PhraseQueue<Phrase>()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var downloading = false
    /** On its way to the disk: its files may be there in part. */
    @Volatile private var unpacking: String? = null
    @Volatile private var system: TextToSpeech? = null
    @Volatile private var systemReady = false
    private var said = 0

    private fun dir(ctx: Context, v: TtsVoice) = File(ctx.filesDir, "tts/${v.pack}")
    private fun model(ctx: Context, v: TtsVoice) = dir(ctx, v).listFiles()?.firstOrNull { it.name.endsWith(".onnx") }

    fun installed(ctx: Context, v: TtsVoice = current) = v.pack != unpacking && when (v.engine) {
        Engine.PIPER -> model(ctx, v) != null && File(dir(ctx, v), "tokens.txt").isFile
        Engine.SUPERTONIC -> SUPERTONIC_FILES.all { File(dir(ctx, v), it).isFile }
        Engine.SYSTEM -> true
    }

    private var appCtx: Context? = null
    private val idle = SpeechIdle()
    private fun now() = android.os.SystemClock.elapsedRealtime()

    /** The status is a failure: see the row's colour in the settings. */
    val failed = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** What the settings row says: nothing about a voice that is switched off. */
    fun shown(on: Boolean, status: String): String? = status.takeIf { on && it.isNotEmpty() }?.let { "Голос: $it" }

    private val retry = SpeechRetry(RETRY_GAP_MS)
    private var network: android.net.ConnectivityManager.NetworkCallback? = null

    /** The download goes again when a network comes up, and by time for a server that didn't answer (no event tells of that). */
    private fun retryLater(app: Context) {
        main.post {
            if (network == null) runCatching {
                val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: android.net.Network) { main.post { retryNow(app) } }
                }
                val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                if (android.os.Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(cb)
                else cm.registerNetworkCallback(android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
                network = cb
            }.onFailure { SsmTrace.log("speech: no network callback: $it") }
            main.removeCallbacks(retryTimed)
            retryTimed = Runnable { retryNow(app) }
            main.postDelayed(retryTimed, RETRY_MS)
        }
    }

    private var retryTimed = Runnable { }

    private fun retryNow(app: Context) {
        val on = kg.forester.sg.data.Repo.settings.voiceAlerts
        if (retry.due(on, failed.value, downloading, now())) prepare(app)
        else if (on && failed.value) { main.removeCallbacks(retryTimed); main.postDelayed(retryTimed, RETRY_MS) }
    }

    private fun noRetry(app: Context) {
        main.post {
            main.removeCallbacks(retryTimed)
            network?.let { cb -> runCatching { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(cb) } }
            network = null
        }
    }

    /**
     * Gets the voice onto the disk (in the background). It goes into memory only for a phrase (see [say]): 20–123 MB
     * of native memory for something said a few times a drive, on a head unit with 2 GB.
     */
    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        // English goes by the head unit's own voice ([say]): no Russian model to fetch for it
        if (kg.forester.sg.data.I18n.english) return
        val v = current
        if (v.engine == Engine.SYSTEM) { prepareSystem(app); return }
        if (downloading) return
        worker.execute {
            val w = current
            if (w.engine == Engine.SYSTEM) return@execute
            runCatching {
                if (!installed(app, w)) download(app, w)
                // the voice used before is not needed any more (20–123 MB each)
                File(app.filesDir, "tts").listFiles()?.filter { it.name != w.pack }?.forEach { it.deleteRecursively() }
                if (current.pack == w.pack) { status.value = "готов (${title(current)})"; failed.value = false; noRetry(app) }
            }.onFailure {
                SsmTrace.log("speech: $it")
                status.value = kg.forester.sg.data.Failure.waitingText(it, "голос")
                failed.value = true
                downloading = false
                unpacking = null
                if (kg.forester.sg.data.Failure.passes(it)) retryLater(app)
            }
            // picked another voice while this one was downloading: the Android one needs nothing loaded, another
            // downloaded voice comes next
            if (current.engine == Engine.SYSTEM) unload()
            else if (current.pack != w.pack) prepare(app)
        }
    }

    /** Another voice was picked: drop the loaded one and get the new one (a Supertonic speaker is just another sid). */
    fun switchVoice(ctx: Context) {
        val v = current
        if (v.engine != Engine.SYSTEM) system?.let { s -> system = null; systemReady = false; runCatching { s.shutdown() } }
        if (v.engine != Engine.SYSTEM && tts != null && loaded == v.pack) { status.value = "готов (${title(v)})"; return }
        worker.execute { unload() }
        status.value = ""
        prepare(ctx)
    }

    /** The head unit's own TTS engine, with whatever Russian voice it has. */
    private fun prepareSystem(app: Context) {
        if (system != null) {
            if (systemReady) status.value = "готов (${title(current)})"
            return
        }
        status.value = "подключаю голос Android…"
        system = TextToSpeech(app) { st ->
            val s = system ?: return@TextToSpeech
            val lang = if (st == TextToSpeech.SUCCESS) runCatching { s.setLanguage(locale()) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                else TextToSpeech.LANG_NOT_SUPPORTED
            systemReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
            runCatching {
                s.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
            val engine = runCatching { s.engines.firstOrNull { it.name == s.defaultEngine }?.label }.getOrNull()
            status.value = when {
                st != TextToSpeech.SUCCESS -> "на магнитоле нет синтеза речи Android"
                !systemReady -> "в голосе Android нет русского: установите Google TTS или RHVoice"
                else -> "готов (голос Android" + (engine?.let { ": $it" } ?: "") + ")"
            }
        }
    }

    /**
     * Not touched outside the [worker]: a phrase being made holds it, so the voice is let go only when nothing is
     * being said, and every phrase looks up the model when its turn comes rather than when it was asked for.
     */
    private fun unload() {
        tts?.let { old -> tts = null; loaded = ""; runCatching { old.release() } }
    }

    private val unloadIfIdle = Runnable {
        worker.execute {
            if (tts == null) return@execute
            if (idle.due(now())) { unload(); SsmTrace.log("speech: idle, voice unloaded") } else scheduleUnload()
        }
    }

    private fun scheduleUnload() {
        main.removeCallbacks(unloadIfIdle)
        main.postDelayed(unloadIfIdle, SpeechIdle.IDLE_MS)
    }

    /** Memory is short: an unused voice goes now, not in a minute. */
    fun trim() {
        worker.execute { if (tts != null && idle.due(now(), pressure = true)) unload() }
    }

    private fun load(ctx: Context, v: TtsVoice) {
        if (tts != null && loaded == v.pack) return
        val d = dir(ctx, v)
        val model = when (v.engine) {
            Engine.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(d, "duration_predictor.int8.onnx").absolutePath,
                    textEncoder = File(d, "text_encoder.int8.onnx").absolutePath,
                    vectorEstimator = File(d, "vector_estimator.int8.onnx").absolutePath,
                    vocoder = File(d, "vocoder.int8.onnx").absolutePath,
                    ttsJson = File(d, "tts.json").absolutePath,
                    unicodeIndexer = File(d, "unicode_indexer.bin").absolutePath,
                    voiceStyle = File(d, "voice.bin").absolutePath,
                ),
                numThreads = 1, debug = false, provider = "cpu",
            )
            else -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model(ctx, v)!!.absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    dataDir = File(d, "espeak-ng-data").absolutePath,
                ),
                numThreads = 1, debug = false, provider = "cpu",
            )
        }
        // one voice that finished downloading after another was picked may still be loaded
        unload()
        val t0 = now()
        tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = model))
        loaded = v.pack
        SsmTrace.log("speech: ${v.id} loaded in ${now() - t0} ms")
        scheduleUnload()
    }

    private fun download(ctx: Context, v: TtsVoice) {
        downloading = true
        unpacking = v.pack
        status.value = "скачиваю голос…"
        failed.value = false
        val tmp = File(ctx.cacheDir, "${v.pack}.tar.bz2")
        var url = URL(url(v.pack))
        var c: HttpURLConnection
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            val code = c.responseCode
            if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            if (code != 200) error("HTTP $code")
            break
        }
        val total = c.contentLength.toLong()
        c.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) status.value = "скачиваю голос: ${done * 100 / total} %"
                }
            }
        }
        status.value = "распаковываю голос…"
        val root = File(ctx.filesDir, "tts").apply { mkdirs() }
        File(root, v.pack).deleteRecursively()
        TarArchiveInputStream(BZip2CompressorInputStream(tmp.inputStream().buffered())).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val f = File(root, e.name).canonicalFile
                if (!f.path.startsWith(root.canonicalPath + File.separator)) continue // no "../" escapes
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { tar.copyTo(it) } }
            }
        }
        tmp.delete()
        downloading = false
        unpacking = null
    }

    /**
     * Says [text]; false if it can't be and the caller should fall back. The chosen voice is the normal one: out of
     * memory it loads, and if that takes over [SAY_WAIT_MS] the head unit's own voice says the phrase (the model goes
     * on loading for the next). A [level] of danger goes ahead of what is queued, by the head unit's voice if it can
     * speak (the model isn't touched for it), else by a beep at once and the model. [preview]: trying this very voice.
     */
    fun say(text: String, preview: Boolean = false, level: Alerts.Level = Alerts.Level.WARN): Boolean {
        val v = current
        val spoken = spoken(text)
        if (spoken.isBlank()) return true
        // the downloaded voices are Russian: English is the head unit's own voice, or not spoken (the caller beeps)
        if (kg.forester.sg.data.I18n.english) return Alerts.systemReady && bySystem(Phrase(spoken, level == Alerts.Level.DANGER, level != Alerts.Level.INFO))
        val danger = level == Alerts.Level.DANGER
        val beeps = level != Alerts.Level.INFO
        val p = Phrase(spoken, danger, beeps)
        if (v.engine == Engine.SYSTEM) {
            if (Alerts.systemReady) return bySystem(p)
            val s = system?.takeIf { systemReady } ?: return false
            val loud = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            return runCatching { s.speak(spoken, TextToSpeech.QUEUE_ADD, loud, "speech${said++}") == TextToSpeech.SUCCESS }.getOrDefault(false)
        }
        val app = appCtx ?: return false
        idle.touch(now())
        when (sayRoute(tts != null && loaded == v.pack, installed(app, v), Alerts.systemReady, preview, danger)) {
            SayRoute.CALLER -> return false
            SayRoute.SYSTEM_NOW -> if (!bySystem(p)) sayAnotherWay(p)
            SayRoute.BEEP_THEN_MODEL -> { Alerts.beep(); enqueue(app, p) }
            SayRoute.MODEL -> enqueue(app, p)
            SayRoute.MODEL_OR_SYSTEM_AFTER_WAIT -> {
                val waiting = Phrase(spoken, danger, beeps, systemWaiting = true)
                enqueue(app, waiting)
                main.postDelayed({
                    if (Alerts.systemReady && waiting.claim() && !bySystem(waiting)) sayAnotherWay(waiting)
                }, SAY_WAIT_MS)
            }
        }
        return true
    }

    /** The language the head unit's voice is asked for. */
    fun locale(): Locale = if (kg.forester.sg.data.I18n.english) Locale.ENGLISH else Locale("ru", "RU")

    /** [text] as the voice gets it, in the chosen language. */
    fun spoken(text: String): String =
        if (kg.forester.sg.data.I18n.english) SpeechText.prepareEnglish(kg.forester.sg.data.tr(text)) else SpeechText.prepare(text)

    /** By the head unit's voice, with the same care for the music as the model's; false if it won't take the phrase. */
    private fun bySystem(p: Phrase): Boolean {
        val turn = beginTurn()
        val queued = Alerts.systemSay(p.spoken, p.urgent, onEnd = { endTurn(turn) }, onFail = { sayAnotherWay(p) })
        if (!queued) endTurn(turn)
        return queued
    }

    /** The head unit's voice didn't say it: the model, if there is one, else a beep. */
    private fun sayAnotherWay(p: Phrase) {
        val app = appCtx
        if (app != null && !kg.forester.sg.data.I18n.english && current.engine != Engine.SYSTEM && installed(app)) enqueue(app, Phrase(p.spoken, p.urgent, p.beepIfLost))
        else if (p.beepIfLost) Alerts.beep()
    }

    private fun enqueue(app: Context, p: Phrase) {
        phrases.add(p, p.urgent)
        // a place held from now to the end of the phrase: music isn't played again under a voice loading or being made
        turns.begin()
        worker.execute {
            try { phrases.poll()?.let { makeAndPlay(app, it) } } finally { leaveTurn(btPath()) }
        }
    }

    /** On the [worker]: the voice's turn for the next phrase, unless the head unit's voice has said it meanwhile. */
    private fun makeAndPlay(app: Context, p: Phrase) {
        val v = current
        if (v.engine == Engine.SYSTEM) return
        var mine = false
        runCatching {
            if (p.taken) return
            load(app, v)
            mine = p.claim()
            if (!mine) return
            val t = tts ?: return
            val t0 = now()
            val audio = if (v.engine == Engine.SUPERTONIC)
                t.generateWithConfig(p.spoken, GenerationConfig(sid = v.sid, speed = 1.05f, numSteps = 5, extra = mapOf("lang" to "ru")))
            else t.generate(text = p.spoken, sid = 0, speed = 1.0f)
            SsmTrace.log("speech: ${p.spoken.length} chars in ${now() - t0} ms for ${audio.samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)} ms of sound")
            awaitSystemVoice()
            play(audio.samples, audio.sampleRate)
        }.onFailure {
            SsmTrace.log("speech say: $it")
            status.value = kg.forester.sg.data.Failure.text(it, "Голос не запустился")
            failed.value = true
            // nothing was said, and the caller has already been told it would be
            if ((mine || !p.systemWaiting) && p.beepIfLost) Alerts.beep()
        }
        idle.touch(now())
        scheduleUnload()
    }

    /** The two voices must not talk over each other: the phrase Android's said while the model loaded is let finish. */
    private fun awaitSystemVoice() {
        var left = 200 // 10 s
        while (left-- > 0 && Alerts.systemSpeaking()) Thread.sleep(50)
    }

    /**
     * The voice brought to full scale (the model speaks well below it), then for «громче» and «максимум»
     * compressed, so the quiet syllables come up too and nothing clips.
     */
    private fun loud(samples: FloatArray, level: Int): ShortArray {
        val peak = samples.maxOf { kotlin.math.abs(it) }.coerceAtLeast(0.05f)
        val k = when (level) { 0 -> 0f; 1 -> 1.8f; else -> 3f }
        val norm = if (k > 0f) kotlin.math.tanh(k) else 1f
        return ShortArray(samples.size) {
            val x = samples[it] / peak
            val y = if (k > 0f) kotlin.math.tanh(k * x) / norm else x
            (y.coerceIn(-1f, 1f) * 0.97f * 32767).toInt().toShort()
        }
    }

    /**
     * Plays as navigation guidance: the head unit lowers the music for it, as for a navigator's voice; at the
     * loudest setting the music pauses while it speaks.
     */
    private fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        val pcm = loud(samples, kg.forester.sg.data.Repo.settings.voiceLoud)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        val turn = beginTurn()
        // the focus goes back whatever happens: kept, the music would stay paused or quiet until the app is closed
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / rate + 200)
        } finally {
            track.release()
            endTurn(turn)
        }
    }

    /** A phrase in the air: the focus it asked for, and the Bluetooth path to look at afterwards. */
    private class Turn(val am: android.media.AudioManager?, val focus: Any?, val legacy: android.media.AudioManager.OnAudioFocusChangeListener, val bt: kg.forester.sg.service.audio.BtVoicePath?, val held: Boolean)

    private fun beginTurn(): Turn {
        val level = kg.forester.sg.data.Repo.settings.voiceLoud
        // ask the music to step back while speaking (like a navigator's voice), give it back afterwards. Never a
        // pause for FYT Bluetooth music: it doesn't come back after one, the phone stays paused (also before the
        // unit has named its source). BT music that still ends up paused is played again after the phrase
        val ctx = appCtx
        val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val bt = btPath()
        if (bt?.playingNow() == true) resumeBt = true
        turns.begin()
        val legacy = android.media.AudioManager.OnAudioFocusChangeListener { }
        // FYT's tuner and Bluetooth module play through the MCU, not Android's mixer: a duck can't lower them, and
        // the unit's service pauses its source on any focus loss (the radio stays off, the phone stays paused). So
        // no focus at all then: the voice is mixed over the source like the unit's own sounds, at the cost of the
        // source not being lowered under it.
        val unitSource = ctx != null && kg.forester.sg.service.audio.AudioPlatforms.current(ctx).ownSources &&
            Fyt.appId.value.let { it == 1 || it == 3 }
        if (unitSource) {
            SsmTrace.log("speech: no focus, the unit's source ${Fyt.appId.value} plays", keep = true)
            return Turn(am, null, legacy, bt, held = false)
        }
        // an Android player: ducked, or at the loudest setting paused for a moment, and back by itself afterwards
        val pause = level >= 2 && bt?.noPauseForVoice != true
        val gain = if (pause) android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        // Android 7.1 has no focus requests yet, only the old call with a listener
        val focus: Any? = if (android.os.Build.VERSION.SDK_INT >= 26) android.media.AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build() else null
        @Suppress("DEPRECATION")
        val got = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) am?.requestAudioFocus(focus as android.media.AudioFocusRequest)
            else am?.requestAudioFocus(legacy, android.media.AudioManager.STREAM_MUSIC, gain)
        }.getOrNull()
        SsmTrace.log("speech: focus ${if (pause) "transient" else "transient may duck"}, answer $got", keep = true)
        return Turn(am, focus, legacy, bt, held = true)
    }

    private fun endTurn(t: Turn) {
        @Suppress("DEPRECATION")
        runCatching {
            if (!t.held) Unit
            else if (android.os.Build.VERSION.SDK_INT >= 26) t.am?.abandonAudioFocusRequest(t.focus as android.media.AudioFocusRequest)
            else t.am?.abandonAudioFocus(t.legacy)
        }
        leaveTurn(t.bt)
    }

    private fun btPath() = appCtx?.let { kg.forester.sg.service.audio.AudioPlatforms.current(it).bt as? kg.forester.sg.service.audio.BtVoicePath }

    private fun leaveTurn(bt: kg.forester.sg.service.audio.BtVoicePath?) {
        if (turns.end() && resumeBt && bt != null) main.postDelayed({ resumeBtMusic(bt) }, 1500)
    }

    // BT music that played when a phrase began: some phones and units pause it for the voice and never resume
    @Volatile private var resumeBt = false
    private val turns = VoiceTurns()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** After the last phrase in a row: if the BT music it found playing is paused now, plays it again. */
    private fun resumeBtMusic(bt: kg.forester.sg.service.audio.BtVoicePath) {
        if (!resumeBt || turns.busy) return
        resumeBt = false
        if (bt.playingNow()) return
        SsmTrace.log("speech: bt music left paused by the voice, play", keep = true)
        runCatching { bt.resume() }
    }
}
