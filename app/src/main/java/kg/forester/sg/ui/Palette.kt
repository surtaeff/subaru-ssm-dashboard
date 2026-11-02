package kg.forester.sg.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * A look of the whole interface: everything that depends on it in one immutable set, read through [C], and replaced only on a
 * switch (one state, so nothing recomposes for it the rest of the time). «Неон» is the original app, value for value.
 * Status colours stay what they mean in every theme, with the shade changed where they must read on it.
 * What has its own dark stage (map skins, the sports display, wiring diagrams, screen previews) and the gauge presets a
 * driver chose are not part of this.
 */
@Immutable
data class Palette(
    val id: String,
    val title: String,
    /** The flat background (also what a weak unit shows instead of the [backdrop]); always solid. */
    val bg: Color,
    val bgTop: Color,
    val card: Color,
    val cardHi: Color,
    /** A dialog's panel: over anything, so solid. */
    val sheet: Color,
    val border: Color,
    val text: Color,
    val sub: Color,
    /** Not for text. */
    val gray: Color,
    val blue: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    /** «Near the limit» between green and orange. */
    val yellow: Color,
    /** The big values. */
    val ink: Color,
    /** What faint lines and fills are made of, with an alpha: white on the dark themes. */
    val hair: Color,
    /** Text and icons on a filled accent. */
    val onAccent: Color,
    /** The banners over the screens. */
    val overlay: Color,
    /** A screen laid over the app for a while. */
    val veil: Color,
    /** What the rail's scrolling fades to at its ends. */
    val railTop: Color,
    val railFoot: Color,
    /** Behind the engine's 3D view. */
    val stageTop: Color,
    val stageFoot: Color,
    /** The lightest thing a translucent panel can lie over, for the contrast tests. */
    val lightest: Color = bg,
    val backdrop: Backdrop = Backdrop.PLAIN,
    /** A diagonal fill for the panels; null = flat [card]. Dropped on the Low tier. */
    val cardGradient: List<Color>? = null,
    /** The fill of a filled button when the accent itself won't carry [onAccent]; null = the accent. */
    val accentFill: List<Color>? = null,
    /** The small icon buttons: their fill and whether they are round; null = no fill, as in Неон. */
    val iconFill: Color? = null,
    val iconRound: Boolean = false,
    /** Soft light behind the active item; off on the Low tier. Never redrawn per frame. */
    val activeGlow: Color? = null,
    /** Thin sans: thin weights, and very light big numerals. */
    val thin: Boolean = false,
    /** The panels' corners, and on a 7" screen. */
    val corner: Dp = 14.dp,
    val cornerSmall: Dp = corner,
    /** The chosen hue applies here. */
    val hueFollows: Boolean = true,
    /** The street-racing menus: italic tracked text, chamfered shapes. */
    val race: Boolean = false,
    /** The active item as a solid shape with [activeText] on it; null = the accent's tint with the usual text. */
    val activeFill: Color? = null,
    val activeText: Color? = null,
    /** The outline of a selected item; null = the accent, as it was. */
    val activeEdge: Color? = null,
    /** An unselected chip's fill and its thin edge; null = the usual ones. */
    val idleFill: Color? = null,
    /** A soft shadow round the panels; off on the Low tier. No blur: a few faint outlines, their shape kept per size. */
    val panelShadow: Color? = null,
    /** A danger button's text on a solid [red], where [red] is too pale for white; null = white on it at 80%. */
    val dangerText: Color? = null,
    /** The theme's own type; null = the system's, and [textStyle] hands back the very style. Loaded at its first use, once. */
    val font: FontFamily? = null,
    /** Below this size the system's sans after all: a display face that is poor when small. 0 = at any size. */
    val fontFromSp: Float = 0f,
    /** Chips, tabs and period buttons solid as well, with [activeText] on them, not only the menu and the plain [Chip]. */
    val solidSmall: Boolean = race,
    /** Medium and heavier text set bold. */
    val boldText: Boolean = false,
    /** Short labels in capitals, and text in the secondary colour [sub] slanted. */
    val shortCaps: Boolean = false,
    val subItalic: Boolean = false,
    /** How a panel's or a settings section's title is written and coloured; a band: white on it, over [titleBand]. */
    val titleCase: TitleCase = TitleCase.AS_IS,
    val titleTracking: Float = 0f,
    val titleColor: Color? = null,
    val titleBand: Color? = null,
    /** The largest corner a panel or a button may have here; null = what each asks for. */
    val cornerCap: Dp? = null,
    /** A static mark on the larger panels. */
    val panelMark: PanelMark = PanelMark.NONE,
    /** The menu on its own bar, with its idle items on [navItem], written in [navText], slanted where [navSlant]. */
    val navBar: Color? = null,
    val navItem: Color? = null,
    val navText: Color? = null,
    val navSlant: Boolean = false,
    /** Peaks and timers, where the theme sets them apart from the accent. */
    val highlight: Color? = null,
    /** How wide a letter and a digit run here, for sizing text without measuring it. */
    val charEm: Float = 0.62f,
    val digitEm: Float = 0.62f,
    private val ownAccent: UiTheme? = null,
) {
    enum class Backdrop { PLAIN, STUDIO, MIDNIGHT, NIGHT_RACE, MINT, NOIR, LAGOON, PURSUIT, CANYON, RALLY, WANTED }
    enum class TitleCase { AS_IS, UPPER, LOWER }
    enum class PanelMark { NONE, CORNERS, STRIPE }

    val shape: androidx.compose.ui.graphics.Shape = if (race) chamferShape(corner) else androidx.compose.foundation.shape.RoundedCornerShape(corner)
    val shapeSmall: androidx.compose.ui.graphics.Shape = if (race) chamferShape(cornerSmall) else androidx.compose.foundation.shape.RoundedCornerShape(cornerSmall)

    /**
     * The style as this theme sets [text]: the very same object outside the racing theme. There: italic, and tracked as a
     * word, less so when small; anything with a digit or a dash gets none, so numbers keep their widths and don't jitter.
     * A theme with its own [font] sets it unless the text asked for a face of its own, and its numbers in steady figures.
     * [color]: what the text is written in, when not in the style.
     */
    fun textStyle(style: TextStyle, text: String, color: Color = style.color): TextStyle {
        if (!race && font == null) return style
        val number = text.isEmpty() || text.any { it in '0'..'9' || it == '—' }
        val small = style.fontSize.isSp && style.fontSize.value < SMALL_TRACK_SP
        val big = !style.fontSize.isSp || style.fontSize.value >= fontFromSp
        return style.copy(
            fontFamily = style.fontFamily ?: font.takeIf { big },
            fontStyle = if (race || (subItalic && color == sub)) FontStyle.Italic else style.fontStyle,
            // the wide-set face brings most of the width by itself
            letterSpacing = if (race) (if (number || small) 0f else 0.015f).em else style.letterSpacing,
            fontFeatureSettings = style.fontFeatureSettings ?: STEADY_FIGURES.takeIf { number && font != null },
        )
    }

    /** What the input fields draw with: the theme's type here, the very same object elsewhere. */
    fun fieldStyle(style: TextStyle): TextStyle = if (!race && font == null) style else style.copy(
        fontStyle = if (race) FontStyle.Italic else style.fontStyle, fontFamily = style.fontFamily ?: font,
    )

    /** [shown], already translated, as this theme writes it: short labels in capitals where it has them, the same string elsewhere. */
    fun cased(shown: String): String =
        if (shortCaps && shown.length in 4..CAPS_UP_TO && shown.none { it in '0'..'9' || it == '/' }) shown.uppercase() else shown

    /** A title as this theme writes it, translated first, since a dictionary knows only the original. */
    fun titled(title: String): String = when (titleCase) {
        TitleCase.AS_IS -> title
        TitleCase.UPPER -> kg.forester.sg.data.tr(title).uppercase()
        TitleCase.LOWER -> kg.forester.sg.data.tr(title).lowercase()
    }

    /** The accent shown for the driver's [chosen] one. Over a light surface it goes as deep as it takes to be read. */
    fun accentFor(chosen: UiTheme): UiTheme = ownAccent ?: if (dark) chosen else UiTheme(
        // main is text and icons, also on a selected item's tinted fill
        deepen(chosen.main, 5.5f), deepen(chosen.deep, 4.5f), deepen(chosen.second, 4.5f),
    )

    private val dark get() = hair == Color.White

    private fun deepen(c: Color, min: Float): Color {
        fun rgb(x: Color) = (((x.red * 255 + 0.5f).toInt()) shl 16) or (((x.green * 255 + 0.5f).toInt()) shl 8) or (x.blue * 255 + 0.5f).toInt()
        // against the darkest panel it has to read on: bg, and white on it for a filled button
        val worst = listOf(bg, cardHi, card).map(::rgb)
        var k = 0f
        while (k < 1f) {
            val m = Color(c.red * (1 - k), c.green * (1 - k), c.blue * (1 - k), 1f)
            if (worst.all { contrast(rgb(m), it) >= min }) return m
            k += 0.02f
        }
        return Color(0xFF111111)
    }

    /** The weight text is set in: the same in Неон; thin here, and big numerals light. */
    fun weight(w: FontWeight, sizeSp: Float): FontWeight = when {
        boldText && w.weight >= FontWeight.Medium.weight -> FontWeight.Bold
        !thin -> w
        sizeSp >= BIG_SP -> FontWeight.Light
        w.weight >= FontWeight.SemiBold.weight -> FontWeight.Medium
        w == FontWeight.Medium -> FontWeight.Normal
        else -> w
    }

    companion object {
        /** From here up a number counts as a big numeral. */
        const val BIG_SP = 20f

        /** Below this a word is tracked less: italic at small sizes on 7" has to stay readable. */
        const val SMALL_TRACK_SP = 13f

        /** Each digit as wide as the others and no kerning between them, in the faces that have it; a number doesn't shift. */
        private const val STEADY_FIGURES = "'tnum', 'kern' 0"

        /** The longest label set in capitals: longer than this it is a sentence, or won't fit. */
        const val CAPS_UP_TO = 26

        val NEON = Palette(
            "neon", "Неон",
            bg = Color(0xFF080A12), bgTop = Color(0xFF0E1222), card = Color(0xFF111522), cardHi = Color(0xFF181D2E),
            sheet = Color(0xFF111522), border = Color(0xFF232A40), text = Color(0xFFEDEFF5), sub = Color(0xFFB4BACC),
            gray = Color(0xFF64748B), blue = Color(0xFF3B82F6), green = Color(0xFF22C55E), orange = Color(0xFFF59E0B),
            red = Color(0xFFEF4444), yellow = Color(0xFFD9C24A), ink = Color.White, hair = Color.White, onAccent = Color.White,
            overlay = Color(0xF0101422), veil = Color(0xE6070A14), railTop = Color(0xFF0D1120), railFoot = Color(0xFF0A0C16),
            stageTop = Color(0xFF1C2233), stageFoot = Color(0xFF080A12),
        )

        val LIGHT = Palette(
            "light", "Светлая",
            bg = Color(0xFFEDF1F6), bgTop = Color(0xFFF4F6FA), card = Color.White, cardHi = Color(0xFFF7F9FC),
            sheet = Color.White, border = Color(0xFFD3D9E3), text = Color(0xFF0F172A), sub = Color(0xFF475569),
            gray = Color(0xFF64748B), blue = Color(0xFF1D4ED8), green = Color(0xFF15803D), orange = Color(0xFFB45309),
            red = Color(0xFFC81E1E), yellow = Color(0xFF7A6200), ink = Color(0xFF0F172A), hair = Color(0xFF0F172A), onAccent = Color.White,
            overlay = Color(0xF7FFFFFF), veil = Color(0xEEF4F6FA), railTop = Color(0xFFF2F4F9), railFoot = Color(0xFFEDF1F6),
            // the engine's model is drawn for a dark stage
            stageTop = Color(0xFF1C2233), stageFoot = Color(0xFF080A12),
        )

        val STUDIO = Palette(
            "studio", "Студия",
            bg = Color(0xFF223946), bgTop = Color(0xFF16262F),
            // frosted: dark enough that white reads wherever the light falls, over a backdrop drawn once
            card = Color(0x5914232B), cardHi = Color(0x731A2C36), sheet = Color(0xFF1E3340),
            border = Color(0x3DFFFFFF), text = Color.White, sub = Color(0xFFD3DDE3),
            gray = Color(0xFF9FB2BD), blue = Color(0xFFA8D1FF), green = Color(0xFF8BEBB2), orange = Color(0xFFFFCB7D),
            red = Color(0xFFFFA9A0), yellow = Color(0xFFF2E08E), ink = Color.White, hair = Color.White, onAccent = Color(0xFF14232B),
            overlay = Color(0xF4192B35), veil = Color(0xEE172731), railTop = Color(0xB3192B35), railFoot = Color(0xB3192B35),
            stageTop = Color(0xFF2A4250), stageFoot = Color(0xFF16262F),
            lightest = Color(0xFF4A6A78), backdrop = Backdrop.STUDIO,
            iconFill = Color(0x1FFFFFFF), thin = true, corner = 24.dp, cornerSmall = 16.dp, hueFollows = false,
            ownAccent = UiTheme(Color.White, Color(0xFFDCEAF5), Color(0xFFA9CBE6)),
        )

        val MIDNIGHT = Palette(
            "midnight", "Полночь",
            bg = Color(0xFF05070D), bgTop = Color(0xFF0A1020), card = Color(0xFF0F1C3C), cardHi = Color(0xFF16264F),
            sheet = Color(0xFF0F1C3C), border = Color(0x804D86FF), text = Color(0xFFF4F7FF), sub = Color(0xFFAAB5CC),
            gray = Color(0xFF6F7E9E), blue = Color(0xFF7FB0FF), green = Color(0xFF4ADE9A), orange = Color(0xFFFBBF24),
            red = Color(0xFFFF8080), yellow = Color(0xFFEAD568), ink = Color.White, hair = Color.White, onAccent = Color.White,
            overlay = Color(0xF20A1228), veil = Color(0xF005070D), railTop = Color(0xFF070B16), railFoot = Color(0xFF05070D),
            stageTop = Color(0xFF101B38), stageFoot = Color(0xFF05070D),
            backdrop = Backdrop.MIDNIGHT, cardGradient = listOf(Color(0xFF0B1530), Color(0xFF16264F)),
            accentFill = listOf(Color(0xFF1E4FD8), Color(0xFF2A64F5)), iconFill = Color(0x1F7FB0FF), iconRound = true,
            activeGlow = Color(0xFF2F6BFF), thin = true, corner = 18.dp, cornerSmall = 14.dp, hueFollows = false,
            ownAccent = UiTheme(Color(0xFF86ABFF), Color(0xFF2F6BFF), Color(0xFF1E4FD8)),
        )

        val NIGHT_RACE = Palette(
            "nightrace", "Ночная гонка",
            bg = Color(0xFF070C0F), bgTop = Color(0xFF0A1519),
            // petrol blue, flat, over wet asphalt drawn once
            card = Color(0xB30F4A63), cardHi = Color(0xCC11536F), sheet = Color(0xFF0D3A4E),
            border = Color(0xFF1B6285), text = Color.White, sub = Color(0xFF7FC4DC),
            gray = Color(0xFF5F8C9E), blue = Color(0xFF7CC6F5), green = Color(0xFF5EE6A0), orange = Color(0xFFFFB84D),
            red = Color(0xFFFF9A90), yellow = Color(0xFFE8D21E), ink = Color.White, hair = Color.White, onAccent = Color.White,
            overlay = Color(0xF20C2C3B), veil = Color(0xF0070C0F), railTop = Color(0xFF070C0F), railFoot = Color(0xFF070C0F),
            stageTop = Color(0xFF12303E), stageFoot = Color(0xFF070C0F),
            lightest = Color(0xFF14242A), backdrop = Backdrop.NIGHT_RACE,
            // white as on a selected chip; a shade deeper than the chip's teal so that it reads at 4.5:1
            accentFill = listOf(Color(0xFF157A87), Color(0xFF157A87)), corner = 16.dp, cornerSmall = 12.dp, hueFollows = false, race = true,
            ownAccent = UiTheme(Color(0xFF5CCBDD), Color(0xFF1E9AA8), Color(0xFF1E9AA8)),
            // the selected item is a solid teal chamfered panel with a pale outline, the others dark glass over the panel
            activeFill = Color(0xFF1E9AA8), activeText = Color.White, activeEdge = Color(0xFF7FC4DC), idleFill = Color(0x73061C26),
            dangerText = Color(0xFF04141A), font = ThemeFonts.exoItalic,
        )

        val MINT = Palette(
            "mint", "Мята",
            bg = Color(0xFFE4EFEA), bgTop = Color(0xFFF6FAF8), card = Color.White, cardHi = Color(0xFFF2F7F4),
            sheet = Color.White, border = Color(0xFFCFE0D8), text = Color(0xFF15312A), sub = Color(0xFF546B62),
            gray = Color(0xFF6B8279), blue = Color(0xFF1B5FAA), green = Color(0xFF15803D), orange = Color(0xFFB45309),
            red = Color(0xFFC81E1E), yellow = Color(0xFF735C00), ink = Color(0xFF15312A), hair = Color(0xFF15312A), onAccent = Color.White,
            overlay = Color(0xF7FFFFFF), veil = Color(0xEEF4F8F6), railTop = Color(0xFFF0F6F3), railFoot = Color(0xFFE4EFEA),
            // the engine's model is drawn for a dark stage
            stageTop = Color(0xFF1C2B27), stageFoot = Color(0xFF0A1410),
            backdrop = Backdrop.MINT, accentFill = listOf(Color(0xFF1E4D3F), Color(0xFF1E4D3F)),
            corner = 22.dp, cornerSmall = 14.dp, hueFollows = false,
            activeFill = Color(0xFF1E4D3F), activeText = Color.White, panelShadow = Color(0xFF15312A),
            ownAccent = UiTheme(Color(0xFF1F6F54), Color(0xFF1E4D3F), Color(0xFF2E8B6B)),
        )

        val NOIR = Palette(
            "noir", "Нуар",
            bg = Color(0xFF000000), bgTop = Color(0xFF000000), card = Color(0xFF0E0E10), cardHi = Color(0xFF17171A),
            sheet = Color(0xFF101012), border = Color(0xFF242426), text = Color.White, sub = Color(0xFF8E8E92),
            gray = Color(0xFF6A6A6E), blue = Color(0xFFD0D0D2), green = Color(0xFF5FD38D), orange = Color(0xFFFFA030),
            red = Color(0xFFFF5A50), yellow = Color(0xFFE6C84A), ink = Color.White, hair = Color.White, onAccent = Color.Black,
            overlay = Color(0xF20A0A0C), veil = Color(0xF0000000), railTop = Color(0xFF000000), railFoot = Color(0xFF000000),
            stageTop = Color(0xFF141416), stageFoot = Color(0xFF000000),
            backdrop = Backdrop.NOIR, accentFill = listOf(Color.White, Color.White), iconRound = true,
            activeGlow = Color.White, thin = true, corner = 16.dp, cornerSmall = 12.dp, hueFollows = false,
            activeFill = Color.White, activeText = Color.Black,
            ownAccent = UiTheme(Color.White, Color(0xFFE6E6E6), Color(0xFFBDBDBD)),
        )

        val LAGOON = Palette(
            "lagoon", "Лагуна",
            bg = Color(0xFF0F5A5C), bgTop = Color(0xFF0B3C40),
            // translucent over a diagonal drawn once
            card = Color(0xC7082A2E), cardHi = Color(0xD8082F33), sheet = Color(0xFF0A3236),
            border = Color(0x402FD6C3), text = Color.White, sub = Color(0xFFA9D5D1),
            gray = Color(0xFF7FB3AE), blue = Color(0xFF9BDDFF), green = Color(0xFF7CF2C0), orange = Color(0xFFFFC56B),
            red = Color(0xFFFFA094), yellow = Color(0xFFF0E08A), ink = Color.White, hair = Color.White, onAccent = Color(0xFF04231F),
            overlay = Color(0xF00A3236), veil = Color(0xF00B3C40), railTop = Color(0xB30A3236), railFoot = Color(0xB30A3236),
            stageTop = Color(0xFF135053), stageFoot = Color(0xFF0B3C40),
            lightest = Color(0xFF1A8A87), backdrop = Backdrop.LAGOON,
            accentFill = listOf(Color(0xFF2FD6C3), Color(0xFF2FE0A8)), activeGlow = Color(0xFF2FE0A8),
            corner = 16.dp, cornerSmall = 12.dp, hueFollows = false,
            ownAccent = UiTheme(Color(0xFF2FE0A8), Color(0xFF19B88C), Color(0xFF2FD6C3)),
        )

        val SHOWROOM = Palette(
            "showroom", "Шоурум",
            bg = Color(0xFFE3E4E6), bgTop = Color(0xFFE3E4E6), card = Color.White, cardHi = Color(0xFFF6F6F7),
            // on white only the thin separators show it; against the grey round a card, next to nothing
            sheet = Color.White, border = Color(0xFFD9DADD), text = Color(0xFF16181B), sub = Color(0xFF5C6067),
            gray = Color(0xFF7E828A), blue = Color(0xFF24272C), green = Color(0xFF0F7D48), orange = Color(0xFFB45309),
            red = Color(0xFFC81E1E), yellow = Color(0xFF6E5900), ink = Color(0xFF16181B), hair = Color(0xFF16181B), onAccent = Color.White,
            overlay = Color(0xF7FFFFFF), veil = Color(0xEEF0F1F2), railTop = Color(0xFFE3E4E6), railFoot = Color(0xFFE3E4E6),
            stageTop = Color(0xFF1F2226), stageFoot = Color(0xFF0C0D0F),
            accentFill = listOf(Color(0xFF16181B), Color(0xFF16181B)),
            corner = 20.dp, cornerSmall = 14.dp, hueFollows = false,
            activeFill = Color(0xFF16181B), activeText = Color.White, panelShadow = Color(0xFF16181B),
            ownAccent = UiTheme(Color(0xFF0C7040), Color(0xFF16181B), Color(0xFF138A52)),
        )

        val PURSUIT = Palette(
            "pursuit", "Погоня",
            bg = Color(0xFF0D0E0A), bgTop = Color(0xFF12130E),
            // near black, over a grimy vignette drawn once
            card = Color(0xE6000000), cardHi = Color(0xEB14150F), sheet = Color(0xFF0B0C08),
            border = Color(0xFF3B3B34), text = Color.White, sub = Color(0xFFB9B48E),
            gray = Color(0xFF7D7A63), blue = Color(0xFF8FC7FF), green = Color(0xFF62DC8E), orange = Color(0xFFFF8F2E),
            red = Color(0xFFFF6A5C), yellow = Color(0xFFE6D36A), ink = Color.White, hair = Color.White, onAccent = Color.Black,
            overlay = Color(0xF20D0E0A), veil = Color(0xF00D0E0A), railTop = Color(0xFF0D0E0A), railFoot = Color(0xFF0D0E0A),
            stageTop = Color(0xFF1D1E16), stageFoot = Color(0xFF0D0E0A),
            backdrop = Backdrop.PURSUIT, accentFill = listOf(Color(0xFFF2C21A), Color(0xFFF2C21A)),
            corner = 0.dp, cornerSmall = 0.dp, cornerCap = 0.dp, hueFollows = false,
            activeFill = Color(0xFFF2C21A), activeText = Color.Black, activeEdge = Color(0xFFF2C21A), idleFill = Color(0xFF16170F),
            solidSmall = true, boldText = true, panelMark = PanelMark.CORNERS,
            font = ThemeFonts.pursuit, fontFromSp = 15f, charEm = 0.72f, digitEm = 0.7f,
            ownAccent = UiTheme(Color(0xFFF2C21A), Color(0xFFF2C21A), Color(0xFFD9AA12)),
        )

        val CANYON = Palette(
            "canyon", "Каньон",
            bg = Color(0xFF0F191E), bgTop = Color(0xFF0B1317),
            card = Color(0xBF0A1216), cardHi = Color(0xCC101B21), sheet = Color(0xFF0E181D),
            border = Color(0x38C5E8EC), text = Color.White, sub = Color(0xFFA3B3BA),
            gray = Color(0xFF6F8088), blue = Color(0xFF8CC8F5), green = Color(0xFF86E88C), orange = Color(0xFFFFA62B),
            red = Color(0xFFFF6257), yellow = Color(0xFFF0D860), ink = Color.White, hair = Color.White, onAccent = Color(0xFF04201C),
            overlay = Color(0xF20E181D), veil = Color(0xF00B1317), railTop = Color(0xFF0B1317), railFoot = Color(0xFF0F191E),
            stageTop = Color(0xFF16242B), stageFoot = Color(0xFF0B1317),
            lightest = Color(0xFF141E24), backdrop = Backdrop.CANYON, accentFill = listOf(Color(0xFF39E6D2), Color(0xFF39E6D2)),
            corner = 8.dp, cornerSmall = 6.dp, cornerCap = 8.dp, hueFollows = false,
            activeFill = Color(0xFF39E6D2), activeText = Color(0xFF04201C), activeEdge = Color(0xFF39E6D2), idleFill = Color(0xFF16232A),
            solidSmall = true, titleCase = TitleCase.UPPER, titleTracking = 0.06f,
            font = ThemeFonts.condensed, charEm = 0.5f, highlight = Color(0xFFE8601C),
            ownAccent = UiTheme(Color(0xFF39E6D2), Color(0xFF39E6D2), Color(0xFF25BFAE)),
        )

        val RALLY = Palette(
            "rally", "Ралли",
            bg = Color(0xFF9AB9AF), bgTop = Color(0xFF8FB1A7),
            // lighter than the sage, the dark text reads on it in daylight
            card = Color(0x99FFFFFF), cardHi = Color(0xB3FFFFFF), sheet = Color(0xFFE6F0EC),
            border = Color(0x800A5E60), text = Color(0xFF0F2E2E), sub = Color(0xFF1F4644),
            gray = Color(0xFF3D6462), blue = Color(0xFF0B4A86), green = Color(0xFF075A2E), orange = Color(0xFF8A4300),
            red = Color(0xFFA30F12), yellow = Color(0xFF5A4800), ink = Color(0xFF0F2E2E), hair = Color(0xFF0F2E2E), onAccent = Color.White,
            overlay = Color(0xF7EDF4F1), veil = Color(0xEEE6F0EC), railTop = Color(0xFF8FB1A7), railFoot = Color(0xFF9AB9AF),
            stageTop = Color(0xFF1B3331), stageFoot = Color(0xFF0B1A19),
            lightest = Color(0xFF9AB9AF), backdrop = Backdrop.RALLY, accentFill = listOf(Color(0xFF0C8385), Color(0xFF0C8385)),
            corner = 4.dp, cornerSmall = 4.dp, cornerCap = 4.dp, hueFollows = false,
            // the owner's teal a shade deeper: 4.5:1 for white on it
            activeFill = Color(0xFF0C8385), activeText = Color.White, solidSmall = true,
            titleCase = TitleCase.LOWER, titleBand = Color(0xFF0C8385),
            font = ThemeFonts.jura, fontFromSp = 14f, charEm = 0.64f, digitEm = 0.7f,
            ownAccent = UiTheme(Color(0xFF0A4A4C), Color(0xFF0A5658), Color(0xFF0B6668)),
        )

        val WANTED = Palette(
            "wanted", "Розыск",
            bg = Color(0xFF0B0B0C), bgTop = Color(0xFF0B0B0C), card = Color(0xFF1A1B1D), cardHi = Color(0xFF232427),
            sheet = Color(0xFF17181A), border = Color(0xFF2C2D30), text = Color.White, sub = Color(0xFFB8B9BC),
            gray = Color(0xFF7D7E83), blue = Color(0xFF9CC7F0), green = Color(0xFF5FD38D), orange = Color(0xFFFFA726),
            red = Color(0xFFFF9180), yellow = Color(0xFFE6C84A), ink = Color.White, hair = Color.White, onAccent = Color.White,
            overlay = Color(0xF2141517), veil = Color(0xF00B0B0C), railTop = Color(0xFF0B0B0C), railFoot = Color(0xFF0B0B0C),
            stageTop = Color(0xFF1D1E20), stageFoot = Color(0xFF0B0B0C),
            backdrop = Backdrop.WANTED, cardGradient = listOf(Color(0xFF1A1B1D), Color(0xFF111111)),
            accentFill = listOf(Color(0xFFE01A24), Color(0xFFE01A24)),
            corner = 4.dp, cornerSmall = 3.dp, cornerCap = 4.dp, hueFollows = false,
            activeFill = Color(0xFFE01A24), activeText = Color.White, activeEdge = Color(0xFFE01A24), idleFill = Color(0xFF1A1B1D),
            solidSmall = true, boldText = true, shortCaps = true, subItalic = true,
            titleColor = Color(0xFFE01A24), panelMark = PanelMark.STRIPE,
            navItem = Color(0xFFF7F7F7), navText = Color(0xFF1A1B1D), navSlant = true,
            font = ThemeFonts.exo, charEm = 0.74f,
            ownAccent = UiTheme(Color(0xFFFF6066), Color(0xFFE01A24), Color(0xFFC8141E)),
        )

        val all = listOf(NEON, LIGHT, STUDIO, MIDNIGHT, NIGHT_RACE, MINT, NOIR, LAGOON, SHOWROOM, PURSUIT, CANYON, RALLY, WANTED)

        fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: NEON
    }
}

/**
 * The background of the whole window for this palette. Неон and Светлая: the plain vertical gradient. Студия and Полночь: a few
 * gradients drawn once, per size, into one bitmap the size of the screen, then only copied: no blur, nothing per frame.
 * [flat] (the Low tier): one colour.
 */
fun Modifier.themeBackdrop(p: Palette, flat: Boolean): Modifier = when {
    // one column of the gradient, stretched sideways: a gradient worked out per pixel for the whole window on every frame was
    // the costliest thing on a screen with nothing else to draw (1920x1080 on a software GPU: 36 ms a frame against 20)
    p.backdrop == Palette.Backdrop.PLAIN -> drawWithCache {
        val h = size.height.toInt().coerceAtLeast(1)
        val w = size.width.toInt().coerceAtLeast(1)
        val strip = ImageBitmap(1, h)
        CanvasDrawScope().draw(this, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(strip), Size(1f, h.toFloat())) {
            drawRect(Brush.verticalGradient(listOf(p.bgTop, p.bg), 0f, h.toFloat()))
        }
        onDrawBehind {
            drawImage(strip, dstSize = androidx.compose.ui.unit.IntSize(w, h), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
        }
    }
    flat -> background(p.bg)
    else -> drawWithCache {
        val image = renderBackdrop(p, size, this)
        onDrawBehind { drawImage(image) }
    }
}

private fun renderBackdrop(p: Palette, size: Size, density: Density): ImageBitmap {
    val w = size.width.toInt().coerceAtLeast(1)
    val h = size.height.toInt().coerceAtLeast(1)
    val image = ImageBitmap(w, h)
    val canvas = androidx.compose.ui.graphics.Canvas(image)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
        val width = this.size.width
        val height = this.size.height
        when (p.backdrop) {
            Palette.Backdrop.STUDIO -> {
                // steel blue to slate teal
                drawRect(Brush.linearGradient(listOf(Color(0xFF16262F), Color(0xFF263E4B), Color(0xFF3B5866)), Offset.Zero, Offset(width, height)))
                // the soft light falling across it
                drawRect(Brush.linearGradient(
                    0f to Color.Transparent, 0.42f to Color(0x24CFE4EE), 0.62f to Color(0x0FCFE4EE), 1f to Color.Transparent,
                    start = Offset(width * 0.1f, 0f), end = Offset(width * 0.75f, height),
                ))
                // the slightly lighter floor
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0x264A6A78), startY = height * 0.72f, endY = height), Offset(0f, height * 0.72f), Size(width, height * 0.28f))
            }
            Palette.Backdrop.MIDNIGHT -> {
                drawRect(Color(0xFF05070D))
                // deep navy from above, like a calm night map
                drawRect(Brush.radialGradient(listOf(Color(0xFF0D1A3A), Color(0x660A1228), Color.Transparent), Offset(width * 0.55f, -height * 0.1f), maxOf(width, height) * 0.75f))
                drawRect(Brush.radialGradient(listOf(Color(0x400A1A40), Color.Transparent), Offset(width * 0.05f, height * 1.05f), maxOf(width, height) * 0.5f))
            }
            Palette.Backdrop.NIGHT_RACE -> {
                // wet asphalt: near black with a faint teal cast, lighter where the street lights reach it
                drawRect(Brush.verticalGradient(0f to Color(0xFF070C0F), 0.42f to Color(0xFF0B171C), 1f to Color(0xFF060A0C)))
                val vanish = Offset(width * 0.42f, height * 0.36f)
                drawRect(Brush.radialGradient(listOf(Color(0x3320606E), Color.Transparent), vanish, maxOf(width, height) * 0.5f))
                // the lights' streaks on the wet road, running to one point
                for (i in 0..6) {
                    val x = width * (-0.25f + i * 0.25f)
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(vanish.x, vanish.y); lineTo(x, height); lineTo(x + width * 0.05f, height); close()
                    }
                    drawPath(path, Brush.verticalGradient(listOf(Color.Transparent, Color(0x1A5FB4C8)), vanish.y, height))
                }
                // the grain of the asphalt, from a fixed seed so the picture is the same each time
                val rnd = java.util.Random(47)
                val grain = List(3000) { Offset(rnd.nextFloat() * width, height * 0.36f + rnd.nextFloat() * height * 0.64f) }
                drawPoints(grain, androidx.compose.ui.graphics.PointMode.Points, Color(0x14BFE4EE), strokeWidth = 1f)
            }
            Palette.Backdrop.MINT -> {
                // white down to mint, across the screen
                drawRect(Brush.linearGradient(listOf(Color(0xFFFBFDFC), Color(0xFFEEF5F1), Color(0xFFDDEAE4)), Offset.Zero, Offset(width, height)))
            }
            Palette.Backdrop.NOIR -> {
                drawRect(Color.Black)
                // the faintest vignette: black in the middle, a touch lighter at the edges
                drawRect(Brush.radialGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color(0xFF0B0B0D), center = Offset(width / 2, height / 2), radius = maxOf(width, height) * 0.72f))
            }
            Palette.Backdrop.LAGOON -> {
                drawRect(Brush.linearGradient(listOf(Color(0xFF0B3C40), Color(0xFF0F5A5C), Color(0xFF137A78)), Offset.Zero, Offset(width, height)))
                // slightly lighter at one corner
                drawRect(Brush.radialGradient(listOf(Color(0x401A8A87), Color.Transparent), Offset(width, height), maxOf(width, height) * 0.45f))
            }
            Palette.Backdrop.PURSUIT -> {
                drawRect(Color(0xFF0D0E0A))
                // a grimy vignette: a little olive light in the middle, and grit from a fixed seed
                drawRect(Brush.radialGradient(0f to Color(0xFF1A1C12), 0.6f to Color(0xFF10110C), 1f to Color(0xFF070806), center = Offset(width * 0.5f, height * 0.45f), radius = maxOf(width, height) * 0.7f))
                val rnd = java.util.Random(11)
                drawPoints(List(2400) { Offset(rnd.nextFloat() * width, rnd.nextFloat() * height) }, androidx.compose.ui.graphics.PointMode.Points, Color(0x12D8D2A8), strokeWidth = 1f)
            }
            Palette.Backdrop.CANYON -> {
                drawRect(Brush.linearGradient(listOf(Color(0xFF0B1317), Color(0xFF141E24)), Offset.Zero, Offset(width, height)))
                // the rock walls, darker, closing in from both sides
                for ((a, b) in listOf(0.34f to 0.16f, 0.66f to 0.84f)) {
                    drawPath(androidx.compose.ui.graphics.Path().apply {
                        moveTo(width * a, 0f); lineTo(width * b, height)
                        lineTo(if (a < 0.5f) 0f else width, height); lineTo(if (a < 0.5f) 0f else width, 0f); close()
                    }, Color(0x22050A0D))
                }
            }
            Palette.Backdrop.RALLY -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF8FB1A7), Color(0xFFA6C3BA))))
                // the soft band across it
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color(0x24FFFFFF), 1f to Color.Transparent, startY = height * 0.18f, endY = height * 0.62f), Offset(0f, height * 0.18f), Size(width, height * 0.44f))
            }
            Palette.Backdrop.WANTED -> {
                drawRect(Color(0xFF0B0B0C))
                // faint dark grey facets, on the slant
                fun facet(x0: Float, x1: Float, x2: Float, x3: Float, c: Color) = drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(width * x0, 0f); lineTo(width * x1, 0f); lineTo(width * x2, height); lineTo(width * x3, height); close()
                }, c)
                facet(0.08f, 0.34f, 0.12f, -0.14f, Color(0xFF131315))
                facet(0.34f, 0.52f, 0.30f, 0.12f, Color(0xFF0F0F11))
                facet(0.72f, 1.05f, 0.83f, 0.50f, Color(0xFF151517))
            }
            Palette.Backdrop.PLAIN -> drawRect(p.bg)
        }
    }
    return image
}

/** A corner cut at an angle: two opposite corners deep, the other two barely, as the menus of a street-racing game. */
fun chamferShape(size: Dp): androidx.compose.ui.graphics.Shape {
    val deep = minOf(size, 16.dp)
    val slight = deep * 0.3f
    return androidx.compose.foundation.shape.CutCornerShape(topStart = slight, topEnd = deep, bottomEnd = slight, bottomStart = deep)
}

/** Round corners smaller than this stay round in the racing theme: dots, bars and tracks are no panels. */
private val CHAMFER_FROM = 6.dp

/**
 * Shadows foundation's in this package, so a panel or a button anywhere takes the theme's corners by a removed import
 * line. Outside the racing theme it is foundation's own. Never in a top-level val: that would keep the first theme's shape.
 */
fun RoundedCornerShape(size: Dp): androidx.compose.ui.graphics.Shape = when {
    size < CHAMFER_FROM -> androidx.compose.foundation.shape.RoundedCornerShape(size)
    C.palette.race -> chamferShape(size)
    else -> androidx.compose.foundation.shape.RoundedCornerShape(C.palette.cornerCap?.let { minOf(size, it) } ?: size)
}

/** Where a slanted menu item sits: on a bar it leans both ways, in a side rail its edge against the screen is straight. */
enum class SlantSide { BAR, LEFT, RIGHT }

/** A menu item on the slant, in px of [lean]: on a bar its top edge moved right and its foot left; beside the screen's edge only the inner edge leans. */
fun slantShape(lean: Dp, side: SlantSide = SlantSide.BAR): androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val d = (lean.value * 2f).coerceAtMost(size.width / 4f)
    val top = slantSpan(side, size.width, d, 0f)
    val foot = slantSpan(side, size.width, d, 1f)
    moveTo(top.first, 0f); lineTo(top.second, 0f); lineTo(foot.second, size.height); lineTo(foot.first, size.height); close()
}

/** From where to where a slanted item of width [w] reaches at height [y], 0 at its top and 1 at its foot, [d] being its whole lean. */
fun slantSpan(side: SlantSide, w: Float, d: Float, y: Float): Pair<Float, Float> = when (side) {
    SlantSide.BAR -> d * (1f - y) to w - d * y
    SlantSide.LEFT -> 0f to w - d * y
    SlantSide.RIGHT -> d * y to w
}

/** The heights a slanted item's label takes, as parts of the item: its room is what the shape leaves it over all of them. */
private const val SLANT_LABEL_TOP = 0.55f
private const val SLANT_LABEL_FOOT = 0.85f

/** What the shape takes from a label's room, start and end, in the units of [d]. */
fun slantLabelInsets(side: SlantSide, d: Float): Pair<Float, Float> {
    val top = slantSpan(side, 0f, d, SLANT_LABEL_TOP)
    val foot = slantSpan(side, 0f, d, SLANT_LABEL_FOOT)
    return maxOf(top.first, foot.first) to maxOf(-top.second, -foot.second)
}

/** How far an icon moves from the item's middle to stand in the middle of the shape at its own height, a third of the way down. */
fun slantIconShift(side: SlantSide, d: Float): Float = slantSpan(side, 0f, d, 0.3f).let { (it.first + it.second) / 2f }

/** The menu's own bar, where the theme has one. */
fun Modifier.navBarFill(): Modifier = C.palette.navBar?.let { background(it) } ?: this

/**
 * The theme's static mark on a larger panel, drawn behind its content, once per size: white corner brackets, or a red stripe
 * down its side. Nothing at all in the other themes, and none on what is too small to carry it, chips above all.
 */
fun Modifier.panelMarks(): Modifier {
    val mark = C.palette.panelMark
    if (mark == Palette.PanelMark.NONE) return this
    return drawWithCache {
        val big = size.minDimension >= PANEL_MARK_FROM.toPx()
        val len = 12.dp.toPx()
        val w = 1.5.dp.toPx()
        val inset = 4.dp.toPx()
        val stripe = 3.dp.toPx()
        onDrawBehind {
            if (big && mark == Palette.PanelMark.STRIPE) drawRect(Color(0xFFC8141E), Offset.Zero, Size(stripe, size.height))
            if (big && mark == Palette.PanelMark.CORNERS) for (right in listOf(false, true)) for (foot in listOf(false, true)) {
                val x = if (right) size.width - inset else inset
                val y = if (foot) size.height - inset else inset
                val dx = if (right) -len else len
                val dy = if (foot) -len else len
                drawLine(Color.White, Offset(x, y), Offset(x + dx, y), w)
                drawLine(Color.White, Offset(x, y), Offset(x, y + dy), w)
            }
        }
    }
}

private val PANEL_MARK_FROM = 72.dp

fun RoundedCornerShape(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): androidx.compose.ui.graphics.Shape =
    androidx.compose.foundation.shape.RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)

/** Soft light behind the active item, where the theme has it: drawn once for its size, only while it is the active one. */
fun Modifier.activeGlow(): Modifier {
    val g = C.palette.activeGlow
    if (g == null || C.flat) return this
    return drawWithCache {
        val b = Brush.radialGradient(listOf(g.copy(alpha = 0.42f), g.copy(alpha = 0.12f), Color.Transparent), Offset(size.width / 2, size.height / 2), size.maxDimension * 0.7f)
        onDrawBehind { drawRect(b) }
    }
}

/** A soft rim round a shape, where there's room outside it: no blur, a few faint outlines growing outwards. Off on the Low tier. */
private fun Modifier.softRim(color: Color, reach: Dp, strength: Float, down: Dp, corner: Dp): Modifier = drawWithCache {
    val px = reach.toPx()
    val fall = down.toPx()
    val r = corner.toPx()
    val steps = 4
    val tint = color.copy(alpha = strength)
    onDrawBehind {
        for (i in 1..steps) {
            val o = px * i / steps
            drawRoundRect(
                tint, Offset(-o, -o + fall), Size(size.width + o * 2, size.height + o * 2),
                androidx.compose.ui.geometry.CornerRadius(r + o),
            )
        }
    }
}

/** The theme's shadow round a panel, put before its clip. */
fun Modifier.panelShadow(): Modifier {
    val p = C.palette
    val c = p.panelShadow
    if (c == null || C.flat) return this
    return softRim(c, 6.dp, 0.022f, 2.dp, if (C.smallCorners) p.cornerSmall else p.corner)
}

/** The soft light round a solid active item (the white disk of Нуар), put before its clip. */
fun Modifier.activeHalo(corner: Dp): Modifier {
    val p = C.palette
    val g = p.activeGlow
    if (g == null || p.activeFill == null || C.flat) return this
    return softRim(g, 7.dp, 0.07f, 0.dp, corner)
}

/**
 * The ends of a scrolling rail fade out into whatever is behind it: for a backdrop no fade colour can match. Draws in a layer
 * of the rail's own size, only for these themes, and only when something is scrolled past that end.
 */
fun Modifier.fadeEnds(top: Boolean, bottom: Boolean, topPx: Float, bottomPx: Float): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        if (top) drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), 0f, topPx), size = Size(size.width, topPx), blendMode = androidx.compose.ui.graphics.BlendMode.DstOut)
        if (bottom) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), size.height - bottomPx, size.height), Offset(0f, size.height - bottomPx), Size(size.width, bottomPx), blendMode = androidx.compose.ui.graphics.BlendMode.DstOut)
    }

/**
 * Chrome that changes a few times a second at most, in a texture of its own: the frames of a needle elsewhere copy it instead of
 * drawing every icon and text of it again. Not where the theme draws outside its bounds (a soft shadow or halo): a texture would cut that.
 */
fun Modifier.chromeLayer(): Modifier {
    val p = C.palette
    return if (p.panelShadow != null || p.activeGlow != null) this
    else graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
}

/** What the theme depends on from the tier and the screen, kept in [C] for the plain reads there. */
@androidx.compose.runtime.Composable
fun ThemeSizes() {
    val tier by kg.forester.sg.data.DeviceTier.current.collectAsState()
    val small = LocalCar.current.small
    // read here, in the composition: a read inside the effect would not bring it back on a change
    val low = tier == kg.forester.sg.data.Tier.LOW
    androidx.compose.runtime.SideEffect {
        C.flat = low
        C.smallCorners = small
    }
}

/** What the screens keep only to be quicker next time: gone when the app leaves the screen or memory runs short. */
fun trimUiCaches() {
    AppIcons.cache.evictAll()
}
