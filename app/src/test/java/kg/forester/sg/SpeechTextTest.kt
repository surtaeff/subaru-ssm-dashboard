package kg.forester.sg

import kg.forester.sg.service.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    private fun say(s: String) = SpeechText.prepare(s)

    @Test fun degreesAgreeWithTheNumber() {
        assertEquals("один градус", say("1°"))
        assertEquals("два градуса", say("2°"))
        assertEquals("пять градусов", say("5°"))
        assertEquals("двадцать один градус", say("21 °C"))
        assertEquals("одиннадцать градусов", say("11°"))
        assertEquals("девяносто один градус", say("91 градусов")) // the voice answer writes the plural every time
    }

    @Test fun prepositionsPutTheNumberInItsCase() {
        assertEquals("выше ста градусов", say("выше 100°"))
        assertEquals("при ста десяти градусах", say("при 110°"))
        assertEquals("около сорока пяти секунд", say("около 45 секунд"))
        assertEquals("до тринадцати и шести десятых вольта", say("до 13,6 В"))
        assertEquals("на трёх тысячах пятистах оборотах", say("на 3500 оборотах"))
        assertEquals("с восьмидесяти пяти градусов до девяноста двух градусов", say("с 85° до 92°"))
        assertEquals("до тысячи километров", say("до 1000 км"))
        assertEquals("до минус пяти градусов", say("до -5°"))
    }

    @Test fun decimals() {
        assertEquals("ноль и семь десятых бара", say("0,7 бар"))
        assertEquals("двенадцать с половиной вольта", say("12,5 В"))
        assertEquals("двенадцать и три десятых вольта", say("12,31 В")) // volts to a tenth
        assertEquals("одна и двенадцать сотых бара", say("1,12 бар"))
        assertEquals("полтора бара", say("1.5 бар"))
        assertEquals("полторы минуты", say("1,5 мин"))
        assertEquals("до полутора градусов", say("до 1,5°"))
        assertEquals("два бара", say("2,00 бар"))
        assertEquals("ноль и шестьдесят две сотых", say("0,62"))
        assertEquals("на два с половиной процента", say("на 2,5%"))
        assertEquals("на одну и одну десятую процента", say("на 1,1%"))
    }

    @Test fun units() {
        assertEquals("сто километров в час", say("100 км/ч"))
        assertEquals("сто один километр в час", say("101 километров в час"))
        assertEquals("тринадцать и четыре десятых литра на сотню", say("13,4 л/100"))
        assertEquals("девять и восемь десятых литра на сотню", say("9,8 л/100 км"))
        assertEquals("сорок процентов", say("40 %"))
        assertEquals("сорок пять секунд", say("45 с"))
        assertEquals("три тысячи оборотов в минуту", say("3000 об/мин"))
        assertEquals("три минуты", say("3 мин"))
        assertEquals("триста пятьдесят километров", say("350 км"))
        assertEquals("просрочено на пять дней.", say("просрочено на 5 дн."))
        assertEquals("сто пятьдесят тысяч километров", say("150 000 км"))
        assertEquals("сто пятьдесят тысяч километров", say("150 000 км"))
        assertEquals("девяносто пять килопаскалей", say("95 кПа"))
        assertEquals("три с половиной грамма в секунду", say("3,5 г/с"))
        assertEquals("в два раза", say("в 2 раза"))
    }

    @Test fun signsRangesOrdinalsTimes() {
        assertEquals("минус две и восемь десятых градуса", say("-2,8°"))
        assertEquals("плюс двенадцать процентов", say("+12%"))
        assertEquals("от двенадцати с половиной до тринадцати вольт", say("12,5–13 В"))
        assertEquals("от двух тысяч пятисот до трёх тысяч оборотов в минуту", say("2500–3000 об/мин"))
        assertEquals("на третьей передаче", say("на 3-й передаче"))
        assertEquals("держит третью передачу", say("держит 3-ю передачу"))
        assertEquals("четырнадцать ноль пять", say("14:05"))
        assertEquals("девять ровно", say("9:00"))
        assertEquals("Круг одна минута пять и двадцать три сотых секунды", say("Круг 1:05,23"))
    }

    @Test fun abbreviations() {
        assertEquals("э-бэ-у", say("ЭБУ"))
        assertEquals("ошибки а-ка-пэ-пэ", say("ошибки АКПП"))
        assertEquals("прокладки гэ-бэ-цэ", say("прокладки ГБЦ"))
        assertEquals("ремень гэ-эр-эм, ролики, помпа", say("ремень грм, ролики, помпа"))
        assertEquals("ай-эй-эм ноль и семьдесят пять сотых", say("IAM 0,75"))
        assertEquals("эф-би-кей-си и эф-эл-кей-си", say("FBKC и FLKC"))
        assertEquals("а-эф-эр двенадцать и восемь десятых", say("AFR 12,8"))
        assertEquals("а-тэ-эф в а-ка-пэ-пэ", say("atf в акпп"))
        assertEquals("лучше ставить нейтралку или паркинг", say("лучше ставить N или P"))
        assertEquals("охлаждающая жидкость сто шесть градусов", say("ОЖ 106°"))
        assertEquals("пора на тэ-о", say("пора на ТО"))
        assertEquals("то есть", say("то есть"))
        assertEquals("у здорового и-джей двадцать", say("у здорового EJ20"))
        assertEquals("настройке Линк", say("настройке Link"))
        assertEquals("ошибка пи ноль четыре два ноль", say("ошибка P0420"))
        assertEquals("дэ-эм-эр-вэ", say("ДМРВ"))
    }

    @Test fun symbolsAndStress() {
        assertEquals("Перегрев!", say("Перегрев! 🔥"))
        assertEquals("проверьте генератор и а-ка-бэ", say("— проверьте генератор и АКБ"))
        assertEquals("Настройки, Основные, Обновление приложения", say("Настройки → Основные → «Обновление приложения»"))
        assertEquals("около тридцати секунд до выключения", say("около 30 секунд перед выключением"))
        assertEquals("встаньте в безопасном месте", say("остановитесь в безопасном месте"))
        assertEquals("После превышения наддува", say("После перенаддува"))
        assertEquals("перемотай вперёд", say("перемотай вперед"))
    }

    @Test fun moreAlerts() {
        assertEquals("На холостых датчик видит мало воздуха: две и три десятых грамма в секунду на восемьсот оборотов в минуту, для двух литров обычно около двух и девяти десятых.",
            say("На холостых датчик видит мало воздуха: 2,3 г/с на 800 об/мин, для 2,0 л обычно около 2,9."))
        assertEquals("Турбина стала выходить на наддув позже: на третьей передаче ноль и восемь десятых бара теперь на три тысячи двести оборотов в минуту",
            say("Турбина стала выходить на наддув позже: на 3-й передаче 0,8 бар теперь на 3200 об/мин"))
        assertEquals("Связи с э-бэ-у нет уже двенадцать минут, а магнитола работает",
            say("Связи с ЭБУ нет уже 12 мин, а магнитола работает"))
        assertEquals("Откаты зажигания совпадают с переключениями а-ка-пэ-пэ, три раза за поездку, от двух тысяч до трёх тысяч пятисот оборотов в минуту.",
            say("Откаты зажигания совпадают с переключениями АКПП (3 раза за поездку, 2000–3500 об/мин)."))
        assertEquals("Двигатель девяносто один градус", say("Двигатель 91 градусов"))
        assertEquals("В баке сорок два литра", say("В баке 42 литров"))
        assertEquals("Хватит на триста двадцать один километр", say("Хватит на 321 километров"))
        assertEquals("Напряжение четырнадцать и две десятых вольта", say("Напряжение 14,2 вольт"))
        assertEquals("Голосовые предупреждения включены. Турбина горячая, подождите тридцать секунд до выключения",
            say("Голосовые предупреждения включены. Турбина горячая, подождите 30 секунд до выключения"))
    }

    /** The phrases of the voice samples: what the voice is given after the text is prepared. */
    @Test fun alerts() {
        val cases = listOf(
            "Перегрев! ОЖ 106°. Сбросьте нагрузку, следите за стрелкой, при 110° остановитесь" to
                "Перегрев! охлаждающая жидкость сто шесть градусов. Сбросьте нагрузку, следите за стрелкой, при ста десяти градусах встаньте",
            "Турбина горячая: дайте мотору поработать на холостых около 45 секунд перед выключением" to
                "Турбина горячая: дайте мотору поработать на холостых около сорока пяти секунд до выключения",
            "Наддув выше нормы: 1,12 бар при цели 0,95. Это нагружает поршни. Проверьте шланги к актуатору вестгейта и соленоиду" to
                "Наддув выше нормы: одна и двенадцать сотых бара при цели ноль и девяносто пять сотых. Это нагружает поршни. Проверьте шланги к актуатору вестгейта и соленоиду",
            "IAM упал до 0,62: ЭБУ сильно урезал угол зажигания из-за детонации. Посмотрите коды и IAM, заправьтесь хорошим бензином" to
                "ай-эй-эм упал до нуля и шестидесяти двух сотых: э-бэ-у сильно урезал угол зажигания из-за детонации. Посмотрите коды и ай-эй-эм, заправьтесь хорошим бензином",
            "Генератор не заряжает: 12,31 В на работающем моторе. Аккумулятор разряжен: 11,9 В, около 40 %" to
                "Генератор не заряжает: двенадцать и три десятых вольта на работающем моторе. Аккумулятор разряжен: одиннадцать и девять десятых вольта, около сорока процентов",
            "Коробка опасно перегрета: ATF 121°. Остановитесь в безопасном месте, поставьте P и дайте мотору поработать на холостых несколько минут" to
                "Коробка опасно перегрета: а-тэ-эф сто двадцать один градус. встаньте в безопасном месте, поставьте паркинг и дайте мотору поработать на холостых несколько минут",
            "ЭБУ постоянно убирает около 12% топлива, а расход за поездку 13,4 л/100. Под наддувом смесь бедная (AFR 12,8)" to
                "э-бэ-у постоянно убирает около двенадцати процентов топлива, а расход за поездку тринадцать и четыре десятых литра на сотню. Под наддувом смесь бедная, а-эф-эр двенадцать и восемь десятых",
            "На скорости мотор не должен греться выше 100°, а сейчас 103°. Если антифриз уходит без видимых потёков, нужна проверка прокладки ГБЦ" to
                "На скорости мотор не должен греться выше ста градусов, а сейчас сто три градуса. Если антифриз уходит без видимых потёков, нужна проверка прокладки гэ-бэ-цэ",
            "Пора на обслуживание: ремень грм, ролики, помпа, осталось 350 км. Выученная коррекция по детонации -2,8° на 3-й передаче" to
                "Пора на обслуживание: ремень гэ-эр-эм, ролики, помпа, осталось триста пятьдесят километров. Выученная коррекция по детонации минус две и восемь десятых градуса на третьей передаче",
        )
        cases.forEach { (raw, said) -> assertEquals(raw, said, say(raw)) }
    }

    @Test
    fun englishForTheVoiceHasNoSymbols() {
        assertEquals("Overheating! Coolant 105 degrees, stop", SpeechText.prepareEnglish("Overheating! Coolant 105 °C — stop"))
        assertEquals("Speed 120 kilometers per hour", SpeechText.prepareEnglish("Speed 120 km/h"))
        assertEquals("Consumption 9.5 liters per 100 kilometers", SpeechText.prepareEnglish("Consumption 9.5 l/100 km"))
        assertEquals("Duty 85 percent", SpeechText.prepareEnglish("Duty 85 %"))
        assertEquals("Hint here.", SpeechText.prepareEnglish("“Hint” here…"))
    }

    @Test
    fun englishNumbersAndUnitsAgree() {
        assertEquals("Coolant 1 degree", SpeechText.prepareEnglish("Coolant 1 °C"))
        assertEquals("Coolant 1.5 degrees", SpeechText.prepareEnglish("Coolant 1.5°"))
        assertEquals("Voltage 12.5 volts", SpeechText.prepareEnglish("Voltage 12,5 V"))
        assertEquals("1 kilometer left", SpeechText.prepareEnglish("1 km left"))
        assertEquals("Range 31 miles", SpeechText.prepareEnglish("Range 31 mi"))
        assertEquals("Speed 1 mile per hour", SpeechText.prepareEnglish("Speed 1 mph"))
        assertEquals("25 miles per gallon", SpeechText.prepareEnglish("25 mpg"))
        assertEquals("Wait 30 seconds", SpeechText.prepareEnglish("Wait 30 s"))
        assertEquals("2 minutes", SpeechText.prepareEnglish("2 min"))
        assertEquals("Odometer 150000 kilometers", SpeechText.prepareEnglish("Odometer 150 000 km"))
        assertEquals("3000 r p m", SpeechText.prepareEnglish("3000 rpm"))
        assertEquals("Boost 250 kilopascals", SpeechText.prepareEnglish("Boost 250 kPa"))
        assertEquals("Fuel 40 liters", SpeechText.prepareEnglish("Fuel 40 l"))
    }

    @Test
    fun englishSignsRangesAndCodes() {
        assertEquals("Outside minus 5 degrees", SpeechText.prepareEnglish("Outside -5 °C"))
        assertEquals("Timing plus 3 degrees", SpeechText.prepareEnglish("Timing +3°"))
        assertEquals("0 to 100 kilometers per hour in 7.5 seconds", SpeechText.prepareEnglish("0-100 km/h in 7,5 s"))
        assertEquals("From 70 to 90 degrees", SpeechText.prepareEnglish("From 70–90 °C"))
        assertEquals("Code P 0 4 2 0", SpeechText.prepareEnglish("Code P0420"))
        assertEquals("EJ 20 engine", SpeechText.prepareEnglish("EJ20 engine"))
        assertEquals("I A M dropped to 8", SpeechText.prepareEnglish("IAM dropped to 8"))
    }

    @Test
    fun englishLeavesWordsAlone() {
        for (s in listOf("Wi-Fi is off", "Mile marker", "Main street", "The miles are fine", "Turn left at Sam's", "Volume 12")) assertEquals(s, SpeechText.prepareEnglish(s))
    }
}
