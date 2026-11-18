package kg.forester.sg.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * YouTube in the app's own player, the way FreeLite does it: NewPipe Extractor (no key, no WebView) gives the streams,
 * H.264 video-only up to 1080p + AAC audio as small DASH manifests from their index (ExoPlayer fetches ~5 s by byte
 * range), else the muxed 360p MP4. YouTube's embed stayed black on head units with an old WebView (2026-11-18).
 */
object YouTubeStreams {
    /** Firefox ESR on Windows, like NewPipe's own client. */
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private const val CONNECT_MS = 10_000
    private const val READ_MS = 20_000
    private const val MAX_BYTES = 16 shl 20
    private const val CONSENT = "SOCS=CAE="

    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            NewPipe.init(Http, Localization("ru", "RU"), ContentCountry("KG"))
            YoutubeProgressiveDashManifestCreator.getCache().setMaximumSize(8)
            ready = true
        }
    }

    /** Videos for [query] from YouTube's own search through NewPipe (no key); live streams left out. Blocking. */
    fun search(query: String): List<AltVideo> {
        init()
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery(query, listOf("videos"), "")
        val info = org.schabi.newpipe.extractor.search.SearchInfo.getInfo(yt, handler)
        return info.relatedItems.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            .filter { it.streamType == org.schabi.newpipe.extractor.stream.StreamType.VIDEO_STREAM && it.duration > 0 }
            .mapNotNull { item ->
                val id = runCatching { yt.streamLHFactory.getId(item.url) }.getOrNull() ?: return@mapNotNull null
                val thumb = item.thumbnails.maxByOrNull { it.width }?.url ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                AltVideo(AltVideo.Service.YOUTUBE, id, item.name ?: return@mapNotNull null, item.uploaderName, thumb, item.duration.toInt())
            }
    }

    /** What plays: video-only + audio, or the muxed stream; and the film's length. */
    class Choice(val video: VideoStream?, val audio: AudioStream?, val muxed: VideoStream?, val durationSec: Long, val title: String)

    /** The streams of [videoId]. Blocking; throws what NewPipe throws (age-restricted, removed, geo-blocked…). */
    fun choose(videoId: String, maxHeight: Int = 1080): Choice {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        fun usable(s: Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.content != null
        val video = info.videoOnlyStreams.filter { usable(it) && it.itagItem != null && isAvc(it) && tier(it) in 1..maxHeight }
            .maxWithOrNull(compareBy<VideoStream> { tier(it) }.thenBy { if (it.fps <= 30) 1 else 0 }.thenByDescending { it.bitrate })
        val audio = info.audioStreams.filter { usable(it) && it.itagItem != null }
            .maxWithOrNull(compareBy<AudioStream> { if (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL) 1 else 0 }
                .thenBy { if (it.format == MediaFormat.M4A) 1 else 0 }.thenBy { it.averageBitrate })
        val muxed = info.videoStreams.filter { usable(it) && it.format == MediaFormat.MPEG_4 }.maxByOrNull { tier(it) }
        if ((video == null || audio == null) && muxed == null) throw IllegalStateException("no playable stream")
        return Choice(video.takeIf { audio != null }, audio.takeIf { video != null }, muxed, info.duration, info.name)
    }

    private fun isAvc(v: VideoStream): Boolean {
        val codec = v.codec?.lowercase().orEmpty()
        return codec.startsWith("avc1") || (codec.isEmpty() && v.format == MediaFormat.MPEG_4)
    }

    /** «720p60» → 720 (a 2.39:1 film's 720p is only 536 px high). */
    fun tier(v: VideoStream): Int =
        Regex("""^(\d{3,4})p""").find(v.resolution.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: v.height

    @OptIn(UnstableApi::class)
    fun source(c: Choice): MediaSource {
        val v = c.video; val a = c.audio
        if (v != null && a != null) return MergingMediaSource(true, dash(v, c.durationSec), dash(a, c.durationSec))
        return progressive(c.muxed!!)
    }

    @OptIn(UnstableApi::class)
    private fun dash(s: Stream, durationSec: Long): MediaSource {
        if (s.itagItem != null && durationSec > 0) runCatching {
            val xml = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(s.content, s.itagItem!!, durationSec)
            val m = DashManifestParser().parse(Uri.parse(s.content), ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val item = MediaItem.Builder().setUri(s.content).setMimeType(MimeTypes.APPLICATION_MPD).build()
            return DashMediaSource.Factory(http(true)).createMediaSource(m, item)
        }
        return progressive(s)
    }

    @OptIn(UnstableApi::class)
    private fun progressive(s: Stream): MediaSource =
        ProgressiveMediaSource.Factory(http(false)).setContinueLoadingCheckIntervalBytes(256 shl 10)
            .createMediaSource(MediaItem.fromUri(s.content))

    private val POST_BODY = byteArrayOf(0x78, 0)
    private val RN = AtomicInteger()

    /** googlevideo asked like YouTube's own clients: POST «x\0», a request number, the range as a URL parameter. */
    @OptIn(UnstableApi::class)
    private fun http(rangeParam: Boolean): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(CONNECT_MS).setReadTimeoutMs(READ_MS)
            .setAllowCrossProtocolRedirects(true).setKeepPostFor302Redirects(true)
        return ResolvingDataSource.Factory(http) { spec -> resolve(spec, rangeParam) }
    }

    @OptIn(UnstableApi::class)
    private fun resolve(spec: DataSpec, rangeParam: Boolean): DataSpec {
        val headers = HashMap(spec.httpRequestHeaders)
        if (spec.uri.path?.startsWith("/videoplayback") != true) {
            headers["User-Agent"] = UA
            return spec.buildUpon().setHttpRequestHeaders(headers).build()
        }
        val sb = StringBuilder(spec.uri.toString())
        if (!sb.contains("&rn=")) sb.append("&rn=").append(RN.incrementAndGet())
        val b = spec.buildUpon()
        if (rangeParam && (spec.position != 0L || spec.length != C.LENGTH_UNSET.toLong())) {
            sb.append("&range=").append(spec.position).append('-')
            if (spec.length != C.LENGTH_UNSET.toLong()) sb.append(spec.position + spec.length - 1)
            b.setPosition(0).setLength(C.LENGTH_UNSET.toLong()).setUriPositionOffset(0)
        }
        val full = sb.toString()
        headers["User-Agent"] = if (YoutubeParsingHelper.isVisionOsStreamingUrl(full)) YoutubeParsingHelper.getVisionOsUserAgent(null) else UA
        if (YoutubeParsingHelper.isWebStreamingUrl(full)) {
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com/"
        }
        return b.setUri(full).setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(POST_BODY).setHttpRequestHeaders(headers).build()
    }

    /** NewPipe's HTTP on HttpURLConnection: its method, headers and body, the consent cookie, gzip, 429 as a captcha. */
    private object Http : Downloader() {
        override fun execute(request: Request): Response {
            val url = request.url()
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.instanceFollowRedirects = true
            c.useCaches = false
            c.requestMethod = request.httpMethod()
            var ua = false
            var cookie = false
            request.headers()?.forEach { (k, vs) ->
                if (k == null || vs == null) return@forEach
                if (k.equals("User-Agent", true)) ua = true
                if (k.equals("Cookie", true)) cookie = true
                vs.filterNotNull().forEachIndexed { i, v -> if (i == 0) c.setRequestProperty(k, v) else c.addRequestProperty(k, v) }
            }
            if (!ua) c.setRequestProperty("User-Agent", UA)
            val host = runCatching { URL(url).host.lowercase() }.getOrDefault("")
            if (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be") {
                val have = c.getRequestProperty("Cookie")
                if (!cookie || have == null) c.setRequestProperty("Cookie", CONSENT)
                else if (!have.contains("SOCS=")) c.setRequestProperty("Cookie", "$have; $CONSENT")
            }
            c.setRequestProperty("Accept-Encoding", "gzip")
            val body = request.dataToSend()
            try {
                if (body != null) {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                } else if (request.httpMethod() == "POST") {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(0)
                    c.outputStream.close()
                }
                val code = c.responseCode
                if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", url)
                var input = if (code >= 400) c.errorStream else c.inputStream
                var text = ""
                if (input != null && request.httpMethod() != "HEAD") {
                    if (c.contentEncoding.equals("gzip", true)) input = GZIPInputStream(input, 16 shl 10)
                    val out = ByteArrayOutputStream(64 shl 10)
                    val buf = ByteArray(16 shl 10)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_BYTES) throw java.io.IOException("response too large")
                        out.write(buf, 0, n)
                    }
                    val cs = c.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
                    text = String(out.toByteArray(), runCatching { charset(cs ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
                }
                input?.close()
                val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
                return Response(code, c.responseMessage, headers, text, c.url.toString())
            } catch (e: java.io.IOException) {
                c.disconnect()
                throw e
            }
        }
    }
}
