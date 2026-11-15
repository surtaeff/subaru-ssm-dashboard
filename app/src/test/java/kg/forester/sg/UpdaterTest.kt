package kg.forester.sg

import kg.forester.sg.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test fun newNameFirst() {
        val both = listOf("subaru-ssm-dashboard.apk", "carpulse-0.9-beta21.apk", "carpulse.apk", "subaru-ssm-dashboard-0.9-beta21.apk")
        assertEquals("carpulse.apk", Updater.pickApk(both, "v0.9-beta21"))
        assertEquals("carpulse-0.9-beta21.apk", Updater.pickApk(both - "carpulse.apk", "v0.9-beta21"))
    }

    @Test fun oldReleasesStillWork() {
        assertEquals("subaru-ssm-dashboard.apk",
            Updater.pickApk(listOf("subaru-ssm-dashboard-0.9-beta20.apk", "subaru-ssm-dashboard.apk"), "v0.9-beta20"))
        assertEquals("subaru-ssm-dashboard-0.9-beta20.apk",
            Updater.pickApk(listOf("manual.pdf", "subaru-ssm-dashboard-0.9-beta20.apk"), "v0.9-beta20"))
    }

    private val split = listOf(
        "carpulse-0.9-beta40.apk", "carpulse-0.9-beta40-arm64-v8a.apk", "carpulse-0.9-beta40-armeabi-v7a.apk",
    )

    @Test fun updaterPicksAbiThenFallsBack() {
        val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
        val arm32 = listOf("armeabi-v7a", "armeabi")
        assertEquals("carpulse-0.9-beta40-arm64-v8a.apk", Updater.pickApk(split, "v0.9-beta40", arm64))
        assertEquals("carpulse-0.9-beta40-armeabi-v7a.apk", Updater.pickApk(split, "v0.9-beta40", arm32))
        // the 64-bit file isn't there: the 32-bit one runs on that unit too
        assertEquals("carpulse-0.9-beta40-armeabi-v7a.apk", Updater.pickApk(split - "carpulse-0.9-beta40-arm64-v8a.apk", "v0.9-beta40", arm64))
        // no file of this ABI: the full APK
        assertEquals("carpulse-0.9-beta40.apk", Updater.pickApk(split - "carpulse-0.9-beta40-armeabi-v7a.apk", "v0.9-beta40", arm32))
        assertEquals("carpulse-0.9-beta40.apk", Updater.pickApk(split, "v0.9-beta40", listOf("x86_64")))
        // a release without splits, as all the old ones
        assertEquals("carpulse-0.9-beta32.apk", Updater.pickApk(listOf("carpulse-0.9-beta32.apk"), "v0.9-beta32", arm64))
        assertEquals("carpulse.apk", Updater.pickApk(listOf("carpulse.apk", "carpulse-0.9-beta40.apk"), "v0.9-beta40", arm64))
    }

    @Test fun neverAnotherAbisFile() {
        val arm32 = listOf("armeabi-v7a", "armeabi")
        assertNull(Updater.pickApk(listOf("carpulse-0.9-beta40-arm64-v8a.apk"), "v0.9-beta40", arm32))
        // not knowing the ABIs: only what runs anywhere
        assertEquals("carpulse-0.9-beta40.apk", Updater.pickApk(split, "v0.9-beta40"))
        assertNull(Updater.pickApk(split.drop(1), "v0.9-beta40"))
    }

    /** What an old install does with a new release: its own list of names, which has to keep finding the full APK. */
    @Test fun oldInstallsStillFindTheFullApk() {
        fun old(names: List<String>, ver: String) = listOf("carpulse.apk", "carpulse-$ver.apk", "subaru-ssm-dashboard.apk", "subaru-ssm-dashboard-$ver.apk")
            .firstNotNullOfOrNull { want -> names.firstOrNull { it.equals(want, ignoreCase = true) } }
        assertEquals("carpulse-0.9-beta40.apk", old(split, "0.9-beta40"))
    }

    @Test fun otherNamesAndNone() {
        // a version suffix that does not match the tag still beats an unrelated APK
        assertEquals("carpulse-0.9-beta21-fix.apk", Updater.pickApk(listOf("other.apk", "carpulse-0.9-beta21-fix.apk"), "v0.9-beta21"))
        assertEquals("other.apk", Updater.pickApk(listOf("notes.txt", "other.apk"), "v1.0"))
        assertNull(Updater.pickApk(listOf("notes.txt"), "v1.0"))
    }

    // ---- the download and the install

    @Test fun fileNames() {
        assertEquals("carpulse-v1.0-beta5.apk", Updater.apkName("v1.0-beta5"))
        assertEquals("carpulse-v1.0-beta5.apk.part", Updater.partName("v1.0-beta5"))
        assertTrue(Updater.partName("v1.0-beta5") != Updater.apkName("v1.0-beta5"))
        assertEquals("carpulse-v1.0_.._x.apk", Updater.apkName("v1.0/../x"))
    }

    @Test fun sizeAndChecksum() {
        val sha = "a".repeat(64)
        assertNull(Updater.checkApk(100, 100, 100, sha, sha))
        assertNull("nothing known but the bytes", Updater.checkApk(100, -1, 0, "", ""))
        assertNull("no checksum in the release", Updater.checkApk(100, 100, 100, sha, ""))
        assertNotNull("cut short", Updater.checkApk(60, 100, 100, sha, ""))
        assertNotNull("server said nothing, the release did", Updater.checkApk(60, -1, 100, sha, ""))
        assertNotNull("empty", Updater.checkApk(0, -1, 0, "", ""))
        assertNotNull("wrong bytes", Updater.checkApk(100, 100, 100, "b".repeat(64), sha))
        assertNull(Updater.checkApk(100, 100, 100, sha.uppercase(), sha))
        assertEquals(sha, Updater.shaOf("sha256:" + sha.uppercase()))
        assertEquals(sha, Updater.shaOf(" $sha "))
        assertEquals("", Updater.shaOf("md5:abc"))
        assertEquals("", Updater.shaOf(null))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Updater.hex(java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())))
    }

    @Test fun freeSpace() {
        val mb = 1024L * 1024
        assertNull(Updater.spaceProblem(400 * mb, 125 * mb))
        assertNotNull(Updater.spaceProblem(200 * mb, 125 * mb))
        assertTrue(Updater.spaceProblem(200 * mb, 125 * mb)!!.contains("313"))
        // an unknown size is taken for the universal APK
        assertNotNull(Updater.spaceProblem(200 * mb, 0))
        assertNull(Updater.spaceProblem(200 * mb, 50 * mb))
    }

    @Test fun installOnlyANewerBuildOfTheSameKey() {
        val me = "kg.forester.sg"
        val key = setOf("k1")
        assertEquals(Updater.Verdict.OK, Updater.verdict(me, 45, key, me, 44, key))
        assertEquals(Updater.Verdict.NOT_NEWER, Updater.verdict(me, 44, key, me, 44, key))
        assertEquals(Updater.Verdict.NOT_NEWER, Updater.verdict(me, 43, key, me, 44, key))
        assertEquals(Updater.Verdict.OTHER_SIGNATURE, Updater.verdict(me, 45, setOf("k2"), me, 44, key))
        // a rotated key keeps the old one in its history
        assertEquals(Updater.Verdict.OK, Updater.verdict(me, 45, setOf("k1", "k2"), me, 44, key))
        // signatures that Android didn't give: the installer decides
        assertEquals(Updater.Verdict.OK, Updater.verdict(me, 45, null, me, 44, key))
        assertEquals(Updater.Verdict.OK, Updater.verdict(me, 45, key, me, 44, emptySet()))
        assertEquals(Updater.Verdict.OTHER_APP, Updater.verdict("com.other", 45, key, me, 44, key))
        assertEquals(Updater.Verdict.UNREADABLE, Updater.verdict(null, 0, null, me, 44, key))
        assertNull(Updater.verdictText(Updater.Verdict.OK))
        Updater.Verdict.values().filter { it != Updater.Verdict.OK }.forEach { assertNotNull(Updater.verdictText(it)) }
    }

    @Test fun installerStatusInWords() {
        assertEquals("Установка отменена", Updater.installText(3, null))
        assertTrue(Updater.installText(6, "INSTALL_FAILED_INSUFFICIENT_STORAGE").contains("мало места"))
        assertTrue(Updater.installText(1, "INSTALL_FAILED_INTERNAL_ERROR").contains("INSTALL_FAILED_INTERNAL_ERROR"))
        assertEquals("Не установилось: ошибка установщика", Updater.installText(1, ""))
        // the numbers are the platform's
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE, 6)
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, 3)
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, 5)
    }

    @Test fun oneDownloadNoBlockingScreenState() {
        val src = java.io.File("src/main/java/kg/forester/sg/ui/LogSettingsScreens.kt").readText()
        val ui = src.substring(src.indexOf("private fun UpdateSettings()")).substringBefore("\n}\n")
        assertTrue(ui.contains("Updater.state.collectAsState()"))
        assertFalse("no download in the screen's own scope", ui.contains("rememberCoroutineScope"))
        val upd = java.io.File("src/main/java/kg/forester/sg/data/Updater.kt").readText()
        assertFalse("the folder is not wiped under an open installer", upd.contains("deleteRecursively(); mkdirs()"))
        assertTrue(upd.contains("PackageInstaller.SessionParams"))
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".data.InstallReceiver"))
    }
}
