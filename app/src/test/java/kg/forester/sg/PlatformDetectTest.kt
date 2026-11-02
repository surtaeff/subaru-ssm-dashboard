package kg.forester.sg

import kg.forester.sg.service.audio.Detected
import kg.forester.sg.service.audio.FYT
import kg.forester.sg.service.audio.GENERIC
import kg.forester.sg.service.audio.HCN
import kg.forester.sg.service.audio.MICRONTEK
import kg.forester.sg.service.audio.NWD
import kg.forester.sg.service.audio.PlatformSignals
import kg.forester.sg.service.audio.SPD
import kg.forester.sg.service.audio.TOPWAY
import kg.forester.sg.service.audio.TS
import kg.forester.sg.service.audio.detectFamily
import kg.forester.sg.service.audio.detectOrder
import kg.forester.sg.service.audio.forLog
import kg.forester.sg.service.audio.needsLaunchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformDetectTest {
    private fun id(s: PlatformSignals) = detectFamily(s).id

    @Test fun orderIsFixedWithGenericLast() {
        assertEquals(listOf(FYT, MICRONTEK, NWD, TS, TOPWAY, SPD, HCN, GENERIC), detectOrder)
    }

    @Test fun nothingKnownIsGeneric() {
        assertEquals(GENERIC, id(PlatformSignals()))
        assertEquals(GENERIC, id(PlatformSignals(packages = setOf("com.android.bluetooth", "ru.yandex.music"))))
    }

    @Test fun fytByAnyOfItsSigns() {
        assertEquals(FYT, id(PlatformSignals(packages = setOf("com.syu.ms"))))
        assertEquals(FYT, id(PlatformSignals(props = mapOf("ro.fyt.platform" to "ums512"))))
        assertEquals(FYT, id(PlatformSignals(props = mapOf("sys.fyt.radio_type" to "1"))))
    }

    @Test fun emptyPropIsNotASign() {
        assertEquals(GENERIC, id(PlatformSignals(props = mapOf("ro.fyt.platform" to "", "sys.fyt.radio_type" to " "))))
    }

    @Test fun microntekByClassChannelOrItsPackages() {
        assertEquals(MICRONTEK, id(PlatformSignals(classes = setOf("android.microntek.CarManager"))))
        assertEquals(MICRONTEK, id(PlatformSignals(avChannel = "av_channel=sys")))
        assertEquals(MICRONTEK, id(PlatformSignals(avChannel = "gsm_bt")))
        // what told a Microntek unit before this layer: such a unit stays one
        listOf("android.microntek.service", "com.microntek.bluetooth", "android.microntek.mtcser").forEach {
            assertEquals(it, MICRONTEK, id(PlatformSignals(packages = setOf(it))))
        }
        assertEquals(MICRONTEK, id(PlatformSignals(launchers = setOf("com.microntek.btmusic"))))
    }

    @Test fun audioParameterEchoIsNotAChannel() {
        listOf("", " ", "av_channel=", "av_channel", "foo=bar", "av_channel=foo=bar", "a;b", "two words", "SYS").forEach {
            assertEquals("[$it]", GENERIC, id(PlatformSignals(avChannel = it)))
        }
    }

    @Test fun nwdNeedsBothPackages() {
        assertEquals(NWD, id(PlatformSignals(packages = setOf("com.nwd.kernel", "com.nwd.radio.service"))))
        assertEquals(GENERIC, id(PlatformSignals(packages = setOf("com.nwd.kernel"))))
        assertEquals(GENERIC, id(PlatformSignals(packages = setOf("com.nwd.radio.service"))))
    }

    @Test fun nwdBySourceSettingAlone() {
        assertEquals(NWD, id(PlatformSignals(mcuSource = "0")))
        assertEquals(GENERIC, id(PlatformSignals(mcuSource = " ")))
    }

    @Test fun tsNeedsPackageAndBinder() {
        assertEquals(TS, id(PlatformSignals(packages = setOf("com.ts.MainUI"), tsBinder = true)))
        assertEquals(GENERIC, id(PlatformSignals(packages = setOf("com.ts.MainUI"))))
        assertEquals(GENERIC, id(PlatformSignals(tsBinder = true)))
    }

    @Test fun topwayOnlyWhenItOpensWithZero() {
        assertEquals(TOPWAY, id(PlatformSignals(twOpen = 0)))
        assertEquals(GENERIC, id(PlatformSignals(twOpen = -1)))
        assertEquals(GENERIC, id(PlatformSignals(twOpen = null)))
    }

    @Test fun spdAndHcnByPackage() {
        assertEquals(SPD, id(PlatformSignals(packages = setOf("com.spd.radio"))))
        assertEquals(HCN, id(PlatformSignals(packages = setOf("com.hcn.autoradio"))))
    }

    @Test fun twoFamiliesGiveGenericAndNameBoth() {
        assertEquals(Detected(GENERIC, listOf(FYT, MICRONTEK)),
            detectFamily(PlatformSignals(packages = setOf("com.syu.ms"), classes = setOf("android.microntek.CarManager"))))
        assertEquals(Detected(GENERIC, listOf(SPD, HCN)),
            detectFamily(PlatformSignals(packages = setOf("com.hcn.autoradio", "com.spd.radio"))))
    }

    @Test fun oneFamilyIsNamedAlone() {
        assertEquals(Detected(FYT, listOf(FYT)), detectFamily(PlatformSignals(packages = setOf("com.syu.ms"))))
    }

    // anyone can install an app with such a name, and the setting isn't known to be NWD's alone: a real sign wins
    @Test fun looseSignsNeverOutvoteARealOne() {
        val fyt = PlatformSignals(packages = setOf("com.syu.ms"))
        assertEquals(Detected(FYT, listOf(FYT)), detectFamily(fyt.copy(launchers = setOf("org.fan.microntek.widget"))))
        assertEquals(Detected(FYT, listOf(FYT)), detectFamily(fyt.copy(mcuSource = "3")))
        // with no real sign the two loose ones still clash
        assertEquals(Detected(GENERIC, listOf(MICRONTEK, NWD)),
            detectFamily(PlatformSignals(launchers = setOf("com.microntek.btmusic"), mcuSource = "3")))
    }

    // these are faked on the units: a firmware calling itself FYT or Microntek proves nothing
    @Test fun brandAndModelCountForNothing() {
        val faked = mapOf("ro.product.brand" to "FYT", "ro.product.model" to "microntek", "ro.product.manufacturer" to "com.syu.ms")
        assertEquals(GENERIC, id(PlatformSignals(props = faked)))
    }

    // a sign only the late probes saw can't take down a unit whose FYT service is really installed
    @Test fun lateSignsLeaveAnInstalledFytServiceAlone() {
        val fyt = setOf("com.syu.ms")
        assertEquals(Detected(FYT, listOf(FYT, MICRONTEK)), detectFamily(PlatformSignals(packages = fyt, avChannel = "sys")))
        assertEquals(Detected(FYT, listOf(FYT, TOPWAY)), detectFamily(PlatformSignals(packages = fyt, twOpen = 0)))
        assertEquals(Detected(FYT, listOf(FYT, TS)), detectFamily(PlatformSignals(packages = fyt + "com.ts.MainUI", tsBinder = true)))
    }

    @Test fun quickSignsStillClashWithAnInstalledFytService() {
        assertEquals(Detected(GENERIC, listOf(FYT, SPD)), detectFamily(PlatformSignals(packages = setOf("com.syu.ms", "com.spd.radio"))))
        assertEquals(Detected(GENERIC, listOf(FYT, MICRONTEK)),
            detectFamily(PlatformSignals(packages = setOf("com.syu.ms", "com.microntek.bluetooth"), avChannel = "sys")))
    }

    // the softer rule is for the installed service only: FYT's properties alone, or another family, clash as before
    @Test fun lateSignsClashWithoutTheFytService() {
        assertEquals(Detected(GENERIC, listOf(FYT, MICRONTEK)),
            detectFamily(PlatformSignals(props = mapOf("ro.fyt.platform" to "ums512"), avChannel = "sys")))
        assertEquals(Detected(GENERIC, listOf(MICRONTEK, SPD)),
            detectFamily(PlatformSignals(packages = setOf("com.spd.radio"), avChannel = "sys")))
    }

    @Test fun launchersAreAskedOnlyWithoutAFirmSign() {
        assertTrue(needsLaunchers(PlatformSignals()))
        assertTrue(needsLaunchers(PlatformSignals(packages = setOf("com.nwd.kernel")))) // half a sign
        assertFalse(needsLaunchers(PlatformSignals(packages = setOf("com.syu.ms"))))
        assertFalse(needsLaunchers(PlatformSignals(classes = setOf("android.microntek.CarManager"))))
    }

    // drivers send these logs: what they installed stays out of them
    @Test fun logCarriesSignsAndFamilyPackagesOnly() {
        val line = PlatformSignals(
            packages = setOf("com.syu.ms"),
            launchers = setOf("com.microntek.btmusic", "com.syu.bt", "com.bank.private", "org.dating.app"),
            props = mapOf("ro.fyt.platform" to "ums512", "sys.fyt.radio_type" to ""),
            avChannel = "foo=bar",
            twOpen = 0,
        ).forLog()
        assertEquals("packages=com.syu.ms launchers=com.microntek.btmusic,com.syu.bt ro.fyt.platform=ums512 tw_open=0", line)
    }
}
