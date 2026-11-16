package kg.forester.sg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.ssm.Obdex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * «Подробнее» under a standard code on «Ошибки»: obdex's causes, symptoms, repair estimate and flags. The base is
 * downloaded once on a tap; nothing is shown for the make's own codes.
 */
@Composable
fun ObdexDetails(code: String) {
    if (!Obdex.applies(code)) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(code) { mutableStateOf(false) }
    val changed by Obdex.changed.collectAsState()
    val progress by Obdex.progress.collectAsState()
    var failed by remember { mutableStateOf(false) }

    Spacer(Modifier.height(4.dp))
    Chip(if (open) "Скрыть подробности" else "Подробнее", open, { open = !open })
    if (!open) return

    val have = remember(changed) { Obdex.have(ctx.filesDir) }
    if (!have) {
        Column(Modifier.padding(top = 6.dp)) {
            T("Причины, симптомы и примерная цена ремонта из открытой базы obdex. Базу нужно скачать один раз, " +
                "около ${Obdex.SIZE_MB} МБ. Тексты в ней на английском.", 12.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            if (progress != null) T(progress!!, 12.sp, C.Violet)
            else Chip("Скачать базу", false, {
                failed = false
                scope.launch {
                    val n = withContext(Dispatchers.IO) { Obdex.download(ctx.filesDir) }
                    failed = n == null
                }
            })
            if (failed) T("Не удалось скачать (нет интернета?). Попробуйте ещё раз", 12.sp, C.Orange)
        }
        return
    }

    val e by androidx.compose.runtime.produceState<Obdex.Entry?>(null, code, changed) {
        value = withContext(Dispatchers.IO) { Obdex.lookup(ctx.filesDir, code) }
    }
    val entry = e
    if (entry == null) {
        T("В базе obdex этого кода нет", 12.sp, C.Sub, modifier = Modifier.padding(top = 6.dp))
        return
    }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        flags(entry).takeIf { it.isNotEmpty() }?.let { T(it.joinToString(" · "), 12.sp, C.Orange) }
        repair(entry)?.let { T(it, 12.sp, C.Text) }
        if (entry.causes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            T("Частые причины", 13.sp, weight = FontWeight.SemiBold)
            entry.causes.forEach { c ->
                Row(verticalAlignment = Alignment.Top) {
                    val l = Obdex.likelihoodRu(c.likelihood)
                    T(l.ifEmpty { "•" }, 11.sp, if (c.likelihood == "high") C.Red else C.Sub, modifier = Modifier.width(56.dp))
                    T(c.text, 12.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        if (entry.symptoms.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            T("Что заметно", 13.sp, weight = FontWeight.SemiBold)
            entry.symptoms.forEach { T("• $it", 12.sp) }
        }
        if (entry.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            T("Что проверяет блок", 13.sp, weight = FontWeight.SemiBold)
            T(entry.description, 12.sp, C.Sub)
        }
        if (entry.related.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            T("Связанные коды: " + entry.related.joinToString(", "), 11.sp, C.Sub)
        }
        Spacer(Modifier.height(4.dp))
        T("Данные: obdex, github.com/foerbsnavi/obdex, CC0. Цены ориентировочные, в евро", 10.sp, C.Sub)
    }
}

private fun flags(e: Obdex.Entry): List<String> = buildList {
    if (e.mil == true) add("горит Check Engine")
    if (e.limp == true) add("возможен аварийный режим")
    if (e.emissions == true) add("влияет на выхлоп")
    if (e.driveCycle == true) add("гаснет после нескольких поездок без ошибки")
}

private fun repair(e: Obdex.Entry): String? {
    val parts = buildList {
        Obdex.difficultyRu(e.difficulty).takeIf { it.isNotEmpty() }?.let { add("Ремонт: $it") }
        if (e.diy == true && e.difficulty != "shop_only") add("можно своими руками")
        e.hours?.let { (a, b) -> add("${num(a)}–${num(b)} ч") }
        e.costEur?.let { (a, b) -> add("примерно $a–$b €") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun num(x: Double) = if (x == Math.floor(x)) x.toInt().toString() else String.format(Locale.US, "%.1f", x).replace('.', ',')
