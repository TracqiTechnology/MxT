package domain.model.pfi

import data.contract.Med17LogFileContract
import data.contract.Med17LogFileContract.Header
import kotlin.math.abs

/**
 * Result of an RPM-dependent PFI share calculation.
 *
 * @property rpmAxis          RPM breakpoints (sorted ascending)
 * @property pfiSharePercent  PFI share at each RPM breakpoint (0–100 %)
 * @property loggedRpmAxis    RPM breakpoints extracted from log data (null when no log was used)
 * @property loggedPfiPercent PFI share from log at each [loggedRpmAxis] point (null when no log)
 */
data class PfiShareResult(
    val rpmAxis: DoubleArray,
    val pfiSharePercent: DoubleArray,
    val loggedRpmAxis: DoubleArray? = null,
    val loggedPfiPercent: DoubleArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PfiShareResult) return false
        return rpmAxis.contentEquals(other.rpmAxis) &&
            pfiSharePercent.contentEquals(other.pfiSharePercent) &&
            loggedRpmAxis.contentNullEquals(other.loggedRpmAxis) &&
            loggedPfiPercent.contentNullEquals(other.loggedPfiPercent)
    }

    override fun hashCode(): Int {
        var h = rpmAxis.contentHashCode()
        h = 31 * h + pfiSharePercent.contentHashCode()
        h = 31 * h + (loggedRpmAxis?.contentHashCode() ?: 0)
        h = 31 * h + (loggedPfiPercent?.contentHashCode() ?: 0)
        return h
    }

    private fun DoubleArray?.contentNullEquals(other: DoubleArray?): Boolean = when {
        this == null && other == null -> true
        this != null && other != null -> contentEquals(other)
        else -> false
    }
}

/**
 * Result of a 2D RPM × Load PFI share calculation.
 *
 * @property rpmAxis             RPM breakpoints (sorted ascending)
 * @property loadAxis            Load breakpoints (sorted ascending, %)
 * @property pfiSharePercent2d   PFI share grid [rpmIdx][loadIdx] in percent (0–100)
 * @property sampleCounts        Per-cell sample count [rpmIdx][loadIdx]
 * @property totalSamples        Total log samples processed
 * @property rpmOnlyCurve        1D RPM-only curve for backward compatibility
 */
data class PfiShare2dResult(
    val rpmAxis: DoubleArray,
    val loadAxis: DoubleArray,
    val pfiSharePercent2d: Array<DoubleArray>,
    val sampleCounts: Array<IntArray>,
    val totalSamples: Int,
    val rpmOnlyCurve: PfiShareResult
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PfiShare2dResult) return false
        return rpmAxis.contentEquals(other.rpmAxis) &&
            loadAxis.contentEquals(other.loadAxis) &&
            pfiSharePercent2d.contentDeepEquals(other.pfiSharePercent2d) &&
            sampleCounts.contentDeepEquals(other.sampleCounts) &&
            totalSamples == other.totalSamples &&
            rpmOnlyCurve == other.rpmOnlyCurve
    }

    override fun hashCode(): Int {
        var h = rpmAxis.contentHashCode()
        h = 31 * h + loadAxis.contentHashCode()
        h = 31 * h + pfiSharePercent2d.contentDeepHashCode()
        h = 31 * h + sampleCounts.contentDeepHashCode()
        h = 31 * h + totalSamples
        h = 31 * h + rpmOnlyCurve.hashCode()
        return h
    }
}

/**
 * Computes RPM-dependent PFI share curves for MED17 dual-injection systems.
 *
 * MED17 2.5T (RS3 / TTRS) uses `InjSys_facPrtnPfi` (0 = pure GDI, 1 = pure PFI)
 * to control the port-vs-direct fuel split.  Real-world behaviour ramps PFI share
 * up towards torque peak, holds steady through the mid-range, then sharply declines
 * towards redline where GDI alone can support the required fuel mass.
 *
 * ## Typical default shape (RS3 2.5T)
 * ```
 *   RPM   1000  2000  3000  4000  4500  5000  5500  6000  6500  7000
 *   PFI%    30    40    50    58    60    60    60    55    35    20
 * ```
 */
object PfiShareCalculator {

    /** Default RPM axis covering idle → redline for the 2.5T 5-cylinder. */
    val DEFAULT_RPM_AXIS = doubleArrayOf(
        1000.0, 2000.0, 3000.0, 4000.0, 4500.0,
        5000.0, 5500.0, 6000.0, 6500.0, 7000.0
    )

    /** Default PFI share (%) matching [DEFAULT_RPM_AXIS]. */
    val DEFAULT_PFI_SHARE = doubleArrayOf(
        30.0, 40.0, 50.0, 58.0, 60.0,
        60.0, 60.0, 55.0, 35.0, 20.0
    )

    /**
     * Build an RPM-dependent PFI share curve.
     *
     * If [targetPfiShare] is `null` the [DEFAULT_PFI_SHARE] is linearly interpolated
     * onto [rpmAxis].  Otherwise [rpmAxis] and [targetPfiShare] must be the same length
     * and are returned directly after validation.
     *
     * @param rpmAxis         RPM breakpoints (must be sorted ascending, at least 1 element)
     * @param targetPfiShare  Desired PFI share (0–100 %) at each RPM point, or null for defaults
     * @return [PfiShareResult] with the computed curve
     */
    fun calculateRpmDependentShare(
        rpmAxis: DoubleArray = DEFAULT_RPM_AXIS,
        targetPfiShare: DoubleArray? = null
    ): PfiShareResult {
        require(rpmAxis.isNotEmpty()) { "RPM axis must not be empty" }
        require(isSortedAscending(rpmAxis)) { "RPM axis must be sorted ascending" }

        val share = if (targetPfiShare != null) {
            require(targetPfiShare.size == rpmAxis.size) {
                "targetPfiShare (${targetPfiShare.size}) must match rpmAxis (${rpmAxis.size})"
            }
            DoubleArray(rpmAxis.size) { i -> targetPfiShare[i].coerceIn(0.0, 100.0) }
        } else {
            // Interpolate default curve onto the requested RPM axis
            DoubleArray(rpmAxis.size) { i ->
                interpolateClamped(rpmAxis[i], DEFAULT_RPM_AXIS, DEFAULT_PFI_SHARE)
            }
        }

        return PfiShareResult(rpmAxis.copyOf(), share)
    }

    /**
     * Extract actual PFI share from MED17 log data and return a smoothed RPM→PFI curve.
     *
     * Reads [Header.RPM_COLUMN_HEADER] and [Header.PFI_SPLIT_FACTOR_HEADER] from the
     * log map.  RPM samples are bucketed into 500 RPM bins and averaged.  Only rows
     * where RPM > 0 are considered.
     *
     * The factor from the log is 0–1; it is converted to 0–100 % in the result.
     *
     * @param logData Parsed MED17 log (header → column of doubles)
     * @return [PfiShareResult] where [PfiShareResult.loggedRpmAxis] and
     *         [PfiShareResult.loggedPfiPercent] contain the observed data, and
     *         [PfiShareResult.rpmAxis] / [PfiShareResult.pfiSharePercent] contain the
     *         default curve for overlay comparison.
     */
    fun refineFromLog(
        logData: Map<Header, List<Double>>
    ): PfiShareResult {
        val rpmColumn = logData[Header.RPM_COLUMN_HEADER]
        val pfiColumn = logData[Header.PFI_SPLIT_FACTOR_HEADER]

        if (rpmColumn.isNullOrEmpty() || pfiColumn.isNullOrEmpty()) {
            return calculateRpmDependentShare()
        }

        val rowCount = minOf(rpmColumn.size, pfiColumn.size)

        // Bucket into 500 RPM bins
        data class Bucket(var sum: Double = 0.0, var count: Int = 0)

        val buckets = mutableMapOf<Int, Bucket>()
        for (i in 0 until rowCount) {
            val rpm = rpmColumn[i]
            if (rpm <= 0.0) continue
            val key = (rpm / RPM_BUCKET_SIZE).toInt() * RPM_BUCKET_SIZE
            val b = buckets.getOrPut(key) { Bucket() }
            b.sum += pfiColumn[i]
            b.count++
        }

        if (buckets.isEmpty()) {
            return calculateRpmDependentShare()
        }

        val sortedKeys = buckets.keys.sorted()
        val logRpm = DoubleArray(sortedKeys.size) { sortedKeys[it].toDouble() }
        val logPfi = DoubleArray(sortedKeys.size) { i ->
            val b = buckets[sortedKeys[i]]!!
            (b.sum / b.count * 100.0).coerceIn(0.0, 100.0)   // factor → %
        }

        val defaultResult = calculateRpmDependentShare()

        return PfiShareResult(
            rpmAxis = defaultResult.rpmAxis,
            pfiSharePercent = defaultResult.pfiSharePercent,
            loggedRpmAxis = logRpm,
            loggedPfiPercent = logPfi
        )
    }

    // ── Internals ────────────────────────────────────────────────────────

    private const val RPM_BUCKET_SIZE = 500

    /** Linear interpolation with clamped extrapolation (same approach as [InjectorScalingSolver]). */
    internal fun interpolateClamped(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        require(xs.size == ys.size && xs.isNotEmpty())
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

    private fun isSortedAscending(a: DoubleArray): Boolean {
        for (i in 1 until a.size) if (a[i] < a[i - 1]) return false
        return true
    }

    // ── 2D RPM × Load binning ───────────────────────────────────────────

    /** RPM bins for 2D grid: 1000–7000 @ 500 RPM steps. */
    val DEFAULT_2D_RPM_BINS = doubleArrayOf(
        1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0,
        4000.0, 4500.0, 5000.0, 5500.0, 6000.0, 6500.0, 7000.0
    )

    /** Load bins for 2D grid: 20–200 % @ 20 % steps. */
    val DEFAULT_2D_LOAD_BINS = doubleArrayOf(
        20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 160.0, 180.0, 200.0
    )

    /**
     * Extract actual PFI share from MED17 log data, binned into a 2D RPM × Load grid.
     *
     * If the log lacks an [Header.ENGINE_LOAD_HEADER] column, falls back to a
     * [PfiShare2dResult] whose 2D grid is synthesised from the 1D RPM-only curve
     * (all load columns identical).
     *
     * @param logData Parsed MED17 log (header → column of doubles)
     * @param rpmBins RPM axis for the output grid (default [DEFAULT_2D_RPM_BINS])
     * @param loadBins Load axis for the output grid (default [DEFAULT_2D_LOAD_BINS])
     * @return [PfiShare2dResult] with the 2D grid plus a backward-compatible 1D curve
     */
    fun refineFromLog2d(
        logData: Map<Header, List<Double>>,
        rpmBins: DoubleArray = DEFAULT_2D_RPM_BINS,
        loadBins: DoubleArray = DEFAULT_2D_LOAD_BINS
    ): PfiShare2dResult {
        val rpmColumn = logData[Header.RPM_COLUMN_HEADER]
        val pfiColumn = logData[Header.PFI_SPLIT_FACTOR_HEADER]
        val loadColumn = logData[Header.ENGINE_LOAD_HEADER]

        // If no RPM or PFI data, return default 2D result
        if (rpmColumn.isNullOrEmpty() || pfiColumn.isNullOrEmpty()) {
            return default2dResult(rpmBins, loadBins)
        }

        // If no load column, fall back to 1D and replicate across loads
        if (loadColumn.isNullOrEmpty()) {
            return fallbackTo1d(logData, rpmBins, loadBins)
        }

        val rowCount = minOf(rpmColumn.size, pfiColumn.size, loadColumn.size)

        val sums = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val counts = Array(rpmBins.size) { IntArray(loadBins.size) }

        for (i in 0 until rowCount) {
            val rpm = rpmColumn[i]
            val load = loadColumn[i]
            val pfi = pfiColumn[i]
            if (rpm <= 0.0 || load <= 0.0) continue

            val rpmIdx = nearestBinIndex(rpm, rpmBins)
            val loadIdx = nearestBinIndex(load, loadBins)

            sums[rpmIdx][loadIdx] += pfi
            counts[rpmIdx][loadIdx]++
        }

        val pfiPercent2d = Array(rpmBins.size) { r ->
            DoubleArray(loadBins.size) { l ->
                if (counts[r][l] > 0) {
                    (sums[r][l] / counts[r][l] * 100.0).coerceIn(0.0, 100.0)
                } else {
                    Double.NaN
                }
            }
        }

        interpolateEmptyCells(pfiPercent2d, counts)

        val rpmOnlyCurve = refineFromLog(logData)

        return PfiShare2dResult(
            rpmAxis = rpmBins.copyOf(),
            loadAxis = loadBins.copyOf(),
            pfiSharePercent2d = pfiPercent2d,
            sampleCounts = counts,
            totalSamples = rowCount,
            rpmOnlyCurve = rpmOnlyCurve
        )
    }

    /**
     * Find the index of the nearest bin to [value].
     */
    internal fun nearestBinIndex(value: Double, bins: DoubleArray): Int {
        var best = 0
        var bestDist = abs(value - bins[0])
        for (i in 1 until bins.size) {
            val dist = abs(value - bins[i])
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    /**
     * Fill NaN cells by averaging cardinal neighbours. Cells with no neighbours
     * at all get the default 50 % PFI share.
     */
    internal fun interpolateEmptyCells(grid: Array<DoubleArray>, counts: Array<IntArray>) {
        for (r in grid.indices) {
            for (l in grid[r].indices) {
                if (counts[r][l] == 0) {
                    val neighbours = mutableListOf<Double>()
                    if (r > 0 && counts[r - 1][l] > 0) neighbours.add(grid[r - 1][l])
                    if (r < grid.size - 1 && counts[r + 1][l] > 0) neighbours.add(grid[r + 1][l])
                    if (l > 0 && counts[r][l - 1] > 0) neighbours.add(grid[r][l - 1])
                    if (l < grid[r].size - 1 && counts[r][l + 1] > 0) neighbours.add(grid[r][l + 1])

                    grid[r][l] = if (neighbours.isNotEmpty()) {
                        neighbours.average()
                    } else {
                        50.0
                    }
                }
            }
        }
    }

    private fun default2dResult(
        rpmBins: DoubleArray = DEFAULT_2D_RPM_BINS,
        loadBins: DoubleArray = DEFAULT_2D_LOAD_BINS
    ): PfiShare2dResult {
        val defaultCurve = calculateRpmDependentShare()
        val grid = Array(rpmBins.size) { r ->
            val pfi = interpolateClamped(rpmBins[r], defaultCurve.rpmAxis, defaultCurve.pfiSharePercent)
            DoubleArray(loadBins.size) { pfi }
        }
        return PfiShare2dResult(
            rpmAxis = rpmBins.copyOf(),
            loadAxis = loadBins.copyOf(),
            pfiSharePercent2d = grid,
            sampleCounts = Array(rpmBins.size) { IntArray(loadBins.size) },
            totalSamples = 0,
            rpmOnlyCurve = defaultCurve
        )
    }

    private fun fallbackTo1d(
        logData: Map<Header, List<Double>>,
        rpmBins: DoubleArray = DEFAULT_2D_RPM_BINS,
        loadBins: DoubleArray = DEFAULT_2D_LOAD_BINS
    ): PfiShare2dResult {
        val rpmOnlyCurve = refineFromLog(logData)
        val grid = Array(rpmBins.size) { r ->
            val pfi = interpolateClamped(rpmBins[r], rpmOnlyCurve.rpmAxis, rpmOnlyCurve.pfiSharePercent)
            DoubleArray(loadBins.size) { pfi }
        }
        return PfiShare2dResult(
            rpmAxis = rpmBins.copyOf(),
            loadAxis = loadBins.copyOf(),
            pfiSharePercent2d = grid,
            sampleCounts = Array(rpmBins.size) { IntArray(loadBins.size) },
            totalSamples = logData[Header.RPM_COLUMN_HEADER]?.size ?: 0,
            rpmOnlyCurve = rpmOnlyCurve
        )
    }
}
