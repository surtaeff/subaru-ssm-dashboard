package kg.forester.sg.flash

import kg.forester.sg.data.BoxBoard

/**
 * A .hex the driver brings (their own build of the sketch, or something else entirely), looked at before it goes onto
 * the board: is it a well-formed Intel HEX, does it fit under the bootloader, is it the SensorBox and for which board.
 */
class FirmwareCheck(
    /** The image, when the file is good enough to upload. */
    val image: HexImage?,
    /** Lines for the screen: what passed (true) and what is a warning or a stop (false). */
    val lines: List<Pair<Boolean, String>>,
    /** The SensorBox firmware's version written in the file, if it is the SensorBox. */
    val fw: Int?,
    /** The board the file was built for, by the SensorBox's own words in it («board=nano»), if it says. */
    val builtFor: String?,
    /** The ESP32-S3's image (a .bin for address 0), when the file is good enough to upload. */
    val bin: ByteArray? = null,
) {
    val ok get() = image != null || bin != null

    companion object {
        /** Files bigger than this are not a sketch for these boards (the ESP32-S3's flash is 4 MB). */
        const val MAX_FILE = 2 * 1024 * 1024
        const val MAX_BIN = 4 * 1024 * 1024

        /** A .bin for the ESP32-S3: an image the chip can start from, and is it the SensorBox. */
        fun ofEsp(b: ByteArray): FirmwareCheck {
            EspFlasher.checkImage(b)?.let { return FirmwareCheck(null, listOf(false to it), null, null) }
            val lines = ArrayList<Pair<Boolean, String>>()
            lines += true to "Образ ESP32 для адреса 0: загрузчик, таблица разделов и программа, ${b.size / 1024} КБ"
            val ascii = String(b, Charsets.ISO_8859_1)
            val builtFor = Regex("I SENSORBOX \\d+ board=([a-z0-9]+)").find(ascii)?.groupValues?.get(1)
            val fw = Regex(" fw=(\\d+) rate=").find(ascii)?.groupValues?.get(1)?.toIntOrNull()
            when {
                ascii.contains("I USBSCREEN") -> lines += false to "Это прошивка цветного экрана, а не SensorBox: плата станет экраном. Экран прошивается в «Цветном экране»"
                builtFor == null -> lines += false to "Это не прошивка SensorBox: приложение не сможет читать с платы датчики и управлять выходами. " +
                    "Стандартная прошивка возвращается кнопкой «Прошить» выше"
                builtFor != BoxBoard.ESP32S3.id -> return FirmwareCheck(null, lines + (false to "Это прошивка SensorBox для ${BoxBoard.of(builtFor).short}, " +
                    "на ESP32-S3 она работать не будет"), fw, builtFor)
                else -> lines += true to "Прошивка SensorBox для ESP32-S3" + (fw?.let { ", версия $it" } ?: "")
            }
            if (fw != null && fw < kg.forester.sg.data.BOX_FIRMWARE) lines += false to
                "Версия $fw старее той, что в приложении (${kg.forester.sg.data.BOX_FIRMWARE}): часть настроек (выходы, дисплеи) может не работать"
            lines += true to "После записи приложение сверит записанное по MD5"
            return FirmwareCheck(null, lines, fw, builtFor, b)
        }

        fun of(text: String, board: BoxBoard): FirmwareCheck {
            if (board == BoxBoard.ESP32S3) return FirmwareCheck(null, listOf(false to "Для ESP32-S3 нужен образ .bin, а не .hex"), null, null)
            val spec = FirmwareFlasher.specs.getValue(board)
            val img = try {
                IntelHex.parse(text, spec.maxSketch)
            } catch (e: Exception) {
                kg.forester.sg.ssm.SsmTrace.log("firmware check: $e")
                val why = kg.forester.sg.data.Failure.link(e, "файл не читается")
                val l = if (why.contains("загрузчик")) "Не помещается в ${board.short}: $why. Возможно, файл собран для другой платы"
                else "Это не прошивка в формате Intel HEX или файл испорчен: $why"
                return FirmwareCheck(null, listOf(false to l), null, null)
            }
            val lines = ArrayList<Pair<Boolean, String>>()
            lines += true to "Формат Intel HEX, контрольные суммы всех строк сходятся"
            lines += true to "Размер ${img.end} байт из ${spec.maxSketch} доступных (${img.end * 100 / spec.maxSketch} %), загрузчик платы не затрагивается"
            val ascii = String(img.bytes, 0, img.end, Charsets.ISO_8859_1)
            val tag = Regex("I SENSORBOX \\d+ board=([a-z0-9]+)").find(ascii)
            val fw = Regex(" fw=(\\d+) rate=").find(ascii)?.groupValues?.get(1)?.toIntOrNull()
            val builtFor = tag?.groupValues?.get(1)
            val otherChip = builtFor?.let { BoxBoard.entries.firstOrNull { b -> b.id == it } }?.let { FirmwareFlasher.specs[it]?.signature != spec.signature } == true
            if (otherChip) return FirmwareCheck(null, lines + (false to "Это прошивка SensorBox для ${BoxBoard.of(builtFor).short}: там другой чип, " +
                "на ${board.short} она работать не будет. Выберите правильную плату на шаге «Плата»"), fw, builtFor)
            when {
                tag == null -> lines += false to "Это не прошивка SensorBox: приложение не сможет читать с платы датчики и управлять выходами. " +
                    "Стандартная прошивка возвращается кнопкой «Прошить» выше"
                builtFor != board.id -> lines += false to "Прошивка SensorBox собрана для ${BoxBoard.of(builtFor).short}, а выбрана ${board.short}: " +
                    "входы и пины не совпадут. Выберите правильную плату или соберите скетч под эту"
                else -> lines += true to "Прошивка SensorBox для ${board.short}" + (fw?.let { ", версия $it" } ?: "")
            }
            if (fw != null && fw < kg.forester.sg.data.BOX_FIRMWARE) lines += false to
                "Версия $fw старее той, что в приложении (${kg.forester.sg.data.BOX_FIRMWARE}): часть настроек (выходы, дисплеи) может не работать"
            lines += true to "Перед записью приложение проверит подпись чипа, после записи прочитает всё обратно и сравнит"
            return FirmwareCheck(img, lines, fw, builtFor)
        }
    }
}
