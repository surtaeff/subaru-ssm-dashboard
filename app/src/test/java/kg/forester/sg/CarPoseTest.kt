package kg.forester.sg

import kg.forester.sg.ui.carFrameLeft
import kg.forester.sg.ui.carPullZoom
import kg.forester.sg.ui.carStartAngle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The car stands in the pose it drives in: nothing about it may change when it moves off. */
class CarPoseTest {
    @Test
    fun aPullDoesNotResizeTheCarWhileMovingOff() {
        for (speed in 0..40) for (accel in listOf(-12f, -3f, 0f, 3f, 8f, 25f)) {
            assertEquals("speed $speed, accel $accel", 1f, carPullZoom(accel, speed), 0f)
        }
    }

    @Test
    fun aPullAtSpeedStillLetsTheCameraLag() {
        assertTrue(carPullZoom(12f, 100) < 0.94f)
        assertTrue(carPullZoom(-12f, 100) > 1.03f)
        assertEquals(1f, carPullZoom(0f, 100), 0f)
    }

    @Test
    fun theCameraLagComesInGradually() {
        var was = 1f
        for (speed in 40..120) {
            val z = carPullZoom(12f, speed)
            assertTrue("speed $speed", z <= was && was - z < 0.003f)
            was = z
        }
    }

    @Test
    fun theCarIsCentredByItsOwnMeta() {
        // a 1000 wide box, a 500 wide frame: the frame's car centre lands on the box's centre, whatever the model says
        for (carX in listOf(0.4928f, 0.5f, 0.5024f, 0.464f, 0.6f)) {
            assertEquals(500f, carFrameLeft(1000f, 500f, carX) + carX * 500f, 0.001f)
        }
    }

    @Test
    fun theCarAlwaysStartsInTheViewItDrivesIn() {
        // never driven, or back on the screen: each model's own rear view
        for (driveFrame in listOf(0, 20, 43, 71)) assertEquals(driveFrame.toFloat(), carStartAngle(driveFrame), 0f)
    }

    @Test
    fun everyModelSaysWhereItsRearViewAndCentreAre() {
        val assets = listOf("app/src/main/assets", "src/main/assets").map(::File).first { it.isDirectory }
        val dirs = assets.listFiles()!!.filter { it.isDirectory && (it.name == "car" || it.name.startsWith("car_")) }
        assertTrue(dirs.size >= 14)
        for (d in dirs) {
            val frames = d.list()!!.count { it.startsWith("car_") }
            val meta = JSONObject(File(d, "meta.json").readText())
            assertTrue(d.name, meta.getInt("driveFrame") in 0 until frames)
            assertTrue(d.name, meta.getDouble("carX") in 0.3..0.7)
        }
    }
}
