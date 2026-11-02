package kg.forester.sg.service

/**
 * What the car's units say now about trouble codes: current codes of the engine and the gearbox unit, the lamp where the
 * protocol tells it (OBD-II mode 01). Null in [kg.forester.sg.data.Repo.checkEngine] is «not known», which shows nothing.
 * The app's tables have no severity for a code, so there is one colour.
 */
data class CheckEngine(val engine: Int = 0, val gearbox: Int = 0, val mil: Boolean = false) {
    val count: Int get() = engine + gearbox
    val on: Boolean get() = mil || count > 0
}

/** The icon's rule: with no link there is no knowing, the last answer doesn't stay up. */
fun checkEngineShown(state: CheckEngine?, connected: Boolean): Boolean = connected && state?.on == true

/** How many of the [table]'s (status byte address, bit) are set in [read]. 0xFF is an address this unit doesn't have. */
fun currentCodes(table: List<Pair<Int, Int>>, read: Map<Int, Int>): Int = table.distinct().count { (addr, bit) ->
    val b = read[addr]
    b != null && b != 0xFF && (b shr bit) and 1 == 1
}

/**
 * When the service asks the units for their codes: nothing else in the app does but the button on «Ошибки». The first
 * time [FIRST_MS] after connecting (the ECU's own start-up checks are over), then every [EVERY_MS]: one extra request
 * per unit in about 1200 of the regular ones. A read or a clear on the screen asks again at once ([ask]).
 */
class CodeWatch {
    private var nextMs = Long.MAX_VALUE

    fun newConnection(now: Long) {
        nextMs = now + FIRST_MS
        asked = false
    }

    fun due(now: Long): Boolean = asked || now >= nextMs

    fun done(now: Long) {
        asked = false
        nextMs = now + EVERY_MS
    }

    companion object {
        const val FIRST_MS = 8_000L
        const val EVERY_MS = 300_000L

        @Volatile private var asked = false

        /** The codes have just been read or cleared by hand: the icon follows on the next cycle. */
        fun ask() { asked = true }
    }
}
