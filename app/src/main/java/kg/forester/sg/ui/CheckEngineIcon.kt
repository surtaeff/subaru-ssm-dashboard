package kg.forester.sg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.service.checkEngineShown

/** The engine's outline, as a car's own lamp has it; in the icons' 24-unit box. */
val EngineOutline: ImageVector by lazy {
    ImageVector.Builder("engine", 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(9f, 5f); lineTo(15f, 5f)
        moveTo(12f, 5f); lineTo(12f, 8f)
        moveTo(2f, 10f); lineTo(2f, 16f)
        moveTo(2f, 13f); lineTo(5f, 13f)
        moveTo(5f, 10f); lineTo(7f, 10f); lineTo(7f, 8f); lineTo(16f, 8f); lineTo(18f, 11f); lineTo(20f, 11f); lineTo(20f, 9f); lineTo(22f, 9f)
        lineTo(22f, 17f); lineTo(20f, 17f); lineTo(20f, 15f); lineTo(18f, 15f); lineTo(15f, 19f); lineTo(9f, 19f); lineTo(7f, 16f); lineTo(5f, 16f); close()
    }.build()
}

/** Whether the header has the icon: from the state the service keeps, this asks the car nothing. */
@Composable
fun rememberCheckEngineShown(): Boolean {
    val state by Repo.checkEngine.collectAsState()
    val link by Repo.link.collectAsState()
    return checkEngineShown(state, link == LinkState.CONNECTED)
}

/** Amber engine with the count, steady, for as long as the units report codes. A tap opens «Ошибки», in the menu or not. */
@Composable
fun CheckEngineIcon() {
    val state by Repo.checkEngine.collectAsState()
    val n = state?.count ?: 0
    Box(contentAlignment = Alignment.Center) {
        IconTap(EngineOutline, "Ошибки двигателя", { Repo.openScreen.value = System.nanoTime() to "Dtc" }, tint = C.Orange, iconSize = 26.dp)
        if (n > 0) T(n.toString(), 12.sp, C.Orange, FontWeight.Bold, Modifier.align(Alignment.Center).offset(x = 20.dp, y = (-13).dp), maxLines = 1)
    }
}
