package kg.forester.sg.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kg.forester.sg.data.I18n
import kg.forester.sg.data.Repo
import kg.forester.sg.data.tr
import kg.forester.sg.service.Alerts
import kotlinx.coroutines.launch

/** English chosen and its dictionary not read yet: a moment of nothing rather than of Russian. Russian: always ready. */
@Composable
fun languageReady(): Boolean {
    if (!I18n.english || I18n.ready) return true
    return androidx.compose.runtime.produceState(false) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { I18n.await() }
        value = true
    }.value
}

/**
 * The app's Text: material3's, with the string through [tr] at the point of display. Being in this package it is the one
 * every screen here gets; for a song, a station or the driver's own name pass it through `I18n.raw`.
 */
@Composable
@NonRestartableComposable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) = androidx.compose.material3.Text(
    C.palette.cased(tr(text)), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign,
    lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, C.palette.textStyle(style, text, if (color != Color.Unspecified) color else style.color),
)

/** The app's Icon: material3's, its description for TalkBack through [tr]. */
@Composable
@NonRestartableComposable
fun Icon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current,
) = androidx.compose.material3.Icon(imageVector, contentDescription?.let { tr(it) }, modifier, tint)

@Composable
@NonRestartableComposable
fun Icon(
    painter: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current,
) = androidx.compose.material3.Icon(painter, contentDescription?.let { tr(it) }, modifier, tint)

/** Settings, first row. A language applies by recreating the activity, once its dictionary has been read. */
@Composable
fun LanguageRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var lang by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Repo.settings.language) }
    fun pick(v: String) {
        if (v == lang) return
        lang = v
        Repo.settings.language = v
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            I18n.choose(ctx, v)
            Alerts.languageChanged(ctx)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { (ctx as? android.app.Activity)?.recreate() }
        }
    }
    androidx.compose.foundation.layout.Column {
        FieldLabel("Язык / Language")
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Chip("Русский", lang == I18n.RU, { pick(I18n.RU) })
            Chip("English", lang == I18n.EN, { pick(I18n.EN) })
        }
    }
}
