package kg.forester.sg

import kg.forester.sg.data.CsvRead
import kg.forester.sg.ui.niceRange
import kg.forester.sg.ui.niceStep
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CsvReadTest {
    @Test
    fun separators() {
        assertEquals(',', CsvRead.separator("Time,rpm,speed_kmh,epoch_ms"))
        assertEquals(';', CsvRead.separator("Время;Обороты (об/мин);Скорость (км/ч)"))
        assertEquals('\t', CsvRead.separator("time\trpm\tboost"))
        // a comma inside quotes is no separator
        assertEquals(';', CsvRead.separator("\"a, b\";c;d"))
    }

    @Test
    fun quotedCells() {
        assertEquals(listOf("a, b", "c \"x\"", "d"), CsvRead.split("\"a, b\",\"c \"\"x\"\"\", d", ','))
        assertEquals(listOf("1", "2,5", ""), CsvRead.split("1;2,5;", ';'))
    }

    @Test
    fun numbers() {
        assertEquals(2.5f, CsvRead.number("2,5", ';'))
        assertEquals(1234.5f, CsvRead.number("1 234,5", ';'))
        assertEquals(12.5f, CsvRead.number("12.5", ','))
        assertTrue(CsvRead.number("abc", ';').isNaN())
        assertTrue(CsvRead.number("", ';').isNaN())
    }

    @Test
    fun unitsInHeader() {
        assertEquals("Boost" to "psi", CsvRead.nameAndUnit("Boost (psi)"))
        assertEquals("Скорость" to "км/ч", CsvRead.nameAndUnit("Скорость [км/ч]"))
        assertEquals("AFR" to "λ", CsvRead.nameAndUnit("AFR · λ"))
        assertEquals("rpm" to "", CsvRead.nameAndUnit("rpm"))
        assertEquals("°C", CsvRead.unitRu("C"))
    }

    @Test
    fun timeColumnByName() {
        assertEquals(2, CsvRead.timeColumn(listOf("RPM", "Boost", "Time (s)")))
        assertEquals(1, CsvRead.timeColumn(listOf("n", "Время")))
        assertEquals(0, CsvRead.timeColumn(listOf("a", "b")))
    }

    @Test
    fun timesOfManyKinds() {
        // seconds with a fraction
        assertArrayEquals(longArrayOf(0, 100, 250), CsvRead.times(listOf("0", "0.1", "0.25"), "Time (s)"))
        // integer seconds: steps of 1 are no ms
        assertArrayEquals(longArrayOf(0, 1000, 2000), CsvRead.times(listOf("0", "1", "2"), "sec"))
        // ms: from the name, or from steps of 20 and more
        assertArrayEquals(longArrayOf(0, 100, 200), CsvRead.times(listOf("0", "100", "200"), "t_ms"))
        assertArrayEquals(longArrayOf(0, 100, 200), CsvRead.times(listOf("0", "100", "200"), "Time"))
        // a decimal comma
        assertArrayEquals(longArrayOf(500, 1500), CsvRead.times(listOf("0,5", "1,5"), "Время"))
        // clock times
        assertArrayEquals(longArrayOf(3_723_500, 3_724_000), CsvRead.times(listOf("01:02:03.500", "01:02:04"), "Time"))
        assertArrayEquals(longArrayOf(62_000), CsvRead.times(listOf("01:02"), "t"))
        // epoch ms stay
        assertArrayEquals(longArrayOf(1_763_200_000_000), CsvRead.times(listOf("1763200000000"), "timestamp"))
    }

    @Test
    fun dates() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(1_763_200_800_123L, CsvRead.dateMs("2025-11-15T10:00:00.123Z"))
        assertEquals(1_763_200_800_000L, CsvRead.dateMs("2025-11-15 10:00:00", utc))
        assertEquals(1_763_200_800_000L - 6 * 3_600_000L, CsvRead.dateMs("2025-11-15T10:00:00+06:00"))
        assertNull(CsvRead.dateMs("vchera"))
    }

    @Test
    fun roundScale() {
        assertEquals(1.0, niceStep(0.8), 1e-9)
        assertEquals(2.5, niceStep(2.3), 1e-9)
        assertEquals(500.0, niceStep(420.0), 1e-9)
        val (lo, hi) = niceRange(13.7f, 96.2f)
        assertTrue(lo <= 13.7f && hi >= 96.2f)
        val step = (hi - lo) / 4
        assertTrue("step $step", step in listOf(20f, 25f, 50f))
        assertEquals(0f, lo % step, 1e-4f)
        // a negative range too
        val (a, b) = niceRange(-7.3f, 2.1f)
        assertTrue(a <= -7.3f && b >= 2.1f)
    }
}
