package kg.forester.sg.torrent

import androidx.media3.common.PlaybackException

/**
 * Error branch for [TorrentPlayerDialog]. ExoPlayer bubbles «Source error» for anything that isn't its specific code,
 * which is useless to a driver — this maps the common cases to an actionable Russian line.
 *
 * Pure Kotlin, no Android types in the signature, so a JVM test can drive it.
 */
object TorrentPlaybackError {

    /** The OOM root cause inside a PlaybackException is nested under `cause.cause…` — walk the chain. */
    fun message(e: Throwable?): String {
        if (e == null) return DEFAULT
        if (hasCause<OutOfMemoryError>(e)) return OOM
        if (e is PlaybackException) return byCode(e.errorCode, e)
        // a non-ExoPlayer throwable (coroutine cancel, our own throw from addMagnet) — show the generic line, exception text stays in logs only
        return DEFAULT
    }

    private fun byCode(code: Int, e: PlaybackException): String = when {
        // IO: 2000-2007 — network / HTTP / container gave up. Group them: the driver can't do anything with the code.
        code in 2000..2007 -> {
            val http = httpStatus(e)
            if (http != null && http in 400..599) "Торрент: раздача отвечает $http, попробуйте другую"
            else "Торрент: нет ответа от раздачи, сид мог уйти"
        }
        // Content/malformed: 3001-3004 — the stream is there but the player can't read it. Usually a codec mismatch.
        code in 3001..3004 -> "Торрент: это видео не играется на этом устройстве (кодек)"
        // Decoder: 4001-4005 — hardware refused the stream. Same user action: lower quality.
        code in 4001..4005 -> "Торрент: не хватает декодера для этого качества. Попробуйте качеством пониже."
        // Everything else — generic line. ExoPlayer's own messages are English framework strings, not for drivers.
        else -> DEFAULT
    }

    /** ExoPlayer's `HttpDataSource.InvalidResponseCodeException` is in the cause chain when the server gave 4xx/5xx. */
    private fun httpStatus(e: Throwable): Int? {
        var cur: Throwable? = e
        while (cur != null) {
            val cls = cur::class.java
            // avoid a hard import on media3-datasource here; read responseCode by reflection when the type matches
            if (cls.simpleName == "InvalidResponseCodeException") {
                runCatching {
                    val f = cls.getField("responseCode")
                    return (f.getInt(cur))
                }
            }
            cur = cur.cause
            if (cur === e) break
        }
        return null
    }

    private inline fun <reified T : Throwable> hasCause(e: Throwable): Boolean {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 16) {
            if (cur is T) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    const val OOM = "Торрент: не хватило памяти для этой раздачи. Попробуйте качеством пониже."
    const val DEFAULT = "Торрент: не удалось воспроизвести, попробуйте другую раздачу"
}
