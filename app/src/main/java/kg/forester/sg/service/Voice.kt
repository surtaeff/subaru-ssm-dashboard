package kg.forester.sg.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import kg.forester.sg.data.Shortcut
import kg.forester.sg.data.Shortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

/** Screens the voice can open in our UI. */
enum class VoiceTarget { HOME, DRIVE, FUEL, GEARBOX, BATTERY, DTC, MEDIA }

/**
 * Offline voice control (Vosk, small Russian model downloaded on first use).
 * Always listening; reacts to "<wake word> <command>" or the wake word followed by a command within a few seconds.
 */
object Voice {
    // our GitHub copy first: alphacephei.com sits on Hetzner, which some Russian operators block (timeouts on every try)
    private val MODEL_URLS = listOf(
        "https://github.com/surtaeff/subaru-ssm-dashboard/releases/download/voice-model/vosk-model-small-ru-0.22.zip",
        "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
    )
    private const val MODEL_DIR = "vosk-ru"

    val status = MutableStateFlow("выключено")
    val lastHeard = MutableStateFlow("")
    val toast = MutableStateFlow<Pair<Long, String>?>(null)
    val navigate = MutableStateFlow<Pair<Long, VoiceTarget>?>(null)
    /** True from the moment the name is heard until the command is done: the screen shows the listening orb. */
    val listening = MutableStateFlow(false)
    /** Loudness of the microphone, 0..1, for the orb to move with the voice. */
    val level = MutableStateFlow(0f)
    /** What is being said right now (the recogniser's partial text), shown under the orb. */
    val partial = MutableStateFlow("")

    private var service: Thread? = null
    @Volatile private var running = false
    /** Bumped by every start and stop: a capture thread that outlived its stop (blocked in a read) quits on its own. */
    @Volatile private var gen = 0
    private var model: Model? = null
    private var armedUntil = 0L
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)
    private lateinit var app: Context
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun wakeWord(ctx: Context) = prefs(ctx).getString("wake", "форестер")!!
    fun setWakeWord(ctx: Context, w: String) = prefs(ctx).edit().putString("wake", w.trim().lowercase()).apply()

    /** Software microphone gain, 1..4. */
    fun gain(ctx: Context) = prefs(ctx).getFloat("gain", 2f)
    fun setGain(ctx: Context, g: Float) = prefs(ctx).edit().putFloat("gain", g).apply()

    /** true: plain MIC source (more sensitive on many head units), false: VOICE_RECOGNITION. */
    fun sensitive(ctx: Context) = prefs(ctx).getBoolean("micSource", true)
    fun setSensitive(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("micSource", v).apply()

    fun restart(ctx: Context) { stop(); start(ctx) }

    /** The driver's own phrases (Настройки, Голос, Свои команды). */
    val ownList = MutableStateFlow<List<OwnCommand>?>(null)

    fun own(ctx: Context): List<OwnCommand> = ownList.value ?: runCatching {
        val arr = org.json.JSONArray(prefs(ctx).getString("own", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> OwnCommand(o.getString("phrase"), o.getString("action")) } }
    }.getOrDefault(emptyList()).also { ownList.value = it }

    fun setOwn(ctx: Context, list: List<OwnCommand>) {
        ownList.value = list
        val arr = org.json.JSONArray()
        list.forEach { arr.put(JSONObject().put("phrase", it.phrase).put("action", it.action)) }
        prefs(ctx).edit().putString("own", arr.toString()).apply()
    }

    /** A command as if it was said (adb test hook, see MainActivity): no microphone or model needed. */
    /**
     * [text] as if it were said after the name: the orb shows it coming word by word, then it is carried out. For
     * trying the assistant where one can't speak (the settings' text field, adb's orbDemo).
     */
    fun rehearse(ctx: Context, text: String) {
        app = ctx.applicationContext
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val steps = 12 * words.size + 6
        main.removeCallbacks(stopListening)
        listening.value = true
        for (k in 0 until steps) main.postDelayed({
            level.value = (0.35 + 0.35 * kotlin.math.sin(k * 0.5) * kotlin.math.sin(k * 0.13)).toFloat().coerceIn(0f, 1f)
            partial.value = words.take(1 + k / 12).joinToString(" ")
        }, k * 100L)
        main.postDelayed({ level.value = 0f; runText(ctx, text) }, steps * 100L)
    }

    fun runText(ctx: Context, text: String) {
        app = ctx.applicationContext
        main.post {
            listening.value = true
            doneListening(text)
            execute(text)
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        if (on) start(ctx) else stop()
    }

    fun modelReady(ctx: Context) = File(ctx.filesDir, "$MODEL_DIR/am").exists() || File(ctx.filesDir, "$MODEL_DIR/conf").exists()

    fun start(ctx: Context) {
        app = ctx.applicationContext
        android.util.Log.i("Voice", "start: enabled=${enabled(app)} running=${service != null} model=${modelReady(app)}")
        if (!enabled(app) || service?.isAlive == true || !starting.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                if (!modelReady(app)) download(app)
                // switched off while the model was coming
                if (!enabled(app)) { starting.set(false); status.value = "выключено"; return@launch }
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val dir = File(app.filesDir, MODEL_DIR)
                // a folder left half-unpacked by an older version: fetch it again instead of failing on every start
                val m = model ?: runCatching { Model(dir.absolutePath) }.getOrElse {
                    dir.deleteRecursively()
                    throw IllegalStateException("модель повреждена, скачаю её заново", it)
                }.also { model = it }
                val rec = Recognizer(m, 16000f)
                val myGen = ++gen
                running = true
                service = Thread({ listenLoop(rec, myGen) }, "voice").also { it.start() }
                status.value = "слушаю «${wakeWord(app)}»"
                starting.set(false)
            }.onFailure {
                starting.set(false)
                android.util.Log.e("Voice", "start failed", it)
                status.value = if (enabled(app)) "ошибка: ${it.message}" else "выключено"
                service = null
            }
        }
    }

    fun stop() {
        running = false
        gen++
        val t = service
        service = null
        runCatching { t?.join(3000) }
        // a start still fetching the model goes on and shows its own progress; only an assistant really off says so
        if (!starting.get()) status.value = "выключено"
    }

    /**
     * Called every half minute by the service: switched on but not listening (the capture thread died, a start
     * failed, the system killed the microphone) means start again.
     */
    fun ensure(ctx: Context) {
        app = ctx.applicationContext
        if (enabled(app) && service?.isAlive != true && !starting.get()) {
            android.util.Log.i("Voice", "watchdog: not listening, starting again")
            service = null
            start(app)
        }
    }

    /**
     * With timeouts: on a head unit with a weak connection a plain openStream could hang for good, and the assistant
     * stayed "starting" forever, so neither the switch nor the watchdog could start it again.
     */
    private fun download(ctx: Context) {
        status.value = "скачиваю модель распознавания (45 МБ)…"
        android.util.Log.i("Voice", "downloading model")
        val tmp = File(ctx.cacheDir, "vosk.zip")
        var failure: java.io.IOException? = null
        for (url in MODEL_URLS) {
            try {
                fetch(ctx, url, tmp)
                failure = null
                break
            } catch (e: java.io.IOException) {
                android.util.Log.w("Voice", "model download from $url failed: $e")
                tmp.delete()
                if (!enabled(ctx)) throw e
                failure = e
            }
        }
        failure?.let { throw java.io.IOException("не удалось скачать модель (${it.message ?: "нет связи"}), попробую ещё раз через полминуты", it) }
        status.value = "распаковываю модель…"
        // unpacked aside and moved in at the end: a half-unpacked folder would look like a ready model
        val part = File(ctx.filesDir, "$MODEL_DIR.part").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(tmp.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                // strip the top-level "vosk-model-small-ru-0.22/" folder
                val rel = e.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val f = File(part, rel)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
                e = zip.nextEntry
            }
        }
        tmp.delete()
        val out = File(ctx.filesDir, MODEL_DIR).apply { deleteRecursively() }
        if (!part.renameTo(out)) error("не удалось распаковать модель")
    }

    /** One source into [tmp]; any failure (no connection, a cut-off file) is an IOException, so the next source is tried. */
    private fun fetch(ctx: Context, url: String, tmp: File) {
        val con = URL(url).openConnection().apply { connectTimeout = 20_000; readTimeout = 30_000 }
        val total = con.contentLengthLong
        con.getInputStream().use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                var shown = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    if (!enabled(ctx)) throw java.io.IOException("выключено")
                    out.write(buf, 0, n)
                    got += n
                    val pct = if (total > 0) (got * 100 / total).toInt() else -1
                    if (pct != shown) {
                        shown = pct
                        status.value = if (pct >= 0) "скачиваю модель распознавания: $pct %" else "скачиваю модель распознавания: ${got / 1_000_000} МБ"
                    }
                }
                if (total > 0 && got != total) throw java.io.IOException("модель скачалась не до конца")
            }
        }
    }

    /**
     * Listens until stopped. The microphone can be lost underneath: another assistant (Alice) takes it, the head
     * unit's audio service restarts. Then the read fails or, on Android 10+, gives pure zeros while the other app
     * records. Either way the capture is opened again a moment later, for as long as the voice is on.
     */
    private fun listenLoop(rec: Recognizer, myGen: Int) {
        try {
            while (running && gen == myGen) {
                val started = capture(rec, myGen)
                if (!running || gen != myGen) break
                // pure zeros: another app took the microphone (Android 10+ does that), or the input gives no sound at all
                // (an emulator with no host microphone, a muted mic); either way try again in a moment
                status.value = if (started) "микрофон молчит: его занял другой ассистент или он выключен, жду…" else "микрофон недоступен, пробую снова…"
                level.value = 0f
                Thread.sleep(2000)
            }
        } catch (_: InterruptedException) {
        } finally {
            rec.close()
        }
    }

    /** One capture, our own so we can boost quiet head-unit microphones and use the platform AGC / noise suppressor; false when it would not start. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun capture(rec: Recognizer, myGen: Int): Boolean {
        val rate = 16000
        val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val source = if (sensitive(app)) android.media.MediaRecorder.AudioSource.MIC else android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
        val ar = runCatching {
            android.media.AudioRecord(source, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        }.getOrNull() ?: return false
        if (ar.state != android.media.AudioRecord.STATE_INITIALIZED) { ar.release(); return false }
        val agc = runCatching { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(ar.audioSessionId)?.also { it.enabled = true } else null }.getOrNull()
        val ns = runCatching { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(ar.audioSessionId)?.also { it.enabled = true } else null }.getOrNull()
        val buf = ShortArray(2048)
        try {
            ar.startRecording()
            if (ar.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) return false
            status.value = "слушаю «${wakeWord(app)}»"
            var zerosSince = 0L
            while (running && gen == myGen) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) return true // the record died under us
                if (n == 0) continue
                // pure digital silence: the system gave the microphone to another app; a real one always hisses a little
                var sound = false
                for (i in 0 until n) if (buf[i].toInt() != 0) { sound = true; break }
                if (!sound) {
                    val now = SystemClock.elapsedRealtime()
                    if (zerosSince == 0L) zerosSince = now else if (now - zerosSince > 4000) return true
                    continue
                }
                zerosSince = 0L
                val g = gain(app)
                if (g != 1f) for (i in 0 until n) {
                    buf[i] = (buf[i] * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                // how loud: the orb follows the voice
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = kotlin.math.sqrt(sum / n) / 32768.0
                level.value = (kotlin.math.log10(rms.coerceAtLeast(1e-4)) / 2.5 + 1.6).toFloat().coerceIn(0f, 1f)
                if (rec.acceptWaveForm(buf, n)) handle(rec.result)
                else onPartial(rec.partialResult)
            }
            return true
        } catch (e: Exception) {
            status.value = "ошибка микрофона: ${e.message}"
            return true
        } finally {
            runCatching { ar.stop() }; ar.release(); agc?.release(); ns?.release()
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The name heard in the middle of a phrase: show that it listens before the phrase is over. */
    private fun onPartial(json: String?) {
        val text = runCatching { JSONObject(json ?: "").optString("partial") }.getOrDefault("").trim().lowercase()
        if (text.isEmpty()) return
        val words = text.split(" ").filter { it.isNotBlank() }
        val at = Commands.wakeEnd(words, wakeWord(app))
        val armed = SystemClock.elapsedRealtime() < armedUntil
        if (at >= 0 || armed) {
            main.post {
                listening.value = true
                partial.value = if (at >= 0) words.drop(at).joinToString(" ") else text
                main.removeCallbacks(stopListening)
                main.postDelayed(stopListening, 8000)
            }
        }
    }

    private val stopListening = Runnable { listening.value = false; partial.value = "" }

    /** The orb stays a moment after the command, with what was understood, then goes. */
    private fun doneListening(text: String) {
        partial.value = text
        main.removeCallbacks(stopListening)
        main.postDelayed(stopListening, 1400)
    }

    private fun handle(json: String?) = main.post { handleOnMain(json) }

    private fun handleOnMain(json: String?) {
        val text = runCatching { JSONObject(json ?: "").optString("text") }.getOrDefault("").trim().lowercase()
        if (text.isEmpty()) return
        lastHeard.value = text
        android.util.Log.i("Voice", "heard: $text")
        val words = text.split(" ").filter { it.isNotBlank() }
        // the name also when the recogniser splits it in two ("форе стер") or hears a known variant
        val wakeAt = Commands.wakeEnd(words, wakeWord(app))
        val now = SystemClock.elapsedRealtime()
        val command = when {
            wakeAt >= 0 -> words.drop(wakeAt).joinToString(" ")
            now < armedUntil -> text
            else -> return
        }
        if (command.isBlank()) {
            armedUntil = now + 6000
            listening.value = true
            partial.value = ""
            main.removeCallbacks(stopListening)
            main.postDelayed(stopListening, 6000)
            beep()
            say("Слушаю…")
            return
        }
        armedUntil = 0
        listening.value = true
        doneListening(command)
        execute(command)
    }

    /** "Поехали" without a place: the next phrase (within 8 s, no name needed) is where to. */
    private var awaitingDestination = false

    private fun execute(cmd: String, ownAllowed: Boolean = true) {
        // the driver's own phrase: its commands one after another, a moment apart for the answers to be said
        if (ownAllowed && !awaitingDestination) Commands.ownFor(cmd, own(app))?.let { (o, steps) ->
            android.util.Log.i("Voice", "own: ${o.phrase} → $steps")
            steps.forEachIndexed { i, step -> main.postDelayed({ execute(step, ownAllowed = false) }, i * 1800L) }
            return
        }
        if (awaitingDestination) {
            awaitingDestination = false
            val c = Commands.parse(cmd)
            if (c?.intent == VoiceIntent.CANCEL) { say("Хорошо"); return }
            val dest = c?.takeIf { it.intent.name.startsWith("NAV_") && it.query != null }?.query ?: cmd
            say("Ищу: $dest")
            Nav.search(app, Nav.pkg(app, c?.app), dest, route = true)
            return
        }
        // the driver's own buttons first, when the whole command is their name ("навигатор", "открой музыку");
        // their own voice phrase may stand anywhere in the command
        shortcutFor(cmd)?.let { say(it.label); Shortcuts.launch(app, it); return }
        val c = Commands.parse(cmd) ?: run { say("Не понял: $cmd"); return }
        android.util.Log.i("Voice", "command: $c")
        run(c)
    }

    /** How the assistant takes [step] (a button's name or an ordinary command), null if not at all: for the own commands editor. */
    fun describe(step: String): String? =
        shortcutFor(step)?.let { "кнопка «${it.label}»" } ?: Commands.parse(step)?.let { Commands.describe(it) }

    private val OPEN_WORDS = setOf("открой", "запусти", "покажи", "включи")

    private fun shortcutFor(cmd: String): Shortcut? {
        val cw = cmd.lowercase().split(" ").filter { it.isNotBlank() && it !in OPEN_WORDS }
        return Shortcuts.list.value.firstOrNull { s ->
            val lw = s.label.lowercase().split(" ").filter { it.isNotBlank() }
            (lw.size == cw.size && lw.indices.all { similar(cw[it], lw[it]) }) ||
                (s.phrase.isNotBlank() && phraseMatches(cmd.lowercase(), s.phrase.lowercase()))
        }
    }

    private fun run(c: VoiceCommand) {
        when (c.intent) {
            // --- music: the head unit's own player / radio / BT get FYT commands; anything else playing (Yandex
            // Music, Spotify, VK…) gets the standard media keys, which go to the app that plays now
            VoiceIntent.NEXT, VoiceIntent.DISLIKE -> { if (fytPlaying()) Fyt.next() else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, ""); say("Следующий") }
            VoiceIntent.PREV -> { if (fytPlaying()) Fyt.prev() else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ""); say("Предыдущий") }
            VoiceIntent.PAUSE -> {
                if (Fyt.music.value.playing) Fyt.playPause() else if (fytPlaying()) Fyt.mute(true) else mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "")
                say("Пауза")
            }
            VoiceIntent.PLAY -> {
                if (Fyt.present(app) && !otherAppPlaying()) {
                    if (!Fyt.music.value.playing) Fyt.playPause()
                    Fyt.mute(false)
                    say("Играю")
                } else playAnything()
            }
            VoiceIntent.RESTART -> {
                MediaSessions.controller(app)?.transportControls?.seekTo(0) ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "")
                say("Сначала")
            }
            VoiceIntent.REWIND, VoiceIntent.FORWARD -> {
                val sign = if (c.intent == VoiceIntent.FORWARD) 1 else -1
                val ctl = MediaSessions.controller(app)
                val pos = ctl?.playbackState?.position
                if (ctl != null && pos != null) ctl.transportControls.seekTo((pos + sign * (c.number ?: 15) * 1000L).coerceAtLeast(0))
                else mediaKey(if (sign > 0) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND, "")
                say(if (sign > 0) "Вперёд" else "Назад")
            }
            VoiceIntent.SHUFFLE_ON, VoiceIntent.SHUFFLE_OFF ->
                say(if (MediaSessions.shuffle(app, c.intent == VoiceIntent.SHUFFLE_ON)) (if (c.intent == VoiceIntent.SHUFFLE_ON) "Перемешиваю" else "По порядку") else needSessions())
            VoiceIntent.REPEAT_ON, VoiceIntent.REPEAT_OFF -> {
                val mode = if (c.intent == VoiceIntent.REPEAT_OFF) 0 else if (c.target == "one") 1 else 2
                say(if (MediaSessions.repeat(app, mode)) (if (mode == 0) "Без повтора" else "Повтор") else needSessions())
            }
            VoiceIntent.LIKE -> {
                val ctl = MediaSessions.controller(app)
                if (ctl != null) { ctl.transportControls.setRating(android.media.Rating.newHeartRating(true)); say("Нравится") } else say(needSessions())
            }
            VoiceIntent.WHAT_PLAYING -> speak(
                when {
                    Fyt.appId.value == 1 && fytPlaying() -> Fyt.radio.value.let { r -> r.name.ifBlank { r.freqText + " " + r.bandText } }
                    Fyt.music.value.playing -> listOf(Fyt.music.value.artist, Fyt.music.value.title).filter { it.isNotBlank() }.joinToString(" — ")
                    else -> MediaSessions.nowPlaying(app) ?: if (MediaSessions.allowed(app)) "Ничего не играет" else needSessions()
                },
            )
            VoiceIntent.PLAY_QUERY -> playFromSearch(c)
            VoiceIntent.MEDIA_APP -> c.app?.let { openApp(it) } ?: say("Не нашёл приложение")
            VoiceIntent.SOURCE_BT -> { BtAudio.enter(app); say("Bluetooth") }
            VoiceIntent.SOURCE_USB -> { Fyt.source(8); Fyt.openMusic(); say("Музыка") }
            // --- volume
            VoiceIntent.VOLUME_UP -> volume(c.number ?: 2)
            VoiceIntent.VOLUME_DOWN -> volume(-(c.number ?: 2))
            VoiceIntent.VOLUME_SET -> setVolume(c.number ?: 10, c.percent)
            VoiceIntent.MUTE, VoiceIntent.UNMUTE -> muteMusic(c.intent == VoiceIntent.MUTE)
            // --- head unit radio
            VoiceIntent.RADIO -> { Fyt.ensureRadio(); go(VoiceTarget.MEDIA, "Радио") }
            // the driver's own stations (FmRadio) first, tuned by frequency; the stock presets only without them
            VoiceIntent.RADIO_NEXT, VoiceIntent.RADIO_PREV -> {
                FmRadio.load(app); FmRadio.step(c.intent == VoiceIntent.RADIO_NEXT)
                say(if (c.intent == VoiceIntent.RADIO_NEXT) "Следующая станция" else "Предыдущая станция")
            }
            VoiceIntent.RADIO_SEEK -> { Fyt.radioSeek((c.number ?: 1) > 0); say("Ищу станцию") }
            VoiceIntent.RADIO_BAND -> { Fyt.radioBand(when (c.target) { "AM" -> 0; "FM" -> 65536; else -> -1 }); say("Диапазон") }
            VoiceIntent.RADIO_FREQ, VoiceIntent.RADIO_PRESET, VoiceIntent.RADIO_NAME -> {
                FmRadio.load(app)
                val st: FmRadio.Station? = when (c.intent) {
                    VoiceIntent.RADIO_FREQ -> c.freq?.let { f -> Math.round(f * 100).toInt().takeIf { it in 8750..10800 }?.let { FmRadio.Station(it, "") } }
                    VoiceIntent.RADIO_PRESET -> c.number?.let { FmRadio.byNumber(it) }
                    else -> c.query?.let { q ->
                        FmRadio.byName(q) ?: RadioLogos.allNamed(app, RadioLogos.country(app)).entries
                            .firstOrNull { it.value.name.lowercase().replace('ё', 'е').contains(q.replace('ё', 'е')) }
                            ?.let { e -> e.key.toDoubleOrNull()?.let { FmRadio.Station(Math.round(it * 100).toInt(), e.value.name) } }
                    }
                }
                when {
                    st != null && Fyt.present(app) -> {
                        FmRadio.tune(st.freq, st.band); go(VoiceTarget.MEDIA, "Радио")
                        say(st.name.ifBlank { String.format(java.util.Locale.US, "%.1f", st.freq / 100.0) })
                    }
                    // a number past the own stations: the stock radio's slot
                    c.intent == VoiceIntent.RADIO_PRESET && (c.number ?: 0) in 1..6 && Fyt.present(app) -> {
                        FmRadio.toPreset(c.number!!); go(VoiceTarget.MEDIA, "Радио"); say("Станция ${c.number}")
                    }
                    else -> {
                        Fyt.ensureRadio(); go(VoiceTarget.MEDIA, "Радио")
                        speak(if (c.intent == VoiceIntent.RADIO_NAME) "Такой станции нет в ваших: сохраните её в Медиа, Радио" else "Не нашёл такую станцию")
                    }
                }
            }
            // --- navigation
            VoiceIntent.NAV_ROUTE -> {
                val q = c.query
                if (q == null) { awaitingDestination = true; armedUntil = SystemClock.elapsedRealtime() + 8000; beep(); say("Куда едем?") }
                else {
                    say("Ищу: $q" + (c.via?.let { " (через $it — добавьте в навигаторе)" } ?: ""))
                    Nav.search(app, Nav.pkg(app, c.app), q, route = true)
                }
            }
            VoiceIntent.NAV_SEARCH, VoiceIntent.NAV_NEARBY -> {
                val q = c.query ?: c.target ?: return say("Что найти?")
                say("Ищу: $q")
                Nav.search(app, Nav.pkg(app, c.app), q, route = false)
            }
            VoiceIntent.NAV_HOME, VoiceIntent.NAV_WORK -> {
                val key = if (c.intent == VoiceIntent.NAV_HOME) "home" else "work"
                val p = prefs(app)
                val lat = p.getFloat("${key}Lat", Float.NaN); val lon = p.getFloat("${key}Lon", Float.NaN)
                val pkg = Nav.pkg(app, c.app)
                when {
                    !lat.isNaN() && !lon.isNaN() -> { say(if (key == "home") "Едем домой" else "Едем на работу"); Nav.routeTo(app, pkg, lat.toDouble(), lon.toDouble()) }
                    else -> speak("Сначала скажите «запомни это место как ${if (key == "home") "дом" else "работу"}», когда будете там")
                }
            }
            VoiceIntent.NAV_SAVE_PLACE -> {
                val g = kg.forester.sg.data.Repo.gps.value
                val key = if (c.target == "work") "work" else "home"
                if (g?.lat == null || g.lon == null) speak("Нет координат GPS")
                else {
                    prefs(app).edit().putFloat("${key}Lat", g.lat.toFloat()).putFloat("${key}Lon", g.lon.toFloat()).apply()
                    speak(if (key == "home") "Запомнил дом" else "Запомнил работу")
                }
            }
            VoiceIntent.NAV_TRAFFIC -> { Nav.traffic(app, Nav.pkg(app, c.app)); say("Пробки") }
            VoiceIntent.NAV_OPEN -> { c.app?.let { openApp(it) } ?: kg.forester.sg.ui.openNavigator(app); say("Навигатор") }
            // no navigator takes these from outside: open it and let its own assistant do it
            VoiceIntent.NAV_CANCEL, VoiceIntent.NAV_ETA, VoiceIntent.NAV_SOUND_OFF, VoiceIntent.NAV_SOUND_ON -> {
                kg.forester.sg.ui.openNavigator(app)
                speak("Скажите навигатору: «Слушай, Алиса, " + when (c.intent) {
                    VoiceIntent.NAV_CANCEL -> "отмени маршрут»"
                    VoiceIntent.NAV_ETA -> "сколько ехать»"
                    VoiceIntent.NAV_SOUND_OFF -> "выключи звук»"
                    else -> "включи звук»"
                })
            }
            VoiceIntent.NAV_WHERE -> whereAmI()
            // --- our app
            VoiceIntent.SCREEN -> runCatching { VoiceTarget.valueOf(c.target ?: "") }.getOrNull()?.let { go(it, screenName(it)) } ?: say("Не понял: ${c.text}")
            VoiceIntent.FULL_SCREEN -> {
                if (c.target == "nav") Shortcuts.list.value.firstOrNull { it.icon == "navigation" }?.let { Shortcuts.launch(app, it.copy(split = false)) }
                else Shortcuts.exitSplit(app)
                say("Весь экран")
            }
            VoiceIntent.READ_VALUE -> readValue(c.target ?: "")
            VoiceIntent.APP_OPEN -> {
                val q = c.query.orEmpty()
                val a = Shortcuts.installedApps(app).firstOrNull { similar(it.label.lowercase(), q) || it.label.lowercase().startsWith(q) }
                if (a != null) { say(a.label); openApp(a.pkg) } else say("Не нашёл приложение «$q»")
            }
            VoiceIntent.CANCEL -> say("Хорошо")
            VoiceIntent.HELP -> speak("Например: поехали домой, громче, следующая, найди заправку, какая температура")
            VoiceIntent.UNSUPPORTED -> speak("Это я пока не умею")
        }
    }

    private fun screenName(t: VoiceTarget) = when (t) {
        VoiceTarget.HOME -> "Главная"; VoiceTarget.DRIVE -> "Динамика"; VoiceTarget.FUEL -> "Расход"
        VoiceTarget.GEARBOX -> "АКПП"; VoiceTarget.BATTERY -> "Аккумулятор"; VoiceTarget.DTC -> "Ошибки"; VoiceTarget.MEDIA -> "Медиа"
    }

    private fun needSessions() = "Разрешите приложению доступ к уведомлениям: Настройки → Голос"

    /** Answers that matter (values, what plays) are spoken too, not only shown. */
    private fun speak(t: String) { say(t); Speech.say(t) }

    private fun openApp(pkg: String) {
        Shortcuts.list.value.firstOrNull { it.pkg == pkg }?.let { Shortcuts.launch(app, it); return }
        app.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            ?: say("Приложение не установлено")
    }

    private val MUSIC_APPS = listOf("ru.yandex.music", "com.uma.musicvk", "com.spotify.music", "com.google.android.apps.youtube.music")

    /** "Включи группу Кино": the standard play-from-search intent of the music app, else its search screen. */
    private fun playFromSearch(c: VoiceCommand) {
        val q = c.query.orEmpty()
        if (q.isBlank()) return run(VoiceCommand(VoiceIntent.PLAY))
        // YouTube: its search with the results, the app has no "play from search" for videos
        if (c.app == "com.google.android.youtube") {
            say("Ищу на ютубе: $q")
            val search = Intent(Intent.ACTION_SEARCH).setPackage(c.app).putExtra("query", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(search) }.isSuccess) return
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(Intent(web).setPackage(c.app)) }.isSuccess || runCatching { app.startActivity(web) }.isSuccess) return
            say("Не нашёл ютуб на магнитоле")
            return
        }
        val pkg = c.app ?: Shortcuts.list.value.firstOrNull { it.pkg in MUSIC_APPS }?.pkg ?: MUSIC_APPS.firstOrNull { Shortcuts.isInstalled(app, it) }
        val i = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(android.app.SearchManager.QUERY, q)
        val ms = android.provider.MediaStore.Audio::class
        when (c.focus) {
            MediaFocus.ARTIST -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE).putExtra(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, q)
            MediaFocus.ALBUM -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE).putExtra(android.provider.MediaStore.EXTRA_MEDIA_ALBUM, q)
            MediaFocus.TRACK -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio").putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, q)
            MediaFocus.GENRE -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE).putExtra("android.intent.extra.genre", q)
            MediaFocus.PLAYLIST, MediaFocus.FAVORITES -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE).putExtra("android.intent.extra.playlist", q)
            else -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        if (pkg != null) i.setPackage(pkg)
        say("Ищу: $q")
        if (i.resolveActivity(app.packageManager) != null && runCatching { app.startActivity(i) }.isSuccess) return
        val ctl = MediaSessions.controller(app)?.takeIf { pkg == null || it.packageName == pkg }
        if (ctl != null) { ctl.transportControls.playFromSearch(q, android.os.Bundle()); return }
        val web = when (pkg) {
            "com.spotify.music" -> "spotify:search:" + Uri.encode(q)
            "ru.yandex.music" -> "https://music.yandex.ru/search?text=" + Uri.encode(q)
            else -> null
        }
        if (web != null) runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        else say("Не нашёл, чем включить «$q»")
    }

    /** Volume to [n] steps (or [percent] of the maximum): stepping the MCU on FYT units, Android's volume elsewhere. */
    private fun setVolume(n: Int, percent: Boolean) {
        val cur = Fyt.volume.value
        if (Fyt.present(app) && cur >= 0) {
            val target = if (percent) Math.round(40 * n / 100f) else n // FYT volume goes 0…40
            Fyt.volumeStep(target - cur)
            say("Громкость $target")
            return
        }
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (if (percent) Math.round(max * n / 100f) else n).coerceIn(0, max)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
        volumeTrace("set $n${if (percent) "%" else ""}", before, am)
        say("Громкость $v")
    }

    private fun muteMusic(on: Boolean) {
        say(if (on) "Без звука" else "Звук включён")
        if (Fyt.mute(on)) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
    }

    /** "Какая температура", "сколько бензина"…: the value spoken. */
    private fun readValue(t: String) {
        val e = kg.forester.sg.data.Repo.live.value
        val trip = kg.forester.sg.data.Repo.trip.value
        val ru = java.util.Locale("ru")
        // the unit agreeing with the number (91 градус, 92 градуса, 95 градусов), so the screen and the voice say it right
        fun n(x: Number?, vararg forms: String) = x?.let {
            val v = Math.round(it.toDouble())
            "$v ${forms.getOrElse(SpeechText.plural(kotlin.math.abs(v))) { forms.lastOrNull() ?: "" }}".trim()
        } ?: "нет данных"
        val deg = arrayOf("градус", "градуса", "градусов")
        speak(when (t) {
            "COOLANT" -> "Двигатель ${n(e.coolantC, *deg)}"
            "INTAKE", "OUTSIDE" -> "Воздух на впуске ${n(e.intakeC, *deg)}"
            "ATF" -> "Коробка ${n(kg.forester.sg.data.Repo.tcu.value?.atfC, *deg)}"
            // most EJs have no oil sensor: the app's estimate, said as one
            "OIL" -> kg.forester.sg.data.Repo.guard.value.let { g ->
                g.oilC?.let { "Масло ${if (g.oilEstimated) "примерно " else ""}${n(it, *deg)}" } ?: "Нет данных о масле"
            }
            "OILP" -> kg.forester.sg.data.SensorBox.role("oilP")?.let { "Давление масла ${String.format(ru, "%.1f", it)} бар" }
                ?: "Датчика давления масла нет"
            "BATTERY" -> "Напряжение ${e.batteryV?.let { String.format(ru, "%.1f", it) } ?: "нет данных"} вольт"
            "SPEED" -> "Скорость ${n(e.speedKmh, "километр в час", "километра в час", "километров в час")}"
            "RPM" -> "Обороты ${n(e.rpm, "")}"
            "BOOST" -> "Наддув ${e.boostBar?.let { String.format(ru, "%.2f", it) } ?: "нет данных"} бар"
            "FUEL" -> trip.fuelLeftL?.let { "В баке ${n(it, "литр", "литра", "литров")}" } ?: e.fuelLevelPct?.let { "В баке ${n(it, "процент", "процента", "процентов")}" } ?: "Нет данных о топливе: отметьте заправку"
            "RANGE" -> "Хватит на ${n(trip.rangeKm, "километр", "километра", "километров")}"
            "CONSUMPTION" -> "Расход ${trip.trip?.per100?.let { String.format(ru, "%.1f", it) } ?: "нет данных"} литров на сотню"
            "TRIP" -> "Проехали ${n(trip.trip?.distanceKm, "километр", "километра", "километров")}"
            "TIME" -> java.text.SimpleDateFormat("H:mm", ru).format(java.util.Date())
            "VOLUME" -> "Громкость ${Fyt.volume.value.takeIf { it >= 0 } ?: (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)}"
            "DTC" -> "Открываю ошибки".also { go(VoiceTarget.DTC, "Ошибки") }
            else -> "Не знаю"
        })
    }

    /** Street and city by GPS (needs the network for the address), else the point in the navigator. */
    private fun whereAmI() {
        val g = kg.forester.sg.data.Repo.gps.value
        if (g?.lat == null || g.lon == null) return speak("Нет координат GPS")
        scope.launch {
            val a = runCatching {
                @Suppress("DEPRECATION")
                android.location.Geocoder(app, java.util.Locale("ru")).getFromLocation(g.lat, g.lon, 1)?.firstOrNull()
            }.getOrNull()
            val text = a?.let { listOfNotNull(it.thoroughfare, it.subThoroughfare, it.locality).joinToString(", ") }?.ifBlank { null }
            main.post { if (text != null) speak(text) else Nav.routeTo(app, Nav.pkg(app, null), g.lat, g.lon) }
        }
    }

    private fun go(t: VoiceTarget, label: String) {
        say(label)
        navigate.value = SystemClock.elapsedRealtime() to t
        val i = app.packageManager.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i != null) runCatching { app.startActivity(i) }
    }

    /**
     * On FYT units (Teyes, many FLY and others) the volume is the MCU's, like the knob: Android's stream volume
     * changes nothing there. Elsewhere the music stream, and if that didn't move, the stream the system picks.
     */
    private fun volume(steps: Int) {
        say(if (steps > 0) "Громче" else "Тише")
        if (Fyt.volumeStep(steps)) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        repeat(kotlin.math.abs(steps)) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == before) {
            repeat(kotlin.math.abs(steps)) { am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI) }
        }
        volumeTrace("step $steps", before, am)
    }

    /**
     * Android's music volume was changed (no FYT sound module): which unit and what the stream did, for the log.
     * Units that keep the volume in their own processor (FYT without the module, Microntek) move the stream and not the sound.
     */
    private fun volumeTrace(what: String, before: Int, am: AudioManager) {
        kg.forester.sg.ssm.SsmTrace.log(
            "voice volume $what: android stream $before → ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, " +
                "fyt=${Fyt.present(app)} sound=${Fyt.soundReady()} fytVol=${Fyt.volume.value} microntek=${BtAudio.microntek(app)} " +
                "music=${am.isMusicActive} ${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}/${android.os.Build.HARDWARE}",
            keep = true,
        )
    }

    /** The head unit's own music, radio or Bluetooth audio is what plays now. */
    private fun fytPlaying(): Boolean {
        if (!Fyt.present(app)) return false
        val androidMusic = (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
        // FYT radio plays past Android's audio: it counts only while no app plays music
        return Fyt.music.value.playing || (Fyt.appId.value == 1 && !androidMusic)
    }

    /** Some other app holds the music stream (Fyt's player is silent). */
    /**
     * «Включи музыку» on a unit without the FYT player (or with another app's music on): what plays goes on; else
     * the app's own player (where it stopped, or the folder played last), else another player paused in the
     * background, else the media screen to pick something. A bare media key reached nobody when nothing had
     * played since the start, and «Играю» was said over silence.
     */
    private fun playAnything() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Fyt.mute(false)
        if (am.isMusicActive) { say("Уже играет"); return }
        if (runCatching { LocalMusic.resume(app) }.getOrDefault(false)) { say("Играю"); return }
        val other = MediaSessions.controller(app)?.takeIf { it.packageName != app.packageName }
        if (other != null) { other.transportControls.play(); say("Играю"); return }
        go(VoiceTarget.MEDIA, "Выберите, что включить")
    }

    private fun otherAppPlaying() = (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive && !Fyt.music.value.playing

    private fun mediaKey(code: Int, label: String) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        say(label)
    }

    private fun say(text: String) {
        toast.value = SystemClock.elapsedRealtime() to text
    }

    private fun beep() {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).startTone(ToneGenerator.TONE_PROP_BEEP2, 150) }
    }

    private fun phraseMatches(cmd: String, phrase: String): Boolean {
        val pw = phrase.split(" ").filter { it.isNotBlank() }
        val cw = cmd.split(" ")
        return pw.all { p -> cw.any { similar(it, p) } }
    }

    /** Fuzzy word match: tolerates the recogniser's spelling of unusual words (e.g. brand names). */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 3 || b.length < 3) return false
        val d = levenshtein(a, b)
        return d <= maxOf(1, b.length / 4) || (b.length >= 5 && (a.startsWith(b.take(4)) || b.startsWith(a.take(4))))
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
