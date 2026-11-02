package kg.forester.sg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import kg.forester.sg.data.ScreenLayout
import kg.forester.sg.data.ScreenModel
import kg.forester.sg.data.ScreenPage
import kg.forester.sg.service.BigSender
import kg.forester.sg.service.ScreenFrames
import kg.forester.sg.ui.ScreenLook
import kg.forester.sg.ui.ScreenRenderer
import java.io.File

/**
 * Debug builds, by adb: `am broadcast -n kg.forester.sg/.ScreenDumpReceiver`. Draws every own layout at every big panel's
 * size, in each look, with the renderer the USB link uses, into the app's external files (screendump/), and writes in
 * wire.txt what the frames would cost on the wire: a page change, and a frame 200 ms later with live values.
 */
class ScreenDumpReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            runCatching { dump(c.applicationContext) }.onFailure { Log.e("ScreenDump", "failed", it) }
            Log.i("ScreenDump", "done")
            pending.finish()
        }.start()
    }

    private fun dump(c: Context) {
        val dir = File(c.getExternalFilesDir(null), "screendump").apply { deleteRecursively(); mkdirs() }
        val wire = StringBuilder()
        val pages = ScreenLayout.entries.filter { it.own }.map { ScreenPage(1, it, emptyList(), "neon") } +
            ScreenPage(2, ScreenLayout.GAUGE_TILES, listOf("rpm", "coolant", "boost"), "sti") + ScreenPage(3, ScreenLayout.TILES6, listOf("rpm", "speed", "coolant", "oil", "battery", "boost"))
        for (m in ScreenModel.entries.filter { it.big }.distinctBy { it.w to it.h }) {
            val rot = if (m.h > m.w) 1 else 0
            val w = if (rot == 1) m.h else m.w
            val h = if (rot == 1) m.w else m.h
            for (p in pages) for (look in listOf(ScreenLook.current()) + ScreenLook.chosen) {
                val name = "${w}x${h}-${p.layout.name.lowercase()}-${look.id.ifEmpty { "app" }}"
                val a = ScreenRenderer.render(c, p, w, h, false, look = look)
                File(dir, "$name.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (look.id.isEmpty() || look.id == "studio") {
                    Thread.sleep(200)
                    val b = ScreenRenderer.render(c, p, w, h, false, look = look)
                    wire.append(name).append(' ').append(cost(a, b)).append('\n')
                }
                a.recycle()
            }
        }
        // a panel that lies on its side: drawn into a kept bitmap, the same as the plain turn
        val up = ScreenRenderer.render(c, pages[0], 1280, 800, false, look = ScreenLook.current())
        for (q in 1..3) {
            val turned = ScreenRenderer.toPanel(up, q)
            val into = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
            ScreenRenderer.toPanelInto(up, q, into)
            wire.append("turn$q same=").append(turned.sameAs(into)).append('\n')
        }
        File(dir, "wire.txt").writeText(wire.toString())
        Log.i("ScreenDump", wire.toString())
    }

    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height); for (i in it.indices) it[i] = ScreenFrames.rgb565(it[i]) }

    private fun cost(a: Bitmap, b: Bitmap): String {
        fun run(prev: IntArray?, cur: IntArray, bmp: Bitmap, jpeg: Boolean): String {
            var bytes = 0
            var rects = 0
            var js = 0
            val buf = java.io.ByteArrayOutputStream()
            val s = BigSender(bmp.width, bmp.height, 131072, { jpeg }, { r ->
                buf.reset()
                val part = Bitmap.createBitmap(bmp, r.x, r.y, r.w, r.h)
                part.compress(Bitmap.CompressFormat.JPEG, 82, buf)
                part.recycle()
                buf.toByteArray()
            }) { head, body ->
                rects++
                if (head.startsWith("J")) js++
                bytes += head.length + 1 + body.size
                true
            }
            s.frame(prev, cur)
            return "${bytes / 1024}KB/${rects}r/${js}j"
        }
        val pa = px(a)
        val pb = px(b)
        return "whole=${run(null, pa, a, true)} wholeLossless=${run(null, pa, a, false)} next=${run(pa, pb, b, true)} changedPx=${pa.indices.count { pa[it] != pb[it] }}"
    }
}
