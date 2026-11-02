package kg.forester.sg.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kg.forester.sg.R

/**
 * The racing themes' type, open fonts under the OFL (see README and assets/licenses). Only descriptions here: a file is read
 * when a text first asks for it in its theme, once, so the other themes and the start of the app never touch one.
 */
object ThemeFonts {
    val exoItalic = FontFamily(
        Font(R.font.exo2_italic_regular, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.exo2_italic_bold, FontWeight.Bold, FontStyle.Italic),
    )
    val exo = FontFamily(
        Font(R.font.exo2_regular, FontWeight.Normal), Font(R.font.exo2_bold, FontWeight.Bold),
        Font(R.font.exo2_italic_regular, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.exo2_italic_bold, FontWeight.Bold, FontStyle.Italic),
    )
    /** One heavy face: asked for lighter, it stays this, and no false bold over it. */
    val pursuit = FontFamily(Font(R.font.pursuit_sans, FontWeight.Bold))
    val condensed = FontFamily(Font(R.font.roboto_condensed_regular, FontWeight.Normal), Font(R.font.roboto_condensed_bold, FontWeight.Bold))
    val jura = FontFamily(Font(R.font.jura_regular, FontWeight.Normal), Font(R.font.jura_bold, FontWeight.Bold))
}
