package domain.model.ldrpid

import data.contract.Me7LogFileContract
import domain.math.Index
import domain.math.LinearInterpolation
import domain.math.map.Map3d
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

object LdrpidCalculator {

    data class LdrpidResult(
        val nonLinearOutput: Map3d,
        val linearOutput: Map3d,
        val kfldrl: Map3d,
        val kfldimx: Map3d
    )

    private const val DEFAULT_RPM_ROWS = 8
    private const val DEFAULT_DUTY_COLS = 10

    /**
     * Derive a sensible RPM axis from log data when the map's axis is degenerate.
     * A degenerate axis is one with ≤1 rows, or one where <30% of WOT log RPM samples
     * fall within its range.
     */
    private fun deriveRpmAxis(
        values: Map<Me7LogFileContract.Header, List<Double>>,
        existingAxis: Array<Double>,
        numRows: Int = DEFAULT_RPM_ROWS
    ): Array<Double> {
        val rpms = values[Me7LogFileContract.Header.RPM_COLUMN_HEADER] ?: return existingAxis
        val throttles = values[Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER] ?: return existingAxis

        // Collect WOT RPM samples (throttle >= 80%)
        val wotRpms = rpms.zip(throttles).filter { it.second >= 80.0 }.map { it.first }
        if (wotRpms.isEmpty()) return existingAxis

        val minRpm = wotRpms.min()
        val maxRpm = wotRpms.max()

        // Check if existing axis is adequate: ≥2 rows and covers ≥30% of WOT data range
        if (existingAxis.size >= 2) {
            val axisMin = existingAxis.first()
            val axisMax = existingAxis.last()
            val coveredSamples = wotRpms.count { it in axisMin..axisMax }
            val coverage = coveredSamples.toDouble() / wotRpms.size
            if (coverage >= 0.3) return existingAxis
        }

        // Build a new RPM axis covering the WOT data range
        // Round to nearest 500 RPM for clean breakpoints
        val roundedMin = (floor(minRpm / 500.0) * 500).coerceAtLeast(1000.0)
        val roundedMax = (ceil(maxRpm / 500.0) * 500).coerceAtMost(9000.0)
        val step = ((roundedMax - roundedMin) / (numRows - 1)).coerceAtLeast(500.0)

        return Array(numRows) { i ->
            (roundedMin + step * i).coerceAtMost(roundedMax)
        }
    }

    /**
     * Derive a sensible duty cycle axis when the map's X axis is degenerate.
     */
    private fun deriveDutyAxis(
        existingAxis: Array<Double>,
        numCols: Int = DEFAULT_DUTY_COLS
    ): Array<Double> {
        if (existingAxis.size >= 2) return existingAxis
        // Standard duty cycle breakpoints from 0 to 95%
        val step = 95.0 / (numCols - 1)
        return Array(numCols) { i -> (step * i).roundToInt().toDouble() }
    }

    fun calculateNonLinearTable(values: Map<Me7LogFileContract.Header, List<Double>>, kfldrlMap: Map3d): Map3d {
        val throttlePlateAngles = values[Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER]!!
        val rpms = values[Me7LogFileContract.Header.RPM_COLUMN_HEADER]!!
        val dutyCycles = values[Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER]!!
        val barometricPressures = values[Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER]!!
        val absoluteBoostPressures = values[Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!

        // Use adequate axes — derive from log data if the map's axes are degenerate
        val rpmAxis = deriveRpmAxis(values, kfldrlMap.yAxis)
        val dutyAxis = deriveDutyAxis(kfldrlMap.xAxis)

        val nonLinearTable = Array(rpmAxis.size) { Array(dutyAxis.size) { 0.0 } }
        val pressure = Array(rpmAxis.size) { DoubleArray(dutyAxis.size) }
        val count = Array(rpmAxis.size) { DoubleArray(dutyAxis.size) }

        for (i in throttlePlateAngles.indices) {
            if (throttlePlateAngles[i] >= 80) {
                val rpm = rpms[i]
                val dutyCycle = dutyCycles[i]
                val barometricPressure = barometricPressures[i]
                val absoluteBoostPressure = absoluteBoostPressures[i]
                val relativeBoostPressure = absoluteBoostPressure - barometricPressure

                val rpmIndex = Index.getInsertIndex(rpmAxis.toList(), rpm)
                val dutyCycleIndex = Index.getInsertIndex(dutyAxis.toList(), dutyCycle)

                if (relativeBoostPressure > 0) {
                    pressure[rpmIndex][dutyCycleIndex] += relativeBoostPressure
                    count[rpmIndex][dutyCycleIndex] += 1
                }
            }
        }

        for (j in nonLinearTable.indices) {
            for (k in nonLinearTable[j].indices) {
                nonLinearTable[j][k] = if (count[j][k] != 0.0) {
                    (pressure[j][k] / count[j][k]) * 0.0145038
                } else {
                    pressure[j][k] * 0.0145038
                }
            }
        }

        for (array in nonLinearTable) {
            array.sort()
            for (i in 0 until array.size - 1) {
                if (array[i] == 0.0) array[i] = 0.1
                if (array[i] >= array[i + 1]) {
                    array[i + 1] = if (i > 0) {
                        val theta = array[i] / array[i - 1]
                        array[i] * (1 + (theta - 1) / 2)
                    } else {
                        array[i] * 1.1
                    }
                    if (array[i + 1].isNaN() || array[i + 1] == 0.0) {
                        array[i + 1] = array[i] + 0.1
                    }
                }
            }
        }

        return Map3d(dutyAxis, rpmAxis, nonLinearTable)
    }

    fun calculateLinearTable(nonLinearTable: Array<Array<Double>>, kfldrlMap: Map3d): Map3d {
        if (nonLinearTable.isEmpty() || nonLinearTable[0].isEmpty()) {
            return Map3d(kfldrlMap.xAxis, kfldrlMap.yAxis, emptyArray())
        }
        val linearTable = Array(nonLinearTable.size) { Array(nonLinearTable[0].size) { 0.0 } }

        for (i in nonLinearTable[0].indices) {
            val min = nonLinearTable[0][i]
            val max = nonLinearTable[nonLinearTable.size - 1][i]
            val step = (max - min) / (nonLinearTable.size - 1)

            for (j in linearTable.indices) {
                linearTable[j][i] = min + step * j
            }
        }

        return Map3d(kfldrlMap.xAxis, kfldrlMap.yAxis, linearTable)
    }

    fun calculateKfldrl(nonLinearTable: Array<Array<Double>>, linearTable: Array<Array<Double>>, kfldrlMap: Map3d): Map3d {
        val dutyAxis = if (kfldrlMap.xAxis.size >= 2) kfldrlMap.xAxis else deriveDutyAxis(kfldrlMap.xAxis)
        val kfldrl = Array(nonLinearTable.size) { i ->
            Array(nonLinearTable[i].size) { j ->
                val x = nonLinearTable[i]
                val y = dutyAxis
                val xi = arrayOf(linearTable[i][j])
                val result = LinearInterpolation.interpolate(x, y, xi)[0]
                if (result.isNaN()) 0.0 else result
            }
        }
        return Map3d(dutyAxis, kfldrlMap.yAxis, kfldrl)
    }

    fun calculateKfldimx(nonLinearTable: Array<Array<Double>>, linearTable: Array<Array<Double>>, kfldrlMap: Map3d, kfldimxMap: Map3d): Map3d {
        if (linearTable.isEmpty() || linearTable[0].isEmpty() || nonLinearTable.isEmpty()) {
            return Map3d(kfldimxMap.xAxis, kfldimxMap.yAxis, emptyArray())
        }
        val dutyAxis = if (kfldrlMap.xAxis.size >= 2) kfldrlMap.xAxis else deriveDutyAxis(kfldrlMap.xAxis)
        if (kfldimxMap.xAxis.size < 2 && dutyAxis.size < 2) {
            return Map3d(kfldimxMap.xAxis, kfldimxMap.yAxis, emptyArray())
        }
        val linearBoostMax = Array(linearTable[0].size) { i ->
            val linearBoost = linearTable.map { it[i] * 68.9476 }
            Collections.max(linearBoost)
        }

        val targetXAxisSize = if (kfldimxMap.xAxis.size >= 2) kfldimxMap.xAxis.size else DEFAULT_DUTY_COLS
        val kfldimxXAxis = Array(targetXAxisSize) { 0.0 }
        val min = ceil(linearBoostMax[0] / 100.0) * 100
        val max = ceil(linearBoostMax[linearBoostMax.size - 1] / 100.0) * 100
        val interval = if (kfldimxXAxis.size > 1) (max - min) / (kfldimxXAxis.size - 1) else 0.0

        for (i in kfldimxXAxis.indices) {
            kfldimxXAxis[i] = min + interval * i
        }

        val kfldimx = Array(nonLinearTable.size) { i ->
            Array(kfldimxXAxis.size) { j ->
                val x = linearBoostMax
                val y = dutyAxis
                val xi = arrayOf(kfldimxXAxis[j])
                LinearInterpolation.interpolate(x, y, xi)[0]
            }
        }

        return Map3d(kfldimxXAxis, kfldimxMap.yAxis, kfldimx)
    }

    fun calculateLdrpid(values: Map<Me7LogFileContract.Header, List<Double>>, kfldrlMap: Map3d, kfldimxMap: Map3d): LdrpidResult {
        val nonLinearTable = calculateNonLinearTable(values, kfldrlMap)
        val linearTable = calculateLinearTable(nonLinearTable.zAxis, kfldrlMap)
        val kfldrl = calculateKfldrl(nonLinearTable.zAxis, linearTable.zAxis, kfldrlMap)
        val kfldimxMap3d = calculateKfldimx(nonLinearTable.zAxis, linearTable.zAxis, kfldrlMap, kfldimxMap)

        return LdrpidResult(nonLinearTable, linearTable, kfldrl, kfldimxMap3d)
    }
}
