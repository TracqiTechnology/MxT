package domain.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Monotone cubic Hermite interpolation using the Fritsch–Carlson method.
 *
 * Produces C¹-continuous curves that preserve the monotonicity of the input
 * data — no overshoot or oscillation between grid points. This is critical
 * for ECU calibration maps where non-physical oscillations in torque,
 * ignition timing, or pressure could cause engine damage.
 *
 * For axes with fewer than 3 points, degenerates to linear interpolation.
 * Inputs outside the axis range are clamped to edge values.
 */
object MonotoneCubicInterpolator {

    /**
     * Interpolate a 1D dataset at the given [x] value.
     *
     * @param xs Strictly increasing x-axis breakpoints
     * @param ys Corresponding y values
     * @param x  The point to evaluate
     * @return Interpolated (or clamped) y value
     */
    fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        val n = xs.size
        require(n == ys.size) { "xs and ys must have the same length" }
        if (n == 0) return 0.0
        if (n == 1) return ys[0]

        // Clamp to axis boundaries
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]

        // For 2-point data, use linear interpolation
        if (n == 2) {
            val t = (x - xs[0]) / (xs[1] - xs[0])
            return ys[0] + t * (ys[1] - ys[0])
        }

        // Compute secant slopes between consecutive points
        val delta = DoubleArray(n - 1) { i ->
            (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i])
        }

        // Compute initial tangent estimates
        val m = computeTangents(delta, n)

        // Apply Fritsch–Carlson monotonicity constraint
        applyMonotonicity(delta, m)

        // Find the interval containing x
        val k = findInterval(xs, x)

        // Evaluate cubic Hermite basis
        return evaluateHermite(xs[k], xs[k + 1], ys[k], ys[k + 1], m[k], m[k + 1], x)
    }

    /**
     * Compute initial tangent estimates using three-point averages.
     * Endpoint tangents use one-sided differences.
     */
    private fun computeTangents(delta: DoubleArray, n: Int): DoubleArray {
        val m = DoubleArray(n)
        m[0] = delta[0]
        m[n - 1] = delta[n - 2]
        for (i in 1 until n - 1) {
            if (delta[i - 1] * delta[i] <= 0.0) {
                // Sign change or zero — tangent must be zero to preserve monotonicity
                m[i] = 0.0
            } else {
                // Harmonic mean of adjacent secants (better than arithmetic for non-uniform spacing)
                m[i] = (2.0 * delta[i - 1] * delta[i]) / (delta[i - 1] + delta[i])
            }
        }
        return m
    }

    /**
     * Apply Fritsch–Carlson monotonicity adjustment.
     * Ensures the interpolant stays monotone within each interval.
     */
    private fun applyMonotonicity(delta: DoubleArray, m: DoubleArray) {
        for (i in delta.indices) {
            if (abs(delta[i]) < 1e-30) {
                // Flat interval — force tangents to zero
                m[i] = 0.0
                m[i + 1] = 0.0
            } else {
                val alpha = m[i] / delta[i]
                val beta = m[i + 1] / delta[i]
                val radiusSq = alpha * alpha + beta * beta
                if (radiusSq > 9.0) {
                    // Outside the monotonicity region — scale back
                    val tau = 3.0 / sqrt(radiusSq)
                    m[i] = tau * alpha * delta[i]
                    m[i + 1] = tau * beta * delta[i]
                }
            }
        }
    }

    private fun findInterval(xs: DoubleArray, x: Double): Int {
        for (i in 0 until xs.size - 1) {
            if (x >= xs[i] && x <= xs[i + 1]) return i
        }
        return (xs.size - 2).coerceAtLeast(0)
    }

    /**
     * Evaluate cubic Hermite polynomial on the interval [x0, x1].
     */
    private fun evaluateHermite(
        x0: Double, x1: Double,
        y0: Double, y1: Double,
        m0: Double, m1: Double,
        x: Double
    ): Double {
        val h = x1 - x0
        val t = (x - x0) / h
        val t2 = t * t
        val t3 = t2 * t

        // Hermite basis functions
        val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
        val h10 = t3 - 2.0 * t2 + t
        val h01 = -2.0 * t3 + 3.0 * t2
        val h11 = t3 - t2

        return h00 * y0 + h10 * h * m0 + h01 * y1 + h11 * h * m1
    }
}
