package kg.forester.sg

import kg.forester.sg.service.DgisDashboard
import kg.forester.sg.service.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DgisDashboardTest {
    @Test
    fun theJsonOfItsServiceAsItBuildsIt() {
        // the keys of DashboardInformationService.buildJsonData (2GIS 7.30, API 2)
        val i = DgisDashboard.parse("""{"activeNavigationMode":"car","arrivalTime":"17:42","remainingTime":"18 мин","totalDistance":"12 км",
            "maneuverDistance":"350 м","maneuverIcon":"crossroad_left","maneuverDescription":"Поверните налево на ул. Ленина",
            "barrierIcon":"speed_bump","barrierDescription":"Лежачий полицейский","progress":40,"speedLimit":60.0,
            "exceedingMaxSpeedLimit":true,"badLocation":false,"trafficCameraType":"speed","trafficCameraSubtype":"front",
            "trafficCameraDistancePercent":35.5,"jamInfoDuration":0,"jamInfoLengthMeters":0,"trafficLightColor":"red",
            "trafficLightArrow":"","trafficLightCountdown":12}""")!!
        assertTrue(i.guiding)
        assertEquals("350 м", i.maneuverDistance)
        assertEquals("Лежачий полицейский", i.barrierDescription)
        assertEquals(60.0 * 3.6, i.speedLimit, 1e-9) // it sends m/s
        assertTrue(i.overLimit)
        assertEquals(35.5f, i.cameraPercent, 1e-4f)
        assertEquals(12, i.lightCountdown)
        // just driving with the map open is no guidance
        assertFalse(DgisDashboard.parse("""{"activeNavigationMode":"freeroam","maneuverDistance":"","totalDistance":""}""")!!.guiding)
        assertNull(DgisDashboard.parse("not json"))
    }

    @Test
    fun arrowsByTheIconName() {
        // the names its native part uses (libv4android.so, 2GIS 7.30)
        assertEquals("left", DgisDashboard.kind("crossroad_left"))
        assertEquals("slight_right", DgisDashboard.kind("crossroad_slightly_right_one"))
        assertEquals("slight_left", DgisDashboard.kind("crossroad_keep_left_many"))
        assertEquals("sharp_right", DgisDashboard.kind("crossroad_sharply_right"))
        assertEquals(Turn.UTURN_LEFT, DgisDashboard.kind("crossroad_uturn"))
        assertEquals(Turn.UTURN_LEFT, DgisDashboard.kind("turn_over_one"))
        assertEquals(Turn.ROUND, DgisDashboard.kind("ringroad_exit_simple"))
        assertEquals(Turn.STRAIGHT, DgisDashboard.kind("crossroad_straight"))
        assertEquals(Turn.FINISH, DgisDashboard.kind("finish"))
        assertNull(DgisDashboard.kind("ferry"))
    }
}
