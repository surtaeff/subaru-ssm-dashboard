package kg.forester.sg.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import kg.forester.sg.ssm.SsmTrace

/**
 * Who plays on the unit, in the log: each change of Android's active players with the app's screen at that moment.
 * Женя Каминский (KENSHI Pad Pro E90, Android 14, 1.0-beta9+, 2026-11-17): the unit's music from Bluetooth or a stick
 * stops while he goes through CarPulse's screens and comes back only after leaving the app. Nothing in the app asks
 * for the focus on a screen change, so the next log has to show what starts or stops right then.
 *
 * Only the attributes are visible to an ordinary app (not the player's app), so the app's own music player is named
 * from its own state.
 */
object AudioWatch {
    /** The screen on top, from the activity; "-" when the app is not on screen. */
    @Volatile var screen: String = "-"

    private const val MAX_LINES = 60
    private var lines = 0
    private var last = ""
    private var started = false

    /** One player as the log shows it: its usage and whether it is ours. */
    data class Player(val usage: Int, val content: Int)

    /** The line for the players now, the same for the same set in any order: "media×2, sonification". */
    fun summary(players: List<Player>): String {
        if (players.isEmpty()) return "nothing"
        return players.map { name(it.usage) }.groupingBy { it }.eachCount().toSortedMap()
            .map { (k, n) -> if (n > 1) "$k×$n" else k }.joinToString(", ")
    }

    fun name(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_MEDIA -> "media"
        AudioAttributes.USAGE_GAME -> "game"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "sonification"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigation"
        AudioAttributes.USAGE_ASSISTANT -> "assistant"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "accessibility"
        AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_NOTIFICATION_EVENT -> "notification"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "ringtone"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "call"
        AudioAttributes.USAGE_ALARM -> "alarm"
        AudioAttributes.USAGE_UNKNOWN -> "unknown"
        else -> "usage$usage"
    }

    fun start(ctx: Context) {
        if (started) return
        if (Build.VERSION.SDK_INT < 26) return
        started = true
        register(ctx)
    }

    @androidx.annotation.RequiresApi(26)
    private fun register(ctx: Context) {
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = changed(configs)
            }, Handler(Looper.getMainLooper()))
            changed(am.activePlaybackConfigurations)
        }.onFailure { SsmTrace.log("audio watch: $it") }
    }

    @androidx.annotation.RequiresApi(26)
    private fun changed(configs: List<AudioPlaybackConfiguration>) {
        val now = summary(configs.map { Player(it.audioAttributes.usage, it.audioAttributes.contentType) })
        if (now == last) return
        last = now
        if (lines >= MAX_LINES) return
        lines++
        SsmTrace.log("audio: players $now; screen $screen; ours: ${ours()}", keep = true)
    }

    /** The app's own music player: whether it plays now (the voice and the click show up as assistant / sonification). */
    private fun ours(): String = if (LocalMusic.state.value.playing) "player" else "none"
}
