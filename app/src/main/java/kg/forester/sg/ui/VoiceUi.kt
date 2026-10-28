package kg.forester.sg.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kg.forester.sg.service.Speech
import kg.forester.sg.service.Voice
import kotlinx.coroutines.delay

/** Short on-screen confirmation of what the voice control understood. */
@Composable
fun VoiceToast(modifier: Modifier = Modifier) {
    val t by Voice.toast.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(t) {
        if (t != null && SystemClock.elapsedRealtime() - t!!.first < 3000) {
            visible = true; delay(2600); visible = false
        }
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(C.CardHi).border(1.dp, C.Purple, RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Mic, null, tint = C.Violet, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            T(t?.second ?: "", 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun VoiceSettings() {
    val ctx = LocalContext.current
    val status by Voice.status.collectAsState()
    val heard by Voice.lastHeard.collectAsState()
    var on by remember { mutableStateOf(Voice.enabled(ctx)) }
    var wake by remember { mutableStateOf(Voice.wakeWord(ctx)) }
    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(470.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Mic, "Голосовое управление")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Голосовой ассистент", 15.sp)
                    T("Офлайн, слушает своё имя. Модель ~45 МБ скачается один раз", 11.sp, C.Sub)
                }
                Switch(on, { on = it; Voice.setEnabled(ctx, it) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = wake, onValueChange = { wake = it }, singleLine = true,
                label = { FieldLabel("Имя ассистента (на что он откликается)") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default,
            )
            Spacer(Modifier.height(8.dp))
            ActionButton("Сохранить имя", wake.isNotBlank()) {
                Voice.setWakeWord(ctx, wake)
                if (on) { Voice.stop(); Voice.start(ctx) }
            }
            Spacer(Modifier.height(12.dp))
            var gain by remember { mutableStateOf(Voice.gain(ctx)) }
            var sens by remember { mutableStateOf(Voice.sensitive(ctx)) }
            T("Чувствительность микрофона", 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1f to "×1", 2f to "×2", 3f to "×3", 4f to "×4").forEach { (g, l) ->
                    Chip(l, gain == g, { gain = g; Voice.setGain(ctx, g) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Чувствительный режим микрофона", 13.sp, modifier = Modifier.weight(1f))
                Switch(sens, { sens = it; Voice.setSensitive(ctx, it); if (on) Voice.restart(ctx) },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(10.dp))
            T("Статус: $status", 13.sp, if (status.startsWith("слушаю")) C.Green else C.Sub)
            T("Услышано: ${heard.ifBlank { "—" }}", 13.sp, C.Sub)
            Spacer(Modifier.height(12.dp))
            // a command typed instead of said: to try the assistant where one can't speak up
            var typed by remember { mutableStateOf("") }
            T("Проверить команду без голоса", 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    placeholder = { T("какая температура масла", 13.sp, C.Sub) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                        focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // the grey example looks typed in, so with the field empty the button says the example instead of greying out
                Box(Modifier.width(120.dp)) { ActionButton("Сказать", true) { Voice.rehearse(ctx, typed.trim().ifEmpty { "какая температура масла" }) } }
            }
            T("Как будто сказали «${Voice.wakeWord(ctx)}, …»: шар покажет фразу, ассистент ответит и выполнит", 11.sp, C.Sub)
            Spacer(Modifier.height(12.dp))
            MediaAccessRow()
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            val w = Voice.wakeWord(ctx).replaceFirstChar { it.uppercase() }
            Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OwnCommands(w)
                Spacer(Modifier.height(6.dp))
                T("Команды", 16.sp, weight = FontWeight.SemiBold)
                (kg.forester.sg.service.Commands.EXAMPLES.map { (cmd, what) -> "«$w, $cmd»" to what } +
                    ("«$w» … пауза … «навигатор»" to "можно и раздельно: после имени есть 6 секунд")).forEach { (cmd, what) ->
                    Column {
                        T(cmd, 14.sp)
                        T(what, 11.sp, C.Sub)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            T("«Алиса» в Яндекс Навигаторе отвечает на «Слушай, Алиса», когда навигатор на экране (в т.ч. в разделённом режиме).", 12.sp, C.Sub)
        }
    }
}

/**
 * The driver's own phrases: «когда скажу» → what to do, written as ordinary commands (several with ";"). A step the
 * assistant doesn't know is marked right away, «Проверить» runs it as if said.
 */
@Composable
private fun OwnCommands(wake: String) {
    val ctx = LocalContext.current
    val list by Voice.ownList.collectAsState()
    val own = list ?: remember { Voice.own(ctx) }
    var phrase by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = C.Text, unfocusedTextColor = C.Text,
        focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
    )
    val cmds = kg.forester.sg.service.Commands
    T("Свои команды", 16.sp, weight = FontWeight.SemiBold)
    T("Своя фраза и что сделать: любые команды из списка ниже, несколько через точку с запятой. " +
        "Приложение называйте в самой команде: на ютубе, в яндекс музыке, в спотифай, в вк, в 2гис. " +
        "Звёздочка ловит любые слова: покажи клип * → включи на ютубе * клип", 11.sp, C.Sub)
    own.forEachIndexed { i, o ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("«$wake, ${o.phrase}»", 14.sp)
                T(o.action, 11.sp, C.Sub)
            }
            Chip("Проверить", false, { Voice.rehearse(ctx, o.phrase) })
            Spacer(Modifier.width(6.dp))
            Chip("Удалить", false, { Voice.setOwn(ctx, own.filterIndexed { k, _ -> k != i }) })
        }
    }
    T("Шаблоны", 13.sp, C.Sub)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cmds.OWN_TEMPLATES.forEach { (name, p, a) -> Chip(name, false, { phrase = p; action = a }) }
    }
    OutlinedTextField(
        value = phrase, onValueChange = { phrase = it }, singleLine = true,
        label = { FieldLabel("Когда скажу") }, placeholder = { T("едем в гараж", 13.sp, C.Sub) },
        colors = fieldColors, modifier = Modifier.fillMaxWidth(),
    )
    T("Обычные русские слова: редкие названия распознаватель может не знать, проверьте по строке Услышано слева", 11.sp, C.Sub)
    OutlinedTextField(
        value = action, onValueChange = { action = it }, singleLine = true,
        label = { FieldLabel("Сделать") }, placeholder = { T("поехали на Ленина 10; включи музыку", 13.sp, C.Sub) },
        colors = fieldColors, modifier = Modifier.fillMaxWidth(),
    )
    fun add(piece: String) { action = if (action.isBlank()) piece else action.trimEnd().removeSuffix(";") + "; " + piece }
    var all by remember { mutableStateOf(false) }
    T("Добавить в «Сделать»", 13.sp, C.Sub)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cmds.OWN_POPULAR.forEach { (name, piece) -> Chip(name, false, { add(piece) }) }
        Chip("Все команды (${cmds.OWN_GROUPS.sumOf { it.second.size }})…", false, { all = true })
    }
    if (all) AllCommandsDialog({ all = false }) { add(it); all = false }
    // each step as the assistant takes it; a "*" stands for what will be said there, checked with a sample word
    val steps = cmds.steps(action)
    steps.forEach { step ->
        val what = Voice.describe(step.replace("*", "кино"))?.replace("кино", "*")
        if (what != null) T("✓ $step → $what", 12.sp, C.Green) else T("✗ $step → не понимаю, переформулируйте как в списке команд", 12.sp, C.Orange)
    }
    if (steps.any { "*" in it } && "*" !in phrase) T("В «Сделать» есть *, а во фразе нет: подставить будет нечего", 12.sp, C.Orange)
    ActionButton("Добавить", phrase.isNotBlank() && steps.isNotEmpty()) {
        val p = phrase.trim().lowercase()
        Voice.setOwn(ctx, own.filterNot { it.phrase == p } + kg.forester.sg.service.OwnCommand(p, action.trim()))
        phrase = ""; action = ""
    }
}

/**
 * Everything the assistant can do, by group, with a search over the names and the phrases: a tap picks the
 * phrase for the «Сделать» field.
 */
@Composable
private fun AllCommandsDialog(onClose: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val needle = q.trim().lowercase()
    val groups = kg.forester.sg.service.Commands.OWN_GROUPS.map { (g, ps) ->
        g to ps.filter { (n, t) -> needle.isEmpty() || needle in n.lowercase() || needle in t.lowercase() || needle in g.lowercase() }
    }.filter { it.second.isNotEmpty() }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(900.dp).heightIn(max = 600.dp).clip(RoundedCornerShape(20.dp))
                .background(C.Card).border(1.dp, C.Border, RoundedCornerShape(20.dp)).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Все команды", 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Chip("Закрыть", false, onClose)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                leadingIcon = { Icon(Lucide.Search, null, tint = C.Sub, modifier = Modifier.size(18.dp)) },
                placeholder = { T("Поиск: заправка, ютуб, громкость…", 13.sp, C.Sub) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groups.isEmpty()) T("Ничего не нашлось", 13.sp, C.Sub)
                groups.forEach { (g, ps) ->
                    T(g, 14.sp, C.Violet, FontWeight.SemiBold)
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ps.forEach { (n, t) ->
                            Column(
                                Modifier.clip(RoundedCornerShape(12.dp)).background(C.CardHi).border(1.dp, C.Border, RoundedCornerShape(12.dp))
                                    .clickable { onPick(t) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                T(n, 13.sp, weight = FontWeight.Medium)
                                T(t, 11.sp, C.Sub)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * «Перемешай», «повтор», «что играет», «нравится» in other players need their media sessions, which Android shows
 * only to an app with notification access: its state and a button to the settings, re-checked on return.
 */
@Composable
private fun MediaAccessRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var resumed by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val on = remember(resumed) { kg.forester.sg.service.MediaSessions.allowed(ctx) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("Управление плеерами: " + if (on) "есть" else "нет доступа", 13.sp, if (on) C.Green else C.Orange)
            T("Для «перемешай», «повтор», «что играет» в Яндекс Музыке, VK, Spotify нужен доступ к уведомлениям " +
                "(приложение их не читает, только видит, какой плеер играет).", 11.sp, C.Sub)
        }
        if (!on) {
            Spacer(Modifier.width(8.dp))
            Chip("Разрешить", false, {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
    }
}

/**
 * The voice of the spoken warnings: a Piper voice in usual or high quality, a Supertonic voice, or the head unit's
 * own Android TTS. A voice not downloaded yet shows its size; picking it downloads it (the old one is deleted).
 */
@Composable
fun SpeechVoicePicker() {
    val ctx = LocalContext.current
    val s = kg.forester.sg.data.Repo.settings
    var picked by remember { mutableStateOf(Speech.voice(s.ttsVoice).id) }
    val v = Speech.voice(picked)
    fun pick(id: String) { if (picked != id) { picked = id; s.ttsVoice = id; Speech.switchVoice(ctx) } }
    val hq = picked.endsWith("-hq")
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Speech.PIPER.forEach { (id, name) ->
            Chip(name, v.engine == Speech.Engine.PIPER && picked.removeSuffix("-hq") == id, { pick(if (hq) "$id-hq" else id) })
        }
        Chip("Supertonic", v.engine == Speech.Engine.SUPERTONIC, { if (v.engine != Speech.Engine.SUPERTONIC) pick("st-f1") })
        Chip("Голос Android", v.engine == Speech.Engine.SYSTEM, { pick("system") })
        Chip("Прослушать", false, {
            if (!Speech.say("Голосовые предупреждения включены. Турбина горячая, подождите 30 секунд до выключения")) Speech.prepare(ctx)
        })
    }
    when (v.engine) {
        Speech.Engine.PIPER -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Качество:", 13.sp, C.Sub)
                val base = picked.removeSuffix("-hq")
                Chip("Обычное · 20 МБ", !hq, { pick(base) })
                Chip("Высокое · 64 МБ", hq, { pick("$base-hq") })
            }
            T("Высокое: чище звук, без шипения сжатой модели, на ARM обычно и быстрее; скачивается дольше.", 11.sp, C.Sub)
        }
        Speech.Engine.SUPERTONIC -> {
            listOf("Женский" to "st-f", "Мужской" to "st-m").forEach { (label, prefix) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(label, 13.sp, C.Sub, modifier = Modifier.width(76.dp))
                    (1..5).forEach { n -> Chip("$n", picked == "$prefix$n", { pick("$prefix$n") }) }
                }
            }
            T("Новая модель, звучит естественнее. ~123 МБ один раз на все десять голосов, процессор нагружает как обычная Ирина.", 11.sp, C.Sub)
        }
        Speech.Engine.SYSTEM -> T("Синтез речи самой магнитолы (Google, RHVoice и т. п.), если в нём есть русский голос. Ничего не скачивается.", 11.sp, C.Sub)
    }
}
