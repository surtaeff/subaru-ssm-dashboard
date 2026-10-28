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

    fun microntek(ctx: Context): Boolean = mtc ?: listOf("android.microntek.service", "com.microntek.bluetooth", "android.microntek.mtcser")
        .any { runCatching { ctx.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false) }
        .also { mtc = it }
    private var mtc: Boolean? = null

    /** The Bluetooth mode was picked (the player screen, a voice command): the head unit's sound goes to the phone. */
    fun enter(ctx: Context) {
        if (Fyt.present(ctx)) { Fyt.source(3); return }
        if (microntek(ctx)) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // the stock music and radio close on this, as when the stock Bluetooth app opens; the BT app itself stays
            runCatching { ctx.sendBroadcast(Intent("com.microntek.bootcheck").putExtra("class", "com.microntek.bluetooth")) }
            runCatching { am.setParameters("av_channel_enter=gsm_bt") }
            SsmTrace.log("bt: microntek channel gsm_bt", keep = true)
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

    /** Connects once to the Bluetooth stack's media browser service (com.android.bluetooth's AVRCP controller). */
    fun connect(ctx: Context) {
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
