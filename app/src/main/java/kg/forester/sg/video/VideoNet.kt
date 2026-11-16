package kg.forester.sg.video

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Network for «Видео» (port of FreeLite's VideoNet.java): HttpURLConnection with a desktop User-Agent (the sites give
 * phones another page), gzip, connect 10 s / read 20 s. Blocking: call from Dispatchers.IO.
 */
object VideoNet {
    const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
    const val CONNECT_MS = 10_000
    const val READ_MS = 20_000

    /** A non-2xx answer: the server is there (not a network failure). The message never holds the query. */
    class HttpError(val code: Int, path: String) : IOException("HTTP $code for $path")

    fun get(url: String, readMs: Int = READ_MS, vararg headers: String): String =
        String(bytes(url, CONNECT_MS, readMs, 6 shl 20, *headers), Charsets.UTF_8)

    /** GET → bytes (2xx only). Gzip is asked for and unpacked here. */
    fun bytes(url: String, connectMs: Int, readMs: Int, maxBytes: Int, vararg headers: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Encoding", "gzip")
        var i = 0
        while (i + 1 < headers.size) { c.setRequestProperty(headers[i], headers[i + 1]); i += 2 }
        var input: InputStream? = null
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code, c.url.path)
            input = c.inputStream
            if ("gzip".equals(c.contentEncoding, ignoreCase = true)) input = GZIPInputStream(input, 16 shl 10)
            val len = c.contentLength
            val out = ByteArrayOutputStream(if (len in 1 until maxBytes) len else 32 shl 10)
            val buf = ByteArray(16 shl 10)
            var total = 0
            while (true) {
                val n = input!!.read(buf)
                if (n <= 0) break
                total += n
                if (total > maxBytes) throw IOException("response too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } catch (e: IOException) {
            c.disconnect()
            throw e
        } finally {
            runCatching { input?.close() }
        }
    }

    /** The status of a GET without reading the body; -1 when the server can't be reached. */
    fun status(url: String, connectMs: Int, readMs: Int): Int {
        val c = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return -1
        return try {
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.responseCode
        } catch (e: IOException) {
            -1
        } finally {
            c.disconnect()
        }
    }

    /** One path segment: spaces as %20, '/' encoded. */
    fun seg(s: String): String = runCatching { URLEncoder.encode(s, "UTF-8").replace("+", "%20") }.getOrDefault(s)

    fun q(s: String): String = runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)
}
