package kg.forester.sg.service.audio

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kg.forester.sg.service.RadioState
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** NWD's source numbers in the setting "mcu_current_source": Android and the radio. */
private const val NWD_ANDROID = 0
private const val NWD_RADIO = 4
private const val NWD_GAP_MS = 3000L

/**
 * The source to ask NWD's kernel for, or null for no request. Only a unit that is on Android (0) or the radio (4)
 * is switched, and not twice within three seconds: a second request while the first is under way restarts the radio.
 */
internal fun nwdSourceRequest(current: Int?, wantRadio: Boolean, lastMs: Long, now: Long): Int? {
    val want = if (wantRadio) NWD_RADIO else NWD_ANDROID
    if (current != NWD_ANDROID && current != NWD_RADIO) return null
    if (current == want || now - lastMs < NWD_GAP_MS) return null
    return want
}

/**
 * EXPERIMENTAL, never run on a unit. NWD's radio is a service of the stock firmware ("RadioFeature"); what is known
 * for sure is the way to make the radio the source. No frequency or seek call is known, so [caps] stays empty (the
 * screen shows internet radio) and [tune] stays honest: false.
 */
class NwdRadio(private val ctx: Context) : RadioBackend {
    override val capsFlow = MutableStateFlow(emptySet<RadioCap>()).asStateFlow()
    override val state: StateFlow<RadioState> = MutableStateFlow(RadioState()).asStateFlow()

    private var lastMs = -10_000L

    private fun currentSource(): Int? = runCatching {
        android.provider.Settings.System.getString(ctx.contentResolver, "mcu_current_source")?.trim()?.toIntOrNull()
    }.getOrNull()

    private fun request(radio: Boolean) {
        runCatching {
            val now = SystemClock.elapsedRealtime()
            val src = nwdSourceRequest(currentSource(), radio, lastMs, now) ?: return
            lastMs = now
            SsmTrace.log("nwd: source -> $src", keep = true)
            // behind the same gate: the service started again within the gap restarts the radio
            if (radio) runCatching { ctx.startService(Intent("com.nwd.radio.service.ACTION_RADIO_SERVICE").setPackage("com.nwd.radio.service")) }
            ctx.sendBroadcast(Intent("ACTION_REQUEST_CHANGE_SOURCE").putExtra("extra_source_id", src.toByte()))
        }.onFailure { SsmTrace.log("nwd: source change failed: $it", keep = true) }
    }

    override suspend fun on() = request(true)

    override suspend fun off() = request(false)
    override suspend fun tune(f: Int) = false
    override fun seek(up: Boolean) {}
    override fun band(b: Int) {}
}
