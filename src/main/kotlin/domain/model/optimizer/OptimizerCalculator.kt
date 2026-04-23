package domain.model.optimizer

import data.contract.Me7LogFileContract
import domain.math.Index
import domain.math.MonotoneCubicInterpolator
import domain.math.map.Map3d
import domain.model.simulator.Me7Simulator
import domain.model.simulator.MechanicalLimitDetector
import domain.model.simulator.PidSimulator
import domain.model.simulator.SafetyModeDetector
import domain.model.simulator.TransientDetector
import domain.model.simulator.EnvironmentalCorrector
import domain.model.simulator.ThrottleBodyChecker
import kotlin.math.abs

/**
 * Suggestion engine that analyzes WOT logs and recommends corrections to
 * all maps in the LDRXN → actual_load signal chain.
 *
 * Integrates the ME7 simulation engine to predict ECU behavior from
 * calibration maps and compare against actual log data.
 *
 * @see domain.model.simulator.Me7Simulator
 * @see documentation/TECHNICAL_BREAKDOWN.md
 */
object OptimizerCalculator {

    /** Conversion factor: 1 mbar = 0.0145038 PSI */
    private const val MBAR_TO_PSI = 0.0145038

    /** Single row of relevant WOT data extracted from a log. */
    data class WotLogEntry(
        val rpm: Double,
        val requestedLoad: Double,      // rlsol_w
        val actualLoad: Double,         // rl_w (or rl)
        val requestedMap: Double,       // pssol_w (mbar absolute)
        val actualMap: Double,          // pvdks_w (mbar absolute)
        val barometricPressure: Double, // pus_w (mbar)
        val wgdc: Double,               // ldtvm (%)
        val throttleAngle: Double,      // wdkba
        val intakeAirTemp: Double = 20.0,         // tans (°C), default standard conditions
        val maxRequestedPressure: Double? = null,  // pvdxs_w (mbar) — max allowed pressure from KFLDHBN/BGRLMXS
        val gear: Int? = null                      // gangi — selected gear
    ) {
        /** Actual relative boost pressure in PSI (above atmospheric). */
        val relativeBoostPsi: Double get() = (actualMap - barometricPressure) * MBAR_TO_PSI

        /** Whether boost is within tolerance of target (tracking, not spooling). */
        fun isTracking(toleranceMbar: Double = 50.0): Boolean =
            abs(requestedMap - actualMap) <= toleranceMbar

        /** Whether the boost ceiling (KFLDHBN/BGRLMXS) appears to be limiting. */
        val boostCeilingLimiting: Boolean get() {
            val maxP = maxRequestedPressure ?: return false
            return maxP > 0 && abs(requestedMap - maxP) < 20.0
        }
    }

    /** Complete result bundle from the optimizer. */
    data class OptimizerResult(
        val suggestedKfldrl: Map3d?,
        val suggestedKfldimx: Map3d?,
        val kfpbrkMultipliers: Map3d?,
        val pressureErrors: List<Pair<Double, Double>>,
        val loadErrors: List<Pair<Double, Double>>,
        val warnings: List<String>,
        val wotEntries: List<WotLogEntry>,
        // Simulation engine results
        val simulationResults: List<Me7Simulator.SimulationResult> = emptyList(),
        val mechanicalLimits: MechanicalLimitDetector.MechanicalLimits = MechanicalLimitDetector.MechanicalLimits(),
        val simulatedPressureSeries: List<Pair<Double, Double>> = emptyList(),
        val simulatedLoadSeries: List<Pair<Double, Double>> = emptyList(),
        // Chain diagnosis (v2)
        val chainDiagnosis: ChainDiagnosis = ChainDiagnosis(),
        // v3: Full auto-calibration output
        val suggestedMaps: SuggestedMaps = SuggestedMaps(),
        val perRpmAnalysis: Map<String, List<RpmBreakpointAnalysis>> = emptyMap(),
        val prediction: PredictionResult? = null,
        val logSummaries: List<LogSummary> = emptyList(),
        // v4: Advanced analysis
        val pulls: List<PullSegmenter.WotPull> = emptyList(),
        val pullConsistency: Map<Int, String> = emptyMap(),
        val safetyModes: SafetyModeDetector.SafetyModeResult? = null,
        val transients: TransientDetector.TransientResult? = null,
        val environmental: EnvironmentalCorrector.EnvironmentalSummary? = null,
        val throttleCheck: ThrottleBodyChecker.ThrottleCheckResult? = null,
        val convergenceHistory: IterativeConvergence.ConvergenceHistory? = null,
        val kfurlSolverResult: KfurlSolver.SolverResult? = null,
        val pidSimulation: PidSimulator.PidSimulationResult? = null,
        // v6: KFPRG solver
        val kfprgSolverResult: KfprgSolver.SolverResult? = null
    )

    // ── Helpers ────────────────────────────────────────────────────────

    data class FilteredLogData(
        val wotEntries: List<WotLogEntry>,
        val mafValues: List<Double>?,
        val injectorOnTimes: List<Double>?,
        val wotRpms: List<Double>?,
        val mafVoltages: List<Double>?
    )

    fun filterWotEntriesWithOptionalData(
        values: Map<Me7LogFileContract.Header, List<Double>>,
        minThrottleAngle: Double = 80.0
    ): FilteredLogData {
        val emptyResult = FilteredLogData(emptyList(), null, null, null, null)
        val rpms = values[Me7LogFileContract.Header.RPM_COLUMN_HEADER] ?: return emptyResult
        val requestedLoads = values[Me7LogFileContract.Header.REQUESTED_LOAD_HEADER] ?: return emptyResult
        val actualLoads = values[Me7LogFileContract.Header.ENGINE_LOAD_HEADER] ?: return emptyResult
        val requestedMaps = values[Me7LogFileContract.Header.REQUESTED_PRESSURE_HEADER] ?: return emptyResult
        val actualMaps = values[Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER] ?: return emptyResult
        val wgdcs = values[Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER] ?: return emptyResult
        val throttleAngles = values[Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER] ?: return emptyResult
        val baroPressures = values[Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER] ?: return emptyResult

        val altLoads = values[Me7LogFileContract.Header.ACTUAL_LOAD_HEADER]
        val useAltLoads = altLoads != null && altLoads.size == rpms.size

        val allMafValues = values[Me7LogFileContract.Header.MAF_GRAMS_PER_SECOND_HEADER]
        val hasMaf = allMafValues != null && allMafValues.size == rpms.size
        val allInjectorTimes = values[Me7LogFileContract.Header.FUEL_INJECTOR_ON_TIME_HEADER]
        val hasInjector = allInjectorTimes != null && allInjectorTimes.size == rpms.size
        val allMafVoltages = values[Me7LogFileContract.Header.MAF_VOLTAGE_HEADER]
        val hasMafVoltage = allMafVoltages != null && allMafVoltages.size == rpms.size

        // Optional enrichment signals for enhanced diagnostics
        val allIntakeTemps = values[Me7LogFileContract.Header.INTAKE_TEMPERATURE_HEADER]
        val hasIntakeTemp = allIntakeTemps != null && allIntakeTemps.size == rpms.size
        val allMaxPressures = values[Me7LogFileContract.Header.REQUESTED_PRESSURE_MAX_HEADER]
        val hasMaxPressure = allMaxPressures != null && allMaxPressures.size == rpms.size
        val allGears = values[Me7LogFileContract.Header.SELECTED_GEAR_HEADER]
        val hasGear = allGears != null && allGears.size == rpms.size

        val entries = mutableListOf<WotLogEntry>()
        val wotMaf = if (hasMaf) mutableListOf<Double>() else null
        val wotInjector = if (hasInjector) mutableListOf<Double>() else null
        val wotRpms = if (hasInjector) mutableListOf<Double>() else null
        val wotMafVoltages = if (hasMafVoltage) mutableListOf<Double>() else null

        for (i in rpms.indices) {
            if (throttleAngles[i] >= minThrottleAngle) {
                entries.add(
                    WotLogEntry(
                        rpm = rpms[i],
                        requestedLoad = requestedLoads[i],
                        actualLoad = if (useAltLoads) altLoads!![i] else actualLoads[i],
                        requestedMap = requestedMaps[i],
                        actualMap = actualMaps[i],
                        barometricPressure = baroPressures[i],
                        wgdc = wgdcs[i],
                        throttleAngle = throttleAngles[i],
                        intakeAirTemp = if (hasIntakeTemp) allIntakeTemps!![i] else 20.0,
                        maxRequestedPressure = if (hasMaxPressure) allMaxPressures!![i] else null,
                        gear = if (hasGear) allGears!![i].toInt() else null
                    )
                )
                wotMaf?.add(allMafValues!![i])
                wotInjector?.add(allInjectorTimes!![i])
                wotRpms?.add(rpms[i])
                wotMafVoltages?.add(allMafVoltages!![i])
            }
        }

        return FilteredLogData(entries, wotMaf, wotInjector, wotRpms, wotMafVoltages)
    }

    fun filterWotEntries(
        values: Map<Me7LogFileContract.Header, List<Double>>,
        minThrottleAngle: Double = 80.0
    ): List<WotLogEntry> {
        return filterWotEntriesWithOptionalData(values, minThrottleAngle).wotEntries
    }

    // ── Phase 1 : Boost Control (KFLDRL + KFLDIMX) with MapDelta ──────

    /**
     * Suggest KFLDRL corrections and return a MapDelta with per-cell confidence.
     */
    fun suggestKfldrlDelta(
        wotEntries: List<WotLogEntry>,
        kfldrlMap: Map3d,
        toleranceMbar: Double = 30.0
    ): MapDelta {
        val rpmAxis = kfldrlMap.yAxis
        val pressureAxis = kfldrlMap.xAxis
        val suggested = Array(rpmAxis.size) { Array(pressureAxis.size) { 0.0 } }
        val sampleCounts = Array(rpmAxis.size) { IntArray(pressureAxis.size) }

        val wgdcSum = Array(rpmAxis.size) { DoubleArray(pressureAxis.size) }

        val boostEntries = wotEntries.filter { it.relativeBoostPsi > 0 }

        for (entry in boostEntries) {
            val rpmIdx = Index.getInsertIndex(rpmAxis.toList(), entry.rpm)
            val pressureIdx = Index.getInsertIndex(pressureAxis.toList(), entry.relativeBoostPsi)

            wgdcSum[rpmIdx][pressureIdx] += entry.wgdc
            sampleCounts[rpmIdx][pressureIdx]++
        }

        for (rpmIdx in rpmAxis.indices) {
            // Compute raw averages for bins with data
            for (pIdx in pressureAxis.indices) {
                if (sampleCounts[rpmIdx][pIdx] > 0) {
                    suggested[rpmIdx][pIdx] = wgdcSum[rpmIdx][pIdx] / sampleCounts[rpmIdx][pIdx]
                } else {
                    suggested[rpmIdx][pIdx] = kfldrlMap.zAxis[rpmIdx][pIdx]
                }
            }

            // Apply monotone cubic smoothing across measured bins
            val measuredIndices = pressureAxis.indices.filter { sampleCounts[rpmIdx][it] > 0 }
            if (measuredIndices.size >= 3) {
                val knownX = measuredIndices.map { pressureAxis[it] }.toDoubleArray()
                val knownY = measuredIndices.map { suggested[rpmIdx][it] }.toDoubleArray()
                for (pIdx in pressureAxis.indices) {
                    if (sampleCounts[rpmIdx][pIdx] > 0) {
                        // Re-evaluate measured bins through smooth curve
                        suggested[rpmIdx][pIdx] = MonotoneCubicInterpolator.interpolate(knownX, knownY, pressureAxis[pIdx])
                    }
                }
            }

            // Enforce monotonicity for unmeasured bins
            for (pIdx in 1 until pressureAxis.size) {
                if (suggested[rpmIdx][pIdx] < suggested[rpmIdx][pIdx - 1] && sampleCounts[rpmIdx][pIdx] == 0) {
                    suggested[rpmIdx][pIdx] = suggested[rpmIdx][pIdx - 1]
                }
            }
        }

        val suggestedMap = Map3d(pressureAxis, rpmAxis, suggested)
        return MapDelta.build("KFLDRL", kfldrlMap, suggestedMap, sampleCounts)
    }

    /**
     * Legacy suggestKfldrl that returns just the Map3d.
     */
    fun suggestKfldrl(
        wotEntries: List<WotLogEntry>,
        kfldrlMap: Map3d,
        toleranceMbar: Double = 30.0,
        rpmTolerance: Double = 200.0
    ): Map3d {
        return suggestKfldrlDelta(wotEntries, kfldrlMap, toleranceMbar).suggested
    }

    /**
     * Suggest KFLDIMX from steady-state (on-target) WGDC data.
     *
     * H3: FR reference (me7-raw.txt): "KFLDIMX: mit den stationären Tastverhältniswerten beschreiben"
     * (populate with steady-state duty cycle values). On a WOT pull, I is maxed out due to
     * integrator windup, so it simply follows IMX.
     *
     * Algorithm:
     * 1. Filter WOT entries where boost is on-target (|actual - requested| < tolerance) — these
     *    represent steady-state conditions where the PID I-term has converged to the WGDC needed.
     * 2. For each (RPM, pressure) bin, the max observed WGDC IS the steady-state I-limiter value.
     * 3. Add configurable overhead margin (default 5%, matching FR LDDIMXN typical range of 3-15%).
     * 4. Falls back to KFLDRL × overhead method if insufficient steady-state data.
     *
     * M2 dependency: Uses spool vs tracking classification — only TRACKING samples inform KFLDIMX.
     */
    fun suggestKfldimxDelta(
        wotEntries: List<WotLogEntry>,
        suggestedKfldrlDelta: MapDelta,
        kfldimxMap: Map3d,
        overheadPercent: Double = 8.0,
        trackingToleranceMbar: Double = 50.0
    ): MapDelta {
        val suggestedKfldrl = suggestedKfldrlDelta.suggested
        val sampleCounts = Array(kfldimxMap.yAxis.size) { IntArray(kfldimxMap.xAxis.size) }

        // Separate on-target (tracking) entries from spool-up entries
        val trackingEntries = wotEntries.filter { it.isTracking(trackingToleranceMbar) && it.relativeBoostPsi > 0 }
        val hasEnoughTrackingData = trackingEntries.size >= 10

        val suggestedZ: Array<Array<Double>>

        if (hasEnoughTrackingData) {
            // H3: Steady-state method — use max observed WGDC from on-target samples per bin
            val steadyStateWgdc = Array(kfldimxMap.yAxis.size) { DoubleArray(kfldimxMap.xAxis.size) }
            val steadyCounts = Array(kfldimxMap.yAxis.size) { IntArray(kfldimxMap.xAxis.size) }

            for (entry in trackingEntries) {
                val rpmIdx = Index.getInsertIndex(kfldimxMap.yAxis.toList(), entry.rpm)
                val pressureIdx = Index.getInsertIndex(kfldimxMap.xAxis.toList(),
                    if (kfldimxMap.xAxis[0] > 100) entry.actualMap - entry.barometricPressure  // relative pressure axis (mbar)
                    else entry.relativeBoostPsi  // PSI axis
                )
                // Track maximum WGDC per bin (represents converged I-term)
                if (entry.wgdc > steadyStateWgdc[rpmIdx][pressureIdx]) {
                    steadyStateWgdc[rpmIdx][pressureIdx] = entry.wgdc
                }
                steadyCounts[rpmIdx][pressureIdx]++
            }

            val margin = 1.0 + overheadPercent / 100.0
            suggestedZ = Array(kfldimxMap.yAxis.size) { rpmIdx ->
                Array(kfldimxMap.xAxis.size) { pIdx ->
                    sampleCounts[rpmIdx][pIdx] = steadyCounts[rpmIdx][pIdx]
                    if (steadyCounts[rpmIdx][pIdx] > 0) {
                        (steadyStateWgdc[rpmIdx][pIdx] * margin).coerceIn(0.0, 100.0)
                    } else {
                        // No steady-state data for this bin — fall back to KFLDRL method
                        val kfldrlVal = interpolateKfldrlValue(suggestedKfldrl, kfldimxMap, rpmIdx, pIdx)
                        if (kfldrlVal > 0) kfldrlVal * margin else kfldimxMap.zAxis[rpmIdx][pIdx]
                    }
                }
            }
        } else {
            // Fallback: Legacy KFLDRL × overhead method (insufficient tracking data)
            val multiplier = 1.0 + overheadPercent / 100.0
            suggestedZ = Array(kfldimxMap.yAxis.size) { rpmIdx ->
                Array(kfldimxMap.xAxis.size) { pIdx ->
                    sampleCounts[rpmIdx][pIdx] = interpolateKfldrlSamples(suggestedKfldrlDelta, kfldimxMap, rpmIdx, pIdx)
                    val kfldrlVal = interpolateKfldrlValue(suggestedKfldrl, kfldimxMap, rpmIdx, pIdx)
                    if (kfldrlVal > 0) kfldrlVal * multiplier else kfldimxMap.zAxis[rpmIdx][pIdx]
                }
            }
        }

        val suggestedMap = Map3d(kfldimxMap.xAxis, kfldimxMap.yAxis, suggestedZ)
        return MapDelta.build("KFLDIMX", kfldimxMap, suggestedMap, sampleCounts)
    }

    /** Look up KFLDRL value for a KFLDIMX bin, handling axis mismatch. */
    private fun interpolateKfldrlValue(suggestedKfldrl: Map3d, kfldimxMap: Map3d, rpmIdx: Int, pIdx: Int): Double {
        return if (kfldimxMap.yAxis.size == suggestedKfldrl.yAxis.size &&
            kfldimxMap.xAxis.size == suggestedKfldrl.xAxis.size
        ) {
            suggestedKfldrl.zAxis[rpmIdx][pIdx]
        } else {
            val kfldrlRpmIdx = Index.getInsertIndex(suggestedKfldrl.yAxis.toList(), kfldimxMap.yAxis[rpmIdx])
            val kfldrlPIdx = Index.getInsertIndex(suggestedKfldrl.xAxis.toList(),
                kfldimxMap.xAxis[pIdx] * MBAR_TO_PSI)
            suggestedKfldrl.zAxis[kfldrlRpmIdx][kfldrlPIdx]
        }
    }

    /** Look up KFLDRL sample count for a KFLDIMX bin, handling axis mismatch. */
    private fun interpolateKfldrlSamples(kfldrlDelta: MapDelta, kfldimxMap: Map3d, rpmIdx: Int, pIdx: Int): Int {
        return if (kfldimxMap.yAxis.size == kfldrlDelta.suggested.yAxis.size &&
            kfldimxMap.xAxis.size == kfldrlDelta.suggested.xAxis.size
        ) {
            kfldrlDelta.sampleCounts[rpmIdx][pIdx]
        } else {
            val kfldrlRpmIdx = Index.getInsertIndex(kfldrlDelta.suggested.yAxis.toList(), kfldimxMap.yAxis[rpmIdx])
            val kfldrlPIdx = Index.getInsertIndex(kfldrlDelta.suggested.xAxis.toList(),
                kfldimxMap.xAxis[pIdx] * MBAR_TO_PSI)
            if (kfldrlRpmIdx < kfldrlDelta.sampleCounts.size &&
                kfldrlPIdx < kfldrlDelta.sampleCounts[0].size)
                kfldrlDelta.sampleCounts[kfldrlRpmIdx][kfldrlPIdx] else 0
        }
    }

    /**
     * Legacy suggestKfldimx that returns just the Map3d.
     */
    fun suggestKfldimx(
        suggestedKfldrl: Map3d,
        kfldimxMap: Map3d,
        overheadPercent: Double = 8.0
    ): Map3d {
        val multiplier = 1.0 + overheadPercent / 100.0
        if (kfldimxMap.yAxis.size == suggestedKfldrl.yAxis.size &&
            kfldimxMap.xAxis.size == suggestedKfldrl.xAxis.size
        ) {
            val suggested = Array(kfldimxMap.yAxis.size) { rpmIdx ->
                Array(kfldimxMap.xAxis.size) { pIdx ->
                    val v = suggestedKfldrl.zAxis[rpmIdx][pIdx]
                    if (v > 0) v * multiplier else kfldimxMap.zAxis[rpmIdx][pIdx]
                }
            }
            return Map3d(kfldimxMap.xAxis, kfldimxMap.yAxis, suggested)
        }
        val suggested = Array(kfldimxMap.yAxis.size) { rpmIdx ->
            val kfldrlRpmIdx = Index.getInsertIndex(suggestedKfldrl.yAxis.toList(), kfldimxMap.yAxis[rpmIdx])
            Array(kfldimxMap.xAxis.size) { pIdx ->
                val pressurePsi = kfldimxMap.xAxis[pIdx] * MBAR_TO_PSI
                val kfldrlPIdx = Index.getInsertIndex(suggestedKfldrl.xAxis.toList(), pressurePsi)
                val v = suggestedKfldrl.zAxis[kfldrlRpmIdx][kfldrlPIdx]
                if (v > 0) v * multiplier else kfldimxMap.zAxis[rpmIdx][pIdx]
            }
        }
        return Map3d(kfldimxMap.xAxis, kfldimxMap.yAxis, suggested)
    }

    // ── Phase 2 : VE Model (KFPBRK) with MapDelta ─────────────────────

    /**
     * Suggest KFPBRK corrections and return a MapDelta with per-cell confidence.
     */
    fun suggestKfpbrkDelta(
        wotEntries: List<WotLogEntry>,
        kfpbrkMap: Map3d,
        toleranceMbar: Double = 30.0,
        rpmTolerance: Double = 200.0,
        simulationResults: List<Me7Simulator.SimulationResult>? = null
    ): MapDelta? {
        val rpmAxis = kfpbrkMap.yAxis
        val xAxis = kfpbrkMap.xAxis
        val suggested = Array(rpmAxis.size) { Array(xAxis.size) { 0.0 } }
        val sampleCounts = Array(rpmAxis.size) { IntArray(xAxis.size) }
        var hasData = false

        for (rpmIdx in rpmAxis.indices) {
            val rpmTarget = rpmAxis[rpmIdx]

            if (simulationResults != null) {
                val simValid = simulationResults.filter { sr ->
                    abs(sr.rpm - rpmTarget) < rpmTolerance &&
                        abs(sr.actualPvdks - sr.actualPssol) <= toleranceMbar &&
                        sr.actualRl > 0 &&
                        sr.kfpbrkCorrectionFactor.isFinite() &&
                        sr.kfpbrkCorrectionFactor > 0
                }

                if (simValid.isNotEmpty()) {
                    hasData = true
                    val avgCorrection = simValid.map { it.kfpbrkCorrectionFactor }.average()
                    val safeCorrection = if (avgCorrection.isFinite()) avgCorrection else 1.0
                    for (xIdx in xAxis.indices) {
                        suggested[rpmIdx][xIdx] = kfpbrkMap.zAxis[rpmIdx][xIdx] * safeCorrection
                        sampleCounts[rpmIdx][xIdx] = simValid.size
                    }
                    continue
                }
            }

            val valid = wotEntries.filter { e ->
                abs(e.rpm - rpmTarget) < rpmTolerance &&
                    abs(e.actualMap - e.requestedMap) <= toleranceMbar &&
                    e.actualLoad > 0
            }

            if (valid.isEmpty()) {
                for (xIdx in xAxis.indices) {
                    suggested[rpmIdx][xIdx] = kfpbrkMap.zAxis[rpmIdx][xIdx]
                }
                continue
            }

            hasData = true
            val avgRatio = valid.map { e -> e.requestedLoad / e.actualLoad }.average()
            val safeRatio = if (avgRatio.isFinite()) avgRatio else 1.0

            for (xIdx in xAxis.indices) {
                suggested[rpmIdx][xIdx] = kfpbrkMap.zAxis[rpmIdx][xIdx] * safeRatio
                sampleCounts[rpmIdx][xIdx] = valid.size
            }
        }

        if (!hasData) return null
        val suggestedMap = Map3d(xAxis, rpmAxis, suggested)
        return MapDelta.build("KFPBRK", kfpbrkMap, suggestedMap, sampleCounts)
    }

    /**
     * Legacy suggestKfpbrk that returns just the Map3d.
     */
    fun suggestKfpbrk(
        wotEntries: List<WotLogEntry>,
        kfpbrkMap: Map3d,
        toleranceMbar: Double = 30.0,
        rpmTolerance: Double = 200.0,
        simulationResults: List<Me7Simulator.SimulationResult>? = null
    ): Map3d? {
        return suggestKfpbrkDelta(wotEntries, kfpbrkMap, toleranceMbar, rpmTolerance, simulationResults)?.suggested
    }

    // ── Phase 2b : KFMIOP/KFMIRL Suggestions (Link 1 Fix) ──────────────

    /**
     * Suggest KFMIOP corrections for RPMs where the torque structure caps rlsol.
     *
     * KFMIOP maps (load%, RPM) → torque. If at a given RPM rlsol < LDRXN * 0.95,
     * the torque structure is limiting load. We scale the maximum torque output at
     * that RPM so that the load request can reach LDRXN.
     *
     * Algorithm: for each RPM row, find the peak torque value. If samples at that
     * RPM are torque-limited, compute the ratio (LDRXN / avg_rlsol) and scale
     * all torque cells in that row proportionally.
     */
    fun suggestKfmiopDelta(
        simulationResults: List<Me7Simulator.SimulationResult>,
        kfmiopMap: Map3d,
        ldrxnTarget: Double,
        rpmTolerance: Double = 250.0
    ): MapDelta? {
        val rpmAxis = kfmiopMap.yAxis
        val xAxis = kfmiopMap.xAxis
        val suggested = Array(rpmAxis.size) { r -> Array(xAxis.size) { c -> kfmiopMap.zAxis[r][c] } }
        val sampleCounts = Array(rpmAxis.size) { IntArray(xAxis.size) }
        var hasChanges = false

        for (rpmIdx in rpmAxis.indices) {
            val rpmTarget = rpmAxis[rpmIdx]
            val nearBy = simulationResults.filter { abs(it.rpm - rpmTarget) < rpmTolerance }
            if (nearBy.isEmpty()) continue

            val capped = nearBy.filter { it.torqueLimited }
            if (capped.isEmpty()) {
                // Not torque-limited at this RPM — keep original values
                for (xIdx in xAxis.indices) {
                    sampleCounts[rpmIdx][xIdx] = nearBy.size
                }
                continue
            }

            // Compute scale factor: how much we need to expand the torque range
            val avgRlsol = capped.map { it.rlsol }.average()
            if (avgRlsol <= 0) continue

            // Scale so the peak torque supports LDRXN instead of just avgRlsol
            val scaleFactor = ldrxnTarget / avgRlsol
            // Safety cap: don't scale more than 30%
            val safeFactor = scaleFactor.coerceAtMost(1.30)

            if (safeFactor > 1.005) {
                hasChanges = true
                for (xIdx in xAxis.indices) {
                    suggested[rpmIdx][xIdx] = kfmiopMap.zAxis[rpmIdx][xIdx] * safeFactor
                    sampleCounts[rpmIdx][xIdx] = capped.size
                }
            } else {
                for (xIdx in xAxis.indices) {
                    sampleCounts[rpmIdx][xIdx] = nearBy.size
                }
            }
        }

        if (!hasChanges) return null
        val suggestedMap = Map3d(xAxis, rpmAxis, suggested)
        return MapDelta.build("KFMIOP", kfmiopMap, suggestedMap, sampleCounts)
    }

    /**
     * Suggest KFMIRL as the inverse of the suggested KFMIOP.
     *
     * KFMIRL maps (torque, RPM) → load%. It is the mathematical inverse of KFMIOP.
     * We use the existing Inverse.calculateInverse() utility.
     */
    fun suggestKfmirlDelta(
        suggestedKfmiop: Map3d,
        kfmirlMap: Map3d
    ): MapDelta {
        val inverse = domain.math.Inverse.calculateInverse(suggestedKfmiop, kfmirlMap)
        // Preserve the first column from the original KFMIRL (idle/low-load values)
        for (i in inverse.zAxis.indices) {
            if (inverse.zAxis[i].isNotEmpty() && kfmirlMap.zAxis[i].isNotEmpty()) {
                inverse.zAxis[i][0] = kfmirlMap.zAxis[i][0]
            }
        }
        // Sample counts: use uniform count since inverse is a mathematical operation
        val sampleCounts = Array(kfmirlMap.yAxis.size) { IntArray(kfmirlMap.xAxis.size) { 100 } }
        return MapDelta.build("KFMIRL", kfmirlMap, inverse, sampleCounts)
    }

    // ── Phase 3 : Intervention Check ──────────────────────────────────

    fun checkInterventions(
        wotEntries: List<WotLogEntry>,
        ldrxnTarget: Double
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (wotEntries.isEmpty()) return warnings

        val capped = wotEntries.filter { e -> e.requestedLoad < ldrxnTarget * 0.95 }
        if (capped.isNotEmpty()) {
            val pct = capped.size.toDouble() / wotEntries.size * 100
            val avgRlsol = capped.map { it.requestedLoad }.average()

            // H1: Distinguish KFLDHBN (boost ceiling) from torque structure limiting
            // FR: rlmax_w = min(rlmxko_w, ldrlts_w, ldrlms_w)
            // When pvdxs_w ≈ pvds_w, the boost ceiling (KFLDHBN/BGRLMXS) is the limiter
            val ceilingLimited = capped.filter { it.boostCeilingLimiting }
            val ceilingPct = if (capped.isNotEmpty()) ceilingLimited.size.toDouble() / capped.size * 100 else 0.0

            if (ceilingPct > 50.0) {
                warnings.add(
                    "Boost Ceiling Limiting: pvdxs_w (max requested pressure) ≈ pvds_w (requested pressure) in " +
                        "${String.format("%.0f", ceilingPct)}% of load-limited samples. " +
                        "KFLDHBN / BGRLMXS is capping boost request, not the torque structure. " +
                        "Increase KFLDHBN pressure ratio limit (ME7) or check BGRLMXS paths (MED17)."
                )
            } else {
                warnings.add(
                    "Torque Intervention Detected: rlsol is below LDRXN target (${ldrxnTarget.format()}) " +
                        "in ${String.format("%.1f", pct)}% of WOT samples (avg rlsol = ${avgRlsol.format()}). " +
                        "Check KFMIOP / KFMIZUFIL to ensure torque requests support this load."
                )
                // Data-mining finding: high torque intervention correlates with MAF/MLHFM underscaling,
                // not KFMIOP miscalibration alone. Empirical data from 4+ years of real tuning shows
                // MLHFM is the most changed map in high-intervention versions.
                if (pct > 5.0) {
                    warnings.add(
                        "INFO: MAF Scaling Check: >${String.format("%.0f", pct)}% torque intervention often " +
                            "indicates MAF/MLHFM underscaling rather than KFMIOP miscalibration. " +
                            "An underscaled MAF causes actual torque (mibas) to read low, widening the gap " +
                            "between requested and actual torque. Verify MLHFM scaling before adjusting KFMIOP."
                    )
                }
            }
        }

        val boostMissing = wotEntries.filter { e -> e.actualMap < e.requestedMap - 50 }
        if (boostMissing.size > wotEntries.size * 0.5) {
            val avgError = boostMissing.map { it.requestedMap - it.actualMap }.average()
            warnings.add(
                "Boost Target Not Reached: Actual pressure is below requested pressure by an average of " +
                    "${avgError.format()} mbar in ${String.format("%.1f", boostMissing.size.toDouble() / wotEntries.size * 100)}% " +
                    "of WOT samples. This may indicate a mechanical limitation (turbo spool, wastegate, boost leak) " +
                    "or incorrect KFLDRL base duty cycle."
            )
        }

        return warnings
    }

    // ── Chart data ────────────────────────────────────────────────────

    fun computePressureErrors(wotEntries: List<WotLogEntry>): List<Pair<Double, Double>> {
        return wotEntries.map { e -> Pair(e.rpm, e.requestedMap - e.actualMap) }
    }

    fun computeLoadErrors(wotEntries: List<WotLogEntry>): List<Pair<Double, Double>> {
        return wotEntries.filter { it.actualLoad > 0 }
            .map { e -> Pair(e.rpm, e.requestedLoad / e.actualLoad) }
    }

    // ── Per-RPM Breakpoint Analysis ───────────────────────────────────

    /**
     * Build per-RPM analysis for each chain link.
     */
    fun buildPerRpmAnalysis(
        simulationResults: List<Me7Simulator.SimulationResult>,
        rpmBreakpoints: Array<Double>?,
        rpmTolerance: Double = 250.0
    ): Map<String, List<RpmBreakpointAnalysis>> {
        if (simulationResults.isEmpty()) return emptyMap()

        // If no breakpoints provided, derive from data (500 RPM bins)
        val breakpoints = rpmBreakpoints ?: run {
            val minRpm = simulationResults.minOf { it.rpm }
            val maxRpm = simulationResults.maxOf { it.rpm }
            val step = 500.0
            val list = mutableListOf<Double>()
            var rpm = (minRpm / step).toInt() * step
            while (rpm <= maxRpm + step) {
                list.add(rpm)
                rpm += step
            }
            list.toTypedArray()
        }

        val link1 = mutableListOf<RpmBreakpointAnalysis>()
        val link2 = mutableListOf<RpmBreakpointAnalysis>()
        val link3 = mutableListOf<RpmBreakpointAnalysis>()
        val link4 = mutableListOf<RpmBreakpointAnalysis>()

        for (bp in breakpoints) {
            val nearBy = simulationResults.filter { abs(it.rpm - bp) < rpmTolerance }
            if (nearBy.isEmpty()) continue
            val count = nearBy.size
            val confidence = when {
                count < 5 -> MapDelta.Confidence.LOW
                count <= 20 -> MapDelta.Confidence.MEDIUM
                else -> MapDelta.Confidence.HIGH
            }

            // Link 1: torque headroom
            link1.add(RpmBreakpointAnalysis(
                rpm = bp,
                sampleCount = count,
                avgError = nearBy.map { it.torqueHeadroom }.average(),
                maxError = nearBy.maxOf { it.torqueHeadroom },
                correction = nearBy.count { it.torqueLimited }.toDouble() / count * 100,
                confidence = confidence
            ))

            // Link 2: pssol error
            link2.add(RpmBreakpointAnalysis(
                rpm = bp,
                sampleCount = count,
                avgError = nearBy.map { it.pssolError }.average(),
                maxError = nearBy.maxOf { abs(it.pssolError) },
                correction = nearBy.map { it.pssolError }.average(),
                confidence = confidence
            ))

            // Link 3: boost error
            link3.add(RpmBreakpointAnalysis(
                rpm = bp,
                sampleCount = count,
                avgError = nearBy.map { it.boostError }.average(),
                maxError = nearBy.maxOf { it.boostError },
                correction = nearBy.map { it.kfldrlCorrection }.average(),
                confidence = confidence
            ))

            // Link 4: VE mismatch
            link4.add(RpmBreakpointAnalysis(
                rpm = bp,
                sampleCount = count,
                avgError = nearBy.map { (it.kfpbrkCorrectionFactor - 1.0) * 100 }.average(),
                maxError = nearBy.maxOf { abs(it.kfpbrkCorrectionFactor - 1.0) * 100 },
                correction = nearBy.map { it.kfpbrkCorrectionFactor }.average(),
                confidence = confidence
            ))
        }

        return mapOf(
            "Link 1" to link1,
            "Link 2" to link2,
            "Link 3" to link3,
            "Link 4" to link4
        )
    }

    // ── Prediction Engine ─────────────────────────────────────────────

    /**
     * Predict what logs will look like after applying the suggested corrections.
     *
     * For each WOT entry:
     * - Estimate corrected pvdks from KFLDRL correction
     * - Estimate corrected rl_w from KFPBRK correction
     * - Re-run chain diagnosis on predicted values
     */
    fun predictOutcome(
        wotEntries: List<WotLogEntry>,
        simulationResults: List<Me7Simulator.SimulationResult>,
        suggestedMaps: SuggestedMaps,
        calibration: Me7Simulator.CalibrationSet,
        kfurl: Double
    ): PredictionResult {
        if (wotEntries.isEmpty() || simulationResults.isEmpty()) {
            return PredictionResult(
                predictedPressureSeries = emptyList(),
                predictedLoadSeries = emptyList(),
                currentAvgLoadDeficit = 0.0,
                predictedAvgLoadDeficit = 0.0,
                currentAvgPressureError = 0.0,
                predictedAvgPressureError = 0.0,
                predictedChainHealth = ChainDiagnosis(),
                convergenceImprovement = 0.0
            )
        }

        val predictedPressure = mutableListOf<Pair<Double, Double>>()
        val predictedLoad = mutableListOf<Pair<Double, Double>>()
        val predictedResults = mutableListOf<Me7Simulator.SimulationResult>()

        for (i in simulationResults.indices) {
            val sim = simulationResults[i]
            val entry = wotEntries[i]

            // Estimate corrected pvdks:
            // If KFLDRL correction says we need +X% WGDC, and we're providing it,
            // assume the boost error gets proportionally reduced.
            // Conservative: reduce boost error by 80% (not 100% due to PID dynamics)
            val boostImprovement = if (suggestedMaps.kfldrl != null) 0.80 else 0.0
            val correctedBoostError = sim.boostError * (1.0 - boostImprovement)
            val predictedPvdks = sim.actualPssol - correctedBoostError

            // Estimate corrected rl_w:
            // If KFPBRK correction factor is X, after correction it should be ~1.0
            val veImprovement = if (suggestedMaps.kfpbrk != null) 0.85 else 0.0
            val correctedVeError = (sim.kfpbrkCorrectionFactor - 1.0) * (1.0 - veImprovement)
            val predictedRl = if (sim.simulatedRlFromPressure > 0) {
                // Compute what rl_w would be at the predicted pressure with corrected VE
                val op = Me7Simulator.OperatingPoint(rpm = entry.rpm, barometricPressure = entry.barometricPressure)
                val rlAtPredictedPressure = Me7Simulator.computeRlFromPressure(
                    pressure = predictedPvdks, op = op, kfurl = kfurl,
                    previousPressure = entry.barometricPressure
                )
                rlAtPredictedPressure * (1.0 + correctedVeError)
            } else sim.actualRl

            predictedPressure.add(Pair(entry.rpm, predictedPvdks))
            predictedLoad.add(Pair(entry.rpm, predictedRl))

            // Build a predicted simulation result for chain diagnosis
            val predTorqueLimited = entry.requestedLoad < calibration.ldrxn * 0.95
                && entry.rpm >= 3500.0
            val predPssolError = sim.pssolError // PLSOL model doesn't change
            val predBoostError = correctedBoostError
            val predKfpbrkCorr = 1.0 + correctedVeError

            predictedResults.add(sim.copy(
                actualPvdks = predictedPvdks,
                boostError = predBoostError,
                actualRl = predictedRl,
                kfpbrkCorrectionFactor = predKfpbrkCorr,
                dominantError = Me7Simulator.diagnoseChain(predTorqueLimited, predPssolError, predBoostError, predKfpbrkCorr),
                totalLoadDeficit = calibration.ldrxn - predictedRl
            ))
        }

        val currentAvgLoadDeficit = simulationResults.map { it.totalLoadDeficit }.average()
        val predictedAvgLoadDeficit = predictedResults.map { it.totalLoadDeficit }.average()
        val currentAvgPressureError = simulationResults.map { it.boostError }.average()
        val predictedAvgPressureError = predictedResults.map { it.boostError }.average()

        val predictedChainHealth = buildChainDiagnosis(predictedResults, calibration.ldrxn, kfurl)

        val currentTotalError = abs(currentAvgLoadDeficit) + abs(currentAvgPressureError) / 10.0
        val predictedTotalError = abs(predictedAvgLoadDeficit) + abs(predictedAvgPressureError) / 10.0
        val improvement = if (currentTotalError > 0) {
            (1.0 - predictedTotalError / currentTotalError) * 100.0
        } else 0.0

        return PredictionResult(
            predictedPressureSeries = predictedPressure,
            predictedLoadSeries = predictedLoad,
            currentAvgLoadDeficit = currentAvgLoadDeficit,
            predictedAvgLoadDeficit = predictedAvgLoadDeficit,
            currentAvgPressureError = currentAvgPressureError,
            predictedAvgPressureError = predictedAvgPressureError,
            predictedChainHealth = predictedChainHealth,
            convergenceImprovement = improvement.coerceIn(0.0, 100.0)
        )
    }

    /**
     * Simplified MED17 prediction: boost-only.
     *
     * For each WOT entry, estimates the boost error reduction if the suggested
     * KFLDRL is applied. No VE model simulation — only boost control chain.
     * Conservative 80% improvement factor accounts for PID dynamics.
     */
    fun predictMed17Outcome(
        wotEntries: List<WotLogEntry>,
        syntheticSimResults: List<Me7Simulator.SimulationResult>,
        suggestedMaps: SuggestedMaps,
        ldrxnTarget: Double,
        toleranceMbar: Double
    ): PredictionResult? {
        if (wotEntries.isEmpty() || suggestedMaps.kfldrl == null) return null

        val boostImprovement = 0.80
        val predictedPressure = mutableListOf<Pair<Double, Double>>()
        val predictedLoad = mutableListOf<Pair<Double, Double>>()
        val predictedSimResults = mutableListOf<Me7Simulator.SimulationResult>()

        for (i in wotEntries.indices) {
            val entry = wotEntries[i]
            val sim = syntheticSimResults[i]

            // Boost error: requested - actual pressure
            val boostError = maxOf(entry.requestedMap - entry.actualMap, 0.0)
            val correctedBoostError = boostError * (1.0 - boostImprovement)
            val predictedPvdks = entry.actualMap + (boostError - correctedBoostError)

            predictedPressure.add(Pair(entry.rpm, predictedPvdks))
            // Load prediction: no VE model, so keep actual load but note improvement
            predictedLoad.add(Pair(entry.rpm, entry.actualLoad))

            val isCapped = entry.rpm >= 3500.0 && entry.requestedLoad < ldrxnTarget * 0.95
            predictedSimResults.add(sim.copy(
                actualPvdks = predictedPvdks,
                boostError = correctedBoostError,
                dominantError = when {
                    isCapped -> Me7Simulator.ErrorSource.TORQUE_CAPPED
                    correctedBoostError > toleranceMbar -> Me7Simulator.ErrorSource.BOOST_SHORTFALL
                    else -> Me7Simulator.ErrorSource.ON_TARGET
                },
                totalLoadDeficit = if (isCapped) ldrxnTarget - entry.requestedLoad else 0.0
            ))
        }

        val currentAvgPressureError = syntheticSimResults.map { it.boostError }.average()
        val predictedAvgPressureError = predictedSimResults.map { it.boostError }.average()
        // Load deficit stays the same for MED17 (no VE model to correct)
        val currentAvgLoadDeficit = syntheticSimResults.map { it.totalLoadDeficit }.average()
        val predictedAvgLoadDeficit = currentAvgLoadDeficit

        val predictedChainHealth = buildMed17ChainDiagnosis(
            wotEntries.mapIndexed { idx, entry ->
                // Create synthetic entries with predicted pressure for chain diagnosis
                entry.copy(actualMap = predictedPressure[idx].second)
            },
            ldrxnTarget, toleranceMbar
        )

        val currentTotalError = abs(currentAvgPressureError)
        val predictedTotalError = abs(predictedAvgPressureError)
        val improvement = if (currentTotalError > 0) {
            (1.0 - predictedTotalError / currentTotalError) * 100.0
        } else 0.0

        return PredictionResult(
            predictedPressureSeries = predictedPressure,
            predictedLoadSeries = predictedLoad,
            currentAvgLoadDeficit = currentAvgLoadDeficit,
            predictedAvgLoadDeficit = predictedAvgLoadDeficit,
            currentAvgPressureError = currentAvgPressureError,
            predictedAvgPressureError = predictedAvgPressureError,
            predictedChainHealth = predictedChainHealth,
            convergenceImprovement = improvement.coerceIn(0.0, 100.0)
        )
    }

    // ── Chain Diagnosis ──────────────────────────────────────────────

    data class ChainDiagnosis(
        val torqueCappedPercent: Double = 0.0,
        val pssolErrorPercent: Double = 0.0,
        val boostShortfallPercent: Double = 0.0,
        val veMismatchPercent: Double = 0.0,
        val onTargetPercent: Double = 0.0,
        val dominantError: Me7Simulator.ErrorSource = Me7Simulator.ErrorSource.ON_TARGET,
        val avgTotalLoadDeficit: Double = 0.0,
        val recommendations: List<String> = emptyList()
    )

    fun buildChainDiagnosis(
        simulationResults: List<Me7Simulator.SimulationResult>,
        ldrxnTarget: Double,
        kfurl: Double
    ): ChainDiagnosis {
        if (simulationResults.isEmpty()) return ChainDiagnosis()

        val total = simulationResults.size.toDouble()

        val torqueCapped = simulationResults.count { it.dominantError == Me7Simulator.ErrorSource.TORQUE_CAPPED }
        val pssolWrong = simulationResults.count { it.dominantError == Me7Simulator.ErrorSource.PSSOL_WRONG }
        val boostShort = simulationResults.count { it.dominantError == Me7Simulator.ErrorSource.BOOST_SHORTFALL }
        val veMismatch = simulationResults.count { it.dominantError == Me7Simulator.ErrorSource.VE_MISMATCH }
        val onTarget = simulationResults.count { it.dominantError == Me7Simulator.ErrorSource.ON_TARGET }

        val torquePct = torqueCapped / total * 100
        val pssolPct = pssolWrong / total * 100
        val boostPct = boostShort / total * 100
        val vePct = veMismatch / total * 100
        val okPct = onTarget / total * 100

        val errorCounts = mapOf(
            Me7Simulator.ErrorSource.TORQUE_CAPPED to torqueCapped,
            Me7Simulator.ErrorSource.PSSOL_WRONG to pssolWrong,
            Me7Simulator.ErrorSource.BOOST_SHORTFALL to boostShort,
            Me7Simulator.ErrorSource.VE_MISMATCH to veMismatch
        )
        val dominant = if (onTarget == simulationResults.size) {
            Me7Simulator.ErrorSource.ON_TARGET
        } else {
            errorCounts.maxByOrNull { it.value }?.key ?: Me7Simulator.ErrorSource.ON_TARGET
        }

        val avgDeficit = simulationResults.map { it.totalLoadDeficit }.average()

        val recs = mutableListOf<String>()

        // Count samples in the low-RPM torque ramp region for context
        val lowRpmSamples = simulationResults.count { it.rpm < 3500.0 }
        val lowRpmBelowLdrxn = simulationResults.count {
            it.rpm < 3500.0 && it.rlsol < ldrxnTarget * 0.95
        }

        if (torquePct > 20) {
            val torqueLimitedResults = simulationResults.filter { it.torqueLimited }
            val avgHeadroom = if (torqueLimitedResults.isNotEmpty()) {
                torqueLimitedResults.map { it.torqueHeadroom }.average()
            } else 0.0
            recs.add(
                "WARNING: Torque structure is capping load in ${String.format("%.0f", torquePct)}% of WOT samples (above 3500 RPM). " +
                    "rlsol averages ${String.format("%.1f", avgHeadroom)}% below LDRXN (${ldrxnTarget.format()}%). " +
                    "Increase KFMIOP/KFMIRL ranges to support the target load."
            )
        }

        // Add context about low-RPM torque ramp if significant
        if (lowRpmBelowLdrxn > 0 && lowRpmSamples > 0) {
            val lowRpmPct = lowRpmBelowLdrxn.toDouble() / total * 100
            recs.add(
                "INFO: ${lowRpmBelowLdrxn} samples (${String.format("%.0f", lowRpmPct)}%) are below 3500 RPM where " +
                    "rlsol < LDRXN is expected (KFMIOP torque ramp limits load at low RPM). " +
                    "This is normal ECU behavior, not a calibration issue."
            )
        }

        if (boostPct > 10) {
            val avgBoostError = simulationResults.filter { it.boostError > 0 }.map { it.boostError }.average()
            recs.add(
                "BOOST: Boost shortfall in ${String.format("%.0f", boostPct)}% of WOT samples. " +
                    "Actual pressure falls short of pssol by avg ${String.format("%.0f", avgBoostError)} mbar. " +
                    "Apply suggested KFLDRL corrections to increase base duty cycle."
            )
        }

        if (vePct > 10) {
            val avgVeError = simulationResults.filter { abs(it.kfpbrkCorrectionFactor - 1.0) > 0.01 }
                .map { (it.kfpbrkCorrectionFactor - 1.0) * 100 }.average()
            recs.add(
                "VE: VE model mismatch in ${String.format("%.0f", vePct)}% of WOT samples. " +
                    "KFPBRK is off by avg ${String.format("%+.1f", avgVeError)}%. " +
                    "Apply suggested KFPBRK corrections so the ECU correctly converts pressure to load."
            )
        }

        if (pssolPct > 10) {
            val avgPssolError = simulationResults.filter { abs(it.pssolError) > 10 }.map { it.pssolError }.average()
            recs.add(
                "ERROR: PLSOL model predicts wrong pressure in ${String.format("%.0f", pssolPct)}% of WOT samples. " +
                    "Simulated pssol deviates from logged pssol by avg ${String.format("%+.0f", avgPssolError)} mbar. " +
                    "Check KFURL value (currently ${String.format("%.4f", kfurl)}) — it may not match your engine's VE."
            )
        }

        if (recs.isEmpty() && okPct > 80) {
            recs.add(
                "OK: Signal chain is healthy: ${String.format("%.0f", okPct)}% of WOT samples are on-target. " +
                    "LDRXN=${ldrxnTarget.format()}% is being achieved."
            )
        }

        return ChainDiagnosis(
            torqueCappedPercent = torquePct,
            pssolErrorPercent = pssolPct,
            boostShortfallPercent = boostPct,
            veMismatchPercent = vePct,
            onTargetPercent = okPct,
            dominantError = dominant,
            avgTotalLoadDeficit = avgDeficit,
            recommendations = recs
        )
    }

    /**
     * MED17-specific chain diagnosis. Unlike [buildChainDiagnosis] which uses mutually
     * exclusive SimulationResult.dominantError counts, this computes percentages
     * independently from the raw WOT entries. Torque-cap and boost-shortfall conditions
     * can overlap (an entry can be both capped AND short on boost).
     *
     * MED17 always reports pssolErrorPercent = 0 and veMismatchPercent = 0 because
     * MED17 uses adaptive fupsrl_w / pbrints_w, not static KFPBRK / KFURL.
     */
    fun buildMed17ChainDiagnosis(
        wotEntries: List<WotLogEntry>,
        ldrxnTarget: Double,
        toleranceMbar: Double
    ): ChainDiagnosis {
        val med17InfoRec = "INFO: MED17 mode — PSSOL and VE model diagnostics are skipped. " +
            "MED17 uses adaptive fupsrl_w/pbrints_w, not static KFPBRK/KFURL."

        if (wotEntries.isEmpty()) return ChainDiagnosis(
            recommendations = listOf(med17InfoRec)
        )

        val total = wotEntries.size.toDouble()

        val torqueCapped = wotEntries.count {
            it.rpm >= 3500.0 && it.requestedLoad < ldrxnTarget * 0.95
        }
        val boostShort = wotEntries.count {
            abs(it.requestedMap - it.actualMap) > toleranceMbar
        }

        val torquePct = torqueCapped / total * 100
        val boostPct = boostShort / total * 100
        val okPct = maxOf(100.0 - torquePct - boostPct, 0.0)

        val dominant = when {
            torquePct > boostPct && torquePct > 20 -> Me7Simulator.ErrorSource.TORQUE_CAPPED
            boostPct > torquePct && boostPct > 10 -> Me7Simulator.ErrorSource.BOOST_SHORTFALL
            torquePct > 20 -> Me7Simulator.ErrorSource.TORQUE_CAPPED
            boostPct > 10 -> Me7Simulator.ErrorSource.BOOST_SHORTFALL
            else -> Me7Simulator.ErrorSource.ON_TARGET
        }

        val avgDeficit = wotEntries.map { ldrxnTarget - it.actualLoad }.average()

        val recs = mutableListOf<String>()

        val lowRpmBelowLdrxn = wotEntries.count {
            it.rpm < 3500.0 && it.requestedLoad < ldrxnTarget * 0.95
        }

        if (torquePct > 20) {
            val torqueLimitedEntries = wotEntries.filter {
                it.rpm >= 3500.0 && it.requestedLoad < ldrxnTarget * 0.95
            }
            val avgHeadroom = if (torqueLimitedEntries.isNotEmpty()) {
                torqueLimitedEntries.map { ldrxnTarget - it.requestedLoad }.average()
            } else 0.0

            // H1: KFLDHBN vs KFMIOP differentiation
            // FR: rlmax_w = min(rlmxko_w, ldrlts_w, ldrlms_w)
            // When pvdxs_w (max allowed pressure) ≈ pvds_w (requested pressure), the
            // boost ceiling (KFLDHBN/BGRLMXS) is limiting, not the torque structure.
            val ceilingLimitedEntries = torqueLimitedEntries.filter { it.boostCeilingLimiting }
            val ceilingPct = if (torqueLimitedEntries.isNotEmpty()) {
                ceilingLimitedEntries.size.toDouble() / torqueLimitedEntries.size * 100
            } else 0.0

            if (ceilingPct > 50) {
                recs.add(
                    "WARNING: Boost ceiling (BGRLMXS/KFLDHBN) is limiting load in ${String.format("%.0f", torquePct)}% " +
                        "of WOT samples. pvdxs_w (max allowed pressure) matches pvds_w in ${String.format("%.0f", ceilingPct)}% " +
                        "of torque-limited entries. The load cap is NOT from KFMIOP/KFMIRL — it is the " +
                        "pressure ratio ceiling (BGRLMXS on MED17, KFLDHBN on ME7). " +
                        "Increase the pressure limit or check turbo protection settings."
                )
            } else {
                recs.add(
                    "WARNING: Torque structure is capping load in ${String.format("%.0f", torquePct)}% of WOT samples (above 3500 RPM). " +
                        "rlsol averages ${String.format("%.1f", avgHeadroom)}% below LDRXN (${ldrxnTarget.format()}%). " +
                        "Increase KFMIOP/KFMIRL ranges to support the target load."
                )
            }
        }

        if (lowRpmBelowLdrxn > 0) {
            val lowRpmPct = lowRpmBelowLdrxn / total * 100
            recs.add(
                "INFO: ${lowRpmBelowLdrxn} samples (${String.format("%.0f", lowRpmPct)}%) are below 3500 RPM where " +
                    "rlsol < LDRXN is expected (KFMIOP torque ramp limits load at low RPM). " +
                    "This is normal ECU behavior, not a calibration issue."
            )
        }

        if (boostPct > 10) {
            val boostErrors = wotEntries
                .filter { abs(it.requestedMap - it.actualMap) > toleranceMbar }
                .map { maxOf(it.requestedMap - it.actualMap, 0.0) }
            val avgBoostError = if (boostErrors.isNotEmpty()) boostErrors.average() else 0.0
            recs.add(
                "BOOST: Boost shortfall in ${String.format("%.0f", boostPct)}% of WOT samples. " +
                    "Actual pressure falls short of pssol by avg ${String.format("%.0f", avgBoostError)} mbar. " +
                    "Apply suggested KFLDRL corrections to increase base duty cycle."
            )
        }

        if (recs.isEmpty() && okPct > 80) {
            recs.add(
                "OK: Signal chain is healthy: ${String.format("%.0f", okPct)}% of WOT samples are on-target. " +
                    "LDRXN=${ldrxnTarget.format()}% is being achieved."
            )
        }

        recs.add(med17InfoRec)

        return ChainDiagnosis(
            torqueCappedPercent = torquePct,
            pssolErrorPercent = 0.0,
            boostShortfallPercent = boostPct,
            veMismatchPercent = 0.0,
            onTargetPercent = okPct,
            dominantError = dominant,
            avgTotalLoadDeficit = avgDeficit,
            recommendations = recs
        )
    }

    // ── Top-level entry point ─────────────────────────────────────────

    fun analyze(
        values: Map<Me7LogFileContract.Header, List<Double>>,
        kfldrlMap: Map3d?,
        kfldimxMap: Map3d?,
        kfpbrkMap: Map3d?,
        kfmiopMap: Map3d? = null,
        kfmirlMap: Map3d? = null,
        ldrxnTarget: Double = 191.0,
        toleranceMbar: Double = 30.0,
        minThrottleAngle: Double = 80.0,
        kfldimxOverheadPercent: Double = 8.0,
        kfurl: Double = 0.106,
        kfurlMap: Map3d? = null,        // Finding 2: RPM-dependent KFURL Kennlinie
        logSummaries: List<LogSummary> = emptyList(),
        // v4: PID gain maps (optional)
        kfldrq0Map: Map3d? = null,
        kfldrq1Map: Map3d? = null,
        kfldrq2Map: Map3d? = null,
        ldrq0dy: Double = 1.0
    ): OptimizerResult {
        val filteredData = filterWotEntriesWithOptionalData(values, minThrottleAngle)
        val wotEntries = filteredData.wotEntries

        // ── Simulation ───────────────────────────────────────────
        val calibration = Me7Simulator.CalibrationSet(
            kfpbrk = kfpbrkMap,
            kfldrl = kfldrlMap,
            kfldimx = kfldimxMap,
            kfurl = kfurl,
            kfurlMap = kfurlMap,    // Finding 2: RPM-dependent KFURL
            ldrxn = ldrxnTarget
        )

        val simulationResults = Me7Simulator.simulateAll(wotEntries, calibration)

        // ── Mechanical limit detection ───────────────────────────
        val mechanicalLimits = MechanicalLimitDetector.detect(
            wotEntries = wotEntries,
            mafValues = filteredData.mafValues,
            injectorOnTimes = filteredData.injectorOnTimes,
            rpms = filteredData.wotRpms,
            mafVoltages = filteredData.mafVoltages
        )

        // ── v3: MapDelta-based suggestions ──────────────────────
        val kfldrlDelta = if (kfldrlMap != null) {
            suggestKfldrlDelta(wotEntries, kfldrlMap, toleranceMbar)
        } else null

        val kfldimxDelta = if (kfldrlDelta != null && kfldimxMap != null) {
            suggestKfldimxDelta(wotEntries, kfldrlDelta, kfldimxMap, kfldimxOverheadPercent)
        } else null

        val kfpbrkDelta = if (kfpbrkMap != null) {
            suggestKfpbrkDelta(wotEntries, kfpbrkMap, toleranceMbar, simulationResults = simulationResults)
        } else null

        // ── v3: KFMIOP/KFMIRL suggestions (Link 1) ──────────────
        val kfmiopDelta = if (kfmiopMap != null && simulationResults.any { it.torqueLimited }) {
            suggestKfmiopDelta(simulationResults, kfmiopMap, ldrxnTarget)
        } else null

        val kfmirlDelta = if (kfmiopDelta != null && kfmirlMap != null) {
            suggestKfmirlDelta(kfmiopDelta.suggested, kfmirlMap)
        } else null

        val suggestedMaps = SuggestedMaps(
            kfldrl = kfldrlDelta,
            kfldimx = kfldimxDelta,
            kfpbrk = kfpbrkDelta,
            kfmiop = kfmiopDelta,
            kfmirl = kfmirlDelta
        )

        // ── Legacy Map3d results (backward compat for existing tabs) ──
        val suggestedKfldrl = kfldrlDelta?.suggested
        val suggestedKfldimx = kfldimxDelta?.suggested
        val kfpbrkMultipliers = kfpbrkDelta?.suggested

        val pressureErrors = computePressureErrors(wotEntries)
        val loadErrors = computeLoadErrors(wotEntries)

        // ── Warnings ────────────────────────────────────────────
        val interventionWarnings = checkInterventions(wotEntries, ldrxnTarget)
        val allWarnings = interventionWarnings + mechanicalLimits.warnings

        // ── Simulation chart series ─────────────────────────────
        val simulatedPressureSeries = simulationResults.map { Pair(it.rpm, it.simulatedPssol) }
        val simulatedLoadSeries = simulationResults
            .filter { it.simulatedRlFromPressure > 0 }
            .map { Pair(it.rpm, it.simulatedRlFromPressure) }

        // ── Chain diagnosis ─────────────────────────────────────
        val chainDiagnosis = buildChainDiagnosis(simulationResults, ldrxnTarget, kfurl)

        // ── Per-RPM analysis ────────────────────────────────────
        val rpmBreakpoints = kfldrlMap?.yAxis
        val perRpmAnalysis = buildPerRpmAnalysis(simulationResults, rpmBreakpoints)

        // ── Prediction ──────────────────────────────────────────
        val prediction = if (wotEntries.isNotEmpty() && simulationResults.isNotEmpty()) {
            predictOutcome(wotEntries, simulationResults, suggestedMaps, calibration, kfurl)
        } else null

        // ── v4: Advanced analysis ─────────────────────────────
        // Phase 21: Per-pull segmentation
        val pulls = PullSegmenter.segmentPulls(wotEntries, ldrxnTarget = ldrxnTarget)
        val pullConsistency = PullSegmenter.checkConsistency(pulls)

        // Phase 17: Safety mode detection
        val safetyModes = SafetyModeDetector.detect(wotEntries)

        // Phase 16: Transient event detection
        val transients = TransientDetector.detect(wotEntries, ldrxnTarget)

        // Phase 18: Environmental conditions
        val environmental = EnvironmentalCorrector.analyzeSummary(wotEntries)

        // Phase 19: Throttle body check
        val throttleCheck = ThrottleBodyChecker.check(wotEntries, mechanicalLimits.turboMaxed)

        // Phase 22: KFURL solver
        val kfurlSolverResult = if (wotEntries.size >= 10) {
            KfurlSolver.solve(wotEntries)
        } else null

        // Phase 26: KFPRG solver — find optimal per-RPM residual gas values
        val kfprgSolverResult = if (wotEntries.size >= 10) {
            KfprgSolver.solve(
                wotEntries = wotEntries,
                kfurl = kfurlSolverResult?.optimalKfurl ?: kfurl,
                kfpbrkMap = kfpbrkMap
            )
        } else null

        // Phase 20: Iterative convergence (only if we have enough data and maps)
        val convergenceHistory = if (wotEntries.size >= 20 &&
            (kfldrlMap != null || kfpbrkMap != null)) {
            IterativeConvergence.converge(
                wotEntries = wotEntries,
                initialCalibration = calibration,
                kfldrlMap = kfldrlMap,
                kfldimxMap = kfldimxMap,
                kfpbrkMap = kfpbrkMap,
                kfmiopMap = kfmiopMap,
                kfmirlMap = kfmirlMap,
                ldrxnTarget = ldrxnTarget,
                toleranceMbar = toleranceMbar,
                kfldimxOverheadPercent = kfldimxOverheadPercent
            )
        } else null

        // Phase 15: PID dynamics simulation (on the longest good pull)
        val pidSimulation = if (kfldrq0Map != null || kfldrq1Map != null || kfldrq2Map != null) {
            val bestPull = pulls.filter { it.quality == PullSegmenter.PullQuality.GOOD }
                .maxByOrNull { it.sampleCount }
                ?: pulls.maxByOrNull { it.sampleCount }

            if (bestPull != null && bestPull.sampleCount >= 10) {
                val pullEntries = wotEntries.subList(
                    bestPull.startIdx,
                    (bestPull.endIdx + 1).coerceAtMost(wotEntries.size)
                )
                PidSimulator.simulate(
                    pullEntries = pullEntries,
                    kfldrq0 = kfldrq0Map,
                    kfldrq1 = kfldrq1Map,
                    kfldrq2 = kfldrq2Map,
                    kfldrl = kfldrlMap,
                    kfldimx = kfldimxMap,
                    ldrq0dy = ldrq0dy
                )
            } else null
        } else null

        // ── Collect v4 warnings ───────────────────────────────
        val v4Warnings = mutableListOf<String>()
        v4Warnings.addAll(safetyModes.warnings)
        v4Warnings.addAll(transients.warnings)
        v4Warnings.addAll(environmental.warnings)
        if (throttleCheck.restricted) {
            v4Warnings.add("WARNING: Throttle restriction: ${throttleCheck.detail}")
        }
        if (kfurlSolverResult != null && kfurlSolverResult.errorReductionPercent > 10) {
            v4Warnings.add("INFO: KFURL solver suggests ${String.format("%.4f", kfurlSolverResult.optimalKfurl)} " +
                "(current: $kfurl) — would reduce pssol RMSE by ${String.format("%.0f", kfurlSolverResult.errorReductionPercent)}%")
        }
        // Finding 2: Report per-RPM KFURL improvement if significant
        if (kfurlSolverResult?.perRpmKfurl != null && kfurlSolverResult.perRpmKfurl.improvementPercent > 5) {
            val perRpm = kfurlSolverResult.perRpmKfurl
            val rpmRange = perRpm.rpmValues.joinToString(", ") {
                "${it.first.toInt()}>${String.format("%.4f", it.second)}"
            }
            v4Warnings.add("INFO: RPM-dependent KFURL would improve pssol RMSE by additional " +
                "${String.format("%.1f", perRpm.improvementPercent)}% vs scalar. " +
                "Per-RPM values: $rpmRange (me7-raw.txt line 54368)")
        }
        // Phase 26: KFPRG solver warnings
        if (kfprgSolverResult != null && kfprgSolverResult.errorReductionPercent > 5) {
            v4Warnings.add("INFO: KFPRG solver suggests ${String.format("%.1f", kfprgSolverResult.optimalKfprg)} hPa " +
                "(default: 70.0) — would reduce VE model RMSE by ${String.format("%.0f", kfprgSolverResult.errorReductionPercent)}%")
        }
        if (kfprgSolverResult?.perRpmKfprg != null && kfprgSolverResult.perRpmKfprg.improvementPercent > 5) {
            val perRpm = kfprgSolverResult.perRpmKfprg
            val rpmRange = perRpm.rpmValues.joinToString(", ") {
                "${it.first.toInt()}>${String.format("%.1f", it.second)}"
            }
            v4Warnings.add("INFO: RPM-dependent KFPRG would improve VE model RMSE by additional " +
                "${String.format("%.1f", perRpm.improvementPercent)}% vs scalar. " +
                "Per-RPM values: $rpmRange hPa (me7-raw.txt line 54365)")
        }
        if (convergenceHistory != null && convergenceHistory.diverged) {
            v4Warnings.add("WARNING: Iterative convergence diverged — corrections may be oscillating. Consider reducing LDRXN target.")
        }
        pullConsistency.forEach { (pullIdx, note) ->
            v4Warnings.add("INFO: Pull ${pullIdx + 1}: $note")
        }
        pidSimulation?.diagnosis?.recommendations?.forEach { rec ->
            v4Warnings.add("INFO: PID: $rec")
        }

        // Data-mining-driven warnings (empirical from 324 tune versions, 185 logs)
        v4Warnings.addAll(buildConvergenceGuidance(wotEntries, suggestedMaps))
        v4Warnings.addAll(buildGearCoverageWarnings(wotEntries))

        val allWarningsV4 = allWarnings + v4Warnings

        return OptimizerResult(
            suggestedKfldrl = suggestedKfldrl,
            suggestedKfldimx = suggestedKfldimx,
            kfpbrkMultipliers = kfpbrkMultipliers,
            pressureErrors = pressureErrors,
            loadErrors = loadErrors,
            warnings = allWarningsV4,
            wotEntries = wotEntries,
            simulationResults = simulationResults,
            mechanicalLimits = mechanicalLimits,
            simulatedPressureSeries = simulatedPressureSeries,
            simulatedLoadSeries = simulatedLoadSeries,
            chainDiagnosis = chainDiagnosis,
            suggestedMaps = suggestedMaps,
            perRpmAnalysis = perRpmAnalysis,
            prediction = prediction,
            logSummaries = logSummaries,
            // v4
            pulls = pulls,
            pullConsistency = pullConsistency,
            safetyModes = safetyModes,
            transients = transients,
            environmental = environmental,
            throttleCheck = throttleCheck,
            convergenceHistory = convergenceHistory,
            kfurlSolverResult = kfurlSolverResult,
            pidSimulation = pidSimulation,
            kfprgSolverResult = kfprgSolverResult
        )
    }

    private fun Double.format(): String = String.format("%.2f", this)

    /**
     * MED17 analysis path. Skips ME7-specific components (VE simulator, MAF-based
     * mechanical limit detection, KFPBRK, KfurlSolver, KfprgSolver, iterative
     * convergence) and uses synthetic torque-cap detection instead.
     *
     * MED17 (speed-density, no MAF) can only diagnose:
     * - Link 1: Torque structure capping (KFMIOP/KFMIRL)
     * - Link 3: Boost shortfall (KFLDRL)
     * Links 2 and 4 (VE model via KFURL/KFPBRK) are always 0%.
     */
    fun analyzeMed17(
        values: Map<Me7LogFileContract.Header, List<Double>>,
        kfldrlMap: Map3d?,
        kfldimxMap: Map3d?,
        kfmiopMap: Map3d? = null,
        kfmirlMap: Map3d? = null,
        ldrxnTarget: Double = 191.0,
        toleranceMbar: Double = 30.0,
        minThrottleAngle: Double = 80.0,
        kfldimxOverheadPercent: Double = 8.0,
        logSummaries: List<LogSummary> = emptyList(),
        kfldrq0Map: Map3d? = null,
        kfldrq1Map: Map3d? = null,
        kfldrq2Map: Map3d? = null,
        fupsrlsValues: List<Double>? = null
    ): OptimizerResult {
        val filteredData = filterWotEntriesWithOptionalData(values, minThrottleAngle)
        val wotEntries = filteredData.wotEntries

        // ── KFLDRL / KFLDIMX suggestions (shared with ME7) ──────────
        val kfldrlDelta = if (kfldrlMap != null) {
            suggestKfldrlDelta(wotEntries, kfldrlMap, toleranceMbar)
        } else null

        val kfldimxDelta = if (kfldrlDelta != null && kfldimxMap != null) {
            suggestKfldimxDelta(wotEntries, kfldrlDelta, kfldimxMap, kfldimxOverheadPercent)
        } else null

        // ── Synthetic torque-cap detection (MED17 — no ME7 simulator) ──
        // Detect entries where rlsol < ldrxn*0.95 above 3500 RPM
        val syntheticTorqueLimited = wotEntries.filter {
            it.rpm >= 3500.0 && it.requestedLoad < ldrxnTarget * 0.95
        }

        // Build synthetic SimulationResults for KFMIOP suggestion
        val syntheticSimResults = wotEntries.map { entry ->
            val isCapped = entry.rpm >= 3500.0 && entry.requestedLoad < ldrxnTarget * 0.95
            Me7Simulator.SimulationResult(
                rpm = entry.rpm,
                ldrxnTarget = ldrxnTarget,
                rlsol = entry.requestedLoad,
                torqueLimited = isCapped,
                torqueHeadroom = if (isCapped) ldrxnTarget - entry.requestedLoad else 0.0,
                simulatedPssol = 0.0,
                actualPssol = entry.requestedMap,
                pssolError = 0.0,
                simulatedPlsol = 0.0,
                actualPvdks = entry.actualMap,
                boostError = maxOf(entry.requestedMap - entry.actualMap, 0.0),
                predictedWgdc = 0.0,
                actualWgdc = entry.wgdc,
                kfldrlCorrection = 0.0,
                simulatedRlFromPressure = 0.0,
                actualRl = entry.actualLoad,
                kfpbrkCorrectionFactor = 1.0,
                dominantError = when {
                    isCapped -> Me7Simulator.ErrorSource.TORQUE_CAPPED
                    abs(entry.requestedMap - entry.actualMap) > toleranceMbar -> Me7Simulator.ErrorSource.BOOST_SHORTFALL
                    else -> Me7Simulator.ErrorSource.ON_TARGET
                },
                totalLoadDeficit = if (isCapped) ldrxnTarget - entry.requestedLoad else 0.0
            )
        }

        val kfmiopDelta = if (kfmiopMap != null && syntheticSimResults.any { it.torqueLimited }) {
            suggestKfmiopDelta(syntheticSimResults, kfmiopMap, ldrxnTarget)
        } else null

        val kfmirlDelta = if (kfmiopDelta != null && kfmirlMap != null) {
            suggestKfmirlDelta(kfmiopDelta.suggested, kfmirlMap)
        } else null

        val suggestedMaps = SuggestedMaps(
            kfldrl = kfldrlDelta,
            kfldimx = kfldimxDelta,
            kfpbrk = null,
            kfmiop = kfmiopDelta,
            kfmirl = kfmirlDelta
        )

        val suggestedKfldrl = kfldrlDelta?.suggested
        val suggestedKfldimx = kfldimxDelta?.suggested

        val pressureErrors = computePressureErrors(wotEntries)
        val loadErrors = computeLoadErrors(wotEntries)

        // ── Simplified chain diagnosis (2-link: torque + boost only) ─
        val chainDiagnosis = buildMed17ChainDiagnosis(wotEntries, ldrxnTarget, toleranceMbar)

        // ── Per-RPM analysis ────────────────────────────────────────
        val rpmBreakpoints = kfldrlMap?.yAxis
        val perRpmAnalysis = buildPerRpmAnalysis(syntheticSimResults, rpmBreakpoints)

        // ── Warnings ────────────────────────────────────────────────
        val interventionWarnings = checkInterventions(wotEntries, ldrxnTarget)

        // ── v4: Advanced analysis ─────────────────────────────────
        val pulls = PullSegmenter.segmentPulls(wotEntries, ldrxnTarget = ldrxnTarget)
        val pullConsistency = PullSegmenter.checkConsistency(pulls)
        val safetyModes = SafetyModeDetector.detect(wotEntries)
        val transients = TransientDetector.detect(wotEntries, ldrxnTarget)
        val environmental = EnvironmentalCorrector.analyzeSummary(wotEntries)
        // ── Mechanical limit detection (turbo/MAP checks — no MAF data) ──
        val mechanicalLimits = MechanicalLimitDetector.detect(
            wotEntries = wotEntries,
            mafValues = null,
            injectorOnTimes = null,
            rpms = wotEntries.map { it.rpm },
            mafVoltages = null
        )
        val throttleCheck = ThrottleBodyChecker.check(wotEntries, turboMaxed = mechanicalLimits.turboMaxed)

        // PID dynamics (if gain maps available)
        val pidSimulation = if (kfldrq0Map != null || kfldrq1Map != null || kfldrq2Map != null) {
            val bestPull = pulls.filter { it.quality == PullSegmenter.PullQuality.GOOD }
                .maxByOrNull { it.sampleCount }
                ?: pulls.maxByOrNull { it.sampleCount }

            if (bestPull != null && bestPull.sampleCount >= 10) {
                val pullEntries = wotEntries.subList(
                    bestPull.startIdx,
                    (bestPull.endIdx + 1).coerceAtMost(wotEntries.size)
                )
                PidSimulator.simulate(
                    pullEntries = pullEntries,
                    kfldrq0 = kfldrq0Map,
                    kfldrq1 = kfldrq1Map,
                    kfldrq2 = kfldrq2Map,
                    kfldrl = kfldrlMap,
                    kfldimx = kfldimxMap
                )
            } else null
        } else null

        // ── Collect warnings ────────────────────────────────────────
        val v4Warnings = mutableListOf<String>()
        v4Warnings.addAll(mechanicalLimits.warnings)
        v4Warnings.addAll(safetyModes.warnings)
        v4Warnings.addAll(transients.warnings)
        v4Warnings.addAll(environmental.warnings)
        if (throttleCheck.restricted) {
            v4Warnings.add("WARNING: Throttle restriction: ${throttleCheck.detail}")
        }
        pullConsistency.forEach { (pullIdx, note) ->
            v4Warnings.add("INFO: Pull ${pullIdx + 1}: $note")
        }
        pidSimulation?.diagnosis?.recommendations?.forEach { rec ->
            v4Warnings.add("INFO: PID: $rec")
        }

        // fupsrls_w convergence check
        v4Warnings.addAll(checkFupsrlsConvergence(fupsrlsValues))

        // Altitude PID gain warning
        if (pidSimulation != null && environmental.avgBaroPressure < 950.0) {
            val altitudeM = (1 - Math.pow(environmental.avgBaroPressure / 1013.25, 0.190284)) * 44330
            v4Warnings.add("INFO: PID: High altitude (~${String.format("%.0f", altitudeM)}m, baro ${String.format("%.0f", environmental.avgBaroPressure)} mbar): " +
                "PID gains may need altitude adjustment. Consider KFLDRQ0H/Q1H/Q2H if available in your ECU.")
        }

        // Data-mining-driven warnings (empirical from 324 tune versions, 185 logs)
        v4Warnings.addAll(buildConvergenceGuidance(wotEntries, suggestedMaps))
        v4Warnings.addAll(buildGearCoverageWarnings(wotEntries))

        val allWarnings = interventionWarnings + v4Warnings

        // ── MED17 Simplified Prediction (boost-only) ────────────────
        val prediction = predictMed17Outcome(
            wotEntries, syntheticSimResults, suggestedMaps,
            ldrxnTarget, toleranceMbar
        )

        return OptimizerResult(
            suggestedKfldrl = suggestedKfldrl,
            suggestedKfldimx = suggestedKfldimx,
            kfpbrkMultipliers = null,
            pressureErrors = pressureErrors,
            loadErrors = loadErrors,
            warnings = allWarnings,
            wotEntries = wotEntries,
            simulationResults = syntheticSimResults,
            mechanicalLimits = mechanicalLimits,
            simulatedPressureSeries = emptyList(),
            simulatedLoadSeries = emptyList(),
            chainDiagnosis = chainDiagnosis,
            suggestedMaps = suggestedMaps,
            perRpmAnalysis = perRpmAnalysis,
            prediction = prediction,
            logSummaries = logSummaries,
            pulls = pulls,
            pullConsistency = pullConsistency,
            safetyModes = safetyModes,
            transients = transients,
            environmental = environmental,
            throttleCheck = throttleCheck,
            convergenceHistory = null,
            kfurlSolverResult = null,
            pidSimulation = pidSimulation,
            kfprgSolverResult = null
        )
    }

    // ── Data-mining-driven diagnostics ─────────────────────────────────

    /**
     * Empirical finding: Only 37% of logs achieve <50 mbar boost error.
     * Typical convergence requires 3–5 KFLDRL iterations.
     * Warn the user when persistent error suggests more iterations are needed.
     */
    fun buildConvergenceGuidance(
        wotEntries: List<WotLogEntry>,
        suggestedMaps: SuggestedMaps
    ): List<String> {
        if (wotEntries.isEmpty()) return emptyList()
        val warnings = mutableListOf<String>()

        val boostErrors = wotEntries.map { abs(it.requestedMap - it.actualMap) }
        val avgError = boostErrors.average()
        val trackingPct = wotEntries.count { it.isTracking(50.0) }.toDouble() / wotEntries.size * 100

        if (avgError > 100 && suggestedMaps.kfldrl != null) {
            warnings.add(
                "INFO: Convergence Guidance: Average boost error is ${String.format("%.0f", avgError)} mbar. " +
                    "Empirical data shows 3–5 KFLDRL iterations are typical to reach <50 mbar error, " +
                    "with each iteration reducing error by ~40 mbar on average. " +
                    "Only ${String.format("%.0f", trackingPct)}% of WOT samples are on-target (tracking)."
            )
        }

        // Warn when KFLDRL is suggested but KFLDIMX is not changing
        if (suggestedMaps.kfldrl != null && suggestedMaps.kfldimx != null) {
            val imxMaxDelta = suggestedMaps.kfldimx!!.let { delta ->
                delta.suggested.zAxis.zip(delta.current.zAxis).maxOfOrNull { (sRow, oRow) ->
                    sRow.zip(oRow).maxOfOrNull { abs(it.first - it.second) } ?: 0.0
                } ?: 0.0
            }
            if (imxMaxDelta < 1.0) {
                warnings.add(
                    "INFO: KFLDIMX Reminder: KFLDRL is changing but KFLDIMX shows minimal delta. " +
                        "Empirical data shows 62% of tuners neglect KFLDIMX when adjusting KFLDRL. " +
                        "KFLDIMX (I-limiter) must follow KFLDRL for stable steady-state boost."
                )
            }
        }

        return warnings
    }

    /**
     * Empirical finding: 3rd gear is 85%+ of WOT data; 2nd/4th have ~70% more boost error.
     * Warn when log data is gear-biased or has insufficient gear coverage.
     */
    fun buildGearCoverageWarnings(wotEntries: List<WotLogEntry>): List<String> {
        val gearEntries = wotEntries.filter { it.gear != null && it.gear in 1..6 }
        if (gearEntries.size < 20) return emptyList()

        val warnings = mutableListOf<String>()
        val gearCounts = gearEntries.groupBy { it.gear!! }
        val uniqueGears = gearCounts.keys
        val dominantGear = gearCounts.maxByOrNull { it.value.size }

        if (uniqueGears.size < 2) {
            warnings.add(
                "INFO: Gear Coverage: All ${gearEntries.size} WOT samples are in gear ${uniqueGears.first()}. " +
                    "Boost behavior varies significantly by gear — consider logging in at least 2 gears " +
                    "(3rd + 4th recommended) for better KFLDRL calibration across the RPM range."
            )
        } else if (dominantGear != null) {
            val dominantPct = dominantGear.value.size.toDouble() / gearEntries.size * 100
            if (dominantPct > 90) {
                val gearList = gearCounts.entries.sortedByDescending { it.value.size }
                    .joinToString(", ") { "G${it.key}=${it.value.size}" }
                warnings.add(
                    "INFO: Gear Bias: ${String.format("%.0f", dominantPct)}% of WOT samples are in gear ${dominantGear.key} ($gearList). " +
                        "Boost error tends to be higher in underrepresented gears due to different load profiles."
                )
            }
        }

        return warnings
    }

    /**
     * Check fupsrls_w (live VE correction factor) for convergence.
     * High variance indicates the adaptive VE model hasn't settled, making
     * all pressure→load conversions unreliable.
     */
    private fun checkFupsrlsConvergence(fupsrlsValues: List<Double>?): List<String> {
        if (fupsrlsValues.isNullOrEmpty()) return emptyList()

        val mean = fupsrlsValues.average()
        if (mean == 0.0) return emptyList()

        val variance = fupsrlsValues.map { (it - mean) * (it - mean) }.average()
        val stddev = kotlin.math.sqrt(variance)
        val cv = stddev / kotlin.math.abs(mean)

        val warnings = mutableListOf<String>()
        if (cv > 0.05) {
            warnings.add("WARNING: VE adaptation (fupsrls_w) shows high variance — may not have converged. " +
                "Mean: ${String.format("%.3f", mean)}, range: ${String.format("%.3f", fupsrlsValues.min())}–${String.format("%.3f", fupsrlsValues.max())}, " +
                "CV: ${String.format("%.1f", cv * 100)}%. Allow more adaptation drives before tuning.")
        }
        return warnings
    }
}
