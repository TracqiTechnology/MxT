package domain.model.fueltrim

import data.contract.Med17LogFileContract
import data.contract.Med17LogFileContract.Header as H
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Analyzes MED17 fuel trim log data (STFT / LTFT) across an RPM × Load grid
 * and produces per-bin corrections to the relative fuel mass (rk_w).
 *
 * MED17 fuel trim signals are multiplicative factors centred on 1.0:
 *   frm_w  = short-term fuel trim (STFT)
 *   fra_w  = long-term fuel trim  (LTFT)
 *   longft1_w = alternative long-term FT
 *
 * A combined trim of 1.05 means the ECU is adding 5 % fuel to compensate for
 * a lean condition.  The suggested correction is the negation: −5 % adjustment
 * to rk_w so the base map delivers the right amount and trims return to 1.0.
 */
object FuelTrimAnalyzer {

    /** Bins where the absolute average trim exceeds this (%) produce a correction. */
    private const val TRIM_THRESHOLD_PCT = 3.0

    /** Bins with fewer samples than this are treated as having no data. */
    private const val MIN_SAMPLES = 3

    /** Default std-dev threshold (%) — bins with higher variance are rejected. */
    private const val STD_DEV_THRESHOLD_PCT = 5.0

    // Default RPM bins — coarse grid covering typical MED17 operating range
    val DEFAULT_RPM_BINS = doubleArrayOf(
        750.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0,
        3500.0, 4000.0, 4500.0, 5000.0, 5500.0, 6000.0, 6500.0, 7000.0
    )

    // Default engine-load bins (%)
    val DEFAULT_LOAD_BINS = doubleArrayOf(
        10.0, 20.0, 30.0, 40.0, 50.0, 60.0,
        70.0, 80.0, 90.0, 100.0, 120.0, 150.0, 180.0, 200.0
    )

    /**
     * Analyse parsed MED17 fuel trim data and return per-bin corrections.
     *
     * @param logData   Parsed log keyed by [Med17LogFileContract.Header].
     * @param rpmBins   RPM axis for the output grid (default [DEFAULT_RPM_BINS]).
     * @param loadBins  Load axis for the output grid (default [DEFAULT_LOAD_BINS]).
     * @param trimThreshold  Combined trim threshold (%) beyond which a correction
     *                       is generated (default [TRIM_THRESHOLD_PCT]).
     * @param minSamples Minimum sample count per bin to produce a valid average
     *                   (default [MIN_SAMPLES]).
     */
    fun analyzeMed17Trims(
        logData: Map<Med17LogFileContract.Header, List<Double>>,
        rpmBins: DoubleArray = DEFAULT_RPM_BINS,
        loadBins: DoubleArray = DEFAULT_LOAD_BINS,
        trimThreshold: Double = TRIM_THRESHOLD_PCT,
        minSamples: Int = MIN_SAMPLES
    ): FuelTrimResult {
        val warnings = mutableListOf<String>()

        val rpm = logData[H.RPM_COLUMN_HEADER].orEmpty()
        val load = logData[H.ENGINE_LOAD_HEADER].orEmpty()

        if (rpm.isEmpty() || load.isEmpty()) {
            val keysFound = logData.entries.filter { it.value.isNotEmpty() }.map { it.key.name }
            warnings.add("Missing RPM or engine load data — keys with data: $keysFound")
            return emptyResult(rpmBins, loadBins, warnings)
        }

        // Resolve STFT — prefer frm_w over fr_w
        val stft: List<Double>? = resolveSignal(
            logData,
            H.STFT_MIXED_COLUMN_HEADER,
            H.STFT_COLUMN_HEADER
        )

        // Resolve LTFT — prefer fra_w over longft1_w
        val ltft: List<Double>? = resolveSignal(
            logData,
            H.LTFT_COLUMN_HEADER,
            H.LONG_TERM_FT_HEADER
        )

        if (stft == null && ltft == null) {
            warnings.add("No fuel trim data found (checked frm_w, fr_w, fra_w, longft1_w)")
            return emptyResult(rpmBins, loadBins, warnings)
        }

        val sampleCount = minOf(
            rpm.size,
            load.size,
            stft?.size ?: Int.MAX_VALUE,
            ltft?.size ?: Int.MAX_VALUE
        )

        warnings.add(0, "Parsed $sampleCount samples (RPM: ${rpm.size}, Load: ${load.size}" +
                "${stft?.let { ", STFT: ${it.size}" } ?: ""}" +
                "${ltft?.let { ", LTFT: ${it.size}" } ?: ""})")

        // Accumulate combined trim per bin
        val trimSums = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val trimCounts = Array(rpmBins.size) { IntArray(loadBins.size) }

        for (i in 0 until sampleCount) {
            val rpmIdx = nearestBinIndex(rpm[i], rpmBins)
            val loadIdx = nearestBinIndex(load[i], loadBins)

            // MED17 trims are multiplicative around 1.0 → convert to %
            val stftPct = stft?.let { (it[i] - 1.0) * 100.0 } ?: 0.0
            val ltftPct = ltft?.let { (it[i] - 1.0) * 100.0 } ?: 0.0

            trimSums[rpmIdx][loadIdx] += stftPct + ltftPct
            trimCounts[rpmIdx][loadIdx]++
        }

        // Compute averages and generate corrections
        val avgTrims = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val corrections = Array(rpmBins.size) { DoubleArray(loadBins.size) }

        for (r in rpmBins.indices) {
            for (l in loadBins.indices) {
                val count = trimCounts[r][l]
                if (count >= minSamples) {
                    val avg = trimSums[r][l] / count
                    avgTrims[r][l] = avg

                    if (abs(avg) > trimThreshold) {
                        // Negate: positive trim (ECU adding fuel) → base map is lean →
                        // increase rk_w by the trim amount (negative correction value
                        // means "the trim was positive so reduce rk_w to compensate"
                        // — actually we want to bake the trim into the map so the ECU
                        // doesn't have to compensate: correction = +avg because if the
                        // ECU adds 5 % we want 5 % more fuel in the base map).
                        //
                        // Convention: positive correction = add fuel to rk_w.
                        corrections[r][l] = avg
                        warnings.add(
                            "RPM=%.0f Load=%.0f%%: avg trim %+.1f%% exceeds ±%.0f%% threshold (%d samples)"
                                .format(rpmBins[r], loadBins[l], avg, trimThreshold, count)
                        )
                    }
                }
            }
        }

        return FuelTrimResult(rpmBins, loadBins, avgTrims, corrections, warnings)
    }

    /**
     * Analyse parsed MED17 fuel trim data with closed-loop stability filtering
     * and per-cell diagnostics.
     *
     * Filters applied (both optional — skipped if the signal is absent):
     * - **B_lr == 1**: only closed-loop samples (lambda feedback active)
     * - **|lamsbg_w − 1.0| < 0.05**: only stoichiometric-request samples
     *
     * Per-bin validation:
     * - Bins with fewer than [minSamples] are rejected
     * - Bins where std_dev > [stdDevThreshold] are rejected (noisy data)
     * - Bins where |mean| ≤ [trimThreshold] produce no correction (within tolerance)
     *
     * @return [FuelTrimDiagnosticResult] with per-cell diagnostics and summary stats.
     */
    fun analyzeMed17TrimsWithDiagnostics(
        logData: Map<Med17LogFileContract.Header, List<Double>>,
        rpmBins: DoubleArray = DEFAULT_RPM_BINS,
        loadBins: DoubleArray = DEFAULT_LOAD_BINS,
        minSamples: Int = MIN_SAMPLES,
        trimThreshold: Double = TRIM_THRESHOLD_PCT,
        stdDevThreshold: Double = STD_DEV_THRESHOLD_PCT
    ): FuelTrimDiagnosticResult {
        val warnings = mutableListOf<String>()

        val rpm = logData[H.RPM_COLUMN_HEADER].orEmpty()
        val load = logData[H.ENGINE_LOAD_HEADER].orEmpty()

        if (rpm.isEmpty() || load.isEmpty()) {
            val keysFound = logData.entries.filter { it.value.isNotEmpty() }.map { it.key.name }
            warnings.add("Missing RPM or engine load data — keys with data: $keysFound")
            return emptyDiagnosticResult(rpmBins, loadBins, warnings)
        }

        val stft: List<Double>? = resolveSignal(
            logData, H.STFT_MIXED_COLUMN_HEADER, H.STFT_COLUMN_HEADER
        )
        val ltft: List<Double>? = resolveSignal(
            logData, H.LTFT_COLUMN_HEADER, H.LONG_TERM_FT_HEADER
        )

        if (stft == null && ltft == null) {
            warnings.add("No fuel trim data found (checked frm_w, fr_w, fra_w, longft1_w)")
            return emptyDiagnosticResult(rpmBins, loadBins, warnings)
        }

        // Optional closed-loop filter signals
        val bLr = logData[H.LAMBDA_CONTROL_ACTIVE_HEADER]?.takeIf { it.isNotEmpty() }
        val lamsbgW = logData[H.REQUESTED_LAMBDA_HEADER]?.takeIf { it.isNotEmpty() }

        if (bLr != null) warnings.add("Closed-loop filter active (B_lr)")
        if (lamsbgW != null) warnings.add("Lambda request filter active (lamsbg_w ≈ 1.0)")

        val sampleCount = minOf(
            rpm.size,
            load.size,
            stft?.size ?: Int.MAX_VALUE,
            ltft?.size ?: Int.MAX_VALUE,
            bLr?.size ?: Int.MAX_VALUE,
            lamsbgW?.size ?: Int.MAX_VALUE
        )

        // Stats accumulators
        val sums = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val sumSq = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val counts = Array(rpmBins.size) { IntArray(loadBins.size) }
        var filtered = 0

        for (i in 0 until sampleCount) {
            // Closed-loop filter: B_lr must be 1.0 (active)
            if (bLr != null && bLr[i] != 1.0) { filtered++; continue }
            // Lambda request filter: must be near stoichiometric
            if (lamsbgW != null && abs(lamsbgW[i] - 1.0) >= 0.05) { filtered++; continue }

            val rpmIdx = nearestBinIndex(rpm[i], rpmBins)
            val loadIdx = nearestBinIndex(load[i], loadBins)

            val stftPct = stft?.let { (it[i] - 1.0) * 100.0 } ?: 0.0
            val ltftPct = ltft?.let { (it[i] - 1.0) * 100.0 } ?: 0.0
            val trimPct = stftPct + ltftPct

            sums[rpmIdx][loadIdx] += trimPct
            sumSq[rpmIdx][loadIdx] += trimPct * trimPct
            counts[rpmIdx][loadIdx]++
        }

        // Build per-cell diagnostics
        var binsWithData = 0
        var binsRejected = 0
        val corrections = Array(rpmBins.size) { DoubleArray(loadBins.size) }

        val diagnostics = Array(rpmBins.size) { r ->
            Array(loadBins.size) { l ->
                val n = counts[r][l]
                if (n > 0) binsWithData++

                if (n == 0) {
                    return@Array FuelTrimCellDiagnostic(
                        rpmBins[r], loadBins[l], 0, 0.0, 0.0, 0.0, true, "no samples"
                    )
                }

                val mean = sums[r][l] / n
                val variance = (sumSq[r][l] / n) - (mean * mean)
                val stdDev = sqrt(max(0.0, variance))

                val rejected: Boolean
                val reason: String?

                when {
                    n < minSamples -> {
                        rejected = true
                        reason = "insufficient samples ($n < $minSamples)"
                        binsRejected++
                    }
                    stdDev > stdDevThreshold -> {
                        rejected = true
                        reason = "std_dev %.1f%% > %.1f%%".format(stdDev, stdDevThreshold)
                        binsRejected++
                    }
                    abs(mean) <= trimThreshold -> {
                        rejected = false
                        reason = "within threshold (±${trimThreshold}%)"
                    }
                    else -> {
                        rejected = false
                        reason = null
                    }
                }

                val correction = if (!rejected && abs(mean) > trimThreshold) mean else 0.0
                corrections[r][l] = correction

                if (correction != 0.0) {
                    warnings.add(
                        "RPM=%.0f Load=%.0f%%: avg trim %+.1f%% (σ=%.1f%%, n=%d)"
                            .format(rpmBins[r], loadBins[l], mean, stdDev, n)
                    )
                }

                FuelTrimCellDiagnostic(
                    rpmBins[r], loadBins[l], n, mean, stdDev, correction,
                    rejected || abs(mean) <= trimThreshold,
                    reason
                )
            }
        }

        // Summary warning at position 0
        warnings.add(0,
            "Processed %,d samples (%,d filtered: closed-loop only) | %d bins with data, %d rejected"
                .format(sampleCount, filtered, binsWithData, binsRejected)
        )

        return FuelTrimDiagnosticResult(
            corrections = corrections,
            diagnostics = diagnostics,
            rpmBins = rpmBins,
            loadBins = loadBins,
            totalSamplesProcessed = sampleCount,
            samplesFilteredOut = filtered,
            binsWithData = binsWithData,
            binsRejected = binsRejected,
            warnings = warnings
        )
    }

    // ── map-switch detection ────────────────────────────────────────

    /**
     * Returns `true` when [tableDescription] contains markers that identify
     * a DS1 map-switch variant (e.g. `InjSys_RelMCorHom1_MAP Gasoline 0`).
     *
     * On MED17 with DS1, map-switch rk_w tables overwrite native ones at
     * runtime, so editing a native table has no effect.
     */
    fun isMapSwitchTable(tableDescription: String): Boolean {
        val d = tableDescription
        return d.contains("_MAP ", ignoreCase = true) ||
                d.contains("_MAP\t", ignoreCase = true) ||
                d.endsWith("_MAP", ignoreCase = true) ||
                d.contains("MAP Gasoline", ignoreCase = true) ||
                d.contains("MAP Ethanol", ignoreCase = true)
    }

    /**
     * Returns `true` when [tableDescription] looks like an rk_w
     * (relative fuel-mass correction) table based on its Funktionsrahmen
     * identifier.
     */
    fun isRkwTable(tableDescription: String): Boolean {
        return tableDescription.contains("RelMCor", ignoreCase = true) ||
                tableDescription.contains("rk_w", ignoreCase = true)
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Return the first non-empty signal list from the candidates, or null. */
    private fun resolveSignal(
        data: Map<Med17LogFileContract.Header, List<Double>>,
        vararg candidates: Med17LogFileContract.Header
    ): List<Double>? {
        for (header in candidates) {
            val list = data[header]
            if (!list.isNullOrEmpty()) return list
        }
        return null
    }

    /** Find the index of the bin closest to [value]. */
    internal fun nearestBinIndex(value: Double, bins: DoubleArray): Int {
        var bestIdx = 0
        var bestDist = abs(value - bins[0])
        for (i in 1 until bins.size) {
            val dist = abs(value - bins[i])
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun emptyResult(
        rpmBins: DoubleArray,
        loadBins: DoubleArray,
        warnings: List<String>
    ) = FuelTrimResult(
        rpmBins, loadBins,
        Array(rpmBins.size) { DoubleArray(loadBins.size) },
        Array(rpmBins.size) { DoubleArray(loadBins.size) },
        warnings
    )

    private fun emptyDiagnosticResult(
        rpmBins: DoubleArray,
        loadBins: DoubleArray,
        warnings: List<String>
    ): FuelTrimDiagnosticResult {
        val diagnostics = Array(rpmBins.size) { r ->
            Array(loadBins.size) { l ->
                FuelTrimCellDiagnostic(rpmBins[r], loadBins[l], 0, 0.0, 0.0, 0.0, true, "no samples")
            }
        }
        return FuelTrimDiagnosticResult(
            corrections = Array(rpmBins.size) { DoubleArray(loadBins.size) },
            diagnostics = diagnostics,
            rpmBins = rpmBins,
            loadBins = loadBins,
            totalSamplesProcessed = 0,
            samplesFilteredOut = 0,
            binsWithData = 0,
            binsRejected = 0,
            warnings = warnings
        )
    }
}
