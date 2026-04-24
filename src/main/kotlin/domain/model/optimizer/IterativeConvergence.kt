package domain.model.optimizer

import domain.math.map.Map3d
import domain.model.simulator.Me7Simulator
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 20: Iterative convergence engine.
 *
 * Repeatedly applies corrections then re-simulates until total error drops
 * below a threshold or max iterations are reached. Uses damping to prevent
 * oscillation between over/under-correction.
 *
 * Algorithm:
 * ```
 * while (iteration < max && error > threshold):
 *     simulate(wot, calibration)
 *     corrections = compute_corrections(sim_results)
 *     calibration = apply_corrections(calibration, corrections, damping=0.7)
 *     error = compute_error(sim_results)
 * ```
 */
object IterativeConvergence {

    /** A single step in the convergence history. */
    data class ConvergenceStep(
        val iteration: Int,
        val totalError: Double,
        val avgPressureError: Double,
        val avgLoadDeficit: Double,
        val cellsModified: Int
    )

    /** Complete convergence history. */
    data class ConvergenceHistory(
        val steps: List<ConvergenceStep>,
        val converged: Boolean,
        val finalError: Double,
        val iterationsToConverge: Int,
        val diverged: Boolean,
        val finalSuggestedMaps: SuggestedMaps
    )

    // ── Parameters ─────────────────────────────────────────────────

    private const val DEFAULT_MAX_ITERATIONS = 10
    private const val DEFAULT_DAMPING = 0.7
    private const val DEFAULT_THRESHOLD = 2.0

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Run iterative convergence on WOT data.
     *
     * @param wotEntries All WOT-filtered log entries
     * @param initialCalibration Current calibration from BIN
     * @param kfldrlMap Current KFLDRL (for suggestion)
     * @param kfldimxMap Current KFLDIMX (for suggestion)
     * @param kfpbrkMap Current KFPBRK (for suggestion)
     * @param kfmiopMap Current KFMIOP (for suggestion)
     * @param kfmirlMap Current KFMIRL (for suggestion)
     * @param ldrxnTarget Target load
     * @param toleranceMbar Tolerance for suggestions
     * @param kfldimxOverheadPercent Overhead for KFLDIMX
     * @param maxIterations Maximum iterations
     * @param damping Damping factor (0.0–1.0): fraction of correction applied each step
     * @param threshold Convergence threshold for total error
     */
    fun converge(
        wotEntries: List<OptimizerCalculator.WotLogEntry>,
        initialCalibration: Me7Simulator.CalibrationSet,
        kfldrlMap: Map3d?,
        kfldimxMap: Map3d?,
        kfpbrkMap: Map3d?,
        kfmiopMap: Map3d? = null,
        kfmirlMap: Map3d? = null,
        ldrxnTarget: Double = 191.0,
        toleranceMbar: Double = 30.0,
        kfldimxOverheadPercent: Double = 8.0,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        damping: Double = DEFAULT_DAMPING,
        threshold: Double = DEFAULT_THRESHOLD
    ): ConvergenceHistory {
        if (wotEntries.isEmpty()) {
            return ConvergenceHistory(emptyList(), false, Double.MAX_VALUE, 0, false, SuggestedMaps())
        }

        val steps = mutableListOf<ConvergenceStep>()
        var currentKfldrl = kfldrlMap
        var currentKfldimx = kfldimxMap
        var currentKfpbrk = kfpbrkMap
        var currentKfmiop = kfmiopMap
        var currentKfmirl = kfmirlMap
        var lastSuggested = SuggestedMaps()
        var previousError = Double.MAX_VALUE
        var diverged = false

        for (iteration in 1..maxIterations) {
            // Build calibration with current maps
            val calibration = Me7Simulator.CalibrationSet(
                kfpbrk = currentKfpbrk,
                kfldrl = currentKfldrl,
                kfldimx = currentKfldimx,
                kfmiop = currentKfmiop,
                kfmirl = currentKfmirl,
                kfurl = initialCalibration.kfurl,
                kfurlMap = initialCalibration.kfurlMap,  // Finding 2: Preserve RPM-dependent KFURL
                ldrxn = ldrxnTarget
            )

            // Simulate
            val simResults = Me7Simulator.simulateAll(wotEntries, calibration)

            // Compute error
            val avgPressureError = simResults.map { abs(it.boostError) }.average()
            val avgLoadDeficit = simResults.map { abs(it.totalLoadDeficit) }.average()
            val totalError = avgLoadDeficit + avgPressureError / 10.0

            // Compute suggestions from current simulation
            val kfldrlDelta = if (currentKfldrl != null) {
                OptimizerCalculator.suggestKfldrlDelta(wotEntries, currentKfldrl, toleranceMbar)
            } else null

            val kfldimxDelta = if (kfldrlDelta != null && currentKfldimx != null) {
                OptimizerCalculator.suggestKfldimxDelta(wotEntries, kfldrlDelta, currentKfldimx, kfldimxOverheadPercent)
            } else null

            val kfpbrkDelta = if (currentKfpbrk != null) {
                OptimizerCalculator.suggestKfpbrkDelta(wotEntries, currentKfpbrk, toleranceMbar, simulationResults = simResults)
            } else null

            val kfmiopDelta = if (currentKfmiop != null && simResults.any { it.torqueLimited }) {
                OptimizerCalculator.suggestKfmiopDelta(simResults, currentKfmiop, ldrxnTarget)
            } else null

            val kfmirlDelta = if (kfmiopDelta != null && currentKfmirl != null) {
                OptimizerCalculator.suggestKfmirlDelta(kfmiopDelta.suggested, currentKfmirl)
            } else null

            lastSuggested = SuggestedMaps(kfldrlDelta, kfldimxDelta, kfpbrkDelta, kfmiopDelta, kfmirlDelta)

            val cellsModified = listOfNotNull(
                kfldrlDelta?.cellsModified, kfpbrkDelta?.cellsModified,
                kfmiopDelta?.cellsModified
            ).sum()

            steps.add(ConvergenceStep(iteration, totalError, avgPressureError, avgLoadDeficit, cellsModified))

            // Check convergence
            if (totalError < threshold) {
                return ConvergenceHistory(steps, true, totalError, iteration, false, lastSuggested)
            }

            // Check divergence
            if (iteration > 2 && totalError > previousError * 1.05) {
                diverged = true
                return ConvergenceHistory(steps, false, totalError, iteration, true, lastSuggested)
            }

            previousError = totalError

            // Apply damped corrections for next iteration
            currentKfldrl = kfldrlDelta?.let { applyDamped(currentKfldrl!!, it.suggested, damping) } ?: currentKfldrl
            currentKfldimx = kfldimxDelta?.let { applyDamped(currentKfldimx!!, it.suggested, damping) } ?: currentKfldimx
            currentKfpbrk = kfpbrkDelta?.let { applyDamped(currentKfpbrk!!, it.suggested, damping) } ?: currentKfpbrk
            currentKfmiop = kfmiopDelta?.let { applyDamped(currentKfmiop!!, it.suggested, damping) } ?: currentKfmiop
            currentKfmirl = kfmirlDelta?.let { applyDamped(currentKfmirl!!, it.suggested, damping) } ?: currentKfmirl
        }

        val finalError = steps.lastOrNull()?.totalError ?: Double.MAX_VALUE
        return ConvergenceHistory(steps, false, finalError, maxIterations, diverged, lastSuggested)
    }

    // ── Private helpers ────────────────────────────────────────────

    /**
     * Apply damped correction: new = current + damping × (suggested − current)
     */
    private fun applyDamped(current: Map3d, suggested: Map3d, damping: Double): Map3d {
        val rows = current.yAxis.size
        val cols = current.xAxis.size
        val dampedZ = Array(rows) { r ->
            Array(cols) { c ->
                current.zAxis[r][c] + damping * (suggested.zAxis[r][c] - current.zAxis[r][c])
            }
        }
        return Map3d(current.xAxis, current.yAxis, dampedZ)
    }
}

