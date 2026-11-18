package kg.forester.sg

import kg.forester.sg.ui.logLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class LogLabelTest {
    @Test fun aDriveLogShowsItsDateAndTime() = assertEquals("17.11.2026 16:00", logLabel("2026-11-17_16-00-15.csv"))

    @Test fun otherFilesKeepTheirName() {
        assertEquals("knock.csv", logLabel("knock.csv"))
        assertEquals("battery.csv", logLabel("battery.csv"))
    }
}
