package kg.forester.sg

import kg.forester.sg.service.Covers
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSplitTest {
    @Test
    fun artistInTheTitle() {
        assertEquals("Кино" to "Группа крови", Covers.split("", "Кино - Группа крови"))
        assertEquals("Кино" to "Группа крови", Covers.split("Unknown", "Кино — Группа крови"))
        // a real artist keeps the title as it is, dash and all
        assertEquals("Сплин" to "Выхода нет - live", Covers.split("Сплин", "Выхода нет - live"))
        assertEquals("" to "Intro", Covers.split("<unknown>", "Intro"))
    }
}
