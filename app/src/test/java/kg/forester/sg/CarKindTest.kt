package kg.forester.sg

import kg.forester.sg.data.Brands
import kg.forester.sg.data.CarKind
import kg.forester.sg.data.CarKind.LogoPick
import kg.forester.sg.data.CarKind.SplashPick
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarKindTest {
    private fun decide(override: String = "auto", vinSubaru: Boolean? = null, obd: Boolean = false, ssm: Boolean = false,
                       link: Boolean = false, last: Boolean = false) = CarKind.decide(override, vinSubaru, obd, ssm, link, last)

    @Test fun subaruStaysSubaru() {
        assertFalse(decide(ssm = true))
        assertFalse(decide(ssm = true, vinSubaru = true))
        assertFalse(decide(link = true))
        // a Subaru whose adapter only got plain OBD-II: its VIN says Subaru
        assertFalse(decide(obd = true, vinSubaru = true))
    }

    @Test fun otherMakeOrNoSsm() {
        assertTrue(decide(obd = true, vinSubaru = false))
        assertTrue(decide(obd = true)) // no SSM at all, VIN unknown
        assertTrue(decide(vinSubaru = false, ssm = true)) // the VIN wins (adb test VIN over the demo)
    }

    @Test fun beforeTheCarAnswersTheLastDecisionStands() {
        assertTrue(decide(last = true))
        assertFalse(decide(last = false))
    }

    @Test fun overrideWins() {
        assertFalse(decide("subaru", vinSubaru = false, obd = true, last = true))
        assertTrue(decide("other", vinSubaru = true, ssm = true))
    }

    @Test fun modelYear() {
        assertEquals(2009, CarKind.modelYear("JTDKB20U093123456", 2026))
        assertEquals(2015, CarKind.modelYear("JF1SJ5LC5FG123456", 2026)) // F
        assertEquals(2010, CarKind.modelYear("WVWZZZ1KZAW123456", 2026)) // A: 1980 is too old for OBD-II
        assertEquals(1999, CarKind.modelYear("1HGCG5655XA123456", 2026)) // X with a digit in place 7
        assertEquals(2024, CarKind.modelYear("5YJ3E1EA1RF123456", 2026)) // R
        assertNull(CarKind.modelYear("JTDKB20U0Z3123456", 2026)) // Z is never a year
        assertNull(CarKind.modelYear("SHORT", 2026))
    }

    @Test fun headerFromVin() {
        assertEquals("Toyota" to "2009 · OBD-II", CarKind.header("Toyota", "JTDKB20U093123456", true, 2026))
        assertEquals("Toyota" to "2009 · подключите адаптер", CarKind.header("Toyota", "JTDKB20U093123456", false, 2026))
        assertEquals("Автомобиль (OBD-II)" to "стандартный OBD-II", CarKind.header(null, "", true, 2026))
        assertEquals("Моя ласточка" to "2009 · OBD-II", CarKind.header("Toyota", "JTDKB20U093123456", true, 2026, "Моя ласточка", true))
    }

    @Test fun headerWithTheRealBase() {
        val b = Brands(File("src/main/assets/brands/wmi.json").readText(), File("src/main/assets/brands/logos.json").readText())
        val make = b.makeFromVin("JTDKB20U093123456")
        assertEquals("toyota", make?.slug)
        assertEquals("2009 · OBD-II", CarKind.header(make?.name, "JTDKB20U093123456", true, 2026).second)
        assertNotNull(b.logoAsset("toyota"))
    }

    private fun logo(mode: String = "auto", other: Boolean = true, custom: String? = null, slug: String? = "toyota",
                     name: String? = "Toyota", asset: String? = null, cached: String? = null, remote: String? = null) =
        CarKind.chooseLogo(mode, other, custom, slug, name, asset, cached, remote)

    @Test fun logoFallbackChain() {
        assertEquals(LogoPick.Subaru, logo(other = false))
        assertEquals(LogoPick.Subaru, logo(slug = "subaru", name = "Subaru"))
        assertEquals(LogoPick.Asset("brands/logos/toyota.webp"), logo(asset = "brands/logos/toyota.webp", cached = "/x.png"))
        assertEquals(LogoPick.File("/files/brands/toyota.png"), logo(cached = "/files/brands/toyota.png", remote = "https://x"))
        assertEquals(LogoPick.Text("Toyota", "https://x"), logo(remote = "https://x"))
        assertEquals(LogoPick.Text("Toyota", null), logo())
        assertEquals(LogoPick.Generic, logo(slug = null, name = null))
    }

    @Test fun logoSettings() {
        assertEquals(LogoPick.None, logo("none"))
        assertEquals(LogoPick.Subaru, logo("subaru", asset = "a"))
        assertEquals(LogoPick.File("/own.png"), logo("custom", other = false, custom = "/own.png"))
        // the own picture is gone: by the car again
        assertEquals(LogoPick.Asset("a"), logo("custom", asset = "a"))
    }

    @Test fun splash() {
        // the neon road is the same for any car, whatever its logo
        assertEquals(SplashPick.Route, CarKind.chooseSplash("route", "/own.png", LogoPick.Asset("a")))
        assertEquals(SplashPick.CarPulse, CarKind.chooseSplash("carpulse", "/own.png", LogoPick.Asset("a")))
        assertEquals(SplashPick.Subaru, CarKind.chooseSplash("subaru", "/own.png", LogoPick.Asset("a")))
        assertEquals(SplashPick.Logo(LogoPick.Asset("a")), CarKind.chooseSplash("car", null, LogoPick.Asset("a")))
        assertEquals(SplashPick.Logo(LogoPick.Subaru), CarKind.chooseSplash("car", null, LogoPick.Subaru))
        assertEquals(SplashPick.Name, CarKind.chooseSplash("car", null, LogoPick.Generic))
        assertEquals(SplashPick.Logo(LogoPick.File("/own.png")), CarKind.chooseSplash("custom", "/own.png", LogoPick.Subaru))
        assertEquals(SplashPick.Name, CarKind.chooseSplash("custom", null, LogoPick.Subaru))
        assertEquals(SplashPick.Name, CarKind.chooseSplash("name", null, LogoPick.Subaru))
    }

    @Test fun obdHomeCardsFromWhatTheCarReports() {
        val cards = CarKind.obdHomeCards(setOf("coolant", "intake", "throttle", "map", "timing", "rpm", "speed", "boost"), turbo = false)
        assertEquals(listOf("coolant", "intake", "throttle", "map", "timing", "rpm", "speed", "fuel", "per100", "range", "gps"), cards)
        assertFalse("knock" in cards)
        val turbo = CarKind.obdHomeCards(setOf("coolant", "battery", "maf", "map", "boost"), turbo = true)
        assertTrue("boost" in turbo)
        assertFalse("map" in turbo) // with a MAF the MAP card is left out
        assertTrue(CarKind.obdHomeCards(setOf("coolant", "intake", "battery", "throttle", "maf", "afr", "afcorr", "aflearn",
            "timing", "pedal", "atm", "rpm", "speed"), false).size <= 15)
    }
}
