package kg.forester.sg.video

import android.content.Context

/**
 * How a film sits on the screen. «Вписать»: all of the picture, black bars where the screen's shape differs (a 16:9 film
 * on a 1920×720 unit has a third of the width empty). «Заполнить»: the screen full, the edges of the picture cut off.
 */
object VideoFit {
    /** The film's size on a [boxW] × [boxH] screen for a picture of [aspect] (width / height): (width, height). */
    fun size(boxW: Float, boxH: Float, aspect: Float, fill: Boolean): Pair<Float, Float> {
        if (boxW <= 0f || boxH <= 0f || aspect <= 0f) return boxW to boxH
        val wider = boxW / boxH > aspect
        // fit: the side that runs out first sets the size; fill: the other one
        return if (wider != fill) (boxH * aspect) to boxH else boxW to (boxW / aspect)
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("video", Context.MODE_PRIVATE)
    fun fill(c: Context): Boolean = prefs(c).getBoolean("fill", false)
    fun setFill(c: Context, on: Boolean) { prefs(c).edit().putBoolean("fill", on).apply() }
}
