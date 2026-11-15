package kg.forester.sg.data

/**
 * «Доработки машины»: what the owner changed, for the hints that would otherwise blame a part for what the mod does
 * (tuning guides of the EJ turbo, the RomRaider forum):
 * - a catalyst gone before the rear O2 sensor (the up-pipe or the front one) makes that sensor switch like the front
 *   one: P0420 is expected, only a tune with the catalyst monitor off clears it for good; the rear sensor's own small
 *   trim (A/F Correction #3) drifts too. A rear catalyst after the sensor changes none of it;
 * - a catless downpipe on a stock map makes more boost than the map asks for (creep, a boost cut) and a load past the
 *   stock tables (lean, knock): the wastegate duty needs tuning;
 * - an up-pipe delete or another turbo moves the spool point (an up-pipe ~300–500 rpm earlier);
 * - an intake of another diameter at the MAF shifts the A/F learning by +10…+30 % and the MAF-based numbers with it,
 *   until the MAF scaling is redone.
 * Tyres are not here: the speed is corrected by GPS (PerfCalc).
 * Stored as [encode] in the settings; [boostKey] says when the learned boost and spool norms no longer apply.
 */
data class CarMods(
    val catUpPipe: Boolean = false,
    val catFront: Boolean = false,
    val catRear: Boolean = false,
    val downpipe: Boolean = false,
    val intake: Boolean = false,
    val tune: Tune = Tune.STOCK,
    val bigTurbo: Boolean = false,
) {
    enum class Tune(val key: String, val title: String) {
        STOCK("stock", "сток"), STAGE1("stage1", "Stage 1"), STAGE2("stage2", "Stage 2"), CUSTOM("custom", "своя настройка")
    }

    /** A catalyst before the rear O2 sensor is gone: the one the P0420 monitor judges. */
    val monitoredCatRemoved: Boolean get() = catUpPipe || catFront

    val anyCatRemoved: Boolean get() = catUpPipe || catFront || catRear

    /** The exhaust flows more on the map the ECU came with: boost control is the map's, not the car's any more. */
    val stockTuneFreeFlow: Boolean get() = tune == Tune.STOCK && (downpipe || monitoredCatRemoved)

    val none: Boolean get() = this == CarMods()

    /** What the learned peak boost and spool point depend on: the exhaust, the turbo, the tune (not the intake). */
    val boostKey: String get() = listOf(catUpPipe, catFront, catRear, downpipe, bigTurbo).joinToString("") { if (it) "1" else "0" } + tune.key

    fun encode(): String = listOfNotNull(
        "catUp".takeIf { catUpPipe }, "catFront".takeIf { catFront }, "catRear".takeIf { catRear },
        "downpipe".takeIf { downpipe }, "intake".takeIf { intake }, "turbo".takeIf { bigTurbo }, "tune=${tune.key}",
    ).joinToString(",")

    /** A short tag for a run's series: "" for a stock car. */
    fun tag(): String = listOfNotNull(
        tune.title.takeIf { tune != Tune.STOCK },
        "без кат. апайп".takeIf { catUpPipe }, "без перед. кат.".takeIf { catFront }, "без задн. кат.".takeIf { catRear },
        "даунпайп".takeIf { downpipe }, "впуск".takeIf { intake }, "турбина".takeIf { bigTurbo },
    ).joinToString(" · ")

    /** The hint for a trouble code with the mods in mind: [base] (assets/dtc_hints.json) or what replaces it. */
    fun dtcHint(code: String, base: String?): String? = when {
        code in CAT_CODES && monitoredCatRemoved ->
            "Катализатор снят (отмечено в «Доработки машины»): эта ошибка ожидаема, задний зонд теперь переключается так же, как передний. " +
                "Надёжно её убирает только прошивка с отключённым монитором катализатора"
        code in POST_CAT_TRIM_CODES && monitoredCatRemoved ->
            "Катализатор снят (отмечено в «Доработки машины»): коррекция смеси по заднему зонду уходит, это ожидаемо. " +
                "Без прошивки ошибка вернётся" + (base?.let { ". Если катализатор на месте: $it" } ?: "")
        else -> base
    }

    companion object {
        private val CAT_CODES = setOf("P0420", "P0430")
        private val POST_CAT_TRIM_CODES = setOf("P2096", "P2097", "P2098", "P2099")

        fun parse(s: String): CarMods {
            val p = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val tune = p.firstOrNull { it.startsWith("tune=") }?.removePrefix("tune=")
            return CarMods(
                catUpPipe = "catUp" in p, catFront = "catFront" in p, catRear = "catRear" in p, downpipe = "downpipe" in p,
                intake = "intake" in p, bigTurbo = "turbo" in p, tune = Tune.entries.firstOrNull { it.key == tune } ?: Tune.STOCK,
            )
        }
    }
}
