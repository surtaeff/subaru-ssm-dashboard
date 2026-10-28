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
import kg.forester.sg.ssm.SsmTrace

/**
 * The phone's music over Bluetooth on head units that are not FYT (FYT goes through [Fyt]: source 3).
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

    fun microntek(ctx: Context): Boolean = mtc ?: (listOf("android.microntek.service", "com.microntek.bluetooth", "android.microntek.mtcser")
        .any { runCatching { ctx.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false) } ||
        launchable(ctx).any { it.activityInfo.packageName.contains("microntek") })
        .also { mtc = it }
    private var mtc: Boolean? = null

    private fun launchable(ctx: Context): List<android.content.pm.ResolveInfo> = runCatching {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    }.getOrDefault(emptyList())

    /**
     * The head unit's own Bluetooth music screen: on Microntek its BT chip plays only while that app has the music
     * page open (it unmutes the chip; the channel alone stays silent). A launcher entry of a Bluetooth package of the
     * unit, the one named like music first ("BT музыка", "Музыка BT", "BT Music").
     */
    private fun stockBtMusic(ctx: Context): android.content.ComponentName? {
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

    private var stockOpenedAt = 0L

    /** The Bluetooth mode was picked (the player screen, a voice command): the head unit's sound goes to the phone. */
    fun enter(ctx: Context) {
        if (Fyt.present(ctx)) { Fyt.source(3); return }
        if (microntek(ctx)) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // the stock music and radio close on this, as when the stock Bluetooth app opens; the BT app itself stays
            runCatching { ctx.sendBroadcast(Intent("com.microntek.bootcheck").putExtra("class", "com.microntek.bluetooth")) }
            runCatching { am.setParameters("av_channel_enter=gsm_bt") }
            SsmTrace.log("bt: microntek channel gsm_bt; launchers: " + launchable(ctx).map { it.activityInfo.packageName + "/" + it.activityInfo.name }
                .filter { "microntek" in it || "bt" in it.lowercase() || "blue" in it.lowercase() || "music" in it.lowercase() }.joinToString(", "), keep = true)
            // the stock BT music screen opened for a moment, then back to us: what drivers did by hand to get sound
            val now = SystemClock.elapsedRealtime()
            val stock = stockBtMusic(ctx)
            if (stock != null && now - stockOpenedAt > 60_000) {
                stockOpenedAt = now
                SsmTrace.log("bt: open ${stock.flattenToShortString()} for a moment", keep = true)
                runCatching { ctx.startActivity(Intent(Intent.ACTION_MAIN).setComponent(stock).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                val back = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { am.setParameters("av_channel_enter=gsm_bt") }
                    back?.let { b -> runCatching { ctx.startActivity(b) } }
                }, 1800)
            }
        }
        connect(ctx)
        kick(ctx, force = true)
    }

    /** Another mode was picked: Android's own sound back on Microntek. */
    fun leave(ctx: Context) {
        if (Fyt.present(ctx) || !microntek(ctx)) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { am.setParameters("av_channel_exit=gsm_bt") }
        runCatching { am.setParameters("av_channel_enter=sys") }
        SsmTrace.log("bt: microntek channel sys", keep = true)
    }

    private var kickedAt = 0L

    /**
     * Android's A2DP sink without the focus: the phone plays, the track runs, nothing sounds. A "play" through the
     * session makes the sink ask for the focus. [force]: right after the mode was picked; otherwise only when the
     * session says it plays while no music is heard, at most every 15 s. Not on Microntek: its BT sound never shows
     * up in Android's audio, "no music" would be true all the time.
     */
    fun kick(ctx: Context, force: Boolean = false) {
        if (Fyt.present(ctx) || (!force && microntek(ctx))) return
        val c = controller(ctx) ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.elapsedRealtime()
        if (!force && (!playing || am.isMusicActive || now - kickedAt < 15_000)) return
        if (force && !playing) return
        kickedAt = now
        SsmTrace.log("bt: play to ${c.packageName} (playing=$playing music=${am.isMusicActive})", keep = true)
        runCatching { c.transportControls.play() }
    }

    /** The Bluetooth session: the one [MediaSessions] sees, else the one from the stack's media browser. */
    fun controller(ctx: Context): MediaController? = MediaSessions.btController(ctx) ?: browsed

    /** Play / pause / next / previous: the session, else the media keys, which the head unit sends to its source. */
    fun control(ctx: Context, key: Int, block: (MediaController.TransportControls) -> Unit) {
        val c = controller(ctx)
        if (c != null) { runCatching { block(c.transportControls) }; return }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    @Volatile private var browsed: MediaController? = null
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
    private fun listenAnnouncements(ctx: Context) {
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

    /** Connects once to the Bluetooth stack's media browser service (com.android.bluetooth's AVRCP controller). */
    fun connect(ctx: Context) {
        if (microntek(ctx)) listenAnnouncements(ctx)
        if (browser != null || Fyt.present(ctx)) return
        val app = ctx.applicationContext
        val svc = runCatching {
            app.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
                .map { it.serviceInfo }.firstOrNull { MediaSessions.isBt(it.packageName) }
        }.getOrNull() ?: return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            if (browser != null) return@post
            var b: MediaBrowser? = null
            b = MediaBrowser(app, ComponentName(svc.packageName, svc.name), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    browsed = runCatching { MediaController(app, b!!.sessionToken) }.getOrNull()
                    SsmTrace.log("bt: browser ${svc.packageName} connected", keep = true)
                }
                override fun onConnectionSuspended() { browsed = null }
                override fun onConnectionFailed() {
                    browsed = null; browser = null
                    SsmTrace.log("bt: browser ${svc.packageName} refused", keep = true)
                }
            }, null)
            browser = b
            runCatching { b.connect() }.onFailure { browser = null; android.util.Log.w(TAG, "connect: $it") }
        }
    }
}
