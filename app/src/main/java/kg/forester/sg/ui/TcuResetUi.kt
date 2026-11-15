package kg.forester.sg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.service.EcuLink
import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ssm.TcuReset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The line and the car as [TcuReset.block] needs them, read afresh: the settings and the transport are no flows. */
private fun resetLine(): TcuReset.Line {
    val t = EcuLink.client?.transport
    val live = Repo.live.value
    return TcuReset.Line(
        demo = Repo.demoOn.value,
        obd = Repo.obdMode.value || EcuLink.obd != null,
        elm = TcuReset.viaElm(t),
        shared = Repo.settings.lineShared,
        otherTester = t?.passive == true,
        connected = t != null && Repo.link.value == LinkState.CONNECTED,
        tcu = Repo.tcuInit.value,
        rpm = live.rpm,
        speedKmh = live.speedKmh,
        range = Repo.tcu.value?.range,
    )
}

/**
 * «Сбросить адаптации АКПП» under the transmission's codes: FreeSSM's «Clear Memory 2», apart from the codes' own
 * clear (level 1). Greyed out with the reason while [TcuReset.block] says no.
 */
@Composable
fun TcuResetSection() {
    val line by produceState(TcuReset.Line(connected = false)) {
        while (true) {
            value = resetLine()
            delay(500)
        }
    }
    val block = TcuReset.block(line)
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(18.dp))
    T("Адаптации АКПП", 13.sp, C.Sub, FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    ActionButton("Сбросить адаптации АКПП", block == null, danger = true) { open = true }
    block?.let {
        Spacer(Modifier.height(4.dp))
        T(it.text, 12.sp, C.Sub)
    }
    if (open) TcuResetDialog { open = false }
}

private enum class Step { CONFIRM, WORKING, FAILED, CYCLE, RESTORE, RESTORING, DONE }

/** What the read and the write gave: the adjustments before (null: no answer, nothing sent) and the echoed byte. */
private class Sent(val old: IntArray?, val echo: Int?)

@Composable
private fun TcuResetDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.CONFIRM) }
    var error by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var adj by remember { mutableStateOf(emptyList<TcuReset.Adjustment>()) }
    var old by remember { mutableStateOf<IntArray?>(null) }
    var cycle by remember { mutableStateOf<TcuReset.Cycle?>(null) }
    var stage by remember { mutableStateOf(TcuReset.Stage.IGNITION_OFF) }
    var waitLeft by remember { mutableStateOf(0) }

    fun start() {
        step = Step.WORKING
        scope.launch {
            val line = resetLine()
            TcuReset.block(line)?.let { error = it.text; step = Step.FAILED; return@launch }
            val init: EcuInit = line.tcu ?: return@launch
            val list = TcuReset.adjustments(init)
            SsmTrace.log("TCU clear memory 2: SSM ID ${init.ssmId}, ROM ID ${init.romId}, ${list.size} adjustments", keep = true)
            val sent = withContext(Dispatchers.IO) {
                EcuLink.use { c ->
                    val before = TcuReset.readAdjustments(c, list)
                    Sent(before, if (before == null) null else TcuReset.clear(c))
                }
            }
            when {
                sent == null -> error = "Нет связи с адаптером: сброс не отправлен"
                sent.old == null -> error = "Блок АКПП не ответил: сброс не отправлен"
                sent.echo == null -> error = "Блок АКПП не подтвердил сброс (ответа нет). Принял ли он команду, неизвестно: " +
                    "выключите зажигание на 10 секунд, включите и попробуйте снова"
                !TcuReset.accepted(sent.echo) -> {
                    val got = "0x%02X".format(sent.echo)
                    error = "Блок АКПП ответил $got вместо 0x20: сброс не принят"
                }
            }
            if (sent == null || sent.old == null || !TcuReset.accepted(sent.echo)) { step = Step.FAILED; return@launch }
            adj = list
            old = sent.old
            // FreeSSM gives the unit 0.8 s before it asks for the ignition
            delay(800)
            cycle = TcuReset.Cycle(init)
            step = Step.CYCLE
        }
    }

    LaunchedEffect(cycle) {
        val cy = cycle ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val tcu = Repo.tcuInit.value
            stage = cy.stage(Repo.link.value == LinkState.CONNECTED, tcu, now)
            waitLeft = cy.waitLeftS(now)
            when (stage) {
                TcuReset.Stage.SAME_UNIT -> {
                    SsmTrace.log("TCU clear memory 2: the unit is back after the ignition cycle, same ids", keep = true)
                    // the codes went with it: the check engine icon asks again
                    kg.forester.sg.service.CodeWatch.ask()
                    step = if (old?.let { TcuReset.offerRestore(adj, it) } == true) Step.RESTORE else Step.DONE
                    return@LaunchedEffect
                }
                TcuReset.Stage.OTHER_UNIT -> {
                    SsmTrace.log("TCU clear memory 2: another unit answered after the ignition cycle (ROM ID ${tcu?.romId})", keep = true)
                    note = "После включения ответил другой блок АКПП (ROM ${tcu?.romId}). FreeSSM в таком случае с блоком " +
                        "больше не работает, поэтому прежние поправки не возвращались."
                    step = Step.DONE
                    return@LaunchedEffect
                }
                else -> Unit
            }
            delay(250)
        }
    }

    fun restore() {
        step = Step.RESTORING
        scope.launch {
            val values = old ?: return@launch
            val ok = withContext(Dispatchers.IO) { EcuLink.use { TcuReset.restore(it, adj, values) } } == true
            note = if (ok) "Прежние поправки возвращены, блок принял все значения."
            else "Не все поправки вернулись: блок не принял часть значений. Проверьте их в FreeSSM («Adjustments»)."
            step = Step.DONE
        }
    }

    val busy = step == Step.WORKING || step == Step.RESTORING
    CarAlertDialog(
        // a tap past the dialog closes it only where nothing is half done
        onDismissRequest = { if (step == Step.CONFIRM || step == Step.FAILED || step == Step.DONE) onClose() },
        containerColor = C.CardHi,
        title = {
            T(
                when (step) {
                    Step.CONFIRM -> "Сбросить адаптации АКПП?"
                    Step.WORKING -> "Сброс…"
                    Step.FAILED -> "Сброс не выполнен"
                    Step.CYCLE -> "Блок принял сброс"
                    Step.RESTORE, Step.RESTORING -> "Вернуть прежние поправки?"
                    Step.DONE -> "Адаптации АКПП сброшены"
                },
                18.sp, weight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                when (step) {
                    Step.CONFIRM -> ConfirmText()
                    Step.WORKING -> T("Читаю поправки и отправляю «Clear Memory 2» блоку АКПП…", 15.sp)
                    Step.FAILED -> T(error, 15.sp, C.Orange)
                    Step.CYCLE -> CycleText(stage, waitLeft)
                    Step.RESTORE, Step.RESTORING -> RestoreText(adj, old)
                    Step.DONE -> DoneText(note)
                }
            }
        },
        confirmButton = {
            when (step) {
                Step.CONFIRM -> TextButton({ start() }) { T("Сбросить", 16.sp, C.Red) }
                Step.RESTORE -> TextButton({ restore() }) { T("Вернуть", 16.sp, C.Violet) }
                Step.CYCLE -> TextButton(onClose) { T("Закрыть", 16.sp, C.Text) }
                else -> TextButton(onClose, enabled = !busy) { T(if (busy) "Подождите" else "Готово", 16.sp, if (busy) C.Sub else C.Violet) }
            }
        },
        dismissButton = {
            when (step) {
                Step.CONFIRM -> TextButton(onClose) { T("Отмена", 16.sp, C.Text) }
                Step.RESTORE -> TextButton({ note = "Поправки оставлены заводскими."; step = Step.DONE }) { T("Оставить заводские", 16.sp, C.Text) }
                else -> Unit
            }
        },
    )
}

@Composable
private fun ConfirmText() {
    T("Что делает", 15.sp, weight = FontWeight.SemiBold)
    T(
        "Блок АКПП стирает выученные значения переключений (адаптации), коды ошибок и поправки (Adjustments в FreeSSM). " +
            "Это «Clear Memory 2» из FreeSSM: запись 0x20 по адресу 0x000060, блок должен ответить тем же байтом.",
        14.sp,
    )
    Spacer(Modifier.height(10.dp))
    T("Риски", 15.sp, weight = FontWeight.SemiBold)
    T(
        "Пока блок заново не обучится, переключения могут быть жёстче или с задержкой. Поправки, если их выставляли " +
            "в FreeSSM, вернутся к заводским; после сброса приложение предложит вернуть прежние, как это делает FreeSSM.",
        14.sp,
    )
    Spacer(Modifier.height(10.dp))
    T("Перед сбросом", 15.sp, weight = FontWeight.SemiBold)
    T("Зажигание включено, двигатель заглушен, машина стоит, селектор в P, стояночный тормоз затянут.", 14.sp)
    Spacer(Modifier.height(10.dp))
    T("После сброса приложение попросит выключить зажигание, подождать и включить его снова.", 14.sp, C.Sub)
}

@Composable
private fun CycleText(stage: TcuReset.Stage, waitLeft: Int) {
    T("Блок АКПП ответил 0x20: команда принята.", 14.sp, C.Green)
    Spacer(Modifier.height(10.dp))
    when (stage) {
        TcuReset.Stage.IGNITION_OFF -> T("Выключите зажигание и подождите, пока пропадёт связь с машиной.", 16.sp, weight = FontWeight.SemiBold)
        TcuReset.Stage.WAIT -> T("Связь пропала. Подождите ещё $waitLeft с, зажигание пока не включайте.", 16.sp, weight = FontWeight.SemiBold)
        else -> T("Включите зажигание (двигатель не заводите) и дождитесь, пока блок АКПП снова ответит.", 16.sp, weight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(10.dp))
    T("Так же делает FreeSSM: после сброса блок должен остаться без питания, а после включения приложение проверит, " +
        "что ответил тот же блок.", 13.sp, C.Sub)
}

@Composable
private fun RestoreText(adj: List<TcuReset.Adjustment>, old: IntArray?) {
    T("До сброса в блоке были выставлены поправки, сброс вернул их к заводским. FreeSSM в этом месте спрашивает то же самое.", 14.sp)
    Spacer(Modifier.height(8.dp))
    if (old != null) adj.indices.filter { old[it] != adj[it].rawDefault }.forEach { T("• " + adj[it].title, 13.sp, C.Sub) }
}

@Composable
private fun DoneText(note: String) {
    if (note.isNotEmpty()) {
        T(note, 14.sp)
        Spacer(Modifier.height(10.dp))
    }
    T("Что дальше", 15.sp, weight = FontWeight.SemiBold)
    T("Пока блок не обучится заново, переключения могут быть жёстче или с задержкой: он учится по ходу езды.", 14.sp)
    Spacer(Modifier.height(8.dp))
    T(
        "Ускоренное обучение по сервисной инструкции Subaru (раздел 4AT(diag), Subaru Select Monitor, обучение после " +
            "Clear Memory 2). Делается на подъёмнике, колёса вывешены:",
        14.sp,
    )
    listOf(
        "1. Селектор P, стояночный тормоз. Проверьте, что кодов ошибок АКПП нет.",
        "2. Прогрейте двигатель, пока ATF не станет 60–90 °C (видно на экране «АКПП»).",
        "3. Селектор в R. Выключите фары, кондиционер, подогрев сидений, обогрев стекла и другие потребители.",
        "4. Выключите зажигание не меньше чем на 30 с, нажмите педаль тормоза до упора и держите её до конца обучения.",
        "5. Включите зажигание. Селектор по очереди в P, R, N, D, N, в каждом положении больше 3 с.",
        "6. Медленно нажмите газ до упора и медленно полностью отпустите.",
        "7. Заведите двигатель, пусть работает на холостых. Селектор в D.",
        "8. Лампа AT OIL TEMP (в инструкции Legacy это SPORT) мигает 2 раза в секунду: обучение идёт. Частое мигание " +
            "прекратилось: обучение закончено. Лампа не мигает или мигает 4 раза в секунду: выключите зажигание и начните снова со сброса.",
        "9. Селектор в N, выключите зажигание, затем селектор в P.",
    ).forEach { T(it, 13.sp) }
}
