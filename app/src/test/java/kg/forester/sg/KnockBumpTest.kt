package kg.forester.sg

import kg.forester.sg.data.BumpRule
import kg.forester.sg.data.KnockStats
import kg.forester.sg.data.Repo
import kg.forester.sg.service.KnockWatch
import kg.forester.sg.service.ShockHold
import kg.forester.sg.ssm.EcuSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Rails read as knock: a label on a pull, only when everything points at the road, and with no sensor nothing changes. */
class KnockBumpTest {
    private class Run(var shock: Float? = 0.6f) {
        val file: File = File.createTempFile("knock", ".csv").also { it.delete(); it.deleteOnExit() }
        var flkc: Float? = 0f
        var iam: Float? = 1f
        val w = KnockWatch(file, { _, _ -> shock }, { flkc to iam }, beeping = false)
        var now = 1_000_000_000L
        val stats get() = Repo.knock.value

        fun feed(ms: Long, k: Float, change: (EcuSnapshot) -> EcuSnapshot = { it }) {
            val end = now + ms
            while (now < end) {
                now += 200
                w.process(change(snap(now, k)))
            }
        }
        /** A short shallow pull, then the quiet that decides. */
        fun blip(k: Float = -1f, ms: Long = 400, change: (EcuSnapshot) -> EcuSnapshot = { it }) {
            feed(600, 0f, change)
            feed(ms, k, change)
            feed(10_600, 0f, change)
        }
        fun rows() = file.readLines().drop(1)
    }

    companion object {
        fun snap(t: Long, k: Float) = EcuSnapshot(
            timeMs = t, rpm = 2200, speedKmh = 70, coolantC = 90, throttlePct = 20f, mafGs = 22f, mapKpa = 80f, atmKpa = 95f, knockDeg = k,
        )
    }

    @Test fun aShortShallowPullWithAShockIsMarkedAndLeavesTheHeadline() {
        val r = Run()
        r.blip()
        assertEquals(1, r.stats.events.size)
        assertTrue(r.stats.events[0].bump)
        assertEquals(0, r.stats.count)
        assertEquals(1, r.stats.bumpCount)
        assertNull(r.stats.maxPullDeg)
        val head = r.file.readLines().first()
        assertTrue(head, head.endsWith(",bump,shock_g"))
        assertTrue(r.rows()[0], r.rows()[0].endsWith(",1,0.60"))
    }

    @Test fun noSensorMeansNoMarksAtAll() {
        val r = Run(shock = null)
        r.blip()
        assertFalse(r.stats.events[0].bump)
        assertEquals(1, r.stats.count)
        assertTrue(r.rows()[0], r.rows()[0].endsWith(",0,"))
    }

    @Test fun eachConditionKeepsItAKnock() {
        fun counted(run: Run = Run(), blip: Run.() -> Unit) = run.apply(blip).stats.let { it.count == 1 && it.bumpCount == 0 }
        assertTrue("small shock", counted(Run(shock = 0.34f)) { blip() })
        assertTrue("deep", counted { blip(k = -2f) })
        assertTrue("long", counted { blip(ms = 1200) })
        assertTrue("boost", counted { blip { it.copy(mapKpa = 116f) } })
        assertTrue("load", counted { blip { it.copy(mafGs = 40f) } })
        assertTrue("pedal down", counted { blip { it.copy(throttlePct = 85f) } })
        assertTrue("throttle moving", counted {
            var t = 20f
            blip { t += 3f; it.copy(throttlePct = t.coerceAtMost(60f)) }
        })
        assertTrue("no load known", counted { blip { it.copy(mafGs = null) } })
        // FLKC or IAM moved during the wait, or is not read at all on this ROM
        assertTrue("flkc", counted {
            feed(600, 0f); feed(400, -1f); flkc = -1.4f; feed(10_600, 0f)
        })
        assertTrue("iam", counted {
            feed(600, 0f); feed(400, -1f); iam = 0.9f; feed(10_600, 0f)
        })
        assertTrue("no flkc", counted { flkc = null; blip() })
    }

    @Test fun anotherPullWithinTenSecondsSpoilsBoth() {
        val r = Run()
        r.feed(600, 0f); r.feed(400, -1f); r.feed(5000, 0f); r.feed(400, -1f); r.feed(10_600, 0f)
        assertEquals(2, r.stats.events.size)
        assertEquals(2, r.stats.count)
        assertEquals(2, r.rows().size)
    }

    @Test fun aLoneBumpAfterAQuietStretchIsNotSpoiledByAnOldPull() {
        val r = Run()
        r.blip()
        r.blip()
        assertEquals(2, r.stats.bumpCount)
    }

    @Test fun nothingWrittenTwiceAndTheTripEndKeepsAnUndecidedPullCounted() {
        val r = Run()
        r.feed(600, 0f); r.feed(400, -1f); r.feed(1000, 0f)
        r.w.reset(r.now)
        assertEquals(1, r.rows().size)
        assertTrue(r.rows()[0], r.rows()[0].contains(",0,"))
    }

    @Test fun anOldFileGetsTheNewHeaderOnce() {
        val r = Run()
        r.file.writeText("time,duration_s,max_pull_deg,rpm,load_g_rev,throttle_pct,speed_kmh,coolant_c,timing_deg\nold,row\n")
        r.blip(); r.blip()
        val lines = r.file.readLines()
        assertTrue(lines[0].endsWith(",bump,shock_g"))
        assertEquals("old,row", lines[1])
        assertEquals(4, lines.size)
    }

    @Test fun shockHoldKeepsPeaksByTime() {
        val h = ShockHold()
        assertNull(h.peak(0, 1000))
        h.sample(1000, 0.2f); h.sample(1020, 0.5f); h.sample(1300, 0.1f)
        assertEquals(0.5f, h.peak(900, 1100)!!, 0f)
        assertEquals(0.1f, h.peak(1250, 1350)!!, 0f)
        assertNull(h.peak(2000, 2100))
        // an old peak is overwritten when its slot comes round again
        h.sample(1000 + 100L * ShockHold.SLOTS, 0.05f)
        assertNull(h.peak(1000, 1050))
    }

    @Test fun theLimitsAreTheBriefs() {
        assertEquals(0.35f, BumpRule.SHOCK_G, 0f)
        assertEquals(10_000L, BumpRule.QUIET_MS)
        assertTrue(KnockStats.MERGE_MS < BumpRule.QUIET_MS)
    }
}
