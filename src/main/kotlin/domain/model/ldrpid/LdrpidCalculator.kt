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

        // Interpolate empty cells and enforce monotonicity.
        // Empty cells are filled using physical model: boost scales with duty cycle.
        for (rowIdx in nonLinearTable.indices) {
            val row = nonLinearTable[rowIdx]
            val filledIndices = row.indices.filter { count[rowIdx][it] > 0 }

            if (filledIndices.isEmpty()) {
                // No data in this RPM row — fill with small ascending placeholders
                for (i in row.indices) row[i] = 0.1 + i * 0.01
            } else {
                // Interpolate/extrapolate empty cells
                for (i in row.indices) {
                    if (row[i] == 0.0) {
                        val left = filledIndices.lastOrNull { it < i }
                        val right = filledIndices.firstOrNull { it > i }
                        row[i] = when {
                            left != null && right != null -> {
                                // Between two data points: linear interpolation
                                val t = (i - left).toDouble() / (right - left)
                                row[left] + t * (row[right] - row[left])
                            }
                            left != null -> {
                                // Beyond rightmost data: gentle upward extrapolation
                                val prevFilled = filledIndices.lastOrNull { it < left }
                                if (prevFilled != null) {
                                    val slope = (row[left] - row[prevFilled]) / (left - prevFilled)
                                    (row[left] + slope * (i - left)).coerceAtLeast(row[left])
                                } else {
                                    row[left] * (1.0 + 0.02 * (i - left))
                                }
                            }
                            right != null -> {
                                // Before leftmost data: scale down proportionally to duty.
                                // Physically, lower duty → lower boost (wastegate more open).
                                val dutyHere = dutyAxis[i].coerceAtLeast(1.0)
                                val dutyThere = dutyAxis[right].coerceAtLeast(1.0)
                                (row[right] * dutyHere / dutyThere).coerceAtLeast(0.1)
                            }
                            else -> 0.1
                        }
                    }
                }
            }

            // Enforce minimum and monotonicity (higher duty → higher or equal boost)
            for (i in row.indices) {
                if (row[i] <= 0.0 || row[i].isNaN()) row[i] = 0.1
            }
            for (i in 1 until row.size) {
                if (row[i] < row[i - 1]) {
                    row[i] = row[i - 1] + 0.01
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
            // Pair boost→duty and sort by boost so interpolation x-axis is ascending
            val pairs = nonLinearTable[i].zip(dutyAxis).sortedBy { it.first }
            val sortedBoost = pairs.map { it.first }.toTypedArray()
            val sortedDuty = pairs.map { it.second }.toTypedArray()
            Array(nonLinearTable[i].size) { j ->
                val xi = arrayOf(linearTable[i][j])
                val result = LinearInterpolation.interpolate(sortedBoost, sortedDuty, xi)[0]
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
            // Pair linearBoostMax→duty and sort by boost for correct interpolation
            val pairs = linearBoostMax.zip(dutyAxis).sortedBy { it.first }
            val sortedBoost = pairs.map { it.first }.toTypedArray()
            val sortedDuty = pairs.map { it.second }.toTypedArray()
            Array(kfldimxXAxis.size) { j ->
                val xi = arrayOf(kfldimxXAxis[j])
                LinearInterpolation.interpolate(sortedBoost, sortedDuty, xi)[0]
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

    /**
     * Check whether multiple log files have consistent boost/duty relationships.
     * Returns a warning message if files appear to be from different tune stages,
     * or null if the data looks consistent (or there's only one file).
     *
     * The check computes a "tune signature" per file: the median relative boost
     * pressure observed during WOT. If the signatures differ by more than 50%
     * (ratio of max to min), the logs likely come from different calibrations
     * and will produce unreliable KFLDRL output.
     */
    fun checkLogConsistency(
        perFileData: List<Map<Me7LogFileContract.Header, List<Double>>>
    ): String? {
        if (perFileData.size <= 1) return null

        val signatures = perFileData.mapNotNull { fileData ->
            val throttles = fileData[Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER] ?: return@mapNotNull null
            val boosts = fileData[Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER] ?: return@mapNotNull null
            val baros = fileData[Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER] ?: return@mapNotNull null

            val wotBoosts = throttles.indices
                .filter { throttles[it] >= 80.0 }
                .map { boosts[it] - baros[it] }
                .filter { it > 0 }

            if (wotBoosts.isEmpty()) return@mapNotNull null
            wotBoosts.sorted()[wotBoosts.size / 2]  // median relative boost
        }

        if (signatures.size <= 1) return null

        val minSig = signatures.min()
        val maxSig = signatures.max()

        if (minSig <= 0) return null

        val ratio = maxSig / minSig
        return if (ratio > 2.0) {
            "Warning: Loaded log files appear to be from different tune stages " +
                "(boost levels vary by %.0f%%). ".format((ratio - 1) * 100) +
                "Mixing logs from different calibrations may produce unreliable " +
                "KFLDRL/KFLDIMX output. Use logs from a single tune stage for best results."
        } else null
    }
}
