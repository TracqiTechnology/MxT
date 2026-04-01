package domain.math

import domain.math.map.Map3d
import kotlin.math.abs

/**
 * Rescales a 3D map to new axis breakpoints using bilinear interpolation.
 *
 * When axis breakpoints change, the Z-values are recomputed by sampling
 * the original map surface at each new grid point via [Map3d.lookup].
 */
object AxisRescaler {

    /**
     * Result of a rescale operation with diagnostics.
     */
    data class RescaleResult(
        val rescaledMap: Map3d,
        /** Per-cell flags: true if the new breakpoint was outside the original axis range (extrapolated/clamped) */
        val extrapolatedCells: Array<BooleanArray>,
        /** Number of cells that required extrapolation (clamped to nearest edge) */
        val extrapolatedCount: Int,
        /** Number of cells where the new breakpoint exactly matched an original breakpoint */
        val exactMatchCount: Int,
        /** Total cells in the output map */
        val totalCells: Int
    )

    private const val EXACT_MATCH_TOLERANCE = 1e-9

    /**
     * Rescale a map to new X-axis and/or Y-axis breakpoints.
     *
     * Uses [Map3d.lookup] for bilinear interpolation at each new (x, y) grid point.
     * Points outside the original axis range are clamped to the nearest edge value.
     *
     * @param original The source map to rescale
     * @param newXAxis New X-axis breakpoints (load, pressure, etc). Pass null to keep original.
     * @param newYAxis New Y-axis breakpoints (RPM, etc). Pass null to keep original.
     * @return RescaleResult with the rescaled map and diagnostics
     * @throws IllegalArgumentException if axes are not strictly monotonically increasing
     */
    fun rescaleMap(
        original: Map3d,
        newXAxis: Array<Double>? = null,
        newYAxis: Array<Double>? = null
    ): RescaleResult {
        val xAxis = newXAxis ?: original.xAxis
        val yAxis = newYAxis ?: original.yAxis

        require(isStrictlyIncreasing(xAxis)) { "X-axis must be strictly monotonically increasing" }
        require(isStrictlyIncreasing(yAxis)) { "Y-axis must be strictly monotonically increasing" }

        val extrapolated = Array(yAxis.size) { BooleanArray(xAxis.size) }
        var extrapolatedCount = 0
        var exactMatchCount = 0

        val zAxis = Array(yAxis.size) { rowIdx ->
            Array(xAxis.size) { colIdx ->
                val x = xAxis[colIdx]
                val y = yAxis[rowIdx]

                val outsideX = x < original.xAxis.first() || x > original.xAxis.last()
                val outsideY = y < original.yAxis.first() || y > original.yAxis.last()
                if (outsideX || outsideY) {
                    extrapolated[rowIdx][colIdx] = true
                    extrapolatedCount++
                }

                val exactX = original.xAxis.any { abs(it - x) < EXACT_MATCH_TOLERANCE }
                val exactY = original.yAxis.any { abs(it - y) < EXACT_MATCH_TOLERANCE }
                if (exactX && exactY) exactMatchCount++

                original.lookup(x, y)
            }
        }

        return RescaleResult(
            rescaledMap = Map3d(xAxis, yAxis, zAxis),
            extrapolatedCells = extrapolated,
            extrapolatedCount = extrapolatedCount,
            exactMatchCount = exactMatchCount,
            totalCells = xAxis.size * yAxis.size
        )
    }

    /**
     * Rescale only the X-axis (convenience method).
     */
    fun rescaleXAxis(original: Map3d, newXAxis: Array<Double>): RescaleResult =
        rescaleMap(original, newXAxis = newXAxis)

    /**
     * Rescale only the Y-axis (convenience method).
     */
    fun rescaleYAxis(original: Map3d, newYAxis: Array<Double>): RescaleResult =
        rescaleMap(original, newYAxis = newYAxis)

    private fun isStrictlyIncreasing(axis: Array<Double>): Boolean {
        for (i in 1 until axis.size) {
            if (axis[i] <= axis[i - 1]) return false
        }
        return true
    }
}
