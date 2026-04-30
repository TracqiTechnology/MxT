package domain.model.injector

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Computes KRKTE scaling and TVUB dead time tables for injector swaps.
 *
 * ## ME7 Injection Time Formula
 *
 * From me7-raw.txt line 257427 and 222461–222693:
 *   te = (rk_w + rkte_w) × KRKTE + TVUB(ubat)
 *
 * Where:
 * - rk_w = relative fuel mass (derived from rl_w and lambda corrections)
 * - KRKTE = scalar injector constant [ms/%] (line 222175: "injection valve constant for calculation")
 * - TVUB = voltage-dependent dead time (Ventilverzugszeit) Kennlinie [V → ms]
 *
 * ME7 does NOT have a 2D injection time Kennfeld. There is no "KFTI" map.
 * There is no "KFLFW" injector linearization curve either — ME7 handles
 * minimum pulse width via the TEMIN constant.
 *
 * Note: KFLF ("Lambdakennfeld bei Teillast") is misnamed — it is actually
 * a multiplicative injection correction factor (1.0 = neutral), NOT a lambda target.
 *
 * ## Scaling When Changing Injectors
 *
 * Since KRKTE is a scalar that encodes injector flow rate:
 *   KRKTE_new = KRKTE_old × (old_flow / new_flow) × √(new_press / old_press)
 *
 * The pressure correction uses Bernoulli's principle: flow ∝ √(ΔP).
 * The KRKTE tab already computes the absolute value from first principles;
 * this calculator provides a quick ratio-based cross-check plus TVUB generation.
 *
 * ## TVUB Dead Time
 *
 * ME7's TVUB is a 1D Kennlinie: battery voltage (V) → dead time (ms).
 * Dead time ∝ 1/voltage (solenoid physics).
 * Confirmed in XDF: "Injector on time offset voltage correction" (5 points).
 * Confirmed in me7-raw.txt line 183466: "Ventilverzugszeit TVUB".
 */
object InjectorScalingSolver {

    /**
     * Compute the KRKTE scale factor from old/new injector specs.
     *
     * KRKTE is a scalar constant [ms/%]. Changing injectors requires scaling
     * it by the ratio of old-to-new flow rates, with optional pressure correction.
     *
     * @param oldSpec Old (stock) injector specifications
     * @param newSpec New injector specifications
     * @return KrkteScalingResult with the scale factor, formula, and warnings
     */
    fun computeKrkteScaling(
        oldSpec: InjectorSpec,
        newSpec: InjectorSpec
    ): KrkteScalingResult {
        val warnings = mutableListOf<String>()

        require(oldSpec.flowRateCcPerMin > 0) { "Old injector flow rate must be positive" }
        require(newSpec.flowRateCcPerMin > 0) { "New injector flow rate must be positive" }

        // Flow ratio: larger injector → smaller KRKTE (more fuel per ms)
        val flowRatio = oldSpec.flowRateCcPerMin / newSpec.flowRateCcPerMin

        // Pressure correction via Bernoulli: flow ∝ √(ΔP)
        val pressureCorrection = if (abs(oldSpec.fuelPressureBar - newSpec.fuelPressureBar) > 0.01) {
            sqrt(newSpec.fuelPressureBar / oldSpec.fuelPressureBar)
        } else {
            1.0
        }

        val scaleFactor = flowRatio * pressureCorrection

        if (scaleFactor > 2.0 || scaleFactor < 0.3) {
            warnings.add(
                "Scale factor %.3f is extreme (%.0f cc/min → %.0f cc/min). Verify injector specs.".format(
                    scaleFactor, oldSpec.flowRateCcPerMin, newSpec.flowRateCcPerMin
                )
            )
        }

        if (abs(oldSpec.deadTimeMs) < 0.001 && abs(newSpec.deadTimeMs) < 0.001) {
            warnings.add(
                "Dead times are both zero. Provide TVUB values from the injector " +
                    "data sheet for best accuracy."
            )
        }

        if (newSpec.flowRateCcPerMin > oldSpec.flowRateCcPerMin * 2.5) {
            warnings.add(
                "New injectors are >2.5× larger than stock. At idle the effective " +
                    "pulse width (te − TVUB) may fall below TEMIN. Check idle stability."
            )
        }

        return KrkteScalingResult(
            scaleFactor = scaleFactor,
            flowRatio = flowRatio,
            pressureCorrection = pressureCorrection,
            oldDeadTimeMs = oldSpec.deadTimeMs,
            newDeadTimeMs = newSpec.deadTimeMs,
            warnings = warnings
        )
    }

    /**
     * Compute TVUB (dead time) table from injector spec voltage/dead-time pairs.
     *
     * ME7's TVUB is a 1D Kennlinie: battery voltage (V) → dead time (ms).
     * The example XDF has 5 voltage points; other BINs may differ.
     *
     * If the spec provides fewer points, this interpolates/extrapolates to fill
     * the target axis. If only a single dead time at 14V is given, it uses the
     * 1/V relationship (dead time ∝ 1/voltage) to estimate the curve.
     *
     * @param spec Injector spec with deadTimeVoltageTable or deadTimeMs
     * @param targetVoltageAxis Optional custom voltage axis
     * @return TVUB result with interpolated dead times
     */
    fun computeTvub(
        spec: InjectorSpec,
        targetVoltageAxis: DoubleArray = doubleArrayOf(
            6.0, 8.0, 10.0, 12.0, 14.0, 16.0
        )
    ): TvubResult {
        val voltageTable = spec.deadTimeVoltageTable

        if (voltageTable.isEmpty()) {
            // Single dead time value at 14V — estimate curve via 1/V relationship
            val refVoltage = 14.0
            val refDeadTime = spec.deadTimeMs
            val deadTimes = DoubleArray(targetVoltageAxis.size) { i ->
                val voltage = targetVoltageAxis[i]
                (refDeadTime * refVoltage / voltage).coerceAtLeast(0.0)
            }
            return TvubResult(targetVoltageAxis, deadTimes)
        }

        // Sort spec data by voltage and interpolate to target axis
        val sortedEntries = voltageTable.entries.sortedBy { it.key }
        val specVoltages = sortedEntries.map { it.key }.toDoubleArray()
        val specDeadTimes = sortedEntries.map { it.value }.toDoubleArray()

        val deadTimes = DoubleArray(targetVoltageAxis.size) { i ->
            interpolate(targetVoltageAxis[i], specVoltages, specDeadTimes)
        }

        return TvubResult(targetVoltageAxis, deadTimes)
    }

    /**
     * Linear interpolation with clamped extrapolation.
     */
    private fun interpolate(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        require(xs.size == ys.size && xs.isNotEmpty()) { "Arrays must be non-empty and same size" }

        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()

        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= x) lo = mid else hi = mid
        }

        val t = (x - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + t * (ys[hi] - ys[lo])
    }
}
