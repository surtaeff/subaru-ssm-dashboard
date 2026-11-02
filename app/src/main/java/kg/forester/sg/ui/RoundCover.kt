package kg.forester.sg.ui

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * The round cover of the players and the ring of rays coming out of its edge. Sizes are shares of the side of the box
 * they sit in; the ring stays inside that box.
 */
object RoundCover {
    /** The cover's diameter with the ring round it, and with the ring off. */
    const val COVER_WITH_RING = 0.78f
    const val COVER_ALONE = 0.92f

    /** A logo stays whole in a circle up to 1/sqrt(2) of its diameter. */
    const val LOGO_FIT = 0.70f
    const val ICON_FIT = 0.38f

    /** How much the cover swells on the loudest bass. */
    const val GROW = 0.02f
    const val GROW_MAX = 1f + GROW

    /** Where the rays start, from the centre. */
    const val RAY_FROM = 0.42f
    private const val RAY_MIN = 0.018f
    private const val RAY_RANGE = 0.062f
    const val RAY_WIDTH = 0.012f

    private const val RAYS = 96
    private const val RAYS_LOW = 48
    private const val LOW_HZ = 15f

    /** The plain animation: this many even humps, going once round in [WAVE_PERIOD] seconds per hump. */
    const val WAVE_HUMPS = 3
    const val WAVE_PERIOD = 2.6f

    /** The picture while it isn't moved by real sound: at rest, short and even. */
    const val REST_LEVEL = 0.04f

    enum class Drive {
        /** Nothing plays: nothing moves. */
        STILL,

        /** The player's real output, band by band. */
        LEVELS,

        /** No real sound to read (Bluetooth, radio, other apps): a plain even wave, which no one can take for a level. */
        WAVE,
    }

    fun drive(playing: Boolean, live: Boolean): Drive = when {
        !playing -> Drive.STILL
        live -> Drive.LEVELS
        else -> Drive.WAVE
    }

    fun rays(low: Boolean): Int = if (low) RAYS_LOW else RAYS

    fun rayLength(level: Float): Float = RAY_MIN + level.coerceIn(0f, 1f) * RAY_RANGE

    /** The level of ray [k] of [rays] at [seconds]: the same hump for all of them, only moved along the ring. */
    fun wave(k: Int, rays: Int, seconds: Float): Float {
        val turn = k.toFloat() / rays * WAVE_HUMPS - seconds / WAVE_PERIOD
        return 0.34f + 0.2f * sin(turn * 2f * PI.toFloat())
    }

    /** The clock the wave is drawn by: on the Low tier it changes [LOW_HZ] times a second, so the ring redraws no more. */
    fun cappedClock(seconds: Float, low: Boolean): Float = if (low) floor(seconds * LOW_HZ) / LOW_HZ else seconds
}
