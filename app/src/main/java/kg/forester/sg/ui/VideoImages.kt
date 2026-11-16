package kg.forester.sg.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Posters for «Видео»: downloaded once, cut down to [MAX_PX] on the long side, kept in memory while the app lives (at
 * most 80, the longest unused go). Three at a time, so a tap on the site isn't queued behind pictures.
 */
internal object VideoImages {
    private const val MAX_PX = 400
    val cache = android.util.LruCache<String, ImageBitmap>(80)
    private val gate = Semaphore(3)
    private val failed = HashSet<String>()

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        synchronized(failed) { if (url in failed) return null }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = kg.forester.sg.video.VideoNet.bytes(url, 8_000, 15_000, 4 shl 20)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }.also {
            if (it != null) cache.put(url, it) else synchronized(failed) { failed += url }
        }
    }
}

/** A picture from the net: nothing (the box's own background) until it is there, nothing when it can't be had. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bmp by produceState(url?.let { VideoImages.cache.get(it) }, url) {
        if (value == null && url != null) value = VideoImages.load(url)
    }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = contentScale) else Box(modifier)
}
