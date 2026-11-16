package kg.forester.sg.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File

/**
 * User pictures for gauge faces (style "photo:<name>"): copied from the gallery, cropped to a centred square
 * and scaled to [SIZE] px, so a huge photo costs no more memory than a small one.
 */
object GaugeImages {
    private const val SIZE = 640
    private val cache = LruCache<String, Bitmap>(16)

    private fun dir(ctx: Context) = File(ctx.filesDir, "gauges").apply { mkdirs() }

    /** Saves the picture behind [uri]; returns its name for the style, or null if it is not an image. */
    fun import(ctx: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val name = "g${System.currentTimeMillis()}.jpg"
        File(dir(ctx), name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        cache.put(name, out)
        name
    }.getOrNull()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gauge_images", Context.MODE_PRIVATE)

    /**
     * Where the next picked picture goes: "tach", "speedo", "custom:<id>" (own design) or "<layout key>/<card id>". Kept in the prefs, not in
     * the picker dialog, because the dialog (or the whole activity) may be gone when the gallery returns.
     */
    fun setTarget(ctx: Context, target: String) = prefs(ctx).edit().putString("target", target).apply()

    /** Gallery result: import the picture and give the waiting gauge the "photo:<name>" style. */
    fun onPicked(ctx: Context, uri: Uri?) {
        val target = prefs(ctx).getString("target", null) ?: return
        prefs(ctx).edit().remove("target").apply()
        // the header's logo or the start-up picture: kept whole, not cut square like a gauge face
        if (target.startsWith("brand:")) { uri?.let { BrandLogos.importCustom(ctx, target.removePrefix("brand:"), it) }; return }
        val name = uri?.let { import(ctx, it) } ?: return
        val style = "photo:$name"
        when {
            target == "tach" -> Repo.settings.tachStyle = style
            target == "speedo" -> Repo.settings.speedoStyle = style
            target.startsWith("custom:") ->
                CustomGauges.byStyleId(target)?.let { CustomGauges.save(it.copy(image = name)) }
            else -> {
                val key = target.substringBefore('/')
                val id = target.substringAfter('/')
                val board = Boards.byKey(key)
                val raw = Repo.boardFlow(key).value
                Repo.settings.setBoard(key, board.format(board.parse(raw).map { if (it.id == id) it.copy(style = style) else it }, board.isFree(raw)))
            }
        }
    }

    /** The app's own art faces: "asset:gauge_art/art_oni.webp" is read from the APK, any other name from the gallery copies. */
    const val ASSET = "asset:"

    fun bitmap(ctx: Context, name: String): Bitmap? = cache.get(name) ?: (
        if (name.startsWith(ASSET)) runCatching { ctx.assets.open(name.removePrefix(ASSET)).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        else BitmapFactory.decodeFile(File(dir(ctx), name).absolutePath)
        )?.also { cache.put(name, it) }
}
