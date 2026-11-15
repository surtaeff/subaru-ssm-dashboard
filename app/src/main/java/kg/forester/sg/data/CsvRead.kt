package kg.forester.sg.data

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reading a CSV log written by something else (a laptop logger, a spreadsheet, another app): the separator, quotes,
 * a decimal comma, the unit in the header and the many ways a time column is written. The app's own logs read the
 * same as before: comma, epoch ms or "yyyy-MM-dd HH:mm:ss" in the first column.
 */
object CsvRead {
    /** The separator of [header]: the most frequent of ; tab , outside quotes (a comma wins a tie: our own logs). */
    fun separator(header: String): Char {
        val counts = HashMap<Char, Int>()
        var quoted = false
        for (c in header) {
            if (c == '"') quoted = !quoted
            else if (!quoted && (c == ',' || c == ';' || c == '\t')) counts[c] = (counts[c] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value } ?: return ','
        return if ((counts[','] ?: 0) == best.value) ',' else best.key
    }

    /** One line split at [sep]; "a, b" in quotes stays one cell and "" inside is a quote. Cells are trimmed. */
    fun split(line: String, sep: Char): List<String> {
        if ('"' !in line) return line.split(sep).map { it.trim() }
        val out = ArrayList<String>()
        val b = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { b.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { out += b.toString().trim(); b.setLength(0) }
                else -> b.append(c)
            }
            i++
        }
        out += b.toString().trim()
        return out
    }

    /** A number in a cell: "12.5", "12,5" (where the separator is not a comma), "1 234,5"; NaN for anything else. */
    fun number(cell: String, sep: Char): Float {
        if (cell.isEmpty()) return Float.NaN
        cell.toFloatOrNull()?.let { return it }
        var s = cell.replace(" ", "").replace(" ", "")
        if (sep != ',' && s.count { it == ',' } == 1 && '.' !in s) s = s.replace(',', '.')
        return s.toFloatOrNull() ?: Float.NaN
    }

    /** A header cell and the unit in it: "Boost (psi)", "Boost [psi]", "Boost {psi}", "Boost · psi". */
    fun nameAndUnit(cell: String): Pair<String, String> {
        val h = cell.trim()
        Regex("^(.*?)\\s*[(\\[{]([^()\\[\\]{}]{1,12})[)\\]}]\\s*$").find(h)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
        Regex("^(.*?)\\s*·\\s*(\\S{1,12})$").find(h)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
        return h to ""
    }

    private val TIME_NAMES = setOf("time", "timestamp", "время", "t", "sec", "seconds", "ms", "millis", "date", "datetime", "дата", "epoch_ms", "t_ms")

    /** The column that holds the time: one named like it, else the first. */
    fun timeColumn(header: List<String>): Int {
        val names = header.map { nameAndUnit(it).first.lowercase(Locale.ROOT) }
        val exact = names.indexOfFirst { it in TIME_NAMES }
        if (exact >= 0) return exact
        val loose = names.indexOfFirst { it.contains("time") || it.contains("время") }
        return if (loose >= 0) loose else 0
    }

    private val CLOCK = Regex("^(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{1,2}(?:[.,]\\d+)?)$")
    private val DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,3})\\d*)?(Z|[+-]\\d{2}:?\\d{2})?$")

    /**
     * The time of each row in ms. Epoch ms (over 1e12) stay as they are, dates become epoch ms (local time unless the
     * cell says the zone), "h:mm:ss.SSS" and "mm:ss" become ms of the day, plain numbers are ms when the column says so
     * ("ms" in the name or the unit) or steps between rows are 20 or more, else seconds. A cell nothing reads gets the
     * previous time plus one second, so a row is never lost.
     */
    fun times(cells: List<String>, header: String): LongArray {
        val (name, unit) = nameAndUnit(header)
        val n = name.lowercase(Locale.ROOT); val u = unit.lowercase(Locale.ROOT)
        val raw = cells.map { it.trim().trim('"') }
        val plain = raw.map { it.replace(',', '.').toDoubleOrNull() }
        val numeric = plain.count { it != null } * 2 > raw.size
        val out = LongArray(raw.size)
        if (numeric) {
            val vals = plain.filterNotNull()
            val big = vals.firstOrNull()?.let { it > 1e12 } == true
            val avgStep = if (vals.size > 1) (vals.last() - vals.first()) / (vals.size - 1) else 0.0
            val ms = big || n.endsWith("ms") || n == "millis" || u == "ms" || u == "мс" ||
                (u != "s" && u != "с" && u != "sec" && !n.startsWith("sec") && avgStep >= 20 && vals.all { it == Math.floor(it) })
            var last = 0L
            for (i in raw.indices) {
                val v = plain[i]
                last = if (v == null) last + 1000 else if (ms) v.toLong() else Math.round(v * 1000)
                out[i] = last
            }
            return out
        }
        var last = 0L
        for (i in raw.indices) {
            val t = clockMs(raw[i]) ?: dateMs(raw[i])
            last = t ?: (last + 1000)
            out[i] = last
        }
        return out
    }

    /** "1:02:03.5", "02:03", "12:34:56,789" → ms; null when the cell is no clock. */
    fun clockMs(s: String): Long? {
        val m = CLOCK.find(s) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].replace(',', '.').toDouble()
        return h * 3_600_000 + min * 60_000 + Math.round(sec * 1000)
    }

    /** "2026-11-15 10:01:02", "2026-11-15T10:01:02.123Z", "…+06:00" → epoch ms; null when the cell is no date. */
    fun dateMs(s: String, zone: TimeZone = TimeZone.getDefault()): Long? {
        val m = DATE.find(s) ?: return null
        val g = m.groupValues
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        f.timeZone = when {
            g[8].isEmpty() -> zone
            g[8] == "Z" -> TimeZone.getTimeZone("UTC")
            else -> TimeZone.getTimeZone("GMT" + g[8].let { if (':' in it) it else it.substring(0, 3) + ":" + it.substring(3) })
        }
        val base = runCatching { f.parse("${g[1]}-${g[2]}-${g[3]} ${g[4]}:${g[5]}:${g[6]}")?.time }.getOrNull() ?: return null
        val frac = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.toLong() ?: 0L
        return base + frac
    }

    /** The unit a chart shows for a header unit written the English way ("C", "kPa", "km/h"…), else as written. */
    fun unitRu(u: String): String = when (u.lowercase(Locale.ROOT)) {
        "c", "°c", "degc", "deg c" -> "°C"
        "v", "volt", "volts" -> "В"
        "kpa" -> "кПа"
        "km/h", "kmh", "kph" -> "км/ч"
        "rpm", "1/min" -> "об/мин"
        "g/s" -> "г/с"
        "ms" -> "мс"
        "s", "sec" -> "с"
        "l/h" -> "л/ч"
        "deg", "°" -> "°"
        else -> u
    }
}
