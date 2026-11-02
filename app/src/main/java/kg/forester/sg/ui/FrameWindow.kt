package kg.forester.sg.ui

import android.content.ComponentCallbacks2
import java.io.File
import java.io.OutputStream

/** How many frames each side of the rear view stay full size: the chase view turns up to 3 into a bend. */
const val FRAME_WINDOW_RADIUS = 3

/**
 * The painted frames on disk are at most this, oldest dropped first (cacheDir, so the system may clear it too). For
 * the SG5 in blue a full-size PNG is 100–240 KB, a half-size one about 70 KB: all 72 at both sizes come to some 20 MB.
 */
const val CAR_TINT_CACHE_BYTES = 40L * 1024 * 1024

/** The frames kept in full size whatever the car does, at 836×408: 7 × 1.36 MB = 10 MB instead of 72 × = 98 MB. */
fun frameWindow(count: Int, drive: Int): Set<Int> {
    if (count <= 0) return emptySet()
    return (-FRAME_WINDOW_RADIUS..FRAME_WINDOW_RADIUS).mapTo(HashSet()) { ((drive + it) % count + count) % count }
}

/**
 * What a memory warning takes: [HALVES] the half-size frames the car on screen doesn't need, [FULL] the app has left
 * the screen, every full-size frame goes (each leaves its half-size copy), [ALL] the system is about to kill
 * something, the half-size frames go too except those the return shows.
 */
enum class FrameTrim { NONE, HALVES, FULL, ALL }

fun frameTrimFor(level: Int) = when {
    level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> FrameTrim.ALL
    level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> FrameTrim.FULL
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> FrameTrim.HALVES
    else -> FrameTrim.NONE
}

/**
 * Which frames are held, at which size. Full size: the rear view's [frameWindow] and the view on screen ([look]).
 * Half size (24 MB for 72): only once asked for (a finger on the car, the turn into the rear view), and whatever
 * full frame is let go leaves one behind, so there is always a picture to show at once. Frames are only let go,
 * never recycled: the one on screen may still be drawn.
 */
class FrameStore<B : Any>(private val count: Int, drive: Int, private val shrink: (B) -> B) {
    private val window = frameWindow(count, drive)
    private val full = HashMap<Int, B>()
    private val half = HashMap<Int, B>()
    private var view = -1

    /** Off the screen: nothing full size fills up again until [show]. */
    @Volatile var hidden = false
        private set

    private fun staysFull(i: Int) = i in window || i == view

    private fun letGoFull(i: Int) {
        val b = full.remove(i) ?: return
        // no memory for the small copy: it is decoded again when wanted
        if (i !in half) runCatching { shrink(b) }.getOrNull()?.let { half[i] = it }
    }

    /** [i] is the view on screen now; the one before it, if out of the window, shrinks. */
    @Synchronized fun look(i: Int) {
        if (count == 0 || i == view) return
        val old = view
        view = i
        if (old !in window) letGoFull(old)
    }

    @Synchronized fun putFull(i: Int, b: B) {
        if (!hidden && staysFull(i)) full[i] = b
    }

    @Synchronized fun putHalf(i: Int, b: B) { half[i] = b }

    @Synchronized fun full(i: Int): B? = full[i]

    /** The sharpest picture of [i] there is, without decoding anything. */
    @Synchronized fun best(i: Int): B? = full[i] ?: half[i]

    @Synchronized fun fullHeld(): Set<Int> = full.keys.toSet()
    @Synchronized fun halfHeld(): Set<Int> = half.keys.toSet()

    /** Every frame has a picture of some size. */
    @Synchronized fun halvesComplete() = (0 until count).all { it in half || it in full }

    /** True if anything was let go. */
    @Synchronized fun trim(t: FrameTrim): Boolean {
        val fulls = full.size
        val halves = half.size
        if (t == FrameTrim.FULL || t == FrameTrim.ALL) {
            hidden = true
            full.keys.toList().forEach { letGoFull(it) }
        }
        if (t == FrameTrim.HALVES || t == FrameTrim.ALL) half.keys.retainAll { staysFull(it) }
        return full.size != fulls || half.size != halves
    }

    @Synchronized fun show() { hidden = false }
}

private val PNG_END = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())

/** What Bitmap.compress returned: false is a full disk, and what it wrote is not a frame. */
fun carTintCompressed(ok: Boolean) {
    if (!ok) throw java.io.IOException("frame not written")
}

/** The folder of one painted car: another model, paint or colour definition, or another install of the app, is a new one. */
fun carTintKey(dir: String, p: CarPaint, build: Long): String =
    "${dir}_${p.id}_${Integer.toHexString(p.rgb)}_${p.hue}_${p.grey}_${p.lift}_${p.needsWhite}_$build".replace(Regex("[^A-Za-z0-9._-]"), "_")

/**
 * Frames already painted, so the start after this one reads them instead of painting 72 frames again (a frame's paint
 * is the slow part). Lives in cacheDir, [cap] bytes at most, oldest dropped first. A frame is written aside and moved
 * in when complete: the head unit's power may go at any moment, and a broken file must not look like a frame.
 */
class TintCache(private val root: File, private val cap: Long) {
    /** The frame, if it is there and complete; one cut short is deleted, a part of a car must not be drawn on every start. */
    fun file(key: String, name: String): File? {
        val f = File(File(root, key), "$name.png")
        if (!f.isFile) return null
        if (endsAsPng(f)) return f
        f.delete()
        return null
    }

    private fun endsAsPng(f: File): Boolean = runCatching {
        if (f.length() < PNG_END.size) return false
        java.io.RandomAccessFile(f, "r").use { r ->
            r.seek(f.length() - PNG_END.size)
            ByteArray(PNG_END.size).also { r.readFully(it) }.contentEquals(PNG_END)
        }
    }.getOrDefault(false)

    @Synchronized fun write(key: String, name: String, body: (OutputStream) -> Unit) {
        val dir = File(root, key).apply { mkdirs() }
        val tmp = File(dir, "$name.tmp")
        val out = File(dir, "$name.png")
        try {
            tmp.outputStream().use { o ->
                body(o)
                o.flush()
                o.fd.sync() // to the flash before it gets the frame's name: the power may go next
            }
            if (!tmp.renameTo(out)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        trim(out)
    }

    /** Only [key] stays: what is on disk for another paint or model is not needed any more. */
    @Synchronized fun retain(key: String) {
        root.listFiles()?.filter { it.name != key }?.forEach { it.deleteRecursively() }
    }

    fun delete(key: String, name: String) { File(File(root, key), "$name.png").delete() }

    private fun trim(keep: File) {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= cap) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= cap) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }
}
