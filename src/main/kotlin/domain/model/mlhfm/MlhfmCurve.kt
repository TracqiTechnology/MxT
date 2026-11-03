package domain.model.mlhfm

import domain.math.map.Map3d

/** Algorithms use a column curve; BIN writes must retain the source table layout. */
object MlhfmCurve {
    fun toColumn(native: Map3d): Map3d {
        val horizontal = native.zAxis.size == 1 && native.zAxis[0].size > 1
        val voltage = if (horizontal) native.xAxis else native.yAxis
        val values = if (horizontal) native.zAxis[0] else {
            require(native.zAxis.all { it.size == 1 }) { "MLHFM must be a one-dimensional curve." }
            native.zAxis.map { it[0] }.toTypedArray()
        }
        require(voltage.size > 1 && voltage.size == values.size) {
            "MLHFM requires a voltage breakpoint for every airflow value. Check the XDF axis definition."
        }
        require(voltage.all { it.isFinite() } && voltage.toList().zipWithNext().all { (a, b) -> a < b }) {
            "MLHFM voltage breakpoints must be finite and strictly increasing. Check for missing XDF labels."
        }
        require(values.all { it.isFinite() }) { "MLHFM airflow values must be finite." }
        return Map3d(emptyArray(), voltage, Array(values.size) { arrayOf(values[it]) })
    }

    fun forWrite(column: Map3d, native: Map3d): Map3d {
        val source = toColumn(native)
        val corrected = toColumn(column)
        require(source.yAxis.contentEquals(corrected.yAxis)) { "MLHFM voltage breakpoints cannot change during correction." }
        return Map3d(native).apply {
            zAxis = if (native.zAxis.size == 1) {
                arrayOf(corrected.zAxis.map { it[0] }.toTypedArray())
            } else Array(corrected.zAxis.size) { corrected.zAxis[it].copyOf() }
        }
    }
}
