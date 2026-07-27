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
    ): FuelTrimResult = analyzeMed17TrimsWithDiagnostics(
        logData = logData,
        rpmBins = rpmBins,
        loadBins = loadBins,
        settings = FuelTrimSettings(
            trimThresholdPercent = trimThreshold,
            minimumSamples = minSamples
        )
    ).toFuelTrimResult()

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
    ): FuelTrimDiagnosticResult = analyzeMed17TrimsWithDiagnostics(
        logData = logData,
        rpmBins = rpmBins,
        loadBins = loadBins,
        settings = FuelTrimSettings(
            trimThresholdPercent = trimThreshold,
            minimumSamples = minSamples,
            standardDeviationLimitPercent = stdDevThreshold
        )
    )

    fun analyzeMed17TrimsWithDiagnostics(
        logData: Map<Med17LogFileContract.Header, List<Double>>,
        rpmBins: DoubleArray = DEFAULT_RPM_BINS,
        loadBins: DoubleArray = DEFAULT_LOAD_BINS,
        settings: FuelTrimSettings
    ): FuelTrimDiagnosticResult {
        val warnings = mutableListOf<String>()

        val rpm = logData[H.RPM_COLUMN_HEADER].orEmpty()
        val load = logData[H.ENGINE_LOAD_HEADER].orEmpty()
        val time = logData[H.TIME_STAMP_COLUMN_HEADER].orEmpty()

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

        // Optional channels must not shorten the usable RPM/load rows. A missing
        // optional value disables that filter for the row instead of truncating
        // the entire analysis.
        val sampleCount = minOf(rpm.size, load.size)

        // Stats accumulators
        val sums = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val sumSq = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val weights = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val counts = Array(rpmBins.size) { IntArray(loadBins.size) }
        var filtered = 0
        var transientFiltered = 0

        for (i in 0 until sampleCount) {
            // Closed-loop filter: B_lr must be 1.0 (active)
            if (settings.requireClosedLoopWhenAvailable &&
                bLr?.getOrNull(i)?.takeIf { it.isFinite() }?.let { it != 1.0 } == true
            ) {
                filtered++
                continue
            }
            // Lambda request filter: must be near stoichiometric
            if (lamsbgW?.getOrNull(i)?.takeIf { it.isFinite() }
                    ?.let { abs(it - 1.0) >= settings.maximumLambdaDeviation } == true
            ) {
                filtered++
                continue
            }
            // Transient filter: compare a physical RPM/second rate. Missing or
            // non-monotonic timestamps disable this filter for the affected row.
            if (i > 0) {
                val previousTime = time.getOrNull(i - 1)
                val currentTime = time.getOrNull(i)
                if (previousTime != null && currentTime != null &&
                    previousTime.isFinite() && currentTime.isFinite() &&
                    currentTime > previousTime
                ) {
                    val rpmPerSecond = abs(rpm[i] - rpm[i - 1]) / (currentTime - previousTime)
                    if (rpmPerSecond > settings.maximumRpmChangePerSecond) {
                        transientFiltered++
                        continue
                    }
                }
            }

            val stftValue = stft?.getOrNull(i)?.takeIf { it.isFinite() }
            val ltftValue = ltft?.getOrNull(i)?.takeIf { it.isFinite() }
            if (stftValue == null && ltftValue == null) {
                filtered++
                continue
            }

            val rpmWeights = interpolatedBinWeights(rpm[i], rpmBins)
            val loadWeights = interpolatedBinWeights(load[i], loadBins)

            val stftPct = stftValue?.let { (it - 1.0) * 100.0 } ?: 0.0
            val ltftPct = ltftValue?.let { (it - 1.0) * 100.0 } ?: 0.0
            val trimPct = stftPct + ltftPct

            for ((rpmIdx, rpmW) in rpmWeights) {
                for ((loadIdx, loadW) in loadWeights) {
                    val w = rpmW * loadW
                    sums[rpmIdx][loadIdx] += trimPct * w
                    sumSq[rpmIdx][loadIdx] += trimPct * trimPct * w
                    weights[rpmIdx][loadIdx] += w
                    counts[rpmIdx][loadIdx]++
                }
            }
        }

        // Build per-cell diagnostics
        var binsWithData = 0
        var binsRejected = 0
        val corrections = Array(rpmBins.size) { DoubleArray(loadBins.size) }

        val diagnostics = Array(rpmBins.size) { r ->
            Array(loadBins.size) { l ->
                val n = counts[r][l]
                val weight = weights[r][l]
                if (weight > 0.0) binsWithData++

                if (n == 0 || weight <= 0.0) {
                    return@Array FuelTrimCellDiagnostic(
                        rpmBins[r], loadBins[l], 0, 0.0, 0.0, 0.0, true, "no samples"
                    )
                }

                val mean = sums[r][l] / weight
                val variance = (sumSq[r][l] / weight) - (mean * mean)
                val stdDev = sqrt(max(0.0, variance))

                val rejected: Boolean
                val reason: String?

                when {
                    weight + 1e-9 < settings.minimumSamples.toDouble() -> {
                        rejected = true
                        reason = "insufficient effective samples (" +
                            "%.2f < %d)".format(weight, settings.minimumSamples)
                        binsRejected++
                    }
                    stdDev > settings.standardDeviationLimitPercent -> {
                        rejected = true
                        reason = "std_dev %.1f%% > %.1f%%".format(
                            stdDev,
                            settings.standardDeviationLimitPercent
                        )
                        binsRejected++
                    }
                    abs(mean) <= settings.trimThresholdPercent -> {
                        rejected = false
                        reason = "within threshold (±${settings.trimThresholdPercent}%)"
                    }
                    else -> {
                        rejected = false
                        reason = null
                    }
                }

                val correction = if (!rejected && abs(mean) > settings.trimThresholdPercent) mean else 0.0
                corrections[r][l] = correction

                if (correction != 0.0) {
                    warnings.add(
                        (
                            "RPM=%.0f Load=%.0f%%: avg trim %+.1f%% exceeds ±%.1f%% threshold " +
                                "(σ=%.1f%%, n=%d, effective n=%.2f)"
                            ).format(
                                rpmBins[r],
                                loadBins[l],
                                mean,
                                settings.trimThresholdPercent,
                                stdDev,
                                n,
                                weight
                            )
                    )
                }

                FuelTrimCellDiagnostic(
                    rpmBins[r], loadBins[l], n, mean, stdDev, correction,
                    rejected,
                    reason,
                    effectiveSampleWeight = weight
                )
            }
        }

        if (transientFiltered > 0) {
            warnings.add(
                "Transient filter: excluded $transientFiltered samples " +
                    "(rate > ${settings.maximumRpmChangePerSecond.toInt()} RPM/s)"
            )
        }

        // ── Bank imbalance detection ────────────────────────────────────
        detectBankImbalance(logData, sampleCount, rpm, bLr, lamsbgW, settings, warnings)

        // Summary warning at position 0
        warnings.add(0,
            (
                "Processed %,d samples (%,d filtered: closed-loop/lambda/missing trim, %,d transient) | " +
                    "%d bins with data, %d rejected | threshold ±%.1f%%, min n=%d, max σ=%.1f%%"
                ).format(
                    sampleCount,
                    filtered,
                    transientFiltered,
                    binsWithData,
                    binsRejected,
                    settings.trimThresholdPercent,
                    settings.minimumSamples,
                    settings.standardDeviationLimitPercent
                )
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

    // ── bank imbalance detection ───────────────────────────────────

    /** Threshold (%) for bank-to-bank trim imbalance warning. */
    private const val BANK_IMBALANCE_THRESHOLD_PCT = 2.0

    /**
     * Detect per-bank fuel trim imbalance.
     *
     * If the log contains bank-specific STFT channels (fr_w_b1 / fr_w_b2),
     * computes the average trim for each bank and warns if the difference
     * exceeds [BANK_IMBALANCE_THRESHOLD_PCT]. A consistent imbalance
     * indicates injector aging or flow drift on one bank.
     */
    private fun detectBankImbalance(
        logData: Map<Med17LogFileContract.Header, List<Double>>,
        sampleCount: Int,
        rpm: List<Double>,
        bLr: List<Double>?,
        lamsbgW: List<Double>?,
        settings: FuelTrimSettings,
        warnings: MutableList<String>
    ) {
        val b1 = logData[H.STFT_BANK1_HEADER]?.takeIf { it.isNotEmpty() } ?: return
        val b2 = logData[H.STFT_BANK2_HEADER]?.takeIf { it.isNotEmpty() } ?: return
        val time = logData[H.TIME_STAMP_COLUMN_HEADER].orEmpty()

        val n = minOf(sampleCount, b1.size, b2.size)
        var sum1 = 0.0
        var sum2 = 0.0
        var count = 0

        for (i in 0 until n) {
            if (settings.requireClosedLoopWhenAvailable &&
                bLr?.getOrNull(i)?.takeIf { it.isFinite() }?.let { it != 1.0 } == true
            ) {
                continue
            }
            if (lamsbgW?.getOrNull(i)?.takeIf { it.isFinite() }
                    ?.let { abs(it - 1.0) >= settings.maximumLambdaDeviation } == true
            ) {
                continue
            }
            if (i > 0) {
                val previousTime = time.getOrNull(i - 1)
                val currentTime = time.getOrNull(i)
                if (previousTime != null && currentTime != null &&
                    previousTime.isFinite() && currentTime.isFinite() &&
                    currentTime > previousTime &&
                    abs(rpm[i] - rpm[i - 1]) / (currentTime - previousTime) >
                    settings.maximumRpmChangePerSecond
                ) {
                    continue
                }
            }

            if (!b1[i].isFinite() || !b2[i].isFinite()) continue
            sum1 += (b1[i] - 1.0) * 100.0
            sum2 += (b2[i] - 1.0) * 100.0
            count++
        }

        if (count >= settings.minimumSamples) {
            val avg1 = sum1 / count
            val avg2 = sum2 / count
            val imbalance = abs(avg1 - avg2)

            if (imbalance > BANK_IMBALANCE_THRESHOLD_PCT) {
                val richer = if (avg1 > avg2) "Bank 1" else "Bank 2"
                warnings.add(
                    "⚠ Bank imbalance detected: Bank 1 avg %+.1f%%, Bank 2 avg %+.1f%% (Δ%.1f%%) — %s is richer, possible injector aging"
                        .format(avg1, avg2, imbalance, richer)
                )
            } else {
                warnings.add(
                    "Bank trims balanced: Bank 1 avg %+.1f%%, Bank 2 avg %+.1f%% (Δ%.1f%%, threshold ±%.0f%%)"
                        .format(avg1, avg2, imbalance, BANK_IMBALANCE_THRESHOLD_PCT)
                )
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Merge separately parsed fuel-trim files without losing row identity when
     * optional channels differ between files.
     */
    fun mergeAlignedLogs(
        logs: List<Map<Med17LogFileContract.Header, List<Double>>>
    ): Map<Med17LogFileContract.Header, List<Double>> {
        val output: LinkedHashMap<H, MutableList<Double>> = linkedMapOf(
            H.TIME_STAMP_COLUMN_HEADER to mutableListOf<Double>(),
            H.RPM_COLUMN_HEADER to mutableListOf(),
            H.ENGINE_LOAD_HEADER to mutableListOf(),
            H.STFT_MIXED_COLUMN_HEADER to mutableListOf(),
            H.LTFT_COLUMN_HEADER to mutableListOf(),
            H.LAMBDA_CONTROL_ACTIVE_HEADER to mutableListOf(),
            H.REQUESTED_LAMBDA_HEADER to mutableListOf(),
            H.STFT_BANK1_HEADER to mutableListOf(),
            H.STFT_BANK2_HEADER to mutableListOf()
        )

        for (log in logs) {
            val rpm = log[H.RPM_COLUMN_HEADER].orEmpty()
            val load = log[H.ENGINE_LOAD_HEADER].orEmpty()
            val rowCount = minOf(rpm.size, load.size)
            if (rowCount == 0) continue

            val time = log[H.TIME_STAMP_COLUMN_HEADER].orEmpty()
            val stft = resolveSignal(log, H.STFT_MIXED_COLUMN_HEADER, H.STFT_COLUMN_HEADER)
            val ltft = resolveSignal(log, H.LTFT_COLUMN_HEADER, H.LONG_TERM_FT_HEADER)
            val closedLoop = log[H.LAMBDA_CONTROL_ACTIVE_HEADER].orEmpty()
            val requestedLambda = log[H.REQUESTED_LAMBDA_HEADER].orEmpty()
            val bank1 = log[H.STFT_BANK1_HEADER].orEmpty()
            val bank2 = log[H.STFT_BANK2_HEADER].orEmpty()

            for (index in 0 until rowCount) {
                output.getValue(H.TIME_STAMP_COLUMN_HEADER)
                    .add(time.getOrNull(index) ?: Double.NaN)
                output.getValue(H.RPM_COLUMN_HEADER).add(rpm[index])
                output.getValue(H.ENGINE_LOAD_HEADER).add(load[index])
                output.getValue(H.STFT_MIXED_COLUMN_HEADER)
                    .add(stft?.getOrNull(index) ?: Double.NaN)
                output.getValue(H.LTFT_COLUMN_HEADER)
                    .add(ltft?.getOrNull(index) ?: Double.NaN)
                output.getValue(H.LAMBDA_CONTROL_ACTIVE_HEADER)
                    .add(closedLoop.getOrNull(index) ?: Double.NaN)
                output.getValue(H.REQUESTED_LAMBDA_HEADER)
                    .add(requestedLambda.getOrNull(index) ?: Double.NaN)
                output.getValue(H.STFT_BANK1_HEADER)
                    .add(bank1.getOrNull(index) ?: Double.NaN)
                output.getValue(H.STFT_BANK2_HEADER)
                    .add(bank2.getOrNull(index) ?: Double.NaN)
            }
        }
        return output
    }

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

    /**
     * Compute interpolated bin weights for a value.
     *
     * Instead of snapping to the single nearest bin, distributes the sample
     * between the two adjacent bins using linear basis weighting.  This produces
     * smoother correction surfaces, especially on coarse grids.
     *
     * Returns a list of (index, weight) pairs. Weights sum to 1.0.
     * At the edges or when the value exactly matches a bin, a single pair is returned.
     */
    internal fun interpolatedBinWeights(value: Double, bins: DoubleArray): List<Pair<Int, Double>> {
        if (bins.size <= 1) return listOf(0 to 1.0)

        // Clamp to axis boundaries
        if (value <= bins.first()) return listOf(0 to 1.0)
        if (value >= bins.last()) return listOf(bins.size - 1 to 1.0)

        // Find the interval [lo, hi] containing value
        var lo = 0
        for (i in 1 until bins.size) {
            if (bins[i] >= value) { lo = i - 1; break }
        }
        val hi = lo + 1

        val span = bins[hi] - bins[lo]
        if (span <= 0.0) return listOf(lo to 1.0)

        val t = (value - bins[lo]) / span
        return if (t < 1e-9) {
            listOf(lo to 1.0)
        } else if (t > 1.0 - 1e-9) {
            listOf(hi to 1.0)
        } else {
            listOf(lo to (1.0 - t), hi to t)
        }
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
