package kg.forester.sg.data

/**
 * Values worked out from two or three sensors (the SensorBox or own CAN channels, see [SensorBox.role]) and the ECU:
 * the fuel pressure the regulator really holds, what the intercooler takes off, how hard the turbine chokes the
 * exhaust. Pure arithmetic, null whenever an input is missing or the number would mean nothing; the cards
 * ([kg.forester.sg.ui.Metrics]) and the analyzer's rules read them.
 *
 * The pressure sensors on these boxes are gauge ones (0 = atmosphere), as the ECU's boost is.
 */
object Derived {
    /** A pressure in bar, whatever unit its sensor or CAN channel was set up in (bar when the unit says nothing). */
    fun bar(v: Float, unit: String): Float = when (unit.lowercase().trim()) {
        "кпа", "kpa" -> v / 100f
        "psi" -> v * 0.0689476f
        "мпа", "mpa" -> v * 10f
        else -> v
    }

    /**
     * Fuel pressure over the manifold, bar: rail (gauge) − (MAP − atmosphere). The stock manifold-referenced (1:1)
     * regulator keeps it the same at idle and under boost (about 3 bar on these cars; a rising-rate one would raise it
     * under boost); falling under boost = the pump or filter can't keep up.
     */
    fun fuelDiffBar(fuelBar: Float?, gaugeKpa: Float?): Float? {
        if (fuelBar == null || gaugeKpa == null || fuelBar.isNaN()) return null
        return fuelBar - gaugeKpa / 100f
    }

    /** What the intercooler takes off the charge air, °C: before it − after it. */
    fun intercoolerDropC(preC: Float?, postC: Float?): Float? {
        if (preC == null || postC == null || preC.isNaN() || postC.isNaN()) return null
        return preC - postC
    }

    /**
     * Intercooler efficiency, %: the share of the heat the turbo added that it takes off again,
     * (before − after) / (before − inlet). [inletC] is the air going into the turbo: on a stock turbo Subaru the
     * ECU's intake temperature (its sensor sits in the MAF, before the turbo). Null while the turbo hardly heats the
     * air (under [MIN_RISE_C] above the inlet): the ratio of two small differences is noise.
     */
    fun intercoolerEfficiencyPct(preC: Float?, postC: Float?, inletC: Float?): Float? {
        val drop = intercoolerDropC(preC, postC) ?: return null
        if (inletC == null || inletC.isNaN()) return null
        val rise = preC!! - inletC
        if (rise < MIN_RISE_C) return null
        return (drop / rise * 100f).coerceIn(0f, 120f)
    }

    const val MIN_RISE_C = 15f

    /** Exhaust back-pressure over boost, bar: what the engine pushes against beyond what it gets in (both gauge). */
    fun exhaustOverBoostBar(exhaustBar: Float?, gaugeKpa: Float?): Float? {
        if (exhaustBar == null || gaugeKpa == null || exhaustBar.isNaN()) return null
        return exhaustBar - gaugeKpa / 100f
    }

    /**
     * Exhaust pressure to boost (both gauge, as tuners quote it): 1.5–2 is usual for a stock-sized turbo, well over 2
     * means it chokes. Null below [MIN_BOOST_BAR] of boost, where any ratio is meaningless.
     */
    fun exhaustRatio(exhaustBar: Float?, gaugeKpa: Float?): Float? {
        if (exhaustBar == null || gaugeKpa == null || exhaustBar.isNaN()) return null
        val boost = gaugeKpa / 100f
        if (boost < MIN_BOOST_BAR) return null
        return exhaustBar / boost
    }

    const val MIN_BOOST_BAR = 0.3f

    /**
     * Volumetric efficiency, %: the air the engine took per cycle against what its displacement holds at the manifold's
     * pressure and the intake temperature. On a turbo car the IAT is read before the intercooler, so it is relative.
     */
    fun vePct(mafGs: Float?, rpm: Int?, iatC: Int?, mapKpa: Float?, litres: Float?): Float? {
        if (mafGs == null || rpm == null || iatC == null || mapKpa == null || litres == null) return null
        if (mafGs.isNaN() || mapKpa.isNaN() || rpm < MIN_VE_RPM || mapKpa < MIN_VE_MAP_KPA || litres <= 0f) return null
        return mafGs * 60f / rpm * 2f * 287.05f * (iatC + 273.15f) / (mapKpa * litres * 1000f) * 100f
    }

    const val MIN_VE_RPM = 800
    const val MIN_VE_MAP_KPA = 20f
}
