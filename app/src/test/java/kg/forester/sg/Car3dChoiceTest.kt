package kg.forester.sg

import kg.forester.sg.ui.CAR3D_UNKNOWN
import kg.forester.sg.ui.CarModels3d
import kg.forester.sg.ui.car3dFromEcu
import kg.forester.sg.ui.car3dPaintId
import kg.forester.sg.ui.car3dStoredId
import kg.forester.sg.ui.car3dShown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Which 3D car the home screen shows, and the stand-in for a car nobody can name. */
class Car3dChoiceTest {
    private fun shown(stored: String = "SG", custom: Boolean = false, other: Boolean = false, demo: Boolean = false) =
        car3dShown(stored, custom, other, demo)

    // ---- what is on the screen ----

    @Test fun aKnownSubaruKeepsItsOwnModel() {
        assertEquals("SH", shown("SH"))
        assertEquals("SG", shown("SG"))
        assertEquals("22B", shown("22B"))
    }

    @Test fun anotherMakeGetsTheStandIn() {
        assertEquals(CAR3D_UNKNOWN, shown("SG", other = true))
        // the last Subaru's model is not this car's
        assertEquals(CAR3D_UNKNOWN, shown("SH", other = true))
    }

    @Test fun aModelPickedByHandStays() {
        assertEquals("SH", shown("SH", custom = true, other = true))
        assertEquals("SK", shown("SK", custom = true, other = false))
        assertEquals(CAR3D_UNKNOWN, shown(CAR3D_UNKNOWN, custom = true))
        assertEquals("GC", shown("GC", custom = true, other = true, demo = true))
    }

    @Test fun theStandInPickedByHandStaysInTheDemoToo() {
        assertEquals(CAR3D_UNKNOWN, shown(CAR3D_UNKNOWN, custom = true, demo = true))
    }

    @Test fun theDemoIsASubaru() {
        assertEquals("SG", shown("SG", demo = true))
        assertEquals("SH", shown("SH", demo = true))
        // the demo after a car nobody could name, and the demo with «другая» set by hand
        assertEquals("SG", shown(CAR3D_UNKNOWN, demo = true))
        assertEquals("SG", shown("SG", other = true, demo = true))
    }

    @Test fun anUndeterminedEcuLeftTheStandInStored() {
        assertEquals(CAR3D_UNKNOWN, shown(CAR3D_UNKNOWN))
    }

    // ---- what a connected ECU leaves stored ----

    @Test fun aRomWithoutACarGetsTheStandIn() {
        assertEquals(CAR3D_UNKNOWN, car3dFromEcu("SG", false, null))
        assertEquals(CAR3D_UNKNOWN, car3dFromEcu("SH", false, null))
    }

    @Test fun aKnownRomGetsItsModel() {
        assertEquals("SG", car3dFromEcu("SG", false, "JDM Forester XT 2003 AT"))
        assertEquals("SHNA", car3dFromEcu("SG", false, "USDM Forester 2.5i 2009 AT"))
        // a known car after a ROM nobody knew: back to its own
        assertEquals("BM", car3dFromEcu(CAR3D_UNKNOWN, false, "USDM Legacy GT 2005 MT"))
    }

    @Test fun anInstallOnAKnownCarIsNotChanged() {
        // same answer as the stored one: nothing moves on the first connection after the update
        assertEquals("GD", car3dFromEcu("GD", false, "USDM Impreza STi 2005 MT"))
    }

    @Test fun aNamedCarWithNoModelOfItsOwnKeepsWhatItHad() {
        // the ECU names it, so it is determined: no stand-in, as before
        assertEquals("SH", car3dFromEcu("SH", false, "USDM Tribeca 2006 AT"))
        assertEquals("SG", car3dFromEcu("SG", false, "USDM Baja Turbo 2005 MT"))
        // but the stand-in doesn't stay on a car that has a name
        assertEquals("SG", car3dFromEcu(CAR3D_UNKNOWN, false, "USDM Tribeca 2006 AT"))
    }

    @Test fun theEcuDoesNotTouchAModelPickedByHand() {
        assertEquals("GC", car3dFromEcu("GC", true, null))
        assertEquals("GC", car3dFromEcu("GC", true, "JDM Forester XT 2003 AT"))
        assertEquals(CAR3D_UNKNOWN, car3dFromEcu(CAR3D_UNKNOWN, true, "JDM Forester XT 2003 AT"))
    }

    // ---- the list and the asset ----

    @Test fun theStandInIsInTheList() {
        assertTrue(CarModels3d.any { it.first == CAR3D_UNKNOWN })
        assertEquals("the ids stay unique", CarModels3d.size, CarModels3d.map { it.first }.toSet().size)
    }

    /** Plain frames in the model's own livery, no paint passes; the old stand-in's folder is gone. */
    @Test fun theStandInsFolderHasFramesPlatesAndMeta() {
        val assets = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
        val names = File(assets, "car_gt3r").list()!!.toSet()
        assertEquals(72, names.count { it.startsWith("car_") && it.endsWith(".webp") })
        assertEquals(setOf("plates.json", "meta.json"), names.filterNot { it.startsWith("car_") }.toSet())
        val meta = File(assets, "car_gt3r/meta.json").readText()
        assertTrue(meta, "\"driveFrame\": 43" in meta && "exhaust2X" in meta)
        assertFalse(meta, "NaN" in meta)
        assertFalse(File(assets, "car_ray").exists())
    }

    @Test fun theStandInIsNeverRepainted() {
        for (p in listOf("red", "black", "white", "chameleon", "custom:2563EB")) assertEquals("chameleon", car3dPaintId(CAR3D_UNKNOWN, p))
        assertEquals("red", car3dPaintId("SG", "red"))
        assertEquals("custom:2563EB", car3dPaintId("SK", "custom:2563EB"))
    }

    @Test fun theOldStandInStoredBecomesTheNewOne() {
        assertEquals(CAR3D_UNKNOWN, car3dStoredId("RAY"))
        assertEquals("SG", car3dStoredId("SG"))
        assertEquals(CAR3D_UNKNOWN, car3dStoredId(CAR3D_UNKNOWN))
    }
}
