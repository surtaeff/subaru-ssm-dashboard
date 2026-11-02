package kg.forester.sg

import kg.forester.sg.data.Updater
import org.junit.Assert.assertEquals
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
}
