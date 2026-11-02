package kg.forester.sg.data

/** After this long out of CONNECTED the last values are no longer the car's. */
const val LINK_LOSS_MS = 2000L

/**
 * When the screens' values are to be wiped after the link goes: [due] is true once, [LINK_LOSS_MS] after the link left
 * CONNECTED. A failing poll writes no null (it only counts failures), so the last reading would otherwise stay on
 * show; this is what clears it. CONNECTING, ERROR and the rest don't restart the count.
 */
class LinkLoss(private val graceMs: Long = LINK_LOSS_MS) {
    private var lostAt: Long? = null
    private var said = false

    /**
     * True while the link has been out of CONNECTED for [graceMs] or more, whatever states it passed through (a cable
     * that fails to open cycles CONNECTING and ERROR): the screens' «нет связи». State, not an event, unlike [due].
     */
    fun lost(link: LinkState, nowMs: Long): Boolean {
        if (link == LinkState.CONNECTED) { lostAt = null; said = false; return false }
        val since = lostAt ?: nowMs.also { lostAt = it }
        return nowMs - since >= graceMs
    }

    fun due(link: LinkState, nowMs: Long): Boolean {
        if (link == LinkState.CONNECTED) { lostAt = null; said = false; return false }
        val since = lostAt ?: nowMs.also { lostAt = it }
        if (said || nowMs - since < graceMs) return false
        said = true
        return true
    }
}

/** What a screen says about a link that is gone; null while it is up. */
fun noLinkText(lost: Boolean): String? = if (lost) "нет связи" else null
