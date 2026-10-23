package kg.forester.sg.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import kg.forester.sg.service.Measured
import kg.forester.sg.service.PerfCalc
import kg.forester.sg.service.PerfRun
import kg.forester.sg.service.RunTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A run to send to the chat: a picture (the times, the G graph with the shifts, the conditions) and its samples as a
 * plain CSV from the start (time, speed, G, rpm, boost, pedal), through the share sheet.
 */
fun shareRun(ctx: Context, run: PerfRun, tr: RunTrace, m: Measured, keys: List<String>) {
    runCatching {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date(run.timeMs))
        val png = File(dir, "Разгон $stamp.png")
        png.outputStream().use { runPicture(run, m, keys).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val csv = File(dir, "Разгон $stamp.csv").also { it.writeText(runCsv(tr, m)) }
        val uris = arrayListOf(png, csv).map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отправить замер")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The samples from the start of the run to its end, the time from the start; the ECU columns at the nearest sample. */
fun runCsv(tr: RunTrace, m: Measured): String {
    val a = m.start ?: 0.0
    val b = m.end ?: m.speed.t.lastOrNull() ?: 0.0
    val g = PerfCalc.gCurve(m.speed, a - 0.5, b + 0.5).associateBy { it.t }
    return buildString {
        append("time_s,speed_kmh,g,rpm,boost_kpa,pedal_pct,throttle_pct\n")
        for (k in 0 until m.speed.size) {
            val t = m.speed.t[k]
            if (t < a - 0.5 || t > b + 0.5) continue
            val e = tr.ecu.minByOrNull { kotlin.math.abs(it.t / 1000.0 - t) }
            fun f(v: Number?, d: Int = 1) = v?.let { String.format(Locale.US, "%.${d}f", it.toDouble()) } ?: ""
            append("${f(t - a, 3)},${f(m.speed.v[k])},${f(g[t]?.g, 3)},${e?.rpm ?: ""},${f(e?.boost)},${f(e?.pedal)},${f(e?.thr)}\n")
        }
    }
}

/** 1600×900, in the app's colours. */
private fun runPicture(run: PerfRun, m: Measured, keys: List<String>): Bitmap {
    val w = 1600; val h = 900
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(C.Bg.toArgb())
    fun paint(color: androidx.compose.ui.graphics.Color, size: Float, bold: Boolean = false) = Paint().apply {
        this.color = color.toArgb(); textSize = size; isAntiAlias = true; isFakeBoldText = bold
    }
    c.drawText("Разгон  ·  " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(run.timeMs)), 48f, 72f, paint(C.Text, 44f, true))
    c.drawText(condLine(run), 48f, 118f, paint(C.Sub, 28f))
    // the times on the left
    var y = 190f
    for (k in keys) {
        val v = run.results[k] ?: continue
        if (y > h - 120) break
        c.drawText(resultShort(k), 48f, y, paint(C.Sub, 32f))
        val trap = run.results[k.replace("m", "v")]?.takeIf { k.endsWith("m") && k != "18m" }?.let { "  %.0f км/ч".format(it) }
            ?: run.results[k + PerfCalc.DIST]?.let { "  %.1f м".format(it) } ?: ""
        c.drawText("%.2f с".format(v) + trap, 230f, y, paint(if (run.clean(k)) C.Text else C.Orange, 38f, true))
        corrected(run, k)?.let { c.drawText("SAE %.2f".format(it), 230f, y + 30f, paint(C.Sub, 22f)) }
        y += 68f
    }
    // the graph on the right
    val gx = 600f; val gy = 160f; val gw = 950f; val gh = 600f
    val a = m.start ?: 0.0; val b = m.end ?: m.speed.t.lastOrNull() ?: 1.0
    val g = PerfCalc.gCurve(m.speed, a - 0.3, b + 0.3)
    if (g.size >= 2) {
        val gMax = maxOf(0.5, g.maxOf { it.g } * 1.2); val gMin = minOf(0.0, g.minOf { it.g }).coerceAtLeast(-0.4)
        val vMax = maxOf(1.0, g.maxOf { it.kmh } * 1.05)
        val t0 = a - 0.3; val t1 = b + 0.3
        fun x(t: Double) = (gx + (t - t0) / (t1 - t0) * gw).toFloat()
        fun yg(v: Double) = (gy + gh - (v - gMin) / (gMax - gMin) * gh).toFloat()
        val grid = paint(C.Border, 22f).apply { strokeWidth = 2f }
        var lv = 0.0
        while (lv <= gMax) { c.drawLine(gx, yg(lv), gx + gw, yg(lv), grid); c.drawText("%.1f g".format(lv), gx + 6f, yg(lv) - 6f, paint(C.Sub, 22f)); lv += 0.2 }
        for (sh in m.shifts) {
            c.drawRect(x(sh.from), gy, maxOf(x(sh.to), x(sh.from) + 3f), gy + gh, paint(C.Orange.copy(alpha = 0.18f), 0f))
            c.drawText("%.2f с".format(sh.sec), x(sh.from) + 6f, gy + 28f, paint(C.Orange, 24f))
        }
        fun curve(color: androidx.compose.ui.graphics.Color, width: Float, py: (kg.forester.sg.service.GPt) -> Float) {
            val p = Path()
            g.forEachIndexed { i, q -> if (i == 0) p.moveTo(x(q.t), py(q)) else p.lineTo(x(q.t), py(q)) }
            c.drawPath(p, paint(color, 0f).apply { style = Paint.Style.STROKE; strokeWidth = width })
        }
        curve(C.Blue.copy(alpha = 0.7f), 3f) { (gy + gh - it.kmh / vMax * gh).toFloat() }
        curve(C.Violet, 5f) { yg(it.g) }
        var s = 0
        while (a + s <= t1) { c.drawText("$s с", x(a + s) + 4f, gy + gh + 30f, paint(C.Sub, 22f)); s += if (b - a > 20) 5 else 2 }
        c.drawText("— ускорение, g     — скорость, до %.0f км/ч".format(g.maxOf { it.kmh }), gx, gy + gh + 70f, paint(C.Sub, 24f))
    }
    c.drawText(runFacts(run, m), 48f, h - 40f, paint(C.Sub, 26f))
    return bmp
}
