package kg.forester.sg.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale

/*
 * Talks to the FYT/Teyes system service com.syu.ms over its private binder protocol (com.syu.ipc), and receives
 * now-playing pushes from the stock player. See research/fyt_ipc.md for the protocol notes.
 */

data class MusicState(
    val source: String = "",           // "music" (stock player) or "bt"
    val title: String = "",
    val artist: String = "",
    val art: Bitmap? = null,           // BT: only when the phone sends a cover (AVRCP cover art is often missing)
    val playing: Boolean = false,
    val posMs: Long = 0,
    val totalMs: Long = 0,
    val updatedMs: Long = 0,
)

data class RadioState(
    val band: Int = -1,                // 65536..65538 FM1..3, 0/1 AM
    val freq: Int = 0,                 // FM in 10 kHz units, AM in kHz
    val preset: Int = 0,
    val name: String = "",
    val logo: Bitmap? = null,
) {
    val isFm get() = band >= 65536
    val freqText: String get() = if (freq == 0) "—" else if (isFm || band == -1) String.format(Locale.US, "%.2f", freq / 100.0) else "$freq"
    val bandText: String get() = when (band) { 0 -> "AM1"; 1 -> "AM2"; 65536 -> "FM1"; 65537 -> "FM2"; 65538 -> "FM3"; else -> "FM" }
}

/** Receives `com.fyt.launcher.music` from com.syu.music (sent to the default launcher about once a second). */
class FytMusicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.extras?.let { e ->
            val playing = e.getBoolean("play_state", false)
            // the stock player keeps pushing while paused: with BT as the source it must not take BT's place
            if (Fyt.appId.value == 3 && !playing) return@let
            Fyt.music.value = MusicState(
                source = "music",
                title = e.getString("title").orEmpty(),
                artist = e.getString("play_artist").orEmpty(),
                playing = playing,
                posMs = e.getLong("play_cur", 0),
                totalMs = e.getLong("play_total", 0),
                updatedMs = System.currentTimeMillis(),
            )
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

object Fyt {
    /** True on FYT-platform head units (Teyes and other UIS7862/… units): the system service is installed. */
    fun present(ctx: android.content.Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo("com.syu.ms", 0); true }.getOrDefault(false)

    private const val D_TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    private const val D_MODULE = "com.syu.ipc.IRemoteModule"
    private const val D_CALLBACK = "com.syu.ipc.IModuleCallback"
    private const val M_MAIN = 0
    private const val M_RADIO = 1
    private const val M_BT = 2
    /** Sound module: the master volume lives in the MCU, Android's stream volume doesn't move it on these units. */
    private const val M_SOUND = 4
    private const val TAG = "Fyt"

    val music = MutableStateFlow(MusicState())
    /** The phone's music over the unit's Bluetooth on its own ([music] is whichever pushed last). */
    val btMusic = MutableStateFlow(MusicState(source = "bt"))
    val radio = MutableStateFlow(RadioState())
    /** sys.fyt.radio_type: "1" the MCU's tuner, "3" / "4" one the stock radio app drives (it must be running), "2" none. */
    @Volatile var radioType = ""
    val appId = MutableStateFlow(0) // current audio source: 1 radio, 3 BT music, 8 music player
    /** Master volume as the MCU reports it (update U_VOL = 2), -1 = unknown. */
    val volume = MutableStateFlow(-1)

    private var main: IBinder? = null
    private var radioModule: IBinder? = null
    private var bt: IBinder? = null
    private var sound: IBinder? = null
    private var app: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stations: Map<String, Pair<String, String>> = emptyMap() // "102.90" -> (id, name)

    // --- binder plumbing -------------------------------------------------------------------------

    private abstract class Callback : Binder() {
        init { attachInterface(null, D_CALLBACK) }
        abstract fun onUpdate(code: Int, ints: IntArray?, strs: Array<String?>?)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                data.enforceInterface(D_CALLBACK)
                val c = data.readInt(); val i = data.createIntArray(); data.createFloatArray(); val s = data.createStringArray()
                runCatching { onUpdate(c, i, s) }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun module(toolkit: IBinder, code: Int): IBinder? {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(D_TOOLKIT); d.writeInt(code)
            toolkit.transact(1, d, r, 0); r.readException(); r.readStrongBinder()
        } catch (e: Exception) { null } finally { d.recycle(); r.recycle() }
    }

    private fun cmd(m: IBinder?, code: Int, ints: IntArray? = null) {
        val b = m ?: return
        val d = Parcel.obtain()
        try {
            d.writeInterfaceToken(D_MODULE); d.writeInt(code)
            d.writeIntArray(ints); d.writeFloatArray(null); d.writeStringArray(null)
            b.transact(1, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally { d.recycle() }
    }

    private fun register(m: IBinder, cb: Callback, update: Int) {
        val d = Parcel.obtain()
        try {
            d.writeInterfaceToken(D_MODULE); d.writeStrongBinder(cb); d.writeInt(update); d.writeInt(1)
            m.transact(3, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally { d.recycle() }
    }

    private val mainCb = object : Callback() {
        override fun onUpdate(code: Int, ints: IntArray?, strs: Array<String?>?) {
            if (code == 0) appId.value = (ints?.getOrNull(0) ?: 0).also { trace("source $it") }
        }
    }

    private val radioCb = object : Callback() {
        override fun onUpdate(code: Int, ints: IntArray?, strs: Array<String?>?) {
            val v = ints?.getOrNull(0) ?: return
            trace("radio update $code = $v")
            val r = radio.value
            radio.value = when (code) {
                0 -> r.copy(band = v)
                1 -> withStation(r.copy(freq = freqUnits(v)))
                // an MCU tuner tells the preset's index, FM ones from 65536 (P1 of the band = 65536); the radio app 1..6
                3 -> r.copy(preset = if (v >= 65536) (v - 65536) % 6 + 1 else v)
                else -> r
            }
        }
    }

    private val soundCb = object : Callback() {
        override fun onUpdate(code: Int, ints: IntArray?, strs: Array<String?>?) {
            if (code == 2) volume.value = ints?.getOrNull(0) ?: -1
        }
    }

    private var btState = MusicState(source = "bt")
    /** The other BT module updates as last seen, so the log shows each change once (which code holds what). */
    private val btSeen = HashMap<Int, String>()

    private val btCb = object : Callback() {
        override fun onUpdate(code: Int, ints: IntArray?, strs: Array<String?>?) {
            val m = btState
            val next = when (code) {
                0 -> m.copy(title = strs?.getOrNull(0).orEmpty())
                1 -> m.copy(artist = strs?.getOrNull(0).orEmpty())
                2 -> m.copy(totalMs = (ints?.getOrNull(0) ?: 0).toLong())
                13 -> m.copy(playing = ints?.getOrNull(0) == 1)
                28 -> m.copy(posMs = (ints?.getOrNull(0) ?: 0).toLong())
                else -> {
                    val v = "ints=${ints?.joinToString()} strs=${strs?.joinToString()}"
                    if (synchronized(btSeen) { btSeen.put(code, v) } != v) Log.i(TAG, "bt update $code (unused): $v")
                    return
                }
            }
            if (code != 28) Log.i(TAG, "bt update $code: playing=${next.playing} title=${next.title} artist=${next.artist}")
            setBt(next)
        }
    }

    private fun setBt(next: MusicState) {
        btState = next.copy(updatedMs = System.currentTimeMillis())
        btMusic.value = btState
        // show BT only when it actually has something and the stock player isn't the fresher source
        val stockFresh = music.value.source == "music" && System.currentTimeMillis() - music.value.updatedMs < 5000
        if (btState.title.isNotBlank() && (btState.playing || !stockFresh)) music.value = btState
    }

    /**
     * The track from elsewhere when the BT module leaves it empty: Android's own AVRCP controller (units whose
     * phone music goes through Android's Bluetooth) announces it, and some BT apps send the old music-player
     * broadcast. That one comes from other players too, so it counts only with BT as the source.
     */
    private val btMeta = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            val intent = i ?: return
            val avrcp = intent.action == AVRCP_TRACK
            if (!avrcp && appId.value != 3) return
            val md = if (avrcp) runCatching {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<MediaMetadata>("android.bluetooth.avrcp-controller.profile.extra.METADATA")
            }.getOrNull() else null
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: intent.getStringExtra("track")
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: intent.getStringExtra("artist")
            val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            Log.i(TAG, "bt meta ${intent.action}: title=$title artist=$artist art=${art != null}")
            if (title.isNullOrBlank()) return
            // the module's own words win while it gives them
            val m = btState
            if (m.title.isNotBlank() && m.title != btMetaTitle) return
            btMetaTitle = title
            setBt(m.copy(title = title, artist = artist.orEmpty(), art = art,
                totalMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: m.totalMs))
        }
    }
    /** The title [btMeta] put in, told apart from one the BT module sent. */
    private var btMetaTitle = ""
    private const val AVRCP_TRACK = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT"

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val tk = service ?: return
            main = module(tk, M_MAIN)?.also { register(it, mainCb, 0) }
            radioModule = module(tk, M_RADIO)?.also { m -> listOf(0, 1, 3).forEach { register(m, radioCb, it) } }
            // 1 = the MCU's tuner, 3 / 4 = one the radio app drives (it gets our commands passed on), 2 = none
            val type = runCatching { Runtime.getRuntime().exec(arrayOf("getprop", "sys.fyt.radio_type")).inputStream.bufferedReader().readText().trim() }.getOrDefault("?")
            radioType = type
            trace("bound: main ${main != null}, radio ${radioModule != null}, radio type $type")
            // all the module's updates, not only the known ones: the log tells which code carries what on this unit
            bt = module(tk, M_BT)?.also { m -> (0 until 100).forEach { register(m, btCb, it) } }
            sound = module(tk, M_SOUND)?.also { register(it, soundCb, 2) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            main = null; radioModule = null; bt = null; sound = null
            handler.postDelayed({ app?.let { bind(it) } }, 3000)
        }
    }

    fun bind(ctx: Context) {
        if (app == null) runCatching {
            val f = IntentFilter(AVRCP_TRACK).apply { addAction("com.android.music.metachanged") }
            // the head unit's Bluetooth module and players are other apps
            androidx.core.content.ContextCompat.registerReceiver(ctx.applicationContext, btMeta, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }
        app = ctx.applicationContext
        loadStations()
        runCatching {
            app!!.bindService(Intent("com.syu.ms.toolkit").setPackage("com.syu.ms"), conn, Context.BIND_AUTO_CREATE)
        }
    }

    // --- stations (Teyes online list in /sdcard/fmbd) -----------------------------------------------

    private fun loadStations() {
        stations = runCatching {
            File("/sdcard/fmbd/fmbd.txt").readText().split('|').mapNotNull { rec ->
                val f = rec.split(',')
                if (f.size >= 5) f[4].trim() to (f[0].trim() to f[3].trim()) else null
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** FM in 10 kHz units as everywhere here: some firmwares report kHz or Hz (seen in the CarRadio app's logs). */
    private fun freqUnits(v: Int) = when (v) {
        in 87_500..108_000 -> v / 10
        in 87_500_000..108_000_000 -> v / 10_000
        else -> v
    }

    private fun withStation(r: RadioState): RadioState {
        val key = if (r.isFm || r.band == -1) String.format(Locale.US, "%.02f", r.freq / 100.0) else r.freq.toString()
        val st = stations[key] ?: return r.copy(name = "", logo = null)
        val logo = runCatching { BitmapFactory.decodeFile("/sdcard/fmbd/${st.first}.bmp") }.getOrNull()
        return r.copy(name = st.second, logo = logo)
    }

    // --- controls ----------------------------------------------------------------------------------

    private fun startMusicService(action: String) {
        runCatching { app?.startService(Intent(action).setPackage("com.syu.music")) }
    }

    /** BT is what plays: the active source says so; before the unit has told it, the latest now-playing push. */
    private fun btActive() = appId.value == 3 || (appId.value != 8 && music.value.source == "bt")

    fun playPause() {
        if (btActive()) btPlay(!btMusic.value.playing) else startMusicService("com.syu.music.playpause")
    }

    fun next() {
        when {
            // the radio: through the driver's own stations (by frequency) when there are any, else the tuner's presets
            appId.value == 1 -> radioStep(true)
            btActive() -> btNext()
            else -> startMusicService("com.syu.music.next")
        }
    }

    fun prev() {
        when {
            appId.value == 1 -> radioStep(false)
            btActive() -> btPrev()
            else -> startMusicService("com.syu.music.prev")
        }
    }

    private fun radioStep(next: Boolean) {
        app?.let { FmRadio.load(it) }
        if (FmRadio.mine.isNotEmpty()) FmRadio.step(next) else cmd(radioModule, if (next) 0 else 1, intArrayOf(0))
    }

    // --- Bluetooth music -----------------------------------------------------------------------------

    /*
     * BT is made the source first and the command waits for the switch: the unit resumes the phone by itself
     * when BT becomes the source, so a play/pause toggle sent at once paused it again, and ⏮ ⏭ sent before the
     * switch went to the old source (the radio's presets, the stock player). Then the BT module's command; when
     * the phone shows no reaction to it, the phone's media session if the unit has one, else the media key.
     */

    fun btPlay(play: Boolean) {
        val wasSource = appId.value == 3
        if (!wasSource) source(3)
        whenBtSource(wasSource) {
            // becoming the source already resumed it
            if (!wasSource && play && btMusic.value.playing) { Log.i(TAG, "bt resumed by the source switch"); return@whenBtSource }
            btCmd(2, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, { before, now -> now.playing != before.playing }) { if (play) it.play() else it.pause() }
        }
    }

    // a new track, or the same one from its start (⏮ late in a track)
    fun btNext() = btSkip(1, KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }
    fun btPrev() = btSkip(0, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }

    private fun btSkip(code: Int, key: Int, session: (MediaController.TransportControls) -> Unit) {
        val wasSource = appId.value == 3
        if (!wasSource) source(3)
        whenBtSource(wasSource) {
            btCmd(code, key, { before, now -> now.title != before.title || now.posMs + 2000 < before.posMs }, session)
        }
    }

    private fun whenBtSource(wasSource: Boolean, block: () -> Unit) {
        if (wasSource) { block(); return }
        val t0 = SystemClock.uptimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                // the switch has come (or isn't coming), then a moment for the unit to resume the phone
                if (appId.value == 3 || SystemClock.uptimeMillis() - t0 > 2500) handler.postDelayed(block, 800)
                else handler.postDelayed(this, 100)
            }
        })
    }

    private fun btCmd(code: Int, key: Int, reacted: (MusicState, MusicState) -> Boolean, session: (MediaController.TransportControls) -> Unit) {
        val before = btMusic.value
        val m = bt
        Log.i(TAG, "bt cmd $code (module ${if (m != null) "bound" else "missing"}, source ${appId.value})")
        if (m == null) { btFallback(key, session); return }
        cmd(m, code)
        // the module never reported anything: nothing to tell a reaction by, and a second try could undo the first
        if (before.updatedMs == 0L) return
        handler.postDelayed({ if (!reacted(before, btMusic.value)) btFallback(key, session) }, 2500)
    }

    private fun btFallback(key: Int, session: (MediaController.TransportControls) -> Unit) {
        val ctx = app ?: return
        val c = MediaSessions.controllerOf(ctx, "com.syu.bt") ?: MediaSessions.btController(ctx)
        if (c != null) { Log.i(TAG, "bt: no reaction, via the session of ${c.packageName}"); session(c.transportControls); return }
        // the key goes to the app that played last: not when that is the app's own player, it would start instead
        if (LocalMusic.state.value.queue.isNotEmpty()) { Log.i(TAG, "bt: no reaction, own player would take the key"); return }
        Log.i(TAG, "bt: no reaction, media key $key")
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    private var radioAskedMs = -10_000L
    private var radioLaunchMs = -120_000L

    /** Also into the exchange log (Настройки → «Журнал обмена»), where a radio that won't switch can be seen. */
    private fun trace(msg: String) {
        Log.i(TAG, msg)
        kg.forester.sg.ssm.SsmTrace.log("fyt: $msg", keep = true)
    }

    /**
     * The radio's activity was started a moment ago and the unit still doesn't say the radio is the source: it
     * plays all the same, so nothing more to wait for (and no second start, see [ensureRadio]).
     */
    val radioUnconfirmed get() = appId.value != 1 && SystemClock.uptimeMillis() - radioLaunchMs < 60_000

    /**
     * Radio as the active source: the source command alone, as a rule. The radio's activity flashed up on every
     * station picked, so it is started (and our screen brought back) only when the source hasn't switched in 1.5 s.
     */
    fun ensureRadio() {
        // started a moment ago with no word of the source since: the source command again could throw it back too
        if (appId.value == 1 || radioUnconfirmed) return
        val ctx = app ?: return
        // asked a moment ago and the switch is on its way: launched again, the radio starts over at its own last
        // station and undoes the seek or preset that follows
        if (SystemClock.uptimeMillis() - radioAskedMs < 3000) return
        radioAskedMs = SystemClock.uptimeMillis()
        trace("radio on (source ${appId.value})")
        cmd(main, 0, intArrayOf(1))
        handler.postDelayed({
            if (appId.value == 1) return@postDelayed
            // started a minute ago, and the unit never said the radio is the source (some don't, or say another
            // number): started again, the radio went back to its own last station each time and undid every seek
            // and preset, so the buttons seemed dead
            if (SystemClock.uptimeMillis() - radioLaunchMs < 60_000) { trace("radio: source still ${appId.value}, not starting it again"); return@postDelayed }
            trace("radio: source still ${appId.value}, starting the radio's activity")
            launchRadioApp(ctx)
        }, 1500)
    }

    /** The stock radio's activity for a moment (it comes up on its own last station), then our screen back. */
    private fun launchRadioApp(ctx: Context) {
        radioLaunchMs = SystemClock.uptimeMillis()
        runCatching {
            ctx.startActivity(Intent().setClassName("com.syu.radio", "com.syu.radio.Launch").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        cmd(main, 0, intArrayOf(1))
        handler.postDelayed({
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); runCatching { ctx.startActivity(it) }
            }
        }, 900)
    }

    /**
     * The tuner didn't react to a command while the radio is the source. On radio type 3 / 4 ms only passes our
     * commands on to the stock radio app, and once Android has killed that app they go nowhere until the driver
     * opens it by hand: that was "the saved stations stopped working". Started again, at most every 20 s;
     * false when it was started just now (the caller waits for it and sends the command again).
     */
    fun wakeRadioApp(): Boolean {
        val ctx = app ?: return false
        // the MCU's own tuner needs no app; a frequency it ignores is not a dead app
        if (radioType == "1" || SystemClock.uptimeMillis() - radioLaunchMs < 20_000) return false
        trace("radio: no reaction (type $radioType, source ${appId.value}), starting the radio app")
        launchRadioApp(ctx)
        return true
    }

    /**
     * Master volume [steps] up (+) or down (−) through the MCU, like the knob; false when the sound module isn't
     * there (not an FYT unit, service not bound yet). Command 0 with -1 / -2 = one step up / down, -3 / -4 = mute
     * on / off; -6 shows the volume bar.
     */
    fun volumeStep(steps: Int): Boolean {
        val m = sound ?: return false
        repeat(kotlin.math.abs(steps)) { cmd(m, 0, intArrayOf(if (steps > 0) -1 else -2)) }
        cmd(m, 0, intArrayOf(-6))
        return true
    }

    fun mute(on: Boolean): Boolean {
        val m = sound ?: return false
        cmd(m, 0, intArrayOf(if (on) -3 else -4))
        return true
    }

    /** Makes [id] the head unit's audio source: 1 radio, 3 BT music, 8 music player (the call ensureRadio makes). */
    fun source(id: Int) { cmd(main, 0, intArrayOf(id)) }

    /** [ensure] false: the caller has made the radio the source and waited for it already. */
    fun radioSeek(up: Boolean, ensure: Boolean = true) {
        if (ensure) ensureRadio()
        trace("radio seek ${if (up) "up" else "down"} from ${radio.value.freq} (source ${appId.value}, module ${radioModule != null})")
        cmd(radioModule, if (up) 5 else 6, intArrayOf(0))
    }
    /*
     * The radio module's commands as com.syu.ms takes them (uis7862 build of 2021-11, decompiled): 0 / 1 preset up /
     * down, 5 / 6 seek, 7 [index] a preset (FM ones from 65536), 11 [band] the band (65536..65538 FM1..3, 0/1 AM,
     * -1 the band key, -3 / -2 FM / AM on other builds; without the number it is ignored), 13 [mode, freq] a
     * frequency. A unit whose tuner the radio app drives itself (radio type 3/4, newer units) gets them passed on to
     * that app, which must be running. Seek and preset up / down go with [0], as the FYT launcher sends them: some
     * services (UIS8141E) ignore them without a value.
     *
     * Command 13 mode 1 converts the frequency by the band limits, which ms learns only when the MCU sends its band
     * range, often only after the stock radio app asked for it: until then it is dropped without a word. That was
     * "works every other time, open the stock radio and it works again". Mode 3 sends the MCU packet with the
     * frequency as it is (FM in 10 kHz units, AM in kHz) and works at once (found by the CarRadio app,
     * github.com/nikhil914/CarRadio); mode 1 stays as the fallback.
     */
    val TUNE_MODES = intArrayOf(3, 1)

    /** Straight to [freq] (FM in 10 kHz units, AM in kHz) by command 13 [mode]; a tuner that doesn't take it stays. */
    fun radioTune(freq: Int, mode: Int = 3) {
        trace("radio tune $freq mode $mode from ${radio.value.freq} (source ${appId.value})")
        cmd(radioModule, 13, intArrayOf(mode, freq))
    }

    /** The tuner's preset [p] (1..6) of the band that plays. */
    fun radioToPreset(p: Int) {
        val index = if (radio.value.isFm || radio.value.band == -1) 65536 + p - 1 else p - 1
        trace("radio preset $p (index $index) from ${radio.value.preset} (source ${appId.value})")
        cmd(radioModule, 7, intArrayOf(index))
    }

    fun radioPreset(next: Boolean, ensure: Boolean = true) {
        if (ensure) ensureRadio()
        trace("radio preset ${if (next) "next" else "prev"} from ${radio.value.preset} (source ${appId.value}, module ${radioModule != null})")
        cmd(radioModule, if (next) 0 else 1, intArrayOf(0))
    }
    /** To [band] (65536..65538 FM1..3, 0 AM), or the next one like the band key (-1). */
    fun radioBand(band: Int = -1) {
        ensureRadio()
        trace("radio band $band from ${radio.value.band}")
        cmd(radioModule, 11, intArrayOf(band))
    }
    fun openRadio() {
        runCatching { app?.startActivity(Intent().setClassName("com.syu.radio", "com.syu.radio.Launch").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun openMusic() {
        app?.packageManager?.getLaunchIntentForPackage("com.syu.music")?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); runCatching { app?.startActivity(it) }
        }
    }
}
