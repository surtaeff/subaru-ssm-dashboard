package kg.forester.sg.data

/** After this long out of CONNECTED the last values are no longer the car's. */
const val LINK_LOSS_MS = 2000L

/**
 * How long the last values stay while the service brings a mute ECU back on a live K-line (the line held low, then
 * the init): about 3.5 s on Серёга's Legacy BP, so the screens keep their numbers instead of dashes every few minutes.
 */
const val LINK_RECOVERY_MS = 6000L

/**
 * When the screens' values are to be wiped after the link goes: [due] is true once, [LINK_LOSS_MS] after the link left
 * CONNECTED (or at the end of a recovery's hold, `holdUntilMs`, when that is later). A failing poll writes no null (it
 * only counts failures), so the last reading would otherwise stay on show; this is what clears it. CONNECTING, ERROR
 * and the rest don't restart the count.
 */
class LinkLoss(private val graceMs: Long = LINK_LOSS_MS) {
    private var lostAt: Long? = null
    private var said = false

    /**
     * True while the link has been out of CONNECTED for [graceMs] or more, whatever states it passed through (a cable
     * that fails to open cycles CONNECTING and ERROR): the screens' «нет связи». State, not an event, unlike [due].
     */
    fun lost(link: LinkState, nowMs: Long, holdUntilMs: Long = 0L): Boolean {
        if (link == LinkState.CONNECTED) { lostAt = null; said = false; return false }
        val since = lostAt ?: nowMs.also { lostAt = it }
        return nowMs - since >= graceMs && nowMs >= holdUntilMs
    }

    fun due(link: LinkState, nowMs: Long, holdUntilMs: Long = 0L): Boolean {
        if (link == LinkState.CONNECTED) { lostAt = null; said = false; return false }
        val since = lostAt ?: nowMs.also { lostAt = it }
        if (said || nowMs - since < graceMs || nowMs < holdUntilMs) return false
        said = true
        return true
    }
}

/** What a screen says about a link that is gone; null while it is up. */
fun noLinkText(lost: Boolean): String? = if (lost) "нет связи" else null
