package kg.forester.sg

import kg.forester.sg.data.NavMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMenuTest {
    private val factory = listOf("Home", "Mine", "Drive", "Media", "Settings")

    @Test fun nobodyTouchedItFactoryOrder() {
        assertEquals(factory, NavMenu.merge(emptyList(), factory))
        assertEquals(factory, NavMenu.visible(NavMenu(emptyList(), emptySet()), factory))
    }

    @Test fun everyScreenEnumIsInTodaysOrder() {
        assertEquals(
            listOf("Home", "Mine", "Drive", "Media", "Fuel", "Gearbox", "Battery", "Dtc", "Sensors", "Perf", "Health", "Log", "Apps", "Settings"),
            Screen.entries.map { it.name },
        )
    }

    @Test fun aSavedOrderStays() {
        val saved = listOf("Media", "Home", "Settings", "Drive", "Mine")
        assertEquals(saved, NavMenu.merge(saved, factory))
    }

    @Test fun aNewFactoryItemComesAtItsFactoryPlace() {
        val later = listOf("Home", "Mine", "New", "Drive", "Media", "Settings")
        // the driver had Media first: New goes right after Mine, its factory neighbour
        val saved = listOf("Media", "Mine", "Home", "Drive", "Settings")
        assertEquals(listOf("Media", "Mine", "New", "Home", "Drive", "Settings"), NavMenu.merge(saved, later))
    }

    @Test fun aNewFirstItemGoesFirst() {
        val later = listOf("New", "Home", "Settings")
        assertEquals(listOf("New", "Settings", "Home"), NavMenu.merge(listOf("Settings", "Home"), later))
    }

    @Test fun severalNewItemsKeepTheirOrder() {
        val later = listOf("Home", "A", "B", "Settings")
        assertEquals(listOf("Settings", "Home", "A", "B"), NavMenu.merge(listOf("Settings", "Home"), later))
    }

    @Test fun anItemNoLongerThereIsDropped() {
        assertEquals(listOf("Home", "Settings"), NavMenu.merge(listOf("Gone", "Home", "Settings"), listOf("Home", "Settings")))
    }

    @Test fun duplicatesInASavedOrderDontRepeat() {
        assertEquals(listOf("Home", "Settings"), NavMenu.merge(listOf("Home", "Home", "Settings"), listOf("Home", "Settings")))
    }

    @Test fun hideAndBringBack() {
        val m = NavMenu(emptyList(), emptySet()).hidden("Drive", true, factory)
        assertEquals(listOf("Home", "Mine", "Media", "Settings"), NavMenu.visible(m, factory))
        assertEquals(factory, NavMenu.visible(m.hidden("Drive", false, factory), factory))
    }

    @Test fun factoryItemsCanBeHiddenButSettingsNever() {
        val m = NavMenu(emptyList(), emptySet()).hidden("Home", true, factory).hidden("Settings", true, factory)
        assertFalse("Home", "Home" in NavMenu.visible(m, factory))
        assertTrue("the way back", "Settings" in NavMenu.visible(m, factory))
        // also from a stored set, whatever it says
        assertTrue("Settings" in NavMenu.visible(NavMenu(emptyList(), setOf("Settings", "Home", "Mine", "Drive", "Media")), factory))
    }

    @Test fun reorder() {
        val m = NavMenu(emptyList(), emptySet()).moved("Media", -1, factory)
        assertEquals(listOf("Home", "Mine", "Media", "Drive", "Settings"), NavMenu.visible(m, factory))
        // past the ends: nothing
        assertEquals(factory, NavMenu.visible(NavMenu(emptyList(), emptySet()).moved("Home", -1, factory), factory))
        assertEquals(factory, NavMenu.visible(NavMenu(emptyList(), emptySet()).moved("Settings", 1, factory), factory))
    }

    @Test fun settingsCanMove() {
        val m = NavMenu(emptyList(), emptySet()).moved("Settings", -1, factory)
        assertEquals(listOf("Home", "Mine", "Drive", "Settings", "Media"), NavMenu.visible(m, factory))
    }

    @Test fun dragToAPlace() {
        val m = NavMenu(emptyList(), emptySet()).movedTo("Home", 3, factory)
        assertEquals(listOf("Mine", "Drive", "Media", "Home", "Settings"), NavMenu.visible(m, factory))
        assertEquals("beyond the end", "Home", NavMenu.visible(NavMenu(emptyList(), emptySet()).movedTo("Home", 99, factory), factory).last())
    }

    @Test fun reorderSkipsWhatThisCarDoesNotShow() {
        // АКПП isn't offered on a manual: «up» must move past what the driver sees, not swap with what he doesn't
        val all = listOf("Home", "Gearbox", "Drive", "Settings")
        val m = NavMenu(emptyList(), emptySet()).moved("Drive", -1, all, shown = listOf("Home", "Drive", "Settings"))
        assertEquals(listOf("Drive", "Home", "Gearbox", "Settings"), NavMenu.merge(m.order, all))
    }

    @Test fun resetGivesTheFactoryBack() {
        val m = NavMenu(listOf("Media", "Home", "Settings", "Mine", "Drive"), setOf("Home"))
        assertTrue(m != NavMenu.FACTORY)
        assertEquals(NavMenu.FACTORY, NavMenu.reset())
        assertEquals(factory, NavMenu.visible(NavMenu.reset(), factory))
    }

    @Test fun storedForm() {
        val m = NavMenu(listOf("Media", "Home"), setOf("Drive", "Mine"))
        assertEquals(m, NavMenu.parse(m.orderText(), m.hiddenText()))
        assertEquals(NavMenu.FACTORY, NavMenu.parse("", ""))
        assertEquals("junk in the prefs", NavMenu.FACTORY, NavMenu.parse(",, ,", " , "))
    }

    @Test fun aHiddenScreenThatWasShowingGivesWayToTheFirstVisible() {
        val shown = listOf("Mine", "Drive", "Settings")
        assertEquals("Mine", NavMenu.afterHiding("Home", shown))
        assertNull("still shown: stays", NavMenu.afterHiding("Drive", shown))
    }

    @Test fun backGoesHomeOrToTheFirstVisibleWhenHomeIsHidden() {
        assertEquals("Home", NavMenu.backTarget("Home", listOf("Home", "Drive")))
        assertEquals("Drive", NavMenu.backTarget("Home", listOf("Drive", "Settings")))
    }
}
