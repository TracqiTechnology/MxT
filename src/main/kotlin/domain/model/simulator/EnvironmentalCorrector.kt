package domain.model.simulator

import domain.math.map.Map3d
import domain.model.optimizer.OptimizerCalculator
import kotlin.math.abs

/**
 * Phase 18: Environmental corrections for altitude and temperature.
 *
 * v3 assumes sea-level, standard-temperature conditions. Real engines operate
 * at altitude (lower air density → different WGDC needed) and varying intake
 * temperatures (affects VE model accuracy).
 *
 * ME7 Reference:
 * - Altitude: KFLDIOPU (me7-raw.txt line 143618, 144473)
 * - Temperature: KFFWTBR (me7-raw.txt line 54498, 54872)
 * - ftu factor: me7-raw.txt line 54882
 *
 * @see documentation/me7-boost-control.md §4
 */
object EnvironmentalCorrector {

    /** Standard conditions assumed by the base VE model. */
    private const val STANDARD_BARO_MBAR = 1013.0
    private const val STANDARD_INTAKE_TEMP_C = 20.0
    private const val STANDARD_COOLANT_TEMP_C = 96.0

    /** Environmental conditions summary from log data. */
    data class EnvironmentalSummary(
        val avgBaroPressure: Double,
        val estimatedAltitudeM: Double,
        val avgIntakeTemp: Double?,      // null if not available in logs
        val avgCoolantTemp: Double?,
        val intakeTempRange: Pair<Double, Double>?,
        val altitudeDeviation: Boolean,  // true if significantly non-sea-level
        val tempDeviation: Boolean,      // true if significantly non-standard
        val kftarxWarning: Boolean = false,  // Finding 4: KFTARX may reduce LDRXN
        val warnings: List<String>
    )

    /**
     * Analyze environmental conditions from WOT log data.
     */
    fun analyzeSummary(
        wotEntries: List<OptimizerCalculator.WotLogEntry>
    ): EnvironmentalSummary {
        if (wotEntries.isEmpty()) {
            return EnvironmentalSummary(
                STANDARD_BARO_MBAR, 0.0, null, null, null,
                altitudeDeviation = false, tempDeviation = false, kftarxWarning = false,
                warnings = emptyList()
            )
        }

        val warnings = mutableListOf<String>()

        val avgBaro = wotEntries.map { it.barometricPressure }.average()
        // Barometric formula approximation: altitude ≈ (1 - (P/1013.25)^0.190284) × 44330
        val altitudeM = (1 - Math.pow(avgBaro / 1013.25, 0.190284)) * 44330

        val altitudeDeviation = abs(avgBaro - STANDARD_BARO_MBAR) > 30 // >30 mbar = ~250m altitude
        if (altitudeDeviation) {
            warnings.add("🏔️ Altitude detected: avg barometric pressure ${String.format("%.0f", avgBaro)} mbar " +
                "(≈${String.format("%.0f", altitudeM)}m / ${String.format("%.0f", altitudeM * 3.281)}ft). " +
                "KFLDRL suggestions may need KFLDIOPU altitude correction.")
        }

        // H2/M3: Real intake air temperature analysis
        // FR reference: KFTARX (me7-raw.txt line 142472, 142587-142589)
        // KFTARX = 1.0 until 75°C, then multiplicatively reduces LDRXN
        val hasIat = wotEntries.any { it.intakeAirTemp != STANDARD_INTAKE_TEMP_C }
        val avgIat = if (hasIat) wotEntries.map { it.intakeAirTemp }.average() else null
        val iatRange = if (hasIat) {
            Pair(wotEntries.minOf { it.intakeAirTemp }, wotEntries.maxOf { it.intakeAirTemp })
        } else null

        var kftarxWarning = false
        val tempDeviation: Boolean

        if (hasIat && avgIat != null) {
            tempDeviation = abs(avgIat - STANDARD_INTAKE_TEMP_C) > 15.0

            if (tempDeviation) {
                // Quantify ftbr impact: ftbr ≈ √(273/(evtmod+273)), evtmod ≈ tans for turbo (KFFWTBR≈0)
                val ftbrStandard = kotlin.math.sqrt(273.0 / (STANDARD_INTAKE_TEMP_C + 273.0))
                val ftbrActual = kotlin.math.sqrt(273.0 / (avgIat + 273.0))
                val pssolShiftPct = ((ftbrStandard / ftbrActual) - 1.0) * 100.0
                warnings.add("🌡️ IAT deviation: avg charge air temp ${String.format("%.0f", avgIat)}°C " +
                    "(range ${String.format("%.0f", iatRange!!.first)}–${String.format("%.0f", iatRange.second)}°C). " +
                    "pssol shifts ${String.format("%+.1f", pssolShiftPct)}% vs 20°C reference. " +
                    "KFLDRL/KFPBRK suggestions account for this via ftbr correction.")
            }

            val maxIat = wotEntries.maxOf { it.intakeAirTemp }
            if (maxIat > 75.0) {
                kftarxWarning = true
                warnings.add("⚠️ CRITICAL: Peak IAT ${String.format("%.0f", maxIat)}°C exceeds KFTARX threshold (75°C). " +
                    "ECU is reducing max load (rlmx) via KFTARX (me7-raw.txt line 142587). " +
                    "rlsol may not reach LDRXN even with correct KFMIOP/KFMIRL. " +
                    "Improve intercooling or adjust KFTARX calibration.")
            } else if (maxIat > 60.0) {
                warnings.add("🌡️ Elevated IAT: peak ${String.format("%.0f", maxIat)}°C approaching KFTARX " +
                    "threshold (75°C). Consider intercooler capacity at sustained WOT.")
            }
        } else {
            tempDeviation = false
            // Fall back to barometric inference (original behavior)
            if (altitudeDeviation && avgBaro < 950) {
                warnings.add("🌡️ High altitude (${String.format("%.0f", altitudeM)}m) with low baro pressure — " +
                    "charge air temperatures may be elevated. KFTARX (me7-raw.txt line 142587) " +
                    "reduces effective LDRXN above tans > 75°C. Monitor intake air temps.")
            }
        }

        return EnvironmentalSummary(
            avgBaroPressure = avgBaro,
            estimatedAltitudeM = altitudeM,
            avgIntakeTemp = avgIat,
            avgCoolantTemp = null,
            intakeTempRange = iatRange,
            altitudeDeviation = altitudeDeviation,
            tempDeviation = tempDeviation,
            kftarxWarning = kftarxWarning,
            warnings = warnings
        )
    }

    /**
     * Normalize observed WGDC to sea-level equivalent using KFLDIOPU.
     *
     * From me7-raw.txt line 144473:
     * > "KFLDIOPU: Tastverhältniskorrekturbedarf als Funktion der Höhe (pu)"
     *
     * @param observedWgdc Actual WGDC from log
     * @param rpm Engine RPM
     * @param baroPressure Barometric pressure from log (pus_w)
     * @param kfldiopu Altitude correction map
     * @return Normalized WGDC at sea level
     */
    fun normalizeWgdcForAltitude(
        observedWgdc: Double,
        rpm: Double,
        baroPressure: Double,
        kfldiopu: Map3d
    ): Double {
        val altitudeCorrection = kfldiopu.lookup(baroPressure, rpm)
        val seaLevelCorrection = kfldiopu.lookup(STANDARD_BARO_MBAR, rpm)
        return observedWgdc - altitudeCorrection + seaLevelCorrection
    }

    /**
     * Compute the combustion chamber temperature factor ftbr.
     *
     * From me7-raw.txt line 54859–54880:
     * > "Diese Temperaturkompensation liefert am Ausgang den Faktor Temperatur
     * > Brennraum (ftbr)"
     *
     * ftbr = 273 / (evtmod + 273) × fwft
     * where:
     *   evtmod = tans + (tmot - tans) × KFFWTBR(rpm, load)
     *   fwft = (tans + 673.425) / 731.334
     *
     * @return ftbr factor (1.0 at standard conditions)
     */
    fun computeFtbr(
        intakeAirTemp: Double,     // tans (°C)
        coolantTemp: Double,       // tmot (°C)
        rpm: Double,
        load: Double,
        kffwtbr: Map3d? = null
    ): Double {
        // KFFWTBR blending factor (0 = pure intake temp, 1 = pure coolant temp)
        val blendFactor = kffwtbr?.lookup(load, rpm) ?: 0.02  // default from ME7 docs
        val evtmod = intakeAirTemp + (coolantTemp - intakeAirTemp) * blendFactor
        val fwft = (intakeAirTemp + 673.425) / 731.334
        return 273.0 / (evtmod + 273.0) * fwft
    }

    /**
     * Compute standard ftbr at reference conditions.
     */
    fun standardFtbr(): Double {
        return computeFtbr(STANDARD_INTAKE_TEMP_C, STANDARD_COOLANT_TEMP_C, 3000.0, 100.0)
    }
}

