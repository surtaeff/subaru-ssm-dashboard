package kg.forester.sg.service

import android.graphics.Bitmap

/**
 * What plays now, for the mini player. [source]: "files", "stream" (both the own player), "fm" (the tuner), "bt", "app"
 * (another player's session, [pkg]; "fyt" is the head unit's stock player).
 */
data class NowPlaying(
    val source: String, val title: String, val artist: String, val art: Bitmap?, val playing: Boolean,
    val posMs: Long = 0, val durMs: Long = 0, val pkg: String = "",
)

/**
 * Which of what is known goes on the strip. [mode] is Media's source: what it shows if that plays, else whatever really
 * plays (a player started from its own screen, a station with Media set elsewhere), else what it shows paused.
 */
fun pickNow(mode: String, own: NowPlaying?, fm: NowPlaying?, bt: NowPlaying?, app: NowPlaying?): NowPlaying? {
    val chosen = when (mode) {
        "bt" -> bt
        "apps" -> app
        "radio" -> own?.takeIf { it.source == "stream" } ?: fm
        else -> own?.takeIf { it.source == "files" }
    }
    if (chosen?.playing == true) return chosen
    return listOfNotNull(own, app, bt, fm).firstOrNull { it.playing } ?: chosen
}

/**
 * What the strip shows of that over time: nothing until something has played; a pause stays for [RECENT_MS]; a short gap
 * (between tracks, a player's session being replaced) keeps the last track and so the strip's height for [GAP_MS].
 */
class MiniHold {
    private var last: NowPlaying? = null
    private var playedAt = 0L
    private var seenAt = 0L

    fun feed(now: NowPlaying?, t: Long): NowPlaying? {
        val prev = last
        if (now != null && now.playing && now.title.isBlank()) {
            // a player between two tracks: it plays, the name isn't there yet
            playedAt = t
            seenAt = t
            return prev ?: now
        }
        if (now != null && now.playing) {
            last = now; playedAt = t; seenAt = t
            return now
        }
        if (prev == null) return null
        if (now != null) {
            if (t - playedAt > RECENT_MS) { last = null; return null }
            last = now; seenAt = t
            return now
        }
        if (t - seenAt > GAP_MS) { last = null; return null }
        return prev.copy(playing = false)
    }

    companion object {
        const val RECENT_MS = 10 * 60_000L
        const val GAP_MS = 8_000L
    }
}

enum class MiniSlot { NONE, TOP, BOTTOM }

/** Where the strip goes for the setting [where] ("bottom", "top", "off"). */
fun miniSlot(where: String, onMedia: Boolean, editingFree: Boolean, shown: Boolean): MiniSlot = when {
    !shown || onMedia || editingFree || where == "off" -> MiniSlot.NONE
    where == "top" -> MiniSlot.TOP
    else -> MiniSlot.BOTTOM
}
