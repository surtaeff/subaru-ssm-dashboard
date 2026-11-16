package kg.forester.sg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gauge design ids live in the settings: one used twice hides the later design behind the first. */
class GaugeStyleIdsTest {
    private val src = listOf("src/main/java/kg/forester/sg/ui/GaugePresets.kt", "src/main/java/kg/forester/sg/ui/Gauges.kt")
        .joinToString("\n") { File(it).readText() }
    private val ids = Regex("""(?:DialStyle\(\s*|artDial\(|lcdDial\()"([a-z0-9_]+)"""").findAll(src).map { it.groupValues[1] }.toList()

    @Test fun everyIdOnce() {
        assertTrue(ids.size > 60)
        assertEquals(emptyList<String>(), ids.groupBy { it }.filter { it.value.size > 1 }.keys.toList())
    }

    @Test fun theArtFacesHaveTheirPictures() {
        val art = Regex("""artDial\("[a-z0-9_]+", "[^"]+", "([a-z_]+)"""").findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(10, art.size)
        for (f in art) assertTrue(f, File("src/main/assets/gauge_art/art_$f.webp").isFile)
    }

    @Test fun theDigitalMetersAreSix() {
        assertEquals(6, ids.count { it.startsWith("jdm_lcd_") })
    }
}
