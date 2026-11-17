package kg.forester.sg.video

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo

object VideoDriving {
    const val MOVING_KMH = 5f
    const val STOPPED_KMH = 2f
    const val GPS_FRESH_MS = 5_000L

    fun speed(ecuKmh: Int?, gps: GpsFix?, nowElapsedMs: Long): Float {
        val g = gps?.takeIf { it.speedKnown && !it.network && (it.arrivedMs == 0L || nowElapsedMs - it.arrivedMs in 0..GPS_FRESH_MS) }?.speedKmh ?: 0f
        return maxOf(ecuKmh?.toFloat() ?: 0f, g)
    }

    fun moving(wasMoving: Boolean, kmh: Float): Boolean = if (wasMoving) kmh >= STOPPED_KMH else kmh >= MOVING_KMH
}

@Composable
fun rememberCarMoving(): Boolean {
    val live by Repo.live.collectAsState()
    val gps by Repo.gps.collectAsState()
    var wasMoving by remember { mutableStateOf(false) }
    val kmh = VideoDriving.speed(live.speedKmh, gps, SystemClock.elapsedRealtime())
    val now = VideoDriving.moving(wasMoving, kmh)
    if (now != wasMoving) wasMoving = now
    return now
}
