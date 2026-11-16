package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Upload
import kg.forester.sg.data.CanChannel
import kg.forester.sg.data.CanChannels
import kg.forester.sg.data.CanProfile
import kg.forester.sg.data.CanProfiles
import kg.forester.sg.ssm.CanDb
import kg.forester.sg.ssm.CanImport
import kg.forester.sg.ssm.CanMsg
import kg.forester.sg.ssm.CanSig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A value of a description to tick: its signal, the name it gets and how it becomes a channel. */
private class CanEntry(val key: String, val msg: CanMsg, val sig: CanSig, val name: String, val preset: Boolean, val make: (String) -> CanChannel?)

/** Every readable signal of [db] as an entry, the multiplexors aside. */
private fun entries(db: CanDb): List<CanEntry> = db.messages.flatMapIndexed { i, m ->
    m.signals.filter { !it.isMuxer }.map { s -> CanEntry("$i/${s.name}", m, s, s.name, false) { id -> CanImport.channel(m, s, id) } }
}

private fun readText(ctx: android.content.Context, uri: android.net.Uri): String {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("файл не открылся")
    if (bytes.size > 8_000_000) error("файл больше 8 МБ")
    return String(bytes, Charsets.UTF_8)
}

/**
 * «Импорт DBC / RealDash XML»: a file the driver picks (SAF), its messages and signals to tick, the ticked ones added
 * to the own channels. A DBC's whole bus can be thousands of signals: the search narrows the list.
 */
@Composable
fun CanImportChip() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var db by remember { mutableStateOf<CanDb?>(null) }
    var file by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "файл"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { CanImport.parse(readText(ctx, uri)) } }
            r.onSuccess { db = it; file = name; msg = "" }
            r.onFailure { msg = "$name: " + (it.message ?: it.toString()); bad = true }
        }
    }
    Chip("Импорт DBC / RealDash XML", false, { pick.launch(arrayOf("*/*")) }, Lucide.Upload)
    db?.let { d ->
        val es = remember(d) { entries(d) }
        CanPickDialog(file, listOfNotNull(
            "Сообщений ${d.messages.size}, сигналов ${d.signalCount}",
            d.kbit?.let { "скорость шины по файлу $it кбит" },
            d.warnings.size.takeIf { it > 0 }?.let { "не разобрано строк: $it" },
        ).joinToString(" · "), null, es, onDone = { a, s -> msg = added(a, s); bad = false; db = null }) { db = null }
    }
    if (msg.isNotEmpty()) T(msg, 12.sp, if (bad) C.Red else C.Green)
}

private fun added(a: Int, same: Int) = "Добавлено значений: $a" + (if (same > 0) ", уже были: $same" else "")

/** «Готовые профили»: the list, then a profile's values to add (or a template's form). */
@Composable
fun CanProfilesDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<CanProfile?>(null) }
    var template by remember { mutableStateOf<CanChannel?>(null) }
    var msg by remember { mutableStateOf("") }
    template?.let { t -> CanChannelsDialog(t) { template = null }; return }
    open?.let { p ->
        var db by remember(p) { mutableStateOf<CanDb?>(null) }
        LaunchedEffect(p) {
            withContext(Dispatchers.IO) {
                runCatching { CanImport.parse(ctx.assets.open("can_profiles/${p.asset}").bufferedReader().use { it.readText() }) }
            }.onSuccess { db = it }.onFailure { msg = "${p.title}: " + (it.message ?: it.toString()); open = null }
        }
        val d = db ?: return
        val es = remember(d) {
            val picked = p.picks.mapIndexedNotNull { i, pick ->
                val (m, s) = CanProfiles.find(d, pick) ?: return@mapIndexedNotNull null
                CanEntry("pick$i", m, s, pick.name, true) { id -> CanProfiles.channel(d, pick, id) }
            }
            val used = picked.map { it.msg.id to it.sig.name }.toSet()
            picked + entries(d).filter { (it.msg.id to it.sig.name) !in used }
        }
        CanPickDialog(p.title, p.about + ". Скорость: ${p.kbit}; ID: ${p.ids}", p, es,
            onDone = { a, s -> msg = added(a, s); open = null }) { open = null }
        return
    }
    CarSheet(onClose, 860.dp, 620.dp, head = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Готовые профили", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Готово", true, onClose)
        }
        Spacer(Modifier.height(4.dp))
        SettingHint("Потоки ЭБУ и CAN-датчиков, которые знают дашборды: выберите свой, основные значения уже отмечены, " +
            "роли (ЕГТ, давление масла, AFR…) расставлены. Скорость CANable поставьте как у устройства")
        if (msg.isNotEmpty()) T(msg, 12.sp, C.Green)
        Spacer(Modifier.height(6.dp))
    }) {
        CanProfiles.all.forEach { p ->
            var about by remember(p.key) { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.CardHi)
                    .clickable {
                        when {
                            p.template != null -> template = p.template.invoke(CanChannels.newId())
                            p.asset != null -> open = p
                            else -> about = !about
                        }
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                T(p.title, 15.sp)
                if (p.template == null) T("Скорость: ${p.kbit} · ID: ${p.ids}", 12.sp, C.Sub)
                if (p.asset == null && p.template == null || about) T(p.about, 12.sp, C.Sub)
                T(when {
                    p.template != null -> p.about
                    p.verified -> "Проверено: ${p.source}"
                    else -> "Не проверено: ${p.source}"
                }, 12.sp, if (p.template != null) C.Sub else if (p.verified) C.Green else C.Orange)
            }
        }
    }
}

/**
 * The values of a description with ticks: [preset] ones ticked already; a message's line ticks all of it.
 * What can't be read here shows why, without a tick.
 */
@Composable
private fun CanPickDialog(
    title: String, info: String, profile: CanProfile?, list: List<CanEntry>,
    onDone: (Int, Int) -> Unit, onClose: () -> Unit,
) {
    var chosen by remember(list) { mutableStateOf(list.filter { it.preset && it.sig.problem == null }.map { it.key }.toSet()) }
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = remember(list, q) {
        if (q.isEmpty()) list else list.filter {
            q in it.name.lowercase() || q in it.sig.name.lowercase() || q in it.msg.name.lowercase() || q in it.msg.idText().lowercase() ||
                q in it.sig.comment.lowercase()
        }
    }
    val limit = 300
    CarSheet(onClose, 860.dp, 620.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(title, 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Chip("Отмена", false, onClose)
            Spacer(Modifier.width(8.dp))
            Chip("Добавить: ${chosen.size}", chosen.isNotEmpty(), {
                if (chosen.isNotEmpty()) {
                    val r = CanProfiles.add(list.filter { it.key in chosen }.map { e -> { id: String -> e.make(id)?.copy(name = e.name) } })
                    onDone(r.first, r.second)
                }
            })
        }
        Spacer(Modifier.height(4.dp))
        T(info, 12.sp, C.Sub)
        profile?.let { p ->
            T(if (p.verified) "Проверено: ${p.source}" else "Не проверено: ${p.source}", 12.sp, if (p.verified) C.Green else C.Orange)
        }
        if (list.size > 12) {
            Spacer(Modifier.height(6.dp))
            FormField("Поиск: имя, ID, комментарий", query, Modifier.fillMaxWidth()) { query = it }
        }
        Spacer(Modifier.height(6.dp))
        if (shown.size > limit) T("Показаны первые $limit из ${shown.size}: уточните поиск", 12.sp, C.Orange)
        val groups = remember(shown) { shown.take(limit).groupBy { it.msg } }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            groups.forEach { (m, es) ->
                item(key = "m" + es.first().key) {
                    val ok = es.filter { it.sig.problem == null }.map { it.key }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).clickable(enabled = ok.isNotEmpty()) {
                            chosen = if (ok.all { it in chosen }) chosen - ok.toSet() else chosen + ok
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(if (m.ext) "${m.idText()} (29 бит)  ${m.name}" else "${m.idText()}  ${m.name}", 13.sp, C.Violet, FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                        T(listOfNotNull("${m.size} байт", m.cycleMs?.let { "$it мс" }).joinToString(" · "), 12.sp, C.Sub)
                    }
                    if (m.comment.isNotBlank()) T(m.comment, 12.sp, C.Sub, maxLines = 2)
                }
                items(es, key = { it.key }) { e -> EntryRow(e, e.key in chosen) { chosen = if (e.key in chosen) chosen - e.key else chosen + e.key } }
            }
        }
    }
}

@Composable
private fun EntryRow(e: CanEntry, on: Boolean, toggle: () -> Unit) {
    val ok = e.sig.problem == null
    val c = remember(e) { if (ok) e.make("·") else null }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(C.CardHi)
            .clickable(enabled = ok, onClick = toggle).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) C.Purple else C.Card).border(1.dp, if (on) C.Purple else C.Border, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { if (on) Icon(Lucide.Check, null, tint = C.Text, modifier = Modifier.size(16.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(e.name + if (e.name != e.sig.name) "  (${e.sig.name})" else "", 14.sp, if (ok) C.Text else C.Sub, maxLines = 1)
            val sub = if (c == null) e.sig.problem.orEmpty() else listOfNotNull(
                c.idText().substringAfter(", "),
                c.formula.takeIf { it != "x" },
                c.units.takeIf { it.isNotBlank() },
                CanChannel.ROLES.firstOrNull { it.first == c.role && c.role.isNotEmpty() }?.let { "роль: ${it.second}" },
                e.sig.values.takeIf { it.isNotEmpty() }?.entries?.take(4)?.joinToString(", ") { "${it.key}=${it.value}" },
            ).joinToString(" · ")
            T(sub, 12.sp, if (ok) C.Sub else C.Orange, maxLines = 1)
            if (e.sig.comment.isNotBlank()) T(e.sig.comment, 11.sp, C.Sub, maxLines = 1)
        }
    }
}
