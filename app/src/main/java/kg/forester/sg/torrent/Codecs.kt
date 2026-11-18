package kg.forester.sg.torrent

/** What a release's name says about its video, and whether this unit decodes it. */
object Codecs {
    data class Caps(val hevc: Boolean, val hevc10: Boolean)

    /** Asked of MediaCodecList once; an unknown unit is taken as AVC only. */
    val caps: Caps by lazy {
        runCatching {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            val hevc = list.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/hevc", true) } }
            val main10 = hevc.any { c ->
                c.getCapabilitiesForType("video/hevc").profileLevels.any {
                    it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                }
            }
            Caps(hevc.isNotEmpty(), main10)
        }.getOrDefault(Caps(hevc = false, hevc10 = false))
    }

    private val HEVC = Regex("""hevc|[xh][ .]?265""", RegexOption.IGNORE_CASE)
    private val TEN = Regex("""10[ -]?bit|\bhdr|dolby ?vision|\bdv\b""", RegexOption.IGNORE_CASE)

    /** 0 plays here, 1 might (HEVC on a unit with an HEVC decoder), 2 won't (10 bit / HDR without Main10, HEVC without any). */
    fun penalty(name: String, caps: Caps): Int {
        val hevc = HEVC.containsMatchIn(name)
        val ten = TEN.containsMatchIn(name)
        return when {
            ten && !caps.hevc10 -> 2
            hevc && !caps.hevc -> 2
            hevc || ten -> 1
            else -> 0
        }
    }
}
