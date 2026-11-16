package kg.forester.sg.video

import kg.forester.sg.data.GpsFix

/**
 * Video on the head unit's screen only while the car stands: from 5 km/h it pauses and the picture goes away, and it
 * may go on below 2 km/h (GPS noise at a stop stays under that). The speed is the larger of the ECU's and a fresh GPS
 * fix's. A car that tells no speed at all counts as standing, as for the other prompts of the app: nothing says it moves.
 */
object VideoDriving {
    const val MOVING_KMH = 5f
    const val STOPPED_KMH = 2f
    /** A GPS fix older than this says nothing about now (a tunnel, a lost signal): the ECU's speed alone then. */
    const val GPS_FRESH_MS = 5_000L

    fun speed(ecuKmh: Int?, gps: GpsFix?, nowElapsedMs: Long): Float {
        val g = gps?.takeIf { it.speedKnown && !it.network && (it.arrivedMs == 0L || nowElapsedMs - it.arrivedMs in 0..GPS_FRESH_MS) }?.speedKmh ?: 0f
        return maxOf(ecuKmh?.toFloat() ?: 0f, g)
    }

    /** With a gap between the two thresholds, so a car creeping in a jam doesn't flicker the picture on and off. */
    fun moving(wasMoving: Boolean, kmh: Float): Boolean = if (wasMoving) kmh >= STOPPED_KMH else kmh >= MOVING_KMH
}
