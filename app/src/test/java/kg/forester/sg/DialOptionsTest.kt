package kg.forester.sg

import kg.forester.sg.ui.DialOptions
import kg.forester.sg.ui.LensMath
import kg.forester.sg.ui.NO_NUMBER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The driver's own numbers and number size travel in a card's style id, beside the «no number» tail. */
class DialOptionsTest {
    @Test fun plainIdsAreUntouched() {
        assertEquals(DialOptions("neon"), DialOptions.parse("neon"))
        assertEquals("neon", DialOptions.parse("neon").format())
        assertEquals("", DialOptions.parse("").format())
        assertEquals("custom:3", DialOptions.parse("custom:3").format())
    }

    @Test fun roundTrip() {
        val o = DialOptions("defi", noNumber = true, marks = listOf(40f, 60f, 90f, 110f), numberPct = 120)
        assertEquals("defi|m40,60,90,110|z120|n", o.format())
        assertEquals(o, DialOptions.parse(o.format()))
        assertEquals(DialOptions("defi", marks = listOf(2.5f)), DialOptions.parse("defi|m2.5"))
        assertEquals(DialOptions("defi", numberPct = 90), DialOptions.parse("defi|z90"))
    }

    /** Code and old saved layouts check the id's end: a hidden number stays the last thing in it. */
    @Test fun noNumberStaysAtTheEnd() {
        assertTrue(DialOptions("a", noNumber = true, marks = listOf(1f), numberPct = 100).format().endsWith(NO_NUMBER))
        assertEquals(DialOptions("a", noNumber = true), DialOptions.parse("a|n"))
        assertFalse(DialOptions("a", marks = listOf(1f)).format().endsWith(NO_NUMBER))
    }

    @Test fun aDesignsOwnBarIsKept() {
        assertEquals("photo:my|file.png", DialOptions.parse("photo:my|file.png").base)
        assertEquals("photo:my|mine", DialOptions.parse("photo:my|mine|n").base)
    }

    @Test fun storedInALayoutItBreaksNoField() {
        val id = DialOptions("defi", marks = listOf(40f, 60f, 90f), numberPct = 120, noNumber = true).format()
        assertFalse(":" in id); assertFalse(";" in id)
    }

    @Test fun whatTheDriverTypes() {
        assertEquals(listOf(40f, 60f, 90f, 110f), DialOptions.parseMarks("40, 60 90;110"))
        assertEquals(listOf(40f, 110f), DialOptions.parseMarks("110, x, 40, 40,"))
        assertEquals(emptyList<Float>(), DialOptions.parseMarks(""))
        assertEquals(16, DialOptions.parseMarks((1..30).joinToString(",")).size)
        assertEquals("40, 60, 2.5", DialOptions.marksText(listOf(40f, 60f, 2.5f)))
    }

    @Test fun marksOnAScale() {
        assertEquals(listOf(40f, 110f), DialOptions.onScale(listOf(-5f, 40f, 110f, 300f), 0f, 240f))
        assertEquals("40", DialOptions.label(40f, 1f))
        assertEquals("2.5", DialOptions.label(2500f, 1000f))
        assertEquals("3", DialOptions.label(3000f, 1000f))
    }

    @Test fun outOfRangeSizeIsClamped() {
        assertEquals(150, DialOptions.parse("a|z900").numberPct)
        assertEquals(70, DialOptions.parse("a|z10").numberPct)
    }

    @Test fun lensDigitsGrowButStayInTheDisk() {
        val at1 = LensMath.digitSize(100f, 2)
        assertTrue(LensMath.digitSize(100f, 2, 1.2f) > at1)
        // four digits already fill the width: no bigger
        assertEquals(LensMath.digitSize(100f, 4), LensMath.digitSize(100f, 4, 1.5f), 0f)
    }

    @Test fun everyStyleReaderGoesThroughTheOptions() {
        val ui = File("src/main/java/kg/forester/sg/ui")
        for (name in listOf("HomeEditor.kt", "UsbScreenUi.kt")) {
            val s = File(ui, name).readText()
            assertFalse(name, "endsWith(NO_NUMBER)" in s || "removeSuffix(NO_NUMBER)" in s)
            assertTrue(name, "DialOptions.parse" in s)
        }
        assertFalse("removeSuffix(NO_NUMBER)" in File(ui, "Gauges.kt").readText())
    }
}
