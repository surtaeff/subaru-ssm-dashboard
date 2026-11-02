package kg.forester.sg.data

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a driver reads when something fails: short, in the app's words. The exception itself goes to the log, never on a
 * screen; a text the app wrote for people (Russian, in the exception) stays as it is.
 */
object Failure {
    enum class Kind { NO_NETWORK, TIMEOUT, NO_SPACE, REFUSED, NO_PERMISSION, OWN, OTHER }

    private val HTTP = Regex("""\bHTTP (\d{3})\b""")

    private fun chain(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause }.take(8)

    fun kind(t: Throwable): Kind {
        val all = chain(t).toList()
        fun has(words: String) = all.any { it.message.orEmpty().contains(words) }
        return when {
            all.any { it is SocketTimeoutException } -> Kind.TIMEOUT
            all.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } -> Kind.NO_NETWORK
            has("ENOSPC") || has("No space left") -> Kind.NO_SPACE
            all.any { it is SocketException } || has("ENETUNREACH") || has("Network is unreachable") -> Kind.NO_NETWORK
            all.any { it is SecurityException } || has("EACCES") -> Kind.NO_PERMISSION
            all.any { HTTP.containsMatchIn(it.message.orEmpty()) } -> Kind.REFUSED
            // a URL that isn't there; a local file that isn't there says so with a path
            all.any { it is FileNotFoundException && it.message.orEmpty().startsWith("http") } -> Kind.REFUSED
            own(t) != null -> Kind.OWN
            else -> Kind.OTHER
        }
    }

    private fun own(t: Throwable): String? = t.message?.takeIf { m -> m.any { it in 'а'..'я' || it in 'А'..'Я' } }

    /** [other]: what to say of whatever isn't one of the known failures; say what didn't work where that is known. */
    fun text(t: Throwable, other: String = "Не получилось"): String = when (kind(t)) {
        Kind.NO_NETWORK -> "Нет интернета"
        Kind.TIMEOUT -> "Сервер не отвечает"
        Kind.NO_SPACE -> "Мало места на устройстве"
        Kind.REFUSED -> "Сервер отказал, попробуйте позже"
        Kind.NO_PERMISSION -> "Нет разрешения"
        Kind.OWN -> own(t) ?: other
        Kind.OTHER -> other
    }

    /** A status line made of these texts, for its colour. */
    fun looksFailed(status: String): Boolean =
        listOf("Нет интернета", "Сервер ", "Мало места", "Нет разрешения", "Не получилось", "Голос не запустился", "ошибка").any { status.startsWith(it) }

    /** A wire to a device: no talk of internet or servers, only what the app itself says about it. */
    fun link(t: Throwable, other: String): String = own(t) ?: other

    /** It can pass by itself: worth trying again without being asked. */
    fun passes(t: Throwable): Boolean = when (kind(t)) {
        Kind.NO_NETWORK, Kind.TIMEOUT -> true
        // the server's own trouble, not a file that is gone
        Kind.REFUSED -> chain(t).any { c -> HTTP.find(c.message.orEmpty())?.groupValues?.get(1)?.startsWith("5") == true }
        else -> false
    }

    /** For a download that goes on by itself: [what] will come when it can. [what] is «голос» or «модель». */
    fun waitingText(t: Throwable, what: String): String = when (kind(t)) {
        Kind.NO_NETWORK -> "Нет интернета, $what скачается, когда он появится"
        Kind.TIMEOUT -> "Сервер не отвечает, $what скачается позже"
        else -> if (passes(t)) "Сервер не отвечает, $what скачается позже" else text(t)
    }
}
