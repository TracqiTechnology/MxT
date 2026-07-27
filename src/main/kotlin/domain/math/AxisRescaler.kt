package domain.math

import domain.math.map.Map3d
import kotlin.math.abs

enum class ResamplingMethod {
    BILINEAR,
    MONOTONE_CUBIC
}

/**
 * Rescales a 3D map to new axis breakpoints using bilinear interpolation by
 * default, with separable monotone cubic Hermite interpolation available when
 * preserving a non-linear surface shape is preferable.
 *
 * When axis breakpoints change, the Z-values are recomputed in two passes:
 * 1. Interpolate each original row along the X-axis to the new X breakpoints
 * 2. Interpolate each resulting column along the Y-axis to the new Y breakpoints
 *
 * Exact source-axis intersections are always copied without interpolation.
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
        val totalCells: Int,
        val method: ResamplingMethod = ResamplingMethod.BILINEAR
    )

    private const val EXACT_MATCH_TOLERANCE = 1e-9

    /**
     * Rescale a map to new X-axis and/or Y-axis breakpoints.
     *
     * For each new grid point, the value is computed by first interpolating
     * along X for each original row, then interpolating those intermediate
     * values along Y with the selected [method].
     *
     * Points outside the original axis range are clamped to the nearest edge value.
     *
     * @param original The source map to rescale
     * @param newXAxis New X-axis breakpoints (load, pressure, etc). Pass null to keep original.
     * @param newYAxis New Y-axis breakpoints (RPM, etc). Pass null to keep original.
     * @param method Bilinear by default; monotone cubic is available explicitly.
     * @return RescaleResult with the rescaled map and diagnostics
     * @throws IllegalArgumentException if axes are not strictly monotonically increasing
     */
    fun rescaleMap(
        original: Map3d,
        newXAxis: Array<Double>? = null,
        newYAxis: Array<Double>? = null,
        method: ResamplingMethod = ResamplingMethod.BILINEAR
    ): RescaleResult {
        require(isStrictlyIncreasing(original.xAxis)) {
            "Source X-axis must be finite and strictly monotonically increasing"
        }
        require(isStrictlyIncreasing(original.yAxis)) {
            "Source Y-axis must be finite and strictly monotonically increasing"
        }
        require(
            original.zAxis.size == original.yAxis.size &&
                original.zAxis.all { row ->
                    row.size == original.xAxis.size && row.all(Double::isFinite)
                }
        ) {
            "Source Z-data must be finite and match the source axis dimensions"
        }
        val xAxis = newXAxis ?: original.xAxis
        val yAxis = newYAxis ?: original.yAxis

        require(isStrictlyIncreasing(xAxis)) {
            "X-axis must be finite and strictly monotonically increasing"
        }
        require(isStrictlyIncreasing(yAxis)) {
            "Y-axis must be finite and strictly monotonically increasing"
        }

        // Compute extrapolation/exact-match diagnostics
        val extrapolated = Array(yAxis.size) { BooleanArray(xAxis.size) }
        var extrapolatedCount = 0
        var exactMatchCount = 0

        for (rowIdx in yAxis.indices) {
            for (colIdx in xAxis.indices) {
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
            }
        }

        val zAxis = when (method) {
            ResamplingMethod.BILINEAR -> resampleBilinear(original, xAxis, yAxis)
            ResamplingMethod.MONOTONE_CUBIC -> resampleMonotoneCubic(original, xAxis, yAxis)
        }

        // Preserve exact source intersections byte-for-byte instead of relying on
        // an interpolator to reproduce them approximately.
        for (row in yAxis.indices) {
            val sourceRow = original.yAxis.indexOfFirst { abs(it - yAxis[row]) < EXACT_MATCH_TOLERANCE }
            if (sourceRow < 0) continue
            for (col in xAxis.indices) {
                val sourceCol = original.xAxis.indexOfFirst { abs(it - xAxis[col]) < EXACT_MATCH_TOLERANCE }
                if (sourceCol >= 0) zAxis[row][col] = original.zAxis[sourceRow][sourceCol]
            }
        }

        return RescaleResult(
            rescaledMap = Map3d(xAxis, yAxis, zAxis),
            extrapolatedCells = extrapolated,
            extrapolatedCount = extrapolatedCount,
            exactMatchCount = exactMatchCount,
            totalCells = xAxis.size * yAxis.size,
            method = method
        )
    }

    /**
     * Rescale only the X-axis (convenience method).
     */
    fun rescaleXAxis(
        original: Map3d,
        newXAxis: Array<Double>,
        method: ResamplingMethod = ResamplingMethod.BILINEAR
    ): RescaleResult = rescaleMap(original, newXAxis = newXAxis, method = method)

    /**
     * Rescale only the Y-axis (convenience method).
     */
    fun rescaleYAxis(
        original: Map3d,
        newYAxis: Array<Double>,
        method: ResamplingMethod = ResamplingMethod.BILINEAR
    ): RescaleResult = rescaleMap(original, newYAxis = newYAxis, method = method)

    private fun resampleBilinear(
        original: Map3d,
        xAxis: Array<Double>,
        yAxis: Array<Double>
    ): Array<Array<Double>> {
        val intermediate = Array(original.yAxis.size) { row ->
            DoubleArray(xAxis.size) { col ->
                interpolateLinearClamped(original.xAxis, original.zAxis[row], xAxis[col])
            }
        }
        return Array(yAxis.size) { row ->
            Array(xAxis.size) { col ->
                val column = Array(original.yAxis.size) { sourceRow -> intermediate[sourceRow][col] }
                interpolateLinearClamped(original.yAxis, column, yAxis[row])
            }
        }
    }

    private fun resampleMonotoneCubic(
        original: Map3d,
        xAxis: Array<Double>,
        yAxis: Array<Double>
    ): Array<Array<Double>> {
        val origXs = original.xAxis.toDoubleArray()
        val origYs = original.yAxis.toDoubleArray()
        val intermediate = Array(original.yAxis.size) { row ->
            val rowValues = DoubleArray(original.xAxis.size) { col -> original.zAxis[row][col] }
            DoubleArray(xAxis.size) { col ->
                MonotoneCubicInterpolator.interpolate(origXs, rowValues, xAxis[col])
            }
        }
        return Array(yAxis.size) { row ->
            Array(xAxis.size) { col ->
                val columnValues = DoubleArray(original.yAxis.size) { sourceRow -> intermediate[sourceRow][col] }
                MonotoneCubicInterpolator.interpolate(origYs, columnValues, yAxis[row])
            }
        }
    }

    private fun interpolateLinearClamped(
        axis: Array<Double>,
        values: Array<Double>,
        point: Double
    ): Double {
        require(axis.size == values.size && axis.isNotEmpty())
        if (axis.size == 1 || point <= axis.first()) return values.first()
        if (point >= axis.last()) return values.last()
        val upper = axis.indexOfFirst { it >= point }
        if (abs(axis[upper] - point) < EXACT_MATCH_TOLERANCE) return values[upper]
        val lower = upper - 1
        val fraction = (point - axis[lower]) / (axis[upper] - axis[lower])
        return values[lower] + fraction * (values[upper] - values[lower])
    }

    private fun isStrictlyIncreasing(axis: Array<Double>): Boolean =
        axis.isNotEmpty() &&
            axis.all(Double::isFinite) &&
            (1 until axis.size).all { axis[it] > axis[it - 1] }
}
