package kg.forester.sg.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Fan
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Snowflake
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.TriangleAlert
import kg.forester.sg.data.Repo
import kg.forester.sg.service.Alerts
import kotlinx.coroutines.delay

/**
 * Over every screen: the shift light, and in the compact layout (split screen, phone) the latest warning and the
 * turbo cool-down. The full layout shows those in the header and the bottom bar instead ([floating] = false),
 * so nothing covers the tabs and the content.
 */
@Composable
fun BoxScope.DriverOverlays(floating: Boolean = true) {
    ShiftLight()
    if (!floating) return
    androidx.compose.foundation.layout.Column(
        Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        AlertBanner(Modifier)
        TurboChip(Modifier)
    }
}

/** How long a warning stays: car notifications are glanceable and brief; danger stays until tapped. */
const val ALERT_SHOW_MS = 6000L

@Composable
private fun ShiftLight() {
    val on by Repo.shift.collectAsState()
    if (!on) return
    val t = rememberInfiniteTransition(label = "shift")
    // 2.5 flashes a second: more than 3 is not advised
    val a = t.animateFloat(0.25f, 0.9f, infiniteRepeatable(tween(200), RepeatMode.Reverse), label = "shiftA")
    // the flash fades the frame in its layer (alpha on the draw itself, no full-screen offscreen buffer):
    // no recomposition of the border on every frame
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = a.value
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
        }.border(
            14.dp, Brush.horizontalGradient(listOf(C.Red, C.Blue, C.Red)),
            RoundedCornerShape(18.dp),
        ),
    )
}

@Composable
private fun AlertBanner(m: Modifier) {
    val a by Alerts.current.collectAsState()
    val alert = a ?: return
    // information and warnings go away on their own, danger stays until tapped
    LaunchedEffect(alert) {
        if (alert.level != Alerts.Level.DANGER) { delay(ALERT_SHOW_MS); if (Alerts.current.value == alert) Alerts.dismiss() }
    }
    val col = when (alert.level) { Alerts.Level.DANGER -> C.Red; Alerts.Level.WARN -> C.Orange; else -> C.Violet }
    Row(
        m.widthIn(max = 760.dp).clip(RoundedCornerShape(14.dp)).background(C.Overlay)
            .border(2.dp, col, RoundedCornerShape(14.dp)).clickable { Alerts.dismiss() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(alertIcon(alert.level), null, tint = col, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        T(alert.text, LocalCar.current.label, C.Text, FontWeight.Medium, maxLines = 3)
    }
}

@Composable
private fun TurboChip(m: Modifier) {
    val guard = Repo.guard.collectAsState()
    val g by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) {
            val s = guard.value
            PillState(s.cooldownSec, s.cold, if (s.cooldownSec > 0) s.heat.toInt() else 0)
        }
    }
    if (g.cooldownSec <= 0) return
    val col = if (g.cooldownSec > 45) C.Orange else C.Green
    Row(
        m.clip(RoundedCornerShape(18.dp)).background(C.Overlay).border(2.dp, col, RoundedCornerShape(18.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Fan, null, tint = col, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(14.dp))
        androidx.compose.foundation.layout.Column {
            T("Турбина остывает", LocalCar.current.secondary, C.Sub)
            T("не глушите ещё ${g.cooldownSec} с", LocalCar.current.label, C.Text, FontWeight.Bold)
            Spacer(Modifier.size(4.dp))
            Box(Modifier.width(220.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.CardHi)) {
                Box(Modifier.fillMaxSize().padding(end = (220 * (1f - (g.heat / 100f).coerceIn(0f, 1f))).dp).background(col))
            }
        }
    }
}

/**
 * The bottom bar's place, taken by the latest warning while it lasts (like a heads-up card: one glance, two lines
 * at most), then the values come back. Tap = hide.
 */
@Composable
fun AlertInBottomBar(alert: Alerts.Alert) {
    LaunchedEffect(alert) {
        if (alert.level != Alerts.Level.DANGER) { delay(ALERT_SHOW_MS); if (Alerts.current.value == alert) Alerts.dismiss() }
    }
    val col = when (alert.level) { Alerts.Level.DANGER -> C.Red; Alerts.Level.WARN -> C.Orange; else -> C.Violet }
    Row(
        Modifier.fillMaxWidth().height(bottomBarHeight()).clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.16f))
            .border(2.dp, col, RoundedCornerShape(14.dp)).clickable { Alerts.dismiss() }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(alertIcon(alert.level), null, tint = col, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        // as large as the two lines the bar's height holds let it be
        FitText(alert.text, LocalCar.current.label, Modifier.weight(1f), C.Text, FontWeight.Medium, maxLines = 2)
        Spacer(Modifier.width(12.dp))
        T(if (alert.level == Alerts.Level.DANGER) "скрыть: нажмите" else "скрыть", LocalCar.current.secondary, C.Sub, maxLines = 1)
    }
}

internal data class PillState(val cooldownSec: Int, val cold: Boolean, val heat: Int)

/**
 * States that last (turbo cool-down, cold engine) as a pill in the header next to the clock: always in the same
 * place, never over the content. Two short lines say what it is and what to do; a tap explains why.
 */
/** What the plate shows now: [turbo] the cool-down (with [cooled] once it is done), else the cold engine. */
class StatusPillUi internal constructor(internal val g: PillState, internal val turbo: Boolean, internal val cooled: Boolean)

/** The plate's state, null while there is nothing to say: the header gives it room only then. */
@Composable
fun rememberStatusPill(): StatusPillUi? {
    // only what this pill shows, not every new state of the guard (one per reading)
    val guard = Repo.guard.collectAsState()
    val g by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) {
            val s = guard.value
            PillState(s.cooldownSec, s.cold, if (s.cooldownSec > 0) s.heat.toInt() else 0)
        }
    }
    // the countdown never hides early: orange while it runs, then green «можно глушить» for a while
    var cooledAt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
    var wasCounting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(g.cooldownSec > 0) {
        if (g.cooldownSec > 0) { wasCounting = true; cooledAt = 0L }
        else if (wasCounting) {
            wasCounting = false
            cooledAt = System.currentTimeMillis()
            delay(15_000)
            cooledAt = 0L
        }
    }
    val cooled = cooledAt > 0L && g.cooldownSec <= 0
    val turbo = g.cooldownSec > 0 || cooled
    return if (!turbo && !g.cold) null else StatusPillUi(g, turbo, cooled)
}

/** The plate, in a slot of the width the caller gives it: its text shrinks inside, it never pushes the buttons. */
@Composable
fun StatusPill(ui: StatusPillUi, modifier: Modifier = Modifier) {
    val g = ui.g; val turbo = ui.turbo; val cooled = ui.cooled
    var info by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val car = LocalCar.current
    val col = if (cooled) C.Green else if (turbo) C.Orange else C.Blue
    Row(
        modifier.padding(end = 14.dp).clip(RoundedCornerShape(18.dp)).background(col.copy(alpha = 0.14f))
            .border(1.5.dp, col, RoundedCornerShape(18.dp)).clickable { info = true }
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (turbo) Lucide.Fan else Lucide.Snowflake, null, tint = col, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f, fill = false)) {
            // a pill in the header: smaller rather than cut, on the parked screens too
            val small = (car.secondary.value * 0.7f).sp
            // on a small screen what to do is enough: the fan says what it is about, the header needs the room
            if (!car.small) FitText(if (cooled) "Турбина остыла" else if (turbo) "Турбина остывает" else "Мотор холодный", car.secondary, color = C.Sub, min = small)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FitText(if (cooled) "можно глушить" else if (turbo) (if (car.small) "ждите ${g.cooldownSec} с" else "не глушите ещё ${g.cooldownSec} с") else if (car.small) "до 3000 об/мин" else "не выше 3000 об/мин",
                    car.secondary, Modifier.weight(1f, fill = false), C.Text, FontWeight.SemiBold, min = small)
            }
            // under the words: beside them it took half the width and cut them
            if (turbo && !cooled && !car.small) {
                Box(Modifier.padding(top = 2.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.CardHi)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((g.heat / 100f).coerceIn(0f, 1f)).background(col))
                }
            }
        }
    }
    if (info) InfoDialog(
        if (turbo) "Турбина остывает" else "Мотор холодный",
        if (turbo) InfoText(
            what = "Турботаймер: сколько ещё дать мотору поработать на холостых, прежде чем глушить.",
            how = "Приложение копит «жар» турбины по наддуву и оборотам за поездку и считает, сколько ему остывать на " +
                "холостых. Полоса — сколько тепла осталось.",
            norm = "Если заглушить горячую турбину, масло в её подшипнике закипает и коксуется — главная причина " +
                "износа турбин. После спокойной езды хватает 20–30 с, после трассы или активной езды — 1–2 минуты. " +
                "Выключить подсказку: Настройки → «Функции» → «Турботаймер».",
        ) else InfoText(
            what = "Мотор ещё не прогрелся: двигатель лучше не нагружать.",
            how = "По температуре антифриза из ЭБУ: любой EJ заканчивает прогревное обогащение смеси на 80°. Подсказка " +
                "пропадает, когда антифриз дойдёт до 80°.",
            norm = "Холодное масло густое и хуже смазывает: до прогрева держите обороты ниже 3000 и не давите в пол.",
        ),
    ) { info = false }
}

/** The triangle is for what needs attention; a mere piece of news gets the plain mark. */
private fun alertIcon(level: Alerts.Level) = if (level == Alerts.Level.INFO) Lucide.Info else Lucide.TriangleAlert
