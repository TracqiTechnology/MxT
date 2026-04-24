package domain.model.fueltrim

import domain.math.map.Map3d

/**
 * Result of analyzing MED17 fuel trim logs.
 *
 * @param rpmBins RPM axis values for the correction grid
 * @param loadBins Engine load (%) axis values for the correction grid
 * @param avgTrims Average combined fuel trim (%) per bin — `[rpmIdx][loadIdx]`
 * @param corrections Suggested relative fuel mass (rk_w) adjustment per bin — `[rpmIdx][loadIdx]`.
 *        Positive means add fuel, negative means remove fuel.
 * @param warnings Diagnostic messages (threshold violations, missing data, etc.)
 */
data class FuelTrimResult(
    val rpmBins: DoubleArray,
    val loadBins: DoubleArray,
    val avgTrims: Array<DoubleArray>,
    val corrections: Array<DoubleArray>,
    val warnings: List<String>
) {
    /** True when every correction bin is zero (nothing to adjust). */
    val isEmpty: Boolean
        get() = corrections.all { row -> row.all { it == 0.0 } }

    /** Average combined fuel trim as a Map3d (xAxis=load, yAxis=RPM). */
    fun toAvgTrimsMap3d(): Map3d = Map3d(
        loadBins.toTypedArray(),
        rpmBins.toTypedArray(),
        avgTrims.map { it.toTypedArray() }.toTypedArray()
    )

    /** Suggested rk_w corrections as a Map3d (xAxis=load, yAxis=RPM). */
    fun toCorrectionsMap3d(): Map3d = Map3d(
        loadBins.toTypedArray(),
        rpmBins.toTypedArray(),
        corrections.map { it.toTypedArray() }.toTypedArray()
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FuelTrimResult) return false
        return rpmBins.contentEquals(other.rpmBins) &&
                loadBins.contentEquals(other.loadBins) &&
                avgTrims.zip(other.avgTrims).all { (a, b) -> a.contentEquals(b) } &&
                corrections.zip(other.corrections).all { (a, b) -> a.contentEquals(b) } &&
                warnings == other.warnings
    }

    override fun hashCode(): Int {
        var result = rpmBins.contentHashCode()
        result = 31 * result + loadBins.contentHashCode()
        result = 31 * result + avgTrims.contentDeepHashCode()
        result = 31 * result + corrections.contentDeepHashCode()
        result = 31 * result + warnings.hashCode()
        return result
    }
}

/**
 * Per-cell diagnostic information from fuel trim analysis.
 *
 * Each cell in the RPM × Load grid has a diagnostic that reports sample count,
 * mean trim, standard deviation, and whether the cell was rejected.
 */
data class FuelTrimCellDiagnostic(
    val rpmBin: Double,
    val loadBin: Double,
    val sampleCount: Int,
    val meanTrimPercent: Double,
    val stdDevPercent: Double,
    /** The correction applied to rk_w — 0.0 if the bin was rejected or within threshold. */
    val correctionApplied: Double,
    val rejected: Boolean,
    val rejectReason: String?
)

/**
 * Extended fuel trim analysis result with per-cell diagnostics and
 * closed-loop stability filtering statistics.
 *
 * @param corrections Per-bin correction (%) — `[rpmIdx][loadIdx]`
 * @param diagnostics Per-cell diagnostic detail — `[rpmIdx][loadIdx]`
 * @param rpmBins RPM axis values for the correction grid
 * @param loadBins Engine load (%) axis values for the correction grid
 * @param totalSamplesProcessed Total log rows examined (before filtering)
 * @param samplesFilteredOut Rows rejected by closed-loop / lambda filters
 * @param binsWithData Bins that received at least one post-filter sample
 * @param binsRejected Bins rejected (high std_dev or insufficient samples)
 * @param warnings Diagnostic messages
 */
data class FuelTrimDiagnosticResult(
    val corrections: Array<DoubleArray>,
    val diagnostics: Array<Array<FuelTrimCellDiagnostic>>,
    val rpmBins: DoubleArray,
    val loadBins: DoubleArray,
    val totalSamplesProcessed: Int,
    val samplesFilteredOut: Int,
    val binsWithData: Int,
    val binsRejected: Int,
    val warnings: List<String>
) {
    /** True when every correction bin is zero. */
    val isEmpty: Boolean
        get() = corrections.all { row -> row.all { it == 0.0 } }

    /** Corrections as a Map3d (xAxis=load, yAxis=RPM). */
    fun toCorrectionsMap3d(): Map3d = Map3d(
        loadBins.toTypedArray(),
        rpmBins.toTypedArray(),
        corrections.map { it.toTypedArray() }.toTypedArray()
    )

    /** Convert to a basic [FuelTrimResult] for backward-compatible consumption. */
    fun toFuelTrimResult(): FuelTrimResult {
        val avgTrims = Array(rpmBins.size) { r ->
            DoubleArray(loadBins.size) { l -> diagnostics[r][l].meanTrimPercent }
        }
        return FuelTrimResult(rpmBins, loadBins, avgTrims, corrections, warnings)
    }
}
