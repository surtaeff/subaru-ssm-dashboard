package kg.forester.sg.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import kg.forester.sg.service.audio.SinkFacts
import kg.forester.sg.service.audio.kickDue
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's music over Bluetooth, what the backends in service/audio share (FYT goes through [Fyt]: source 3).
 *
 * Two things keep it silent while the track and the seconds run:
 *  - Android's own A2DP sink (com.android.bluetooth) plays only with audio focus, and it asks for focus by itself
 *    only on a "play" from the head unit's side, not when the phone starts (config a2dp_sink_automatically_request
 *    _audio_focus is off on most units). After CarPlay, our player or anything else took the focus it stays muted:
 *    a "play" sent to its session asks for the focus again ([kick]).
 *  - Microntek (MTCB/MTCD/MTCE, PX5 and others) has the BT chip outside Android: its sound comes on a channel of
 *    the audio processor that only the stock Bluetooth app opens ("av_channel_enter=gsm_bt", as in its activity).
 *
 * Its session is also taken straight from the Bluetooth stack's media browser, so the track and the buttons work
 * without the notification access [MediaSessions] needs (the same way Android Automotive's media app does it).
 */
object BtAudio {
    private const val TAG = "BtAudio"

    internal fun launchable(ctx: Context): List<android.content.pm.ResolveInfo> = runCatching {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    }.getOrDefault(emptyList())

    /**
     * The head unit's own Bluetooth music screen: on Microntek its BT chip plays only while that app has the music
     * page open (it unmutes the chip; the channel alone stays silent). A launcher entry of a Bluetooth package of the
     * unit, the one named like music first ("BT музыка", "Музыка BT", "BT Music").
     */
    internal fun stockBtMusic(ctx: Context): android.content.ComponentName? {
        val pm = ctx.packageManager
        val bt = launchable(ctx).filter { r ->
            val p = r.activityInfo.packageName
            p != ctx.packageName && (MediaSessions.isBt(p) || "btmusic" in p) && ("microntek" in p || "mtc" in p || p.startsWith("com.android.") || "btmusic" in p)
        }
        fun label(r: android.content.pm.ResolveInfo) = runCatching { r.loadLabel(pm).toString().lowercase() }.getOrDefault("")
        val pick = bt.firstOrNull { "btmusic" in it.activityInfo.packageName || "music" in it.activityInfo.name.lowercase() }
            ?: bt.firstOrNull { label(it).let { l -> "муз" in l || "music" in l } }
            ?: bt.firstOrNull { "microntek" in it.activityInfo.packageName }
        return pick?.let { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    /**
     * The head unit's own Bluetooth app (pairing, phone, music) for the «Настройки Bluetooth» button: units with two
     * modules (TS18 and others) pair the phone there, while Android's Bluetooth settings show the OBD module.
     */
    fun stockBtApp(ctx: Context): Intent? {
        if (Fyt.present(ctx)) return ctx.packageManager.getLaunchIntentForPackage("com.syu.bt")
        // only the firmware's own apps: an installed one with "bt" in its name (a BT scanner, a btc wallet) is not it
        val own = launchable(ctx).firstOrNull { r ->
            val p = r.activityInfo.packageName
            r.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 &&
                p != ctx.packageName && MediaSessions.isBt(p) && !p.startsWith("com.android.") && !p.startsWith("com.google.")
        }?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        return (own ?: stockBtMusic(ctx))?.let { Intent(Intent.ACTION_MAIN).setComponent(it) }
    }

    private var launchersLogged = false

    /** Once a run: the unit's Bluetooth and music launchers, so a sent log shows which app plays the phone there. */
    internal fun logLaunchers(ctx: Context) {
        if (launchersLogged) return
        launchersLogged = true
        SsmTrace.log("bt: launchers: " + launchable(ctx).map { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .filter { "microntek" in it || "bt" in it.lowercase() || "blue" in it.lowercase() || "music" in it.lowercase() }.joinToString(", "), keep = true)
    }

    /** What the unit says about its source and the phone's session when Bluetooth is picked, for a sent log. */
    internal fun logPick(ctx: Context) {
        val src = runCatching { android.provider.Settings.System.getString(ctx.contentResolver, "mcu_current_source") }.getOrNull()
            ?: runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "mcu_current_source") }.getOrNull()
        val c = controller(ctx)
        // the head-unit families by their package prefixes: FYT, Microntek, NWD, TS, TW, SPD, HCN
        val families = listOf("com.syu.", "android.microntek.", "com.microntek.", "com.nwd.", "com.ts.", "android.tw.", "com.spd.", "com.hcn.")
        val pkgs = runCatching {
            ctx.packageManager.getInstalledPackages(0).map { it.packageName }.filter { p -> families.any { p.startsWith(it) } }
        }.getOrDefault(emptyList())
        SsmTrace.log("bt pick: mcu_current_source=${src ?: "—"} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "session=${c?.packageName ?: "—"}/${c?.playbackState?.state ?: "—"} packages(${pkgs.size})=${pkgs.joinToString(",")}", keep = true)
    }

    private var kickedAt = 0L

    /**
     * Android's A2DP sink without the focus: the phone plays, the track runs, nothing sounds. A "play" through the
     * session makes the sink ask for the focus. Only as [kickDue] says: never before the driver asked in this run.
     */
    fun kick(ctx: Context, userAsked: Boolean) {
        val c = controller(ctx) ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.elapsedRealtime()
        if (!kickDue(userAsked, playing, am.isMusicActive, if (kickedAt == 0L) Long.MAX_VALUE else now - kickedAt)) return
        kickedAt = now
        SsmTrace.log("bt: play to ${c.packageName} (playing=$playing music=${am.isMusicActive})", keep = true)
        runCatching { c.transportControls.play() }
    }

    /** The Bluetooth session: the one [MediaSessions] sees, else the one from the stack's media browser, if it counts. */
    fun controller(ctx: Context): MediaController? =
        MediaSessions.btController(ctx) ?: browsed.value?.takeIf { MediaSessions.counts(ctx, it) }

    /** What the plans in service/audio decide by. */
    internal fun facts(ctx: Context): SinkFacts {
        val c = controller(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return SinkFacts(
            sdk = android.os.Build.VERSION.SDK_INT,
            session = c != null,
            playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING,
            heard = am.isMusicActive,
            voiceBusy = voiceBusy(ctx),
        )
    }

    /** A call, or a voice playing: the navigator's, an assistant's, ours. A navigator that speaks as "media" isn't seen here. */
    internal fun voiceBusy(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (callOn(ctx)) return true
        if (android.os.Build.VERSION.SDK_INT < 26) return false
        return runCatching { am.activePlaybackConfigurations.any { it.audioAttributes.usage in VOICE_USAGES } }.getOrDefault(false)
    }

    internal fun callOn(ctx: Context) = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode != AudioManager.MODE_NORMAL

    private val VOICE_USAGES = setOf(
        android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, android.media.AudioAttributes.USAGE_ASSISTANT,
        android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION,
        android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
    )

    /** GAIN and an abandon at once, see [kg.forester.sg.service.audio.focusStep] for when. */
    internal fun focusPulse(ctx: Context): Int? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val l = AudioManager.OnAudioFocusChangeListener { }
        @Suppress("DEPRECATION")
        val got = runCatching { am.requestAudioFocus(l, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) }.getOrNull()
        @Suppress("DEPRECATION")
        runCatching { am.abandonAudioFocus(l) }
        SsmTrace.log("bt: focus pulse, answer $got", keep = true)
        return got
    }

    internal fun prepare(ctx: Context) { controller(ctx)?.let { c -> runCatching { c.transportControls.prepare() } } }

    /** The browser's session, up to [ms] when the browser is on its way. */
    internal suspend fun awaitSession(ctx: Context, ms: Long) {
        if (!connect(ctx) || controller(ctx) != null) return
        withTimeoutOrNull(ms) { browsed.first { it != null } }
    }

    /** Play / pause / next / previous: the session, else the media key if there is one to send. */
    fun control(ctx: Context, key: Int?, block: (MediaController.TransportControls) -> Unit) {
        val c = controller(ctx)
        if (c != null) { runCatching { block(c.transportControls) }; return }
        if (key == null) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    private val browsed = kotlinx.coroutines.flow.MutableStateFlow<MediaController?>(null)
    private var browser: MediaBrowser? = null

    /** A track a head unit announced in a broadcast (Microntek and others without a media session); blank if none. */
    data class Announced(val title: String = "", val artist: String = "", val playing: Boolean? = null)
    val announced = kotlinx.coroutines.flow.MutableStateFlow(Announced())
    private var listening = false
    private var heard = 0

    /**
     * Microntek gives the phone's track to no media session. Players and some head-unit Bluetooth apps announce it in
     * broadcasts ("com.android.music.metachanged" and the like): taken from there when they do, and the first ones
     * that come go to the log, to see what a unit sends.
     */
    internal fun listenAnnouncements(ctx: Context) {
        if (listening) return
        listening = true
        val f = android.content.IntentFilter().apply {
            listOf(
                "com.android.music.metachanged", "com.android.music.playstatechanged", "com.android.music.musicservicecommand",
                "com.microntek.music.report", "com.microntek.bt.report", "com.microntek.btmusic.report", "com.microntek.musicinfo",
            ).forEach { addAction(it) }
        }
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val x = i.extras
                if (heard < 30) {
                    heard++
                    SsmTrace.log("bt: broadcast ${i.action} " + (x?.keySet()?.joinToString(",") { k -> "$k=${x.get(k)}".take(60) } ?: ""), keep = true)
                }
                if (x == null) return
                fun str(vararg k: String) = k.firstNotNullOfOrNull { x.getString(it)?.takeIf { v -> v.isNotBlank() } }
                val title = str("track", "title", "music_title", "song", "name") ?: return
                val playing = listOf("playing", "isPlaying", "isplaying").firstOrNull { x.containsKey(it) }?.let { x.getBoolean(it) }
                announced.value = Announced(title, str("artist", "music_artist", "singer").orEmpty(), playing)
            }
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) ctx.applicationContext.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else ctx.applicationContext.registerReceiver(r, f)
        }
    }

    /**
     * Connects once to the Bluetooth stack's media browser service (com.android.bluetooth's AVRCP controller); false
     * when the unit has none.
     */
    fun connect(ctx: Context): Boolean {
        if (browser != null) return true
        val app = ctx.applicationContext
        val svc = runCatching {
            app.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
                .map { it.serviceInfo }.firstOrNull { MediaSessions.isBt(it.packageName) }
        }.getOrNull() ?: return false
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            if (browser != null) return@post
            var b: MediaBrowser? = null
            b = MediaBrowser(app, ComponentName(svc.packageName, svc.name), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    browsed.value = runCatching { MediaController(app, b!!.sessionToken) }.getOrNull()
                    SsmTrace.log("bt: browser ${svc.packageName} connected", keep = true)
                }
                override fun onConnectionSuspended() { browsed.value = null }
                override fun onConnectionFailed() {
                    browsed.value = null; browser = null
                    SsmTrace.log("bt: browser ${svc.packageName} refused", keep = true)
                }
            }, null)
            browser = b
            runCatching { b.connect() }.onFailure { browser = null; android.util.Log.w(TAG, "connect: $it") }
        }
        return true
    }
}
