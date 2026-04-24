package domain.math

import domain.math.map.Map3d

object Inverse {
    fun calculateInverse(input: Map3d, output: Map3d): Map3d {
        val inverse = Map3d(output)

        // Guard: iterate only over rows that exist in BOTH maps' y-axes and z-axes
        val rowCount = minOf(input.yAxis.size, input.zAxis.size, inverse.yAxis.size, inverse.zAxis.size)
        for (i in 0 until rowCount) {
            // For each RPM row, the KFMIOP z-values (torque%) are the x-axis
            // and the KFMIOP x-axis (load%) are the y-axis of the inverse lookup.
            val xs = input.zAxis[i].map { it }.toDoubleArray()
            val ys = input.xAxis.map { it }.toDoubleArray()

            if (xs.size >= 3) {
                // Use monotone cubic for smoother C¹-continuous inverse,
                // especially in high-load regions where KFMIOP has steep gradients
                for (j in output.xAxis.indices) {
                    inverse.zAxis[i][j] = MonotoneCubicInterpolator.interpolate(xs, ys, output.xAxis[j])
                }
            } else {
                // Degenerate: fall back to linear interpolation
                for (j in output.xAxis.indices) {
                    val xi = arrayOf(output.xAxis[j])
                    inverse.zAxis[i][j] = LinearInterpolation.interpolate(xs.toTypedArray(), ys.toTypedArray(), xi)[0]
                }
            }
        }

        return inverse
    }
}
