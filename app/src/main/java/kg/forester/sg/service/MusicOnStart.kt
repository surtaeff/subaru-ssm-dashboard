package kg.forester.sg.service

import android.content.Context
import android.media.AudioManager
import kg.forester.sg.data.Repo
import kg.forester.sg.service.audio.AudioPlatforms
import kg.forester.sg.service.audio.Transport
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «Включать музыку при запуске»: when the unit comes on (a cold start or a wake from ACC sleep) the source picked in
 * Медиа goes on by itself: the own player where it stopped, Bluetooth from the phone, the radio, or the chosen player
 * app. Asked by Ruen16 and Михаил: each start was Медиа, play, back. Nothing is done when something already plays
 * (the unit's own resume, the phone's), and the tries stop after [GIVE_UP_MS]: a phone takes 10-30 s to connect.
 */
object MusicOnStart {
    private const val FIRST_MS = 5_000L
    private const val STEP_MS = 4_000L
    const val GIVE_UP_MS = 45_000L
    private var job: Job? = null

    /** What to do about a start, [playing] being whether anything plays already; null = nothing (off or playing). */
    fun plan(on: Boolean, playing: Boolean, source: String, musicApp: String): String? = when {
        !on || playing -> null
        source == "apps" && musicApp.isEmpty() -> null
        source in setOf("files", "bt", "radio", "apps") -> source
        else -> null
    }

    fun start(ctx: Context, scope: CoroutineScope, why: String) {
        if (!Repo.settings.musicOnStart) return
        job?.cancel()
        val app = ctx.applicationContext
        job = scope.launch {
            delay(FIRST_MS)
            var waited = FIRST_MS
            var tries = 0
            while (waited <= GIVE_UP_MS) {
                val s = Repo.settings
                val what = plan(s.musicOnStart, playingNow(app), s.mediaSource, s.musicApp)
                if (what == null) { if (tries > 0) SsmTrace.log("music on start: playing"); return@launch }
                // Bluetooth asks again until the phone is there; the others go once (a second play would toggle back)
                if (tries == 0 || what == "bt") {
                    SsmTrace.log("music on start ($why): $what, try ${tries + 1}")
                    runCatching { go(app, what) }.onFailure { SsmTrace.log("music on start: $it") }
                }
                tries++
                delay(STEP_MS); waited += STEP_MS
            }
            SsmTrace.log("music on start: nothing plays after ${GIVE_UP_MS / 1000} s")
        }
    }

    private fun playingNow(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // the FYT tuner plays past Android's audio: its app being the unit's source counts too
        return am.isMusicActive || Fyt.music.value.playing || Fyt.appId.value == 1 || LocalMusic.state.value.playing
    }

    private suspend fun go(ctx: Context, what: String) {
        val audio = AudioPlatforms.current(ctx)
        when (what) {
            "files" -> LocalMusic.resume(ctx)
            "bt" -> { audio.bt.enter(byUser = true); audio.bt.send(Transport.PLAY) }
            "radio" -> {
                val internet = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE).getBoolean("internet", false)
                // the internet station plays from the own player's queue (kept while the unit only slept)
                if (internet) LocalMusic.resume(ctx) else audio.radio.on()
            }
            "apps" -> {
                val pkg = Repo.settings.musicApp
                if (pkg == "fyt") { if (!Fyt.music.value.playing) Fyt.playPause() }
                else MediaSessions.controllerOf(ctx, pkg)?.transportControls?.play()
                    // no session yet: the media key reaches the player the system last played through
                    ?: mediaPlayKey(ctx)
            }
        }
    }

    private fun mediaPlayKey(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
    }
}
