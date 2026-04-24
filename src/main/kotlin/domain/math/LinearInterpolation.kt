package domain.math

import java.util.Arrays

object LinearInterpolation {

    fun interpolate(xIn: Array<Double>, yIn: Array<Double>, xi: Array<Double>): Array<Double> {
        val x = xIn.clone()
        val y = yIn.clone()

        require(x.size == y.size) { "X and Y must be the same length" }
        require(x.size > 1) { "X must contain more than one value" }

        val dx = DoubleArray(x.size - 1)
        val dy = DoubleArray(x.size - 1)
        val slope = DoubleArray(x.size - 1)
        val intercept = DoubleArray(x.size - 1)

        for (i in 0 until x.size - 1) {
            dx[i] = x[i + 1] - x[i]
            if (dx[i] < 0) {
                throw IllegalArgumentException("X must be sorted ${x[i + 1]} ${x[i]}")
            }
            if (dx[i] == 0.0) {
                x[i + 1] += x[i + 1] * 0.01
            }
            dy[i] = y[i + 1] - y[i]
            slope[i] = dy[i] / dx[i]
            intercept[i] = y[i] - x[i] * slope[i]
        }

        return Array(xi.size) { i ->
            if (xi[i] < x[0]) {
                y[0]
            } else {
                var loc = Arrays.binarySearch(x, xi[i])
                if (loc < -1) {
                    loc = -loc - 2
                    minOf(
                        slope[minOf(loc, slope.size - 1)] * xi[i] + intercept[minOf(loc, intercept.size - 1)],
                        y[y.size - 1]
                    )
                } else {
                    y[loc]
                }
            }
        }
    }
}
