package domain.model.ldrpid

import data.contract.Me7LogFileContract
import domain.math.Index
import domain.math.MonotoneCubicInterpolator
import domain.math.map.Map3d
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

object LdrpidCalculator {

    data class LdrpidResult(
        val nonLinearOutput: Map3d,
        val linearOutput: Map3d,
        val kfldrl: Map3d,
        val kfldimx: Map3d,
        /** Per-cell sample count for the non-linear table [rpmIdx][dutyIdx]. Cells with 0 were interpolated. */
        val nonLinearSampleCounts: Array<IntArray> = emptyArray(),
        /** Per-cell sample count for KFLDRL [rpmIdx][pressureIdx]. Derived from non-linear table. */
        val kfldrlSampleCounts: Array<IntArray> = emptyArray(),
        /** Per-cell sample count for KFLDIMX [rpmIdx][pressureIdx]. Derived from KFLDRL. */
        val kfldimxSampleCounts: Array<IntArray> = emptyArray(),
        val logDiagnostics: List<LogDiagnosticRow> = emptyList()
    )

    data class LogDiagnosticRow(
        val rpm: Double,
        val sampleCount: Int,
        val measuredDutyCells: Int,
        val averageAbsolutePressureErrorMbar: Double?,
        val maximumOvershootMbar: Double?,
        val percentWithinTolerance: Double?
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
        if (roundedMax <= roundedMin) return arrayOf(roundedMin)
        val rowCount = minOf(
            numRows,
            ((roundedMax - roundedMin) / 500.0).toInt() + 1
        ).coerceAtLeast(2)
        val step = (roundedMax - roundedMin) / (rowCount - 1)

        return Array(rowCount) { i -> roundedMin + step * i }
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
        // Interior gaps use monotone cubic; edges use physical extrapolation.
        for (rowIdx in nonLinearTable.indices) {
            val row = nonLinearTable[rowIdx]
            val filledIndices = row.indices.filter { count[rowIdx][it] > 0 }

            if (filledIndices.isNotEmpty()) {
                val firstFilled = filledIndices.first()
                val lastFilled = filledIndices.last()

                // Interior gaps (between first and last known points): monotone cubic if enough data
                if (filledIndices.size >= 3) {
                    val knownX = filledIndices.map { dutyAxis[it] }.toDoubleArray()
                    val knownY = filledIndices.map { row[it] }.toDoubleArray()
                    for (i in (firstFilled + 1) until lastFilled) {
                        if (row[i] == 0.0) {
                            row[i] = MonotoneCubicInterpolator.interpolate(knownX, knownY, dutyAxis[i])
                        }
                    }
                } else {
                    // Interior with < 3 points: linear
                    for (i in (firstFilled + 1) until lastFilled) {
                        if (row[i] == 0.0) {
                            val left = filledIndices.lastOrNull { it < i }
                            val right = filledIndices.firstOrNull { it > i }
                            if (left != null && right != null) {
                                val span = dutyAxis[right] - dutyAxis[left]
                                val t = if (span == 0.0) 0.0 else
                                    (dutyAxis[i] - dutyAxis[left]) / span
                                row[i] = row[left] + t * (row[right] - row[left])
                            }
                        }
                    }
                }

                // Extrapolation beyond rightmost data: slope-based
                for (i in (lastFilled + 1) until row.size) {
                    if (row[i] == 0.0) {
                        val prevFilled = filledIndices.lastOrNull { it < lastFilled }
                        row[i] = if (prevFilled != null) {
                            val span = dutyAxis[lastFilled] - dutyAxis[prevFilled]
                            val slope = if (span == 0.0) 0.0 else
                                (row[lastFilled] - row[prevFilled]) / span
                            (row[lastFilled] + slope * (dutyAxis[i] - dutyAxis[lastFilled]))
                                .coerceAtLeast(row[lastFilled])
                        } else {
                            row[lastFilled] * (1.0 + 0.02 * (i - lastFilled))
                        }
                    }
                }

                // Extrapolation before leftmost data: duty-proportional
                for (i in 0 until firstFilled) {
                    if (row[i] == 0.0) {
                        val dutyHere = dutyAxis[i].coerceAtLeast(1.0)
                        val dutyThere = dutyAxis[firstFilled].coerceAtLeast(1.0)
                        row[i] = (row[firstFilled] * dutyHere / dutyThere).coerceAtLeast(0.1)
                    }
                }
            }

            if (filledIndices.isNotEmpty()) {
                // Enforce minimum and monotonicity only on rows supported by the log.
                for (i in row.indices) {
                    if (count[rowIdx][i] == 0.0 && (row[i] <= 0.0 || !row[i].isFinite())) {
                        row[i] = 0.1
                    }
                }
                for (i in 1 until row.size) {
                    if (count[rowIdx][i] == 0.0 && row[i] < row[i - 1]) {
                        row[i] = row[i - 1] + 0.01
                    }
                }
            }
        }

        return Map3d(dutyAxis, rpmAxis, nonLinearTable)
    }

    /**
     * Calculate the non-linear boost table and return per-cell sample counts.
     * Cells with count=0 were interpolated rather than measured.
     */
    fun calculateNonLinearTableWithCounts(
        values: Map<Me7LogFileContract.Header, List<Double>>,
        kfldrlMap: Map3d
    ): Pair<Map3d, Array<IntArray>> {
        val throttlePlateAngles = values[Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER]!!
        val rpms = values[Me7LogFileContract.Header.RPM_COLUMN_HEADER]!!
        val dutyCycles = values[Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER]!!
        val barometricPressures = values[Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER]!!
        val absoluteBoostPressures = values[Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!

        val rpmAxis = deriveRpmAxis(values, kfldrlMap.yAxis)
        val dutyAxis = deriveDutyAxis(kfldrlMap.xAxis)

        val nonLinearTable = Array(rpmAxis.size) { Array(dutyAxis.size) { 0.0 } }
        val pressure = Array(rpmAxis.size) { DoubleArray(dutyAxis.size) }
        val count = Array(rpmAxis.size) { DoubleArray(dutyAxis.size) }
        val sampleCounts = Array(rpmAxis.size) { IntArray(dutyAxis.size) }

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
                    sampleCounts[rpmIndex][dutyCycleIndex]++
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

        for (rowIdx in nonLinearTable.indices) {
            val row = nonLinearTable[rowIdx]
            val filledIndices = row.indices.filter { count[rowIdx][it] > 0 }

            if (filledIndices.isNotEmpty()) {
                val firstFilled = filledIndices.first()
                val lastFilled = filledIndices.last()

                // Interior gaps: monotone cubic if enough data
                if (filledIndices.size >= 3) {
                    val knownX = filledIndices.map { dutyAxis[it] }.toDoubleArray()
                    val knownY = filledIndices.map { row[it] }.toDoubleArray()
                    for (i in (firstFilled + 1) until lastFilled) {
                        if (row[i] == 0.0) {
                            row[i] = MonotoneCubicInterpolator.interpolate(knownX, knownY, dutyAxis[i])
                        }
                    }
                } else {
                    for (i in (firstFilled + 1) until lastFilled) {
                        if (row[i] == 0.0) {
                            val left = filledIndices.lastOrNull { it < i }
                            val right = filledIndices.firstOrNull { it > i }
                            if (left != null && right != null) {
                                val span = dutyAxis[right] - dutyAxis[left]
                                val t = if (span == 0.0) 0.0 else
                                    (dutyAxis[i] - dutyAxis[left]) / span
                                row[i] = row[left] + t * (row[right] - row[left])
                            }
                        }
                    }
                }

                // Extrapolation beyond rightmost data
                for (i in (lastFilled + 1) until row.size) {
                    if (row[i] == 0.0) {
                        val prevFilled = filledIndices.lastOrNull { it < lastFilled }
                        row[i] = if (prevFilled != null) {
                            val span = dutyAxis[lastFilled] - dutyAxis[prevFilled]
                            val slope = if (span == 0.0) 0.0 else
                                (row[lastFilled] - row[prevFilled]) / span
                            (row[lastFilled] + slope * (dutyAxis[i] - dutyAxis[lastFilled]))
                                .coerceAtLeast(row[lastFilled])
                        } else {
                            row[lastFilled] * (1.0 + 0.02 * (i - lastFilled))
                        }
                    }
                }

                // Extrapolation before leftmost data
                for (i in 0 until firstFilled) {
                    if (row[i] == 0.0) {
                        val dutyHere = dutyAxis[i].coerceAtLeast(1.0)
                        val dutyThere = dutyAxis[firstFilled].coerceAtLeast(1.0)
                        row[i] = (row[firstFilled] * dutyHere / dutyThere).coerceAtLeast(0.1)
                    }
                }
            }

            if (filledIndices.isNotEmpty()) {
                for (i in row.indices) {
                    if (count[rowIdx][i] == 0.0 && (row[i] <= 0.0 || !row[i].isFinite())) {
                        row[i] = 0.1
                    }
                }
                for (i in 1 until row.size) {
                    if (count[rowIdx][i] == 0.0 && row[i] < row[i - 1]) {
                        row[i] = row[i - 1] + 0.01
                    }
                }
            }
        }

        return Pair(Map3d(dutyAxis, rpmAxis, nonLinearTable), sampleCounts)
    }

    fun calculateLinearTable(
        nonLinearTable: Array<Array<Double>>,
        kfldrlMap: Map3d,
        xAxis: Array<Double> = kfldrlMap.xAxis,
        yAxis: Array<Double> = kfldrlMap.yAxis
    ): Map3d {
        if (nonLinearTable.isEmpty() || nonLinearTable[0].isEmpty()) {
            return Map3d(xAxis, yAxis, emptyArray())
        }
        val linearTable = Array(nonLinearTable.size) { Array(nonLinearTable[0].size) { 0.0 } }

        for (rowIndex in nonLinearTable.indices) {
            val row = nonLinearTable[rowIndex]
            val supported = row.filter { it > 0.0 && it.isFinite() }
            if (supported.isEmpty()) continue
            val min = supported.min()
            val max = supported.max()
            for (columnIndex in row.indices) {
                val fraction = if (row.size <= 1) 0.0 else columnIndex.toDouble() / (row.size - 1)
                linearTable[rowIndex][columnIndex] = min + (max - min) * fraction
            }
        }

        return Map3d(xAxis, yAxis, linearTable)
    }

    fun calculateKfldrl(
        nonLinearTable: Array<Array<Double>>,
        linearTable: Array<Array<Double>>,
        kfldrlMap: Map3d,
        dutyAxis: Array<Double> =
            if (kfldrlMap.xAxis.size >= 2) kfldrlMap.xAxis else deriveDutyAxis(kfldrlMap.xAxis),
        rpmAxis: Array<Double> = kfldrlMap.yAxis
    ): Map3d {
        val kfldrl = Array(nonLinearTable.size) { i ->
            // Pair boost→duty and sort by boost so interpolation x-axis is ascending
            val pairs = nonLinearTable[i].zip(dutyAxis)
                .filter { it.first > 0.0 && it.first.isFinite() }
                .sortedBy { it.first }
            if (pairs.size < 2) {
                return@Array Array(linearTable[i].size) { column ->
                    kfldrlMap.zAxis.getOrNull(i)?.getOrNull(column) ?: 0.0
                }
            }
            val sortedBoost = pairs.map { it.first }.toDoubleArray()
            val sortedDuty = pairs.map { it.second }.toDoubleArray()
            Array(nonLinearTable[i].size) { j ->
                val result = MonotoneCubicInterpolator.interpolate(sortedBoost, sortedDuty, linearTable[i][j])
                if (result.isNaN()) 0.0 else result
            }
        }
        return Map3d(dutyAxis, rpmAxis, kfldrl)
    }

    fun calculateKfldimx(
        nonLinearTable: Array<Array<Double>>,
        linearTable: Array<Array<Double>>,
        kfldrlMap: Map3d,
        kfldimxMap: Map3d,
        dutyAxis: Array<Double> =
            if (kfldrlMap.xAxis.size >= 2) kfldrlMap.xAxis else deriveDutyAxis(kfldrlMap.xAxis),
        rpmAxis: Array<Double> = kfldimxMap.yAxis
    ): Map3d {
        if (linearTable.isEmpty() || linearTable[0].isEmpty() || nonLinearTable.isEmpty()) {
            return Map3d(kfldimxMap.xAxis, kfldimxMap.yAxis, emptyArray())
        }
        if (kfldimxMap.xAxis.size < 2 && dutyAxis.size < 2) {
            return Map3d(kfldimxMap.xAxis, kfldimxMap.yAxis, emptyArray())
        }
        val supportedBoost = linearTable.flatMap { row ->
            row.filter { it > 0.0 && it.isFinite() }.map { it * 68.9476 }
        }
        if (supportedBoost.isEmpty()) {
            return Map3d(kfldimxMap.xAxis, kfldimxMap.yAxis, kfldimxMap.zAxis)
        }

        val targetXAxisSize = if (kfldimxMap.xAxis.size >= 2) kfldimxMap.xAxis.size else DEFAULT_DUTY_COLS
        val kfldimxXAxis = Array(targetXAxisSize) { 0.0 }
        val observedMin = floor(supportedBoost.min() / 100.0) * 100
        val observedMax = ceil(supportedBoost.max() / 100.0) * 100
        // The linked KFLDIMX pressure axis has a fixed binary representation.
        // Keep log-derived breakpoints inside the existing calibration envelope
        // so high-boost logs cannot generate an axis the ECU cannot encode.
        val nativeMin = kfldimxMap.xAxis.minOrNull()
        val nativeMax = kfldimxMap.xAxis.maxOrNull()
        val nativeEnvelopeIsUsable = nativeMin != null && nativeMax != null && nativeMax > nativeMin
        val observedRangeOverlapsNative = nativeEnvelopeIsUsable &&
            observedMax > nativeMin && observedMin < nativeMax
        val min = if (observedRangeOverlapsNative) maxOf(observedMin, nativeMin) else observedMin
        val max = if (observedRangeOverlapsNative) minOf(observedMax, nativeMax) else observedMax
        if (nativeEnvelopeIsUsable && !observedRangeOverlapsNative) {
            for (i in kfldimxXAxis.indices) {
                kfldimxXAxis[i] = kfldimxMap.xAxis[i]
            }
        }
        val interval = if (kfldimxXAxis.size > 1) (max - min) / (kfldimxXAxis.size - 1) else 0.0

        if (!nativeEnvelopeIsUsable || observedRangeOverlapsNative) {
            for (i in kfldimxXAxis.indices) {
                kfldimxXAxis[i] = min + interval * i
            }
        }

        val kfldimx = Array(nonLinearTable.size) { i ->
            // Each RPM row uses its own boost→duty relationship.
            val rowBoost = linearTable[i].map { it * 68.9476 }
            val pairs = rowBoost.zip(dutyAxis).filter { it.first > 0.0 }.sortedBy { it.first }
            if (pairs.size < 2) {
                return@Array Array(kfldimxXAxis.size) { column ->
                    kfldimxMap.zAxis.getOrNull(i)?.getOrNull(column) ?: 0.0
                }
            }
            val sortedBoost = pairs.map { it.first }.toDoubleArray()
            val sortedDuty = pairs.map { it.second }.toDoubleArray()
            Array(kfldimxXAxis.size) { j ->
                MonotoneCubicInterpolator.interpolate(sortedBoost, sortedDuty, kfldimxXAxis[j])
            }
        }

        return Map3d(kfldimxXAxis, rpmAxis, kfldimx)
    }

    /**
     * Derive KFLDRL sample counts from the non-linear table counts.
     * For each KFLDRL cell (RPM × pressure), find the non-linear duty column
     * whose boost contributed via interpolation and copy its count.
     */
    fun deriveKfldrlSampleCounts(
        nonLinearTable: Array<Array<Double>>,
        linearTable: Array<Array<Double>>,
        nonLinearCounts: Array<IntArray>,
        kfldrlMap: Map3d
    ): Array<IntArray> {
        if (nonLinearTable.isEmpty() || nonLinearTable[0].isEmpty()) {
            return Array(kfldrlMap.yAxis.size) { IntArray(kfldrlMap.xAxis.size) }
        }
        return Array(nonLinearTable.size) { rpmIdx ->
            val boostValues = nonLinearTable[rpmIdx]
            IntArray(linearTable[rpmIdx].size) { colIdx ->
                val targetBoost = linearTable[rpmIdx][colIdx]
                val nearestDutyIdx = boostValues.indices.minByOrNull {
                    kotlin.math.abs(boostValues[it] - targetBoost)
                } ?: 0
                if (rpmIdx < nonLinearCounts.size && nearestDutyIdx < nonLinearCounts[rpmIdx].size) {
                    nonLinearCounts[rpmIdx][nearestDutyIdx]
                } else 0
            }
        }
    }

    /**
     * Derive KFLDIMX sample counts from KFLDRL counts.
     * For each KFLDIMX cell, find the corresponding KFLDRL column via index lookup.
     */
    fun deriveKfldimxSampleCounts(
        kfldrlCounts: Array<IntArray>,
        kfldrlMap: Map3d,
        kfldimxMap: Map3d,
        linearTable: Map3d? = null
    ): Array<IntArray> {
        if (kfldrlCounts.isEmpty()) {
            return Array(kfldimxMap.yAxis.size) { IntArray(kfldimxMap.xAxis.size) }
        }
        return Array(kfldimxMap.yAxis.size) { rpmIdx ->
            val kfldrlRpmIdx = if (kfldrlMap.yAxis.isNotEmpty()) {
                Index.getInsertIndex(kfldrlMap.yAxis.toList(), kfldimxMap.yAxis.getOrElse(rpmIdx) { 0.0 })
            } else rpmIdx
            IntArray(kfldimxMap.xAxis.size) { colIdx ->
                val targetPressureMbar = kfldimxMap.xAxis.getOrElse(colIdx) { 0.0 }
                val pressureRow = linearTable?.zAxis?.getOrNull(kfldrlRpmIdx)
                val kfldrlColIdx = if (!pressureRow.isNullOrEmpty()) {
                    pressureRow.indices.minByOrNull { index ->
                        kotlin.math.abs(pressureRow[index] * 68.9476 - targetPressureMbar)
                    } ?: 0
                } else if (kfldrlMap.xAxis.isNotEmpty()) {
                    Index.getInsertIndex(kfldrlMap.xAxis.toList(), targetPressureMbar)
                } else {
                    colIdx
                }
                if (kfldrlRpmIdx < kfldrlCounts.size && kfldrlColIdx < kfldrlCounts[kfldrlRpmIdx].size) {
                    kfldrlCounts[kfldrlRpmIdx][kfldrlColIdx]
                } else 0
            }
        }
    }

    /**
     * Calculate all LDRPID tables with per-cell sample counts for confidence display.
     */
    fun calculateWithCounts(
        values: Map<Me7LogFileContract.Header, List<Double>>,
        kfldrlMap: Map3d,
        kfldimxMap: Map3d
    ): LdrpidResult {
        val (measuredNonLinear, nonLinearCounts) = calculateNonLinearTableWithCounts(values, kfldrlMap)
        val supportedRows = nonLinearCounts.indices.filter { nonLinearCounts[it].sum() > 0 }
        val workingZ = interpolateUnsupportedRpmRows(
            measuredNonLinear.zAxis,
            measuredNonLinear.yAxis,
            supportedRows
        )
        val nonLinearMap3d = Map3d(measuredNonLinear.xAxis, measuredNonLinear.yAxis, workingZ)
        val linearTable = calculateLinearTable(
            workingZ,
            kfldrlMap,
            measuredNonLinear.xAxis,
            measuredNonLinear.yAxis
        )
        val kfldrl = calculateKfldrl(
            workingZ,
            linearTable.zAxis,
            kfldrlMap,
            measuredNonLinear.xAxis,
            measuredNonLinear.yAxis
        )
        val kfldimxMap3d = calculateKfldimx(
            workingZ,
            linearTable.zAxis,
            kfldrlMap,
            kfldimxMap,
            measuredNonLinear.xAxis,
            measuredNonLinear.yAxis
        )

        // Rows outside the measured RPM envelope are not extrapolated into a
        // writable calibration. Preserve the original map values there.
        if (supportedRows.isNotEmpty()) {
            val firstSupported = supportedRows.first()
            val lastSupported = supportedRows.last()
            for (row in kfldrl.zAxis.indices) {
                if (row < firstSupported || row > lastSupported) {
                    kfldrlMap.zAxis.getOrNull(row)?.let { original ->
                        for (column in kfldrl.zAxis[row].indices) {
                            original.getOrNull(column)?.let { kfldrl.zAxis[row][column] = it }
                        }
                    }
                }
            }
            for (row in kfldimxMap3d.zAxis.indices) {
                if (row < firstSupported || row > lastSupported) {
                    kfldimxMap.zAxis.getOrNull(row)?.let { original ->
                        for (column in kfldimxMap3d.zAxis[row].indices) {
                            original.getOrNull(column)?.let { kfldimxMap3d.zAxis[row][column] = it }
                        }
                    }
                }
            }
        }

        val kfldrlCounts = deriveKfldrlSampleCounts(nonLinearMap3d.zAxis, linearTable.zAxis, nonLinearCounts, kfldrlMap)
        val kfldimxCounts = deriveKfldimxSampleCounts(
            kfldrlCounts,
            kfldrl,
            kfldimxMap3d,
            linearTable
        )

        return LdrpidResult(
            nonLinearOutput = nonLinearMap3d,
            linearOutput = linearTable,
            kfldrl = kfldrl,
            kfldimx = kfldimxMap3d,
            nonLinearSampleCounts = nonLinearCounts,
            kfldrlSampleCounts = kfldrlCounts,
            kfldimxSampleCounts = kfldimxCounts,
            logDiagnostics = analyzeLogDiagnostics(values, nonLinearMap3d.yAxis, nonLinearCounts)
        )
    }

    private fun interpolateUnsupportedRpmRows(
        source: Array<Array<Double>>,
        rpmAxis: Array<Double>,
        supportedRows: List<Int>
    ): Array<Array<Double>> {
        val result = Array(source.size) { row -> source[row].copyOf() }
        if (supportedRows.size < 2) return result

        for (row in source.indices) {
            if (row in supportedRows) continue
            val lower = supportedRows.lastOrNull { it < row } ?: continue
            val upper = supportedRows.firstOrNull { it > row } ?: continue
            val span = rpmAxis[upper] - rpmAxis[lower]
            val fraction = if (span == 0.0) 0.0 else (rpmAxis[row] - rpmAxis[lower]) / span
            for (column in result[row].indices) {
                result[row][column] =
                    result[lower][column] + fraction * (result[upper][column] - result[lower][column])
            }
        }
        return result
    }

    fun analyzeLogDiagnostics(
        values: Map<Me7LogFileContract.Header, List<Double>>,
        rpmAxis: Array<Double>,
        nonLinearCounts: Array<IntArray> = emptyArray()
    ): List<LogDiagnosticRow> {
        val rpm = values[Me7LogFileContract.Header.RPM_COLUMN_HEADER].orEmpty()
        val throttle = values[Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER].orEmpty()
        val actual = values[Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER].orEmpty()
        val requested = values[Me7LogFileContract.Header.REQUESTED_PRESSURE_HEADER].orEmpty()
        if (rpmAxis.isEmpty() || rpm.isEmpty() || throttle.isEmpty() || actual.isEmpty()) return emptyList()

        data class Accumulator(
            var samples: Int = 0,
            var absoluteError: Double = 0.0,
            var overshoot: Double = 0.0,
            var withinTolerance: Int = 0,
            var targetSamples: Int = 0
        )

        val accumulators = Array(rpmAxis.size) { Accumulator() }
        val rowCount = minOf(rpm.size, throttle.size, actual.size)
        for (index in 0 until rowCount) {
            if (throttle[index] < 80.0) continue
            val rpmIndex = Index.getInsertIndex(rpmAxis.toList(), rpm[index])
            val accumulator = accumulators[rpmIndex]
            accumulator.samples++
            val target = requested.getOrNull(index)?.takeIf { it.isFinite() } ?: continue
            val error = actual[index] - target
            accumulator.targetSamples++
            accumulator.absoluteError += kotlin.math.abs(error)
            accumulator.overshoot = maxOf(accumulator.overshoot, error)
            if (kotlin.math.abs(error) <= 50.0) accumulator.withinTolerance++
        }

        return rpmAxis.indices.map { index ->
            val accumulator = accumulators[index]
            val hasTarget = accumulator.targetSamples > 0
            LogDiagnosticRow(
                rpm = rpmAxis[index],
                sampleCount = accumulator.samples,
                measuredDutyCells = nonLinearCounts.getOrNull(index)?.count { it > 0 } ?: 0,
                averageAbsolutePressureErrorMbar = if (hasTarget) {
                    accumulator.absoluteError / accumulator.targetSamples
                } else null,
                maximumOvershootMbar = if (hasTarget) accumulator.overshoot.coerceAtLeast(0.0) else null,
                percentWithinTolerance = if (hasTarget) {
                    accumulator.withinTolerance * 100.0 / accumulator.targetSamples
                } else null
            )
        }
    }

    fun calculateLdrpid(values: Map<Me7LogFileContract.Header, List<Double>>, kfldrlMap: Map3d, kfldimxMap: Map3d): LdrpidResult {
        val nonLinearTable = calculateNonLinearTable(values, kfldrlMap)
        val linearTable = calculateLinearTable(
            nonLinearTable.zAxis,
            kfldrlMap,
            nonLinearTable.xAxis,
            nonLinearTable.yAxis
        )
        val kfldrl = calculateKfldrl(
            nonLinearTable.zAxis,
            linearTable.zAxis,
            kfldrlMap,
            nonLinearTable.xAxis,
            nonLinearTable.yAxis
        )
        val kfldimxMap3d = calculateKfldimx(
            nonLinearTable.zAxis,
            linearTable.zAxis,
            kfldrlMap,
            kfldimxMap,
            nonLinearTable.xAxis,
            nonLinearTable.yAxis
        )

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
