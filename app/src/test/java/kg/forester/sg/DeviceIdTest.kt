package kg.forester.sg

import kg.forester.sg.data.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdTest {
    private class Mem(var v: String? = null) : DeviceId.Slot {
        override fun get() = v
        override fun put(id: String) { v = id }
    }

    private val pkg = "kg.forester.sg"
    private var n = 0
    private fun rnd() = DeviceId.fromBytes(ByteArray(5) { (it + 7 * ++n).toByte() })

    @Test fun deviceIdSameAfterReinstall() {
        val first = DeviceId.resolve(Mem(), Mem(), "a1b2c3d4e5f60718", pkg, ::rnd)
        // reinstall: the app's storage is empty, the unit is the same
        val again = DeviceId.resolve(Mem(), Mem(), "a1b2c3d4e5f60718", pkg, ::rnd)
        assertEquals(first, again)
        // update: the stored value is used even if the system value were to change
        assertEquals(first, DeviceId.resolve(Mem(first), Mem(), "ffffffffffff0001", pkg, ::rnd))
    }

    @Test fun deviceIdDiffersBetweenDevices() {
        val a = DeviceId.resolve(Mem(), Mem(), "a1b2c3d4e5f60718", pkg, ::rnd)
        val b = DeviceId.resolve(Mem(), Mem(), "a1b2c3d4e5f60719", pkg, ::rnd)
        assertNotEquals(a, b)
    }

    @Test fun deviceIdAlphabet() {
        val ids = (0 until 300).map { DeviceId.derive("%016x".format(it * 7919L + 123456789L), pkg)!! } +
            (0 until 300).map { DeviceId.fromBytes(ByteArray(5) { i -> (it * 31 + i * 17).toByte() }) }
        for (id in ids) {
            assertEquals(8, id.length)
            assertTrue(id, id.all { it in DeviceId.ALPHABET })
            assertTrue(id, id.none { it in "0O1I" })
            assertTrue(DeviceId.isValid(id))
        }
        assertFalse(DeviceId.isValid("ABCD0EFG"))
        assertFalse(DeviceId.isValid("ABC"))
        assertFalse(DeviceId.isValid(null))
        // the raw ANDROID_ID is not in the id
        assertFalse(DeviceId.derive("a1b2c3d4e5f60718", pkg)!!.lowercase() in "a1b2c3d4e5f60718")
    }

    @Test fun badAndroidIdFallsBack() {
        assertNull(DeviceId.derive("", pkg))
        assertNull(DeviceId.derive(null, pkg))
        assertNull(DeviceId.derive("9774d56d682e549c", pkg))
        assertNull(DeviceId.derive("9774D56D682E549C", pkg))
        val shared = Mem()
        val prefs = Mem()
        val id = DeviceId.resolve(prefs, shared, "9774d56d682e549c", pkg, ::rnd)
        assertTrue(DeviceId.isValid(id))
        // the random one is kept in the shared folder too ...
        assertEquals(id, shared.v)
        assertEquals(id, prefs.v)
        // ... and found there after a reinstall
        assertEquals(id, DeviceId.resolve(Mem(), shared, "", pkg, ::rnd))
    }

    @Test fun derivedIsStoredAndTheSystemValueIsNotKept() {
        val prefs = Mem(); val shared = Mem()
        val id = DeviceId.resolve(prefs, shared, "a1b2c3d4e5f60718", pkg, ::rnd)
        assertEquals(id, prefs.v)
        assertNull(shared.v)
    }
}

/** The hardware serial in the id, and the per-install key. */
class DeviceIdSerialAndKeyTest {
    private val pkg = "kg.forester.sg"
    private val aid = "a1b2c3d4e5f60718"
    private fun sha(s: String) = DeviceId.fromBytes(java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    private class Mem(var v: String? = null) : DeviceId.Slot {
        override fun get() = v
        override fun put(id: String) { v = id }
    }

    @org.junit.Test fun noGoodSerialKeepsTheOldForm() {
        org.junit.Assert.assertEquals(sha("$aid|$pkg"), DeviceId.derive(aid, pkg))
        for (bad in listOf(null, "", "unknown", "0123456789ABCDEF", "00000000", "short")) {
            org.junit.Assert.assertEquals(bad.toString(), sha("$aid|$pkg"), DeviceId.derive(aid, pkg, bad))
        }
    }

    @org.junit.Test fun aGoodSerialIsMixedIn() {
        org.junit.Assert.assertEquals(sha("$aid|fyt8581a00123|$pkg"), DeviceId.derive(aid, pkg, "FYT8581A00123"))
    }

    @org.junit.Test fun cloneWithTheSameAndroidIdDiffersBySerial() {
        org.junit.Assert.assertNotEquals(DeviceId.derive(aid, pkg, "SERIAL000001"), DeviceId.derive(aid, pkg, "SERIAL000002"))
    }

    @org.junit.Test fun serialAloneWhenTheAndroidIdIsBad() {
        org.junit.Assert.assertEquals(sha("serial000001|$pkg"), DeviceId.derive("9774d56d682e549c", pkg, "SERIAL000001"))
        org.junit.Assert.assertEquals(sha("serial000001|$pkg"), DeviceId.derive(null, pkg, "SERIAL000001"))
        org.junit.Assert.assertNull(DeviceId.derive(null, pkg, "unknown"))
    }

    @org.junit.Test fun sameInputsSameIdSoAReinstallKeepsIt() {
        val a = DeviceId.resolve(Mem(), Mem(), aid, pkg, serial = "SERIAL000001")
        val b = DeviceId.resolve(Mem(), Mem(), aid, pkg, serial = "SERIAL000001")
        org.junit.Assert.assertEquals(a, b)
    }

    @org.junit.Test fun theSerialIsReadThroughReflectionAndCaught() {
        org.junit.Assert.assertNull("no such class on a JVM: nothing, no crash", DeviceId.hardwareSerial())
    }

    @org.junit.Test fun theKeyIs128BitsOfHexAndMadeOnce() {
        val slot = Mem()
        val k = DeviceId.key(slot)
        org.junit.Assert.assertTrue(k, Regex("[0-9a-f]{32}").matches(k))
        org.junit.Assert.assertEquals(k, slot.v)
        org.junit.Assert.assertEquals(k, DeviceId.key(slot))
        org.junit.Assert.assertNotEquals(k, DeviceId.key(Mem()))
    }

    @org.junit.Test fun aDamagedKeyIsReplaced() {
        val slot = Mem("not a key")
        val k = DeviceId.key(slot)
        org.junit.Assert.assertTrue(Regex("[0-9a-f]{32}").matches(k))
        org.junit.Assert.assertEquals(k, slot.v)
    }

    @org.junit.Test fun theKeyStaysOutOfEveryBackup() {
        fun xml(n: String) = listOf("src/main/res/xml", "app/src/main/res/xml").map { java.io.File(it, n) }.first { it.isFile }.readText()
        org.junit.Assert.assertTrue(xml("backup_rules.xml").contains("path=\"device_id.xml\""))
        val d = xml("data_extraction_rules.xml")
        org.junit.Assert.assertEquals(2, Regex("path=\"device_id.xml\"").findAll(d).count())
    }
}
