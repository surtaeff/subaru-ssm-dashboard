package kg.forester.sg

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kg.forester.sg.ui.AccentTheme
import kg.forester.sg.ui.Palette
import kg.forester.sg.ui.contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PaletteTest {
    private fun rgb(c: Color) = (((c.red * 255 + 0.5f).toInt()) shl 16) or (((c.green * 255 + 0.5f).toInt()) shl 8) or (c.blue * 255 + 0.5f).toInt()

    @Test fun aSlantedItemsSideEdgeIsStraightAndItsLabelStaysInside() {
        val w = 134f
        val d = 12f
        // beside the screen: the outer edge doesn't lean, and left and right mirror each other
        for (y in listOf(0f, 0.5f, 1f)) {
            assertEquals(0f, kg.forester.sg.ui.slantSpan(kg.forester.sg.ui.SlantSide.LEFT, w, d, y).first, 0f)
            assertEquals(w, kg.forester.sg.ui.slantSpan(kg.forester.sg.ui.SlantSide.RIGHT, w, d, y).second, 0f)
            assertEquals(w - kg.forester.sg.ui.slantSpan(kg.forester.sg.ui.SlantSide.LEFT, w, d, y).second, kg.forester.sg.ui.slantSpan(kg.forester.sg.ui.SlantSide.RIGHT, w, d, y).first, 0.001f)
        }
        for (side in kg.forester.sg.ui.SlantSide.entries) {
            val (start, end) = kg.forester.sg.ui.slantLabelInsets(side, d)
            for (y in listOf(0.55f, 0.7f, 0.85f)) {
                val (from, to) = kg.forester.sg.ui.slantSpan(side, w, d, y)
                assertTrue("$side at $y", start >= from - 0.001f && w - end <= to + 0.001f)
            }
        }
    }

    /** [fg] over [bg] (which may itself be translucent, over [under]): the ratio a reader gets. */
    private fun ratio(fg: Color, bg: Color, under: Color = Color.White): Float {
        fun over(top: Color, base: Color) = Color(
            top.red * top.alpha + base.red * (1 - top.alpha), top.green * top.alpha + base.green * (1 - top.alpha),
            top.blue * top.alpha + base.blue * (1 - top.alpha), 1f,
        )
        val b = over(bg, under)
        return contrast(rgb(over(fg, b)), rgb(b))
    }

    private fun assertColor(what: String, hex: Long, c: Color) = assertEquals(what, Color(hex), c)

    @Test fun neonIsExactlyTheOldConstants() {
        val p = Palette.NEON
        assertColor("Bg", 0xFF080A12, p.bg)
        assertColor("top of the background", 0xFF0E1222, p.bgTop)
        assertColor("Card", 0xFF111522, p.card)
        assertColor("CardHi", 0xFF181D2E, p.cardHi)
        assertColor("dialog", 0xFF111522, p.sheet)
        assertColor("Border", 0xFF232A40, p.border)
        assertColor("Text", 0xFFEDEFF5, p.text)
        assertColor("Sub", 0xFFB4BACC, p.sub)
        assertColor("Blue", 0xFF3B82F6, p.blue)
        assertColor("Green", 0xFF22C55E, p.green)
        assertColor("Orange", 0xFFF59E0B, p.orange)
        assertColor("Red", 0xFFEF4444, p.red)
        assertColor("Gray", 0xFF64748B, p.gray)
        assertColor("near", 0xFFD9C24A, p.yellow)
        assertEquals(Color.White, p.ink)
        assertEquals(Color.White, p.hair)
        assertEquals(Color.White, p.onAccent)
        assertColor("banner", 0xF0101422, p.overlay)
        assertColor("veil", 0xE6070A14, p.veil)
        assertColor("rail top", 0xFF0D1120, p.railTop)
        assertColor("rail foot", 0xFF0A0C16, p.railFoot)
        assertColor("stage", 0xFF1C2233, p.stageTop)
        assertEquals(p.bg, p.stageFoot)
    }

    @Test fun neonDrawsNothingNew() {
        val p = Palette.NEON
        assertEquals(null, p.cardGradient)
        assertEquals(null, p.iconFill)
        assertEquals(null, p.accentFill)
        assertFalse(p.thin)
        assertEquals(14f, p.corner.value, 0f)
        assertEquals(14f, p.cornerSmall.value, 0f)
        assertEquals(Palette.Backdrop.PLAIN, p.backdrop)
        assertFalse(p.race)
        assertNull(p.font); assertNull(p.cornerCap); assertNull(p.navBar); assertNull(p.highlight)
        assertFalse(p.solidSmall || p.shortCaps || p.subItalic || p.boldText || p.navSlant)
        assertEquals(Palette.PanelMark.NONE, p.panelMark)
        assertEquals(Palette.TitleCase.AS_IS, p.titleCase)
        assertSame("Настройки", "Настройки".let { p.cased(it) })
        assertEquals(0.62f, p.charEm, 0f); assertEquals(0.62f, p.digitEm, 0f)
        // the weights of the text stay
        for (w in listOf(androidx.compose.ui.text.font.FontWeight.Bold, androidx.compose.ui.text.font.FontWeight.Normal)) assertEquals(w, p.weight(w, 60f))
        val accent = AccentTheme.VIOLET.theme
        assertSame("the hue is the driver's", accent, p.accentFor(accent))
    }

    @Test fun neonIsTheDefaultAndAnUnknownIdFallsBackToIt() {
        assertSame(Palette.NEON, Palette.byId(null))
        assertSame(Palette.NEON, Palette.byId("what"))
        assertEquals(listOf("neon", "light", "studio", "midnight", "nightrace", "mint", "noir", "lagoon", "showroom", "pursuit", "canyon", "rally", "wanted"), Palette.all.map { it.id })
        assertEquals(listOf("Неон", "Светлая", "Студия", "Полночь", "Ночная гонка", "Мята", "Нуар", "Лагуна", "Шоурум", "Погоня", "Каньон", "Ралли", "Розыск"), Palette.all.map { it.title })
    }

    @Test fun textReadsInEveryTheme() {
        for (p in Palette.all) for (under in listOf(p.bg, p.bgTop, p.lightest)) for (panel in listOf(p.card, p.cardHi)) {
            val w = "${p.id} on ${panel} over $under"
            assertTrue("$w text", ratio(p.text, panel, under) >= 4.5f)
            assertTrue("$w secondary", ratio(p.sub, panel, under) >= 4.5f)
            assertTrue("$w value", ratio(p.ink, panel, under) >= 4.5f)
        }
        for (p in Palette.all) {
            assertTrue(p.id, ratio(p.text, p.sheet) >= 4.5f)
            assertTrue(p.id, ratio(p.sub, p.sheet) >= 4.5f)
            assertEquals("${p.id}: the dialog must not be see-through", 1f, p.sheet.alpha, 0f)
            assertEquals("${p.id}: the flat background must be solid", 1f, p.bg.alpha, 0f)
        }
    }

    @Test fun statusColoursKeepContrastAndStayRecognisable() {
        // Неон's are the old ones, whatever they measure; the new themes are held to text contrast
        for (p in Palette.all - Palette.NEON) for (under in listOf(p.bg, p.lightest)) for (panel in listOf(p.card, p.cardHi)) {
            for ((n, c) in listOf("red" to p.red, "orange" to p.orange, "green" to p.green, "yellow" to p.yellow, "blue" to p.blue)) {
                assertTrue("${p.id} $n on $panel over $under: ${ratio(c, panel, under)}", ratio(c, panel, under) >= 4.5f)
            }
        }
        for (p in Palette.all) {
            assertTrue(p.id + " red", p.red.red > p.red.green && p.red.red > p.red.blue)
            assertTrue(p.id + " green", p.green.green > p.green.red && p.green.green > p.green.blue)
            assertTrue(p.id + " orange", p.orange.red > p.orange.green && p.orange.green > p.orange.blue)
        }
    }

    @Test fun lightKeepsEveryAccentReadableOnWhite() {
        val p = Palette.LIGHT
        for (t in AccentTheme.entries) {
            val a = p.accentFor(t.theme)
            for (panel in listOf(p.card, p.cardHi, p.bg)) {
                assertTrue("${t.id} main ${ratio(a.main, panel)}", ratio(a.main, panel) >= 4.5f)
                assertTrue("${t.id} deep is a chart line and a needle", ratio(a.deep, panel) >= 3f)
                assertTrue("${t.id} second", ratio(a.second, panel) >= 3f)
            }
            assertTrue("white on a filled button", ratio(p.onAccent, a.main) >= 4.5f)
        }
        // a pale own colour too
        val pale = kg.forester.sg.ui.UiTheme(Color(0xFFFFFF88), Color(0xFFFFFFAA), Color(0xFFEEEEFF))
        assertTrue(ratio(p.accentFor(pale).main, p.card) >= 4.5f)
    }

    @Test fun chartLinesAndNeedlesOnLight() {
        val p = Palette.LIGHT
        for (c in listOf(p.blue, p.green, p.orange, p.red, p.gray)) assertTrue("$c", ratio(c, p.card) >= 3f)
        assertTrue("grid is visible", ratio(p.border, p.card) >= 1.3f)
    }

    @Test fun studioAndMidnightHaveTheirOwnAccent() {
        val own = AccentTheme.GREEN.theme
        for (p in listOf(Palette.STUDIO, Palette.MIDNIGHT, Palette.NIGHT_RACE)) {
            assertFalse(p.id, p.hueFollows)
            assertEquals(p.id, p.accentFor(AccentTheme.RED.theme), p.accentFor(own))
            val a = p.accentFor(own)
            for (panel in listOf(p.card, p.cardHi)) for (under in listOf(p.bg, p.lightest)) assertTrue("${p.id} ${ratio(a.main, panel, under)}", ratio(a.main, panel, under) >= 4.5f)
            assertTrue(p.id, ratio(p.onAccent, p.accentFill?.last() ?: a.main) >= 4.5f)
        }
        assertEquals(Color(0xFF2F6BFF), Palette.MIDNIGHT.accentFor(own).deep)
        assertTrue(Palette.NEON.hueFollows && Palette.LIGHT.hueFollows)
    }

    @Test fun theFourLooksFromTheBrief() {
        assertEquals(Color(0xFF05070D), Palette.MIDNIGHT.bg)
        assertEquals(listOf(Color(0xFF0B1530), Color(0xFF16264F)), Palette.MIDNIGHT.cardGradient)
        assertEquals(Palette.Backdrop.STUDIO, Palette.STUDIO.backdrop)
        assertEquals(24f, Palette.STUDIO.corner.value, 0f)
        assertTrue(Palette.STUDIO.cornerSmall < Palette.STUDIO.corner)
        assertTrue("translucent, over a cached background", Palette.STUDIO.card.alpha < 1f)
        assertTrue(Palette.STUDIO.thin && Palette.MIDNIGHT.thin)
        val studio = Palette.STUDIO.weight(androidx.compose.ui.text.font.FontWeight.Bold, 60f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Light, studio)
    }

    @Test fun nightRaceFromTheBrief() {
        val p = Palette.NIGHT_RACE
        assertTrue(p.race)
        assertEquals(Palette.Backdrop.NIGHT_RACE, p.backdrop)
        // petrol blue at 60 to 80 %, flat, no glow
        assertEquals(Color(0xFF0F4A63).copy(alpha = p.card.alpha), p.card)
        assertTrue(p.card.alpha in 0.6f..0.8f)
        assertEquals(null, p.cardGradient)
        assertEquals(null, p.activeGlow)
        assertEquals(Color(0xFF7FC4DC), p.sub)
        assertEquals(Color.White, p.ink)
        assertEquals("the stars' yellow marks what is active", Color(0xFFE8D21E), p.yellow)
        assertEquals("teal bars", Color(0xFF1E9AA8), p.accentFor(AccentTheme.GREEN.theme).deep)
        assertEquals("a filled button writes white, as a selected chip does", Color.White, p.onAccent)
        assertEquals(Color.White, p.activeText)
    }

    @Test fun onlyNightRaceSlantsTheText() {
        val base = androidx.compose.ui.text.TextStyle(fontSize = 15.sp)
        for (p in Palette.all - Palette.NIGHT_RACE) {
            if (p.font == null) assertSame("${p.id} keeps the very style", base, p.textStyle(base, "Настройки"))
            assertFalse(p.id, p.race)
            assertNull(p.id, p.textStyle(base, "Настройки").fontStyle)
        }
        val label = Palette.NIGHT_RACE.textStyle(base, "Настройки")
        assertEquals(androidx.compose.ui.text.font.FontStyle.Italic, label.fontStyle)
        assertTrue("tracking on a word", label.letterSpacing.value >= 0.015f)
    }

    @Test fun numbersGetNoTrackingAndKeepTheirTabularFigures() {
        val base = androidx.compose.ui.text.TextStyle(fontSize = 40.sp, fontFeatureSettings = "tnum")
        for (t in listOf("88.8", "—", "12 В", "105°C", "14:05")) {
            val v = Palette.NIGHT_RACE.textStyle(base, t)
            assertEquals(t, androidx.compose.ui.text.font.FontStyle.Italic, v.fontStyle)
            assertEquals(t, 0f, v.letterSpacing.value, 0f)
            assertEquals("tnum", v.fontFeatureSettings)
        }
    }

    @Test fun smallTextIsTrackedLess() {
        val small = Palette.NIGHT_RACE.textStyle(androidx.compose.ui.text.TextStyle(fontSize = 11.sp), "подпись")
        val big = Palette.NIGHT_RACE.textStyle(androidx.compose.ui.text.TextStyle(fontSize = 17.sp), "подпись")
        assertTrue(small.letterSpacing.value < big.letterSpacing.value)
    }

    // ---- the rules of the look, by source

    @Test fun chamferedShapesStayOutOfTheConstantsThatOutliveASwitch() {
        // a shape kept in a top-level val would stay as the theme was at its first read, also after going back to Неон
        val offenders = ui.listFiles()!!.filter { it.extension == "kt" }.flatMap { f ->
            Regex("(?m)^(private |internal )?val \\w+(: *\\w+)? *= *RoundedCornerShape\\(").findAll(f.readText()).map { f.name + ": " + it.value }.toList()
        } + Regex("(?m)^(private )?val \\w+(: *\\w+)? *= *RoundedCornerShape\\(").findAll(File("src/main/java/kg/forester/sg/MainActivity.kt").readText()).map { it.value }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun theDarkStagesKeepTheirOwnRoundShapes() {
        for (n in listOf("ClusterNav.kt", "SportDisplay.kt", "SportEditor.kt", "Engine3D.kt", "MiniMap.kt")) {
            assertTrue(n, text(n).contains("import androidx.compose.foundation.shape.RoundedCornerShape"))
        }
    }

    private val ui = File("src/main/java/kg/forester/sg/ui")
    private fun text(n: String) = File(ui, n).readText()

    @Test fun noBlurAndNoPerFrameGlowOnTheThemePath() {
        val src = text("Palette.kt")
        assertFalse(src.contains(".blur("))
        assertFalse(src.contains("RenderEffect"))
        assertFalse(src.contains("setShadowLayer"))
        assertTrue("the backdrop is made once per size", src.contains("drawWithCache"))
    }

    @Test fun thePaletteChangesOnlyOnASwitch() {
        val c = text("Components.kt")
        assertEquals("written only in applyPalette", 1, Regex("(?m)^\\s+palette = ").findAll(c).count())
        assertTrue(c.contains("var palette by mutableStateOf(Palette.NEON)"))
        val objC = c.substring(c.indexOf("object C {"), c.indexOf("val Rounded"))
        assertFalse("every colour of C comes from the palette", objC.contains("Color(0x"))
    }

    @Test fun theChooserIsInBothPlacesAndNeonIsTheDefault() {
        assertTrue(text("LogSettingsScreens.kt").contains("UiThemeChooser()"))
        val picker = text("ThemePicker.kt")
        assertTrue(picker.substring(picker.indexOf("fun ThemeDialog"), picker.indexOf("fun UiThemeChooser")).contains("UiThemeChooser()"))
        val repo = File("src/main/java/kg/forester/sg/data/Repo.kt").readText()
        assertTrue(repo.contains("p.getString(\"uiTheme\", kg.forester.sg.ui.Palette.NEON.id)"))
        assertTrue("applied at the start", repo.contains("C.applyPalette(settings.uiTheme)"))
    }

    @Test fun theNeonDialKeepsItsColoursWhateverThemeStartedTheApp() {
        val g = text("Gauges.kt")
        val core = g.substring(g.indexOf("private val CoreDialStyles"), g.indexOf("needle = Color(0xFFFF4D6D)"))
        assertFalse("a lazy list must not catch the theme that was on at its first read", Regex("\\bC\\.[A-Z]").containsMatchIn(core))
    }

    // ---------------------------------------------------------------- the four more themes

    private val newOnes = listOf(Palette.MINT, Palette.NOIR, Palette.LAGOON, Palette.SHOWROOM)

    @Test fun thirteenThemesInTheChooserAndInEnglish() {
        assertEquals(13, Palette.all.size)
        assertEquals(13, Palette.all.map { it.id }.toSet().size)
        assertEquals(listOf("Мята", "Нуар", "Лагуна", "Шоурум"), newOnes.map { it.title })
        val en = java.io.File("src/main/assets/i18n/en.tsv").readLines().associate { it.substringBefore('\t') to it.substringAfter('\t') }
        assertEquals(listOf("Mint", "Noir", "Lagoon", "Showroom"), newOnes.map { en[it.title] })
        for (p in newOnes) assertFalse(p.id, p.hueFollows)
    }

    @Test fun onlyTheNewThemesHaveASolidActiveItemOrAShadow() {
        for (p in Palette.all - newOnes.toSet() - gameOnes.toSet() - Palette.NIGHT_RACE) {
            assertNull(p.id, p.activeFill); assertNull(p.id, p.activeText); assertNull(p.id, p.panelShadow)
        }
        assertNull(Palette.NIGHT_RACE.panelShadow)
    }

    @Test fun activeItemsAndButtonsRead() {
        for (p in listOf(Palette.MINT, Palette.NOIR, Palette.SHOWROOM)) {
            assertTrue(p.id, ratio(p.activeText!!, p.activeFill!!) >= 4.5f)
        }
        for (p in newOnes) for (c in p.accentFill!!) assertTrue("${p.id} on a filled button", ratio(p.onAccent, c) >= 4.5f)
    }

    @Test fun theLightOnesKeepDaylightContrast() {
        for (p in listOf(Palette.MINT, Palette.SHOWROOM, Palette.RALLY)) {
            val a = p.accentFor(AccentTheme.RED.theme)
            for (panel in listOf(p.card, p.cardHi, p.bg)) {
                assertTrue("${p.id} accent text on $panel", ratio(a.main, panel) >= 4.5f)
                assertTrue("${p.id} deep is a chart line and a needle", ratio(a.deep, panel) >= 3f)
                assertTrue("${p.id} second", ratio(a.second, panel) >= 3f)
            }
            for (c in listOf(p.blue, p.green, p.orange, p.red, p.gray)) assertTrue("${p.id} line $c", ratio(c, p.card) >= 3f)
            assertTrue("${p.id} separators show", ratio(p.border, p.card) >= 1.2f)
            assertTrue(p.id + " dark text", ratio(p.text, p.bg) >= 4.5f && ratio(p.sub, p.bg) >= 4.5f)
        }
    }

    @Test fun noirHasNoColourExceptStatus() {
        val p = Palette.NOIR
        assertEquals(Color(0xFF000000), p.bg)
        val a = p.accentFor(AccentTheme.GREEN.theme)
        for (c in listOf(a.main, a.deep, a.second, p.text, p.sub, p.blue, p.card, p.border)) {
            assertEquals(c.red, c.green, 0.02f); assertEquals(c.green, c.blue, 0.02f)
        }
        assertEquals(Color.White, p.activeFill)
        assertEquals(Color.Black, p.activeText)
        // warnings stand out sharply
        assertTrue(p.red.red > 0.9f && p.red.green < 0.5f)
        assertTrue(p.orange.red > 0.9f && p.orange.blue < 0.4f)
    }

    @Test fun lagoonIsTranslucentOverItsDiagonal() {
        val p = Palette.LAGOON
        assertEquals(Palette.Backdrop.LAGOON, p.backdrop)
        assertTrue(p.card.alpha in 0.7f..0.85f && p.cardHi.alpha in 0.7f..0.85f)
        assertEquals(0.25f, p.border.alpha, 0.01f)
        for (c in p.accentFor(AccentTheme.RED.theme).let { listOf(it.main, it.second) }) assertTrue(ratio(c, p.card, p.lightest) >= 3f)
    }

    @Test fun aSheetOfNineChoicesStaysOneScroller() {
        val t = java.io.File("src/main/java/kg/forester/sg/ui/ThemePicker.kt").readText()
        assertTrue("CarSheet(onClose, 620.dp, 600.dp, head = {" in t)
        assertTrue("Palette.all.chunked(perRow)" in t)
    }

    @Test fun selectionGoesThroughTheToken() {
        val src = java.io.File("src/main/java/kg/forester/sg/ui/Components.kt").readText()
        assertTrue("C.activeFill(C.Purple.copy(alpha = 0.28f))" in src)
        assertTrue("C.activeText(C.Text)" in src)
        assertTrue("activeFill(C.Purple.copy(alpha = 0.3f))" in java.io.File("src/main/java/kg/forester/sg/MainActivity.kt").readText())
    }

    // ---- selected against unselected, in all the themes ----

    private fun over(top: Color, base: Color) = Color(
        top.red * top.alpha + base.red * (1 - top.alpha), top.green * top.alpha + base.green * (1 - top.alpha),
        top.blue * top.alpha + base.blue * (1 - top.alpha), 1f,
    )

    /** The panel the chips lie on: the card, over the lightest the backdrop gets. */
    private fun panel(p: Palette) = over(p.card, p.lightest)

    private fun accent(p: Palette) = p.accentFor(AccentTheme.values().first().theme)

    private fun chipSelected(p: Palette) = over(p.activeFill ?: accent(p).deep.copy(alpha = 0.28f), panel(p))
    private fun chipIdle(p: Palette) = over(p.idleFill ?: p.cardHi, panel(p))

    private fun strong(a: Color, b: Color) = contrast(rgb(a), rgb(b))

    @Test fun nightRaceChipsAreSolidTealWithWhiteTextAndAPaleEdge() {
        val p = Palette.NIGHT_RACE
        assertEquals(Color(0xFF1E9AA8), p.activeFill)
        assertEquals(Color.White, p.activeText)
        assertEquals(p.sub, p.activeEdge)
        // dark glass: darker than the panel, its thin edge keeps it seen
        assertTrue(chipIdle(p).luminanceOf() < panel(p).luminanceOf())
        assertTrue(strong(chipSelected(p), chipIdle(p)) >= 3f)
        assertTrue("white on teal, the owner's colours", ratio(p.activeText!!, p.activeFill!!) >= 3f)
        assertTrue("pale text on the unselected", ratio(p.sub, chipIdle(p)) >= 4.5f)
        assertTrue("the unselected's edge shows", strong(p.border, chipIdle(p)) >= 1.5f)
    }

    /** Fill 3:1, or an edge in the accent at 3:1 against the unselected together with another text colour. */
    @Test fun selectedDiffersFromUnselectedInEveryTheme() {
        for (p in Palette.all) {
            val sel = chipSelected(p)
            val idle = chipIdle(p)
            val fill = strong(sel, idle)
            val edge = strong(p.activeEdge ?: accent(p).deep, idle)
            val text = (p.activeText ?: p.text) != p.sub
            assertTrue("${p.id}: chip fill %.2f, edge %.2f".format(fill, edge), fill >= 3f || (edge >= 3f && text))
            // the menu's item lies on the bare background, with no fill when not selected
            val railIdle = p.bg
            val railSel = over(p.activeFill ?: accent(p).deep.copy(alpha = 0.3f), railIdle)
            val railFill = strong(railSel, railIdle)
            val railEdge = strong(p.activeEdge ?: accent(p).deep, railIdle)
            assertTrue("${p.id}: menu fill %.2f, edge %.2f".format(railFill, railEdge), railFill >= 3f || (railEdge >= 3f && text))
            // a switch: its track in the accent against the panel; the thumb's place is the second cue
            assertTrue("${p.id}: switch %.2f".format(strong(accent(p).deep, panel(p))), strong(accent(p).deep, panel(p)) >= 1.8f)
        }
    }

    // ---- the four racing-game looks and the type ----

    private val gameOnes = listOf(Palette.PURSUIT, Palette.CANYON, Palette.RALLY, Palette.WANTED)

    @Test fun theGameThemesFromTheBrief() {
        val en = java.io.File("src/main/assets/i18n/en.tsv").readLines().associate { it.substringBefore('\t') to it.substringAfter('\t') }
        assertEquals(listOf("Pursuit", "Canyon", "Rally", "Wanted"), gameOnes.map { en[it.title] })
        assertEquals("Night Race", en[Palette.NIGHT_RACE.title])
        for (p in gameOnes) {
            assertFalse(p.id, p.hueFollows)
            assertTrue(p.id, p.solidSmall)
            assertTrue(p.id, p.backdrop != Palette.Backdrop.PLAIN)
            assertEquals("no real glow", null, p.activeGlow)
        }
        val pursuit = Palette.PURSUIT
        assertEquals(Color(0xFF0D0E0A), pursuit.bg)
        assertEquals(Color(0xFFB9B48E), pursuit.sub)
        assertEquals(Color(0xFFF2C21A), pursuit.activeFill)
        assertEquals(Color.Black, pursuit.activeText)
        assertEquals(0f, pursuit.corner.value, 0f)
        assertEquals(Palette.PanelMark.CORNERS, pursuit.panelMark)
        val canyon = Palette.CANYON
        assertEquals(Color(0xFF39E6D2), canyon.activeFill)
        assertEquals(Color(0xFFE8601C), canyon.highlight)
        assertEquals(Palette.TitleCase.UPPER, canyon.titleCase)
        assertTrue("warnings stay more saturated than the highlight, and apart from it", canyon.orange != canyon.highlight)
        val rally = Palette.RALLY
        assertEquals(Palette.TitleCase.LOWER, rally.titleCase)
        assertEquals(Color(0xFF0F2E2E), rally.text)
        assertEquals(rally.titleBand, rally.activeFill)
        assertTrue("white on the band", ratio(Color.White, rally.titleBand!!) >= 4.5f)
        val wanted = Palette.WANTED
        assertEquals(Color(0xFFE01A24), wanted.activeFill)
        assertEquals(Color(0xFFE01A24), wanted.titleColor)
        assertTrue(wanted.navSlant)
        assertEquals(Palette.PanelMark.STRIPE, wanted.panelMark)
    }

    @Test fun theMenuOnItsOwnBarReads() {
        val p = Palette.WANTED
        assertTrue(ratio(p.navText!!, p.navItem!!) >= 4.5f)
        assertEquals("no strip behind the rail: its buttons stand on the background", null, p.navBar)
        assertEquals(Color(0xFFB8B9BC), p.sub)
        assertTrue(ratio(p.sub, p.card) >= 4.5f)
        assertTrue(ratio(p.activeText!!, p.activeFill!!) >= 4.5f)
        assertTrue(strong(p.activeFill!!, p.navItem!!) >= 3f)
    }

    @Test fun warningsStayApartFromTheRedOfWanted() {
        val p = Palette.WANTED
        val accent = p.activeFill!!
        for (c in listOf(p.red, p.orange)) assertTrue("$c", strong(c, accent) >= 1.8f)
        assertTrue("orange is orange", p.orange.green > 0.5f && p.orange.blue < 0.3f)
    }

    @Test fun solidActiveItemsRead() {
        for (p in gameOnes) {
            assertTrue(p.id, ratio(p.activeText!!, p.activeFill!!) >= 4.5f)
            for (c in p.accentFill!!) assertTrue("${p.id} on a filled button", ratio(p.onAccent, c) >= 4.5f)
        }
    }

    @Test fun onlyTheRacingThemesHaveType() {
        assertEquals(setOf("nightrace", "pursuit", "canyon", "rally", "wanted"), Palette.all.filter { it.font != null }.map { it.id }.toSet())
    }

    @Test fun aThemesTypeIsSetAndItsNumbersKeepStill() {
        val base = androidx.compose.ui.text.TextStyle(fontSize = 20.sp)
        for (p in gameOnes + Palette.NIGHT_RACE) {
            assertEquals(p.id, p.font, p.textStyle(base, "Настройки").fontFamily)
            assertNull(p.id, p.textStyle(base, "Настройки").fontFeatureSettings)
            assertTrue(p.id, "tnum" in p.textStyle(base, "88.8").fontFeatureSettings!!)
            // what asked for a face of its own keeps it
            val mono = base.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            assertEquals(p.id, androidx.compose.ui.text.font.FontFamily.Monospace, p.textStyle(mono, "Настройки").fontFamily)
        }
        // a display face stops being used where it is poor
        val small = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        assertNull(Palette.PURSUIT.textStyle(small, "подпись").fontFamily)
    }

    @Test fun theFontsAreSteadyByTheirFiles() {
        // no font tool here: the licences and files at least are all in, and the total stays under what was allowed
        val dir = java.io.File("src/main/res/font")
        val names = listOf("exo2_italic_regular", "exo2_italic_bold", "exo2_regular", "exo2_bold", "pursuit_sans", "roboto_condensed_regular", "roboto_condensed_bold", "jura_regular", "jura_bold")
        var total = 0L
        for (n in names) { val f = java.io.File(dir, "$n.ttf"); assertTrue(n, f.exists()); total += f.length() }
        assertTrue("under 1 MB: $total", total < 1_000_000)
        for (n in listOf("Exo2", "RussoOne", "RobotoCondensed", "Jura")) {
            assertTrue(n, "SIL OPEN FONT LICENSE" in java.io.File("src/main/assets/licenses/OFL-$n.txt").readText())
        }
        val readme = java.io.File("../README.md").readText()
        for (n in listOf("Exo 2", "Russo One", "Roboto Condensed", "Jura")) assertTrue(n, n in readme)
    }

    @Test fun wantedWritesLabelsInCapitalsAndSecondaryTextOnTheSlant() {
        val p = Palette.WANTED
        assertEquals("НАСТРОЙКИ", p.cased("Настройки"))
        assertEquals("ТЕМП. ОЖ", p.cased("Темп. ОЖ"))
        for (keep in listOf("км/ч", "кПа", "88.8", "Топливо 12", "бар", "100%", "л/100 км")) assertSame(keep, keep.let { p.cased(it) })
        val sentence = "Под подсветку приборов вашей машины, или любой свой"
        assertSame(sentence, sentence.let { p.cased(it) })
        val base = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
        assertEquals(androidx.compose.ui.text.font.FontStyle.Italic, p.textStyle(base, "подпись", p.sub).fontStyle)
        assertNull(p.textStyle(base, "подпись", p.text).fontStyle)
        // a settled text is the same when cased twice: FitText cases what it measures, Text again
        assertEquals("НАСТРОЙКИ", p.cased(p.cased("Настройки")))
    }

    @Test fun titlesAreWrittenAsTheThemeWants() {
        assertEquals("ТОПЛИВО", Palette.CANYON.titled("Топливо"))
        assertEquals("топливо", Palette.RALLY.titled("Топливо"))
        assertSame("Топливо", "Топливо".let { Palette.NEON.titled(it) })
    }

    @Test fun noThemeHasACapThatBreaksThePanelCorner() {
        for (p in Palette.all) p.cornerCap?.let { assertTrue(p.id, p.corner <= it) }
    }

    private fun Color.luminanceOf() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

    @Test fun noChevronsNoBracketAnywhere() {
        val ui = java.io.File("src/main/java/kg/forester/sg")
        val hits = ui.walkTopDown().filter { it.extension == "kt" }.filter { "activeMark" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun aDangerButtonReadsInTheRacingThemeAndStaysAsItWasElsewhere() {
        val race = Palette.NIGHT_RACE
        assertTrue(ratio(race.dangerText!!, race.red, race.bg) >= 4.5f)
        assertEquals(1f, race.red.alpha)
        Palette.all.filter { it !== race }.forEach { assertNull(it.id, it.dangerText) }
    }

    @Test fun noDashedLineUnderPanelHeaders() {
        val hits = java.io.File("src/main/java/kg/forester/sg").walkTopDown().filter { it.extension == "kt" }
            .filter { "segmentedLine" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
