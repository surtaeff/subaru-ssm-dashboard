package kg.forester.sg.service

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import kg.forester.sg.R

/**
 * What a button's tap does to the sound, apart from Android. [tap] costs the tapping thread one flag and one post;
 * the play and stop calls run on the thread [post] goes to, since on older units SoundPool.play builds its audio track
 * on the spot. A new tap stops the one before, so taps restart the click rather than stack.
 */
class TapPlayer(private val out: Out, private val post: (Runnable) -> Unit, on: Boolean) {
    interface Out {
        /** The stream started, 0 when it could not start. */
        fun play(): Int
        fun stop(stream: Int)
    }

    @Volatile var enabled = on
    @Volatile var ready = false
    private var stream = 0

    private val once = Runnable {
        if (ready) {
            if (stream != 0) out.stop(stream)
            stream = out.play()
        }
    }

    fun tap() {
        if (enabled) post(once)
    }
}

/**
 * The buttons' click: one SoundPool with one stream, loaded once off the main thread. It asks for no audio focus and
 * plays as sonification, so music is neither paused nor ducked.
 */
object TapSound {
    @Volatile private var player: TapPlayer? = null

    fun init(ctx: Context, on: Boolean) {
        if (player != null) return
        val handler = Handler(HandlerThread("tap-sound").apply { start() }.looper)
        var pool: SoundPool? = null
        var id = 0
        var logged = 0
        val p = TapPlayer(object : TapPlayer.Out {
            override fun play(): Int {
                // the first clicks of a run in a sent log: a unit source ("fyt: source …") changing right after one
                // would say the head unit reacts to any Android sound, focus or not
                if (logged < 3) { logged++; kg.forester.sg.ssm.SsmTrace.log("tap: click, no focus (unit source ${Fyt.appId.value})", keep = true) }
                return pool?.play(id, 1f, 1f, 1, 0, 1f) ?: 0
            }
            override fun stop(stream: Int) { pool?.stop(stream) }
        }, { handler.post(it) }, on)
        player = p
        handler.post {
            // a unit whose audio can't do this just has silent buttons
            runCatching {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build().apply {
                    setOnLoadCompleteListener { _, _, status -> p.ready = status == 0 }
                    id = load(ctx.applicationContext, R.raw.button, 1)
                }
            }
        }
    }

    fun setEnabled(on: Boolean) { player?.enabled = on }

    fun tap() { player?.tap() }
}
