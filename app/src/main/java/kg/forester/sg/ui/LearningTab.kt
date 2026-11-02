package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wind
import kg.forester.sg.data.Repo
import kg.forester.sg.service.EcuLink
import kg.forester.sg.ssm.LearnedValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Обучение»: the learned values table (LV in RomRaider): IAM, the stored A/F trims by airflow and the fine
 * learning knock correction by RPM and load, read on request. The last reading stays until the next one.
 */
@Composable
internal fun LearningTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val init by Repo.ecuInit.collectAsState()
    val obd by Repo.obdMode.collectAsState()
    var result by remember {
        mutableStateOf(runCatching { LearnedValues.Result.fromJson(JSONObject(Repo.settings.learnedLast)) }.getOrNull())
    }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun readNow() {
        val i = init
        if (busy) return
        if (Repo.settings.demo) {
            result = LearnedValues.demo()
            status = "Демо-режим: пример, не ваша машина"
            return
        }
        if (i == null || obd || EcuLink.client == null) {
            status = "Нужна связь с ЭБУ по SSM: включите зажигание и подключите кабель"
            return
        }
        busy = true
        status = "Читаю из ЭБУ…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                EcuLink.mutex.withLock {
                    val c = EcuLink.client ?: return@withLock Result.failure(IllegalStateException("Связь с ЭБУ потеряна"))
                    runCatching { LearnedValues.read(ctx, c, i) }
                }
            }
            busy = false
            r.onSuccess {
                result = it
                Repo.settings.learnedLast = it.toJson().toString()
                status = listOfNotNull(
                    if (it.flkc == null) it.flkcError ?: "Таблицу FLKC не прочитать: в определениях нет её сетки для этой прошивки" else null,
                    if (it.missed > 0) "ЭБУ не ответил на часть значений (${it.missed}), они остались пустыми" else null,
                ).joinToString(". ")
            }.onFailure { kg.forester.sg.ssm.SsmTrace.log("learning: $it"); status = kg.forester.sg.data.Failure.text(it, "Не прочиталось") }
        }
    }
    val fmt = remember { SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Wind, "Обучение ЭБУ") {
                Chip(if (busy) "Читаю…" else "Прочитать", false, { readNow() })
            }
            Spacer(Modifier.height(10.dp))
            val r = result
            if (r == null) {
                T("То, что мозги выучили под ваш мотор и бензин: IAM, топливные коррекции по диапазонам расхода воздуха " +
                    "и таблица коррекции зажигания по детонации. Нажмите «Прочитать» с включённым зажиганием.", 13.sp, C.Sub)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    val iam = r.iam
                    T(iam?.let { "%.3f".format(it) } ?: "—", 40.sp,
                        if (iam == null) C.Sub else if (iam >= 0.95) C.Green else if (iam >= 0.75) C.Orange else C.Red, FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.padding(bottom = 7.dp)) {
                        T("IAM", 13.sp, C.Sub)
                        T(fmt.format(Date(r.timeMs)), 12.sp, C.Sub)
                    }
                }
                T("ROM ${r.romId}" + (r.cal?.let { " · $it" } ?: ""), 12.sp, C.Sub)
                Spacer(Modifier.height(14.dp))
                T("Топливная коррекция (выученная), %", 14.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (r.afBanks.isEmpty()) T("Для этой прошивки адресов нет", 13.sp, C.Sub)
                else {
                    val heads = r.afStarts?.map { "%.2f".format(it) } ?: listOf("A", "B", "C", "D")
                    Grid(
                        corner = if (r.afStarts != null) "MAF г/с" else "диапазон",
                        cols = heads,
                        rows = r.afBanks.indices.map { if (r.afBanks.size > 1) "банк ${it + 1}" else "" },
                        cell = { row, col -> r.afBanks[row].getOrNull(col) },
                        text = { "%.2f".format(it) },
                        color = { v -> if (kotlin.math.abs(v) >= 10) C.Red else if (kotlin.math.abs(v) >= 5) C.Orange else C.Text },
                        cellWidth = 64.dp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                NoteBox("Коррекция в пределах ±5% это норма. Больше плюса: подсос воздуха или слабый бензонасос и форсунки, " +
                    "больше минуса: переобогащение (форсунки льют, датчик кислорода, MAF). " +
                    "IAM 1,0 значит мозги дают полный угол; ниже, если ловили детонацию.")
            }
            if (status.isNotEmpty()) { Spacer(Modifier.height(8.dp)); T(status, 13.sp, C.Orange) }
        }
        Panel(Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Flame, "Коррекция зажигания (FLKC), °")
            Spacer(Modifier.height(10.dp))
            val r = result
            val t = r?.flkc
            if (r == null || t == null) {
                T(if (r == null) "Появится после чтения." else "Для прошивки ${r.romId} нет сетки таблицы в определениях, " +
                    "доступно только текущее значение FLKC на вкладке Мотор.", 13.sp, C.Sub)
            } else {
                Grid(
                    corner = "RPM \\ нагрузка г/об",
                    cols = r.loadStarts!!.map { "%.2f".format(it) },
                    rows = r.rpmStarts!!.map { "%.0f".format(it) },
                    cell = { row, col -> t.getOrNull(row)?.getOrNull(col) },
                    text = { if (it == 0.0) "0" else "%.2f".format(it) },
                    color = { v -> if (v <= -2.8) C.Red else if (v < -0.1) C.Orange else if (v > 0.1) C.Green else C.Sub },
                    cellWidth = 64.dp,
                )
                Spacer(Modifier.height(12.dp))
                val worst = t.flatten().minOrNull() ?: 0.0
                NoteBox(if (worst >= -0.1) "Все ячейки по нулям: мозги не ловили детонацию, зажигание на месте."
                    else "Минус в ячейке значит мозги убирают там угол из за детонации. Пара ячеек до −1,4° бывает и на " +
                        "стоке; много ячеек или −2,8° и ниже: плохой бензин, перегрев, нагар или проблема с датчиком детонации.")
            }
        }
    }
}

/** A small table: [corner] above the row names, a header of [cols], then the cells coloured by value. */
@Composable
private fun Grid(
    corner: String,
    cols: List<String>,
    rows: List<String>,
    cell: (Int, Int) -> Double?,
    text: (Double) -> String,
    color: (Double) -> Color,
    cellWidth: Dp,
) {
    val first = 92.dp
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            T(corner, 11.sp, C.Sub, modifier = Modifier.width(first), maxLines = 2)
            cols.forEach { T(it, 12.sp, C.Sub, FontWeight.Medium, Modifier.width(cellWidth), textAlign = TextAlign.Center) }
        }
        Spacer(Modifier.height(4.dp))
        rows.forEachIndexed { ri, name ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                T(name, 12.sp, C.Sub, modifier = Modifier.width(first))
                cols.indices.forEach { ci ->
                    val v = cell(ri, ci)
                    Box(
                        Modifier.width(cellWidth).padding(horizontal = 2.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (v != null && color(v) != C.Sub && color(v) != C.Text) color(v).copy(alpha = 0.16f) else C.CardHi)
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(v?.let(text) ?: "—", 13.sp, v?.let(color) ?: C.Sub, FontWeight.SemiBold) }
                }
            }
        }
    }
}
