package domain.model.ldrpid

import data.contract.Me7LogFileContract
import data.contract.Med17LogFileContract
import data.parser.med17log.Med17LogAdapter
import data.parser.med17log.Med17LogParser
import data.parser.med17log.Med17LogParser.LogType
import domain.math.map.Map3d
import java.io.File
import kotlin.test.*

/**
 * End-to-end tests for the full MED17 LDRPID flow:
 *   Dyno Spectrum (DS1) CSV → Med17LogParser → Med17LogAdapter → LdrpidCalculator
 *
 * Uses real log files from src/test/resources/logs/.
 */
class LdrpidMed17E2ETest {

    // ── Axis definitions matching a realistic KFLDRL ──
    // RPM axis must cover the WOT data range in the logs (4425-8208 RPM)
    private val rpmAxis = arrayOf(3000.0, 4000.0, 5000.0, 5500.0, 6000.0, 6500.0, 7000.0, 7500.0)
    private val dutyAxis = arrayOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 95.0)

    // Degenerate axis for testing recovery from partial BINs
    private val degenerateRpmAxis = arrayOf(5000.0)
    private val degenerateDutyAxis = arrayOf(50.0)

    private fun buildKfldrlMap(): Map3d {
        val z = Array(rpmAxis.size) { Array(dutyAxis.size) { 30.0 } }
        return Map3d(dutyAxis, rpmAxis, z)
    }

    private fun buildKfldimxMap(): Map3d {
        val pressureAxis = arrayOf(200.0, 400.0, 600.0, 800.0, 1000.0, 1200.0)
        val z = Array(rpmAxis.size) { Array(pressureAxis.size) { 30.0 } }
        return Map3d(pressureAxis, rpmAxis, z)
    }

    /** Simulate a degenerate 1-row map from a partial BIN */
    private fun buildDegenerateKfldrlMap(): Map3d {
        val z = Array(1) { Array(1) { 30.0 } }
        return Map3d(degenerateDutyAxis, degenerateRpmAxis, z)
    }

    private fun buildDegenerateKfldimxMap(): Map3d {
        val z = Array(1) { Array(1) { 30.0 } }
        return Map3d(degenerateDutyAxis, degenerateRpmAxis, z)
    }

    private fun logFile(name: String): File {
        val url = javaClass.classLoader.getResource("logs/$name")
            ?: error("Test resource not found: logs/$name")
        return File(url.toURI())
    }

    // ── 1. Parse → Adapt → Calculate with WOT log ──────────────────

    @Test
    fun `full pipeline with WOT log produces four non-empty maps`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))

        // Parsing should yield rows for all LDRPID signals
        val rpmList = med17Data[Med17LogFileContract.Header.RPM_COLUMN_HEADER]!!
        assertTrue(rpmList.isNotEmpty(), "Parser should extract RPM values from WOT log")

        // Adapt to ME7 format
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)
        val me7Rpms = me7Data[Me7LogFileContract.Header.RPM_COLUMN_HEADER]!!
        assertEquals(rpmList.size, me7Rpms.size, "Adapter should preserve row count")

        // Calculate
        val kfldrl = buildKfldrlMap()
        val kfldimx = buildKfldimxMap()
        val result = LdrpidCalculator.calculateLdrpid(me7Data, kfldrl, kfldimx)

        // All four maps should be well-formed
        assertEquals(rpmAxis.size, result.nonLinearOutput.yAxis.size)
        assertEquals(dutyAxis.size, result.nonLinearOutput.xAxis.size)
        assertEquals(rpmAxis.size, result.linearOutput.yAxis.size)
        assertEquals(rpmAxis.size, result.kfldrl.yAxis.size)
        assertEquals(rpmAxis.size, result.kfldimx.yAxis.size)

        // Non-linear output should have actual boost data — not degenerate 0.10/0.11/0.12 filler
        val nonLinearValues = result.nonLinearOutput.zAxis.flatMap { it.toList() }
        assertTrue(nonLinearValues.any { it > 0.1 },
            "Non-linear output should contain real boost values from WOT data")

        // At least some rows should have meaningful boost (>1 PSI) — reject degenerate filler
        val rowsWithRealBoost = result.nonLinearOutput.zAxis.count { row -> row.any { it > 1.0 } }
        assertTrue(rowsWithRealBoost >= 2,
            "At least 2 RPM rows should have meaningful boost data (>1 PSI), got $rowsWithRealBoost")
    }

    // ── 2. Verify parsed signal values are physically plausible ─────

    @Test
    fun `parsed WOT log signals are within physically plausible ranges`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))

        val rpms = med17Data[Med17LogFileContract.Header.RPM_COLUMN_HEADER]!!
        val throttles = med17Data[Med17LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER]!!
        val boosts = med17Data[Med17LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!
        val baros = med17Data[Med17LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER]!!
        val wgdcs = med17Data[Med17LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER]!!

        assertTrue(rpms.all { it in 0.0..10000.0 }, "RPMs should be 0–10000")
        assertTrue(throttles.all { it in 0.0..110.0 }, "Throttle should be 0–110%")
        assertTrue(boosts.all { it in 0.0..6000.0 }, "Boost pressure should be 0–6000 hPa")
        assertTrue(baros.all { it in 800.0..1200.0 }, "Baro should be 800–1200 hPa")
        assertTrue(wgdcs.all { it in 0.0..110.0 }, "WGDC should be 0–110%")
    }

    // ── 3. Adapter preserves data integrity ─────────────────────────

    @Test
    fun `adapter maps all LDRPID signals to ME7 equivalents`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        // Each required ME7 signal should be populated
        val requiredHeaders = listOf(
            Me7LogFileContract.Header.RPM_COLUMN_HEADER,
            Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER,
            Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER,
            Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER,
            Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER,
        )

        for (header in requiredHeaders) {
            val values = me7Data[header]
            assertNotNull(values, "ME7 header $header should be present")
            assertTrue(values.isNotEmpty(), "ME7 header $header should have data")
        }

        // Values should match the source MED17 data exactly
        val med17Rpms = med17Data[Med17LogFileContract.Header.RPM_COLUMN_HEADER]!!
        val me7Rpms = me7Data[Me7LogFileContract.Header.RPM_COLUMN_HEADER]!!
        assertEquals(med17Rpms, me7Rpms, "RPM values should be identical after adaptation")
    }

    // ── 4. KFLDRL output has monotonic rows and sane values ─────────

    @Test
    fun `KFLDRL rows are monotonically non-decreasing`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildKfldrlMap(), buildKfldimxMap())

        for ((rowIdx, row) in result.nonLinearOutput.zAxis.withIndex()) {
            for (i in 1 until row.size) {
                assertTrue(row[i] >= row[i - 1],
                    "Non-linear row $rowIdx should be non-decreasing: ${row.contentToString()}")
            }
        }
    }

    @Test
    fun `KFLDRL z-values are within 0-100 duty cycle range`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildKfldrlMap(), buildKfldimxMap())

        // Print non-linear (raw boost) output for inspection
        println("Non-linear output (boost PSI × 0.0145):")
        for ((rowIdx, row) in result.nonLinearOutput.zAxis.withIndex()) {
            println("  RPM=${rpmAxis[rowIdx]}: ${row.map { "%.2f".format(it) }}")
        }

        // Print KFLDRL output for inspection
        println("KFLDRL (linearized duty correction):")
        for ((rowIdx, row) in result.kfldrl.zAxis.withIndex()) {
            println("  RPM=${rpmAxis[rowIdx]}: ${row.map { "%.2f".format(it) }}")
            for (c in row.indices) {
                assertTrue(row[c] >= 0.0,
                    "KFLDRL z[$rowIdx][$c]=${row[c]} should be >= 0")
                assertTrue(row[c] <= 100.0,
                    "KFLDRL z[$rowIdx][$c]=${row[c]} should be <= 100")
            }
        }
    }

    // ── 5. Linear table has equal step sizes per column ─────────────

    @Test
    fun `linear table has equal step sizes per column`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildKfldrlMap(), buildKfldimxMap())
        val linear = result.linearOutput.zAxis

        for (col in linear[0].indices) {
            if (linear.size < 3) continue
            val step = linear[1][col] - linear[0][col]
            for (row in 2 until linear.size) {
                val actual = linear[row][col] - linear[row - 1][col]
                assertEquals(step, actual, 0.01,
                    "Column $col should have equal step size ($step), got $actual at row $row")
            }
        }
    }

    // ── 6. Cruise-only log (no WOT) still produces valid output ─────

    @Test
    fun `cruise-only log produces valid but zero-boost output`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2023-05-19_21.08.33_log.csv"))

        val rpms = med17Data[Med17LogFileContract.Header.RPM_COLUMN_HEADER]!!
        // This log may have data but no WOT rows (throttle < 80)
        // The pipeline should still run without errors

        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)
        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildKfldrlMap(), buildKfldimxMap())

        // Maps should be well-formed regardless of WOT content
        assertEquals(rpmAxis.size, result.nonLinearOutput.zAxis.size)
        assertEquals(dutyAxis.size, result.nonLinearOutput.zAxis[0].size)

        // All values should be non-negative
        for (row in result.nonLinearOutput.zAxis) {
            for (v in row) {
                assertTrue(v >= 0.0, "Boost values should be non-negative even with no WOT data")
            }
        }
    }

    // ── 7. Empty log data produces valid output ─────────────────────

    @Test
    fun `empty log data produces valid calculator output`() {
        // Simulate empty parsed result (all lists empty)
        val emptyData = mutableMapOf<Me7LogFileContract.Header, List<Double>>()
        for (header in Me7LogFileContract.Header.entries) {
            emptyData[header] = emptyList()
        }

        val result = LdrpidCalculator.calculateLdrpid(emptyData, buildKfldrlMap(), buildKfldimxMap())

        assertEquals(rpmAxis.size, result.nonLinearOutput.zAxis.size,
            "Output should have correct row count even with empty data")
        assertEquals(dutyAxis.size, result.nonLinearOutput.zAxis[0].size,
            "Output should have correct column count even with empty data")
    }

    // ── 8. KFLDIMX output has reasonable pressure axis ──────────────

    @Test
    fun `KFLDIMX has recalculated pressure x-axis from boost data`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildKfldrlMap(), buildKfldimxMap())

        // KFLDIMX x-axis is recalculated from boost data, should not be all zeros
        val xAxis = result.kfldimx.xAxis
        assertTrue(xAxis.isNotEmpty(), "KFLDIMX should have a pressure x-axis")
        assertTrue(xAxis.any { it > 0.0 }, "KFLDIMX x-axis should have positive pressure values")

        // Axis should be monotonically increasing
        for (i in 1 until xAxis.size) {
            assertTrue(xAxis[i] >= xAxis[i - 1],
                "KFLDIMX x-axis should be non-decreasing: ${xAxis.contentToString()}")
        }
    }

    // ── 9. parseLogDirectory works with single-file directory ───────

    @Test
    fun `parseLogDirectory aggregates files in directory`() {
        val parser = Med17LogParser()
        val logDir = logFile("2025-01-21_16.24.32_log(1).csv").parentFile

        var progressCalled = false
        val med17Data = parser.parseLogDirectory(LogType.LDRPID, logDir) { value, max ->
            progressCalled = true
            assertTrue(value in 1..max, "Progress should be within range")
        }

        assertTrue(progressCalled, "Progress callback should be invoked")

        val rpms = med17Data[Med17LogFileContract.Header.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.isNotEmpty(), "Directory parse should produce data from CSV files")
    }

    // ── 10. Second WOT log file also works through pipeline ─────────

    @Test
    fun `second WOT log also produces valid LDRPID results`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2023-05-19_21.31.12_log.csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildKfldrlMap(), buildKfldimxMap())

        assertTrue(result.nonLinearOutput.yAxis.size >= 2, "Should have ≥2 RPM rows")
        assertTrue(result.nonLinearOutput.xAxis.size >= 2, "Should have ≥2 duty columns")

        // This log has WOT rows, so we expect some non-trivial boost values
        val hasBoostData = result.nonLinearOutput.zAxis.flatMap { it.toList() }.any { it > 0.1 }
        assertTrue(hasBoostData, "WOT log should produce non-trivial boost values")
    }

    // ── 11. NonLinear boost values are in correct duty-column positions ──

    @Test
    fun `nonLinear boost at RPM 5000 has data spread across columns not just right edge`() {
        // Bug: sort() pushed real boost data to the rightmost columns and
        // filled left columns with 0.10/0.11/0.12 filler. After fix, data
        // should appear in the actual duty-cycle columns where it was measured.
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildKfldrlMap(), buildKfldimxMap())

        // RPM=5000 is index 2. The log has WOT data at various duty cycles for this RPM.
        // After fix, real boost data should NOT all be crammed into the last 4-5 columns.
        val row5000 = result.nonLinearOutput.zAxis[2]

        // Count columns with filler-level values (< 0.5 PSI)
        val fillerCount = row5000.count { it < 0.5 }

        // With the sort bug, 5 of 10 columns are filler (0.10-0.12).
        // After fix, at most 2-3 columns should be filler (duty ranges with no WOT data)
        assertTrue(fillerCount <= 4,
            "RPM=5000 row should not have >4 filler columns. Got $fillerCount filler in: ${row5000.map { "%.2f".format(it) }}")
    }

    // ── 12. KFLDRL at low RPM should NOT echo the duty axis ────────

    @Test
    fun `KFLDRL at populated RPMs is not a duty axis echo`() {
        // Bug: When nonLinear was all ~0.12, KFLDRL echoed [10,20,30,...,95].
        // After fix, KFLDRL at RPMs with actual WOT data should have meaningful
        // duty corrections, not a 1:1 echo of the duty axis.
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildKfldrlMap(), buildKfldimxMap())

        // RPM=5500 (index 3) has WOT data in the log. KFLDRL should NOT echo axis.
        val kfldrlRow = result.kfldrl.zAxis[3]
        val isExactAxisEcho = kfldrlRow.zip(dutyAxis).all { (v, d) ->
            kotlin.math.abs(v - d) < 0.01
        }
        assertFalse(isExactAxisEcho,
            "KFLDRL at RPM=5500 should NOT be an exact echo of the duty axis. " +
            "Got: ${kfldrlRow.map { "%.2f".format(it) }}")

        // RPMs with no WOT data (like 3000) may echo the axis — that's acceptable
        // since placeholder values produce proportional duty output.
    }

    // ── 13. KFLDIMX has values below max duty ──────────────────────

    @Test
    fun `KFLDIMX values span a range not all clamped to max`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildKfldrlMap(), buildKfldimxMap())

        val kfldimxFlat = result.kfldimx.zAxis.flatMap { it.toList() }
        val distinctRounded = kfldimxFlat.map { "%.0f".format(it) }.distinct()

        assertTrue(distinctRounded.size >= 3,
            "KFLDIMX should have at least 3 distinct values, not all clamped. " +
            "Distinct values: $distinctRounded")
    }

    // ── 14. Degenerate maps (partial BIN) produce sensible output ─────

    @Test
    fun `degenerate 1-row map from partial BIN derives RPM axis from log data`() {
        val parser = Med17LogParser()
        val med17Data = parser.parseLogFile(LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val me7Data = Med17LogAdapter.toMe7LdrpidFormat(med17Data)

        // Simulate partial BIN: KFLDRL is 1x1 with degenerate axes
        val result = LdrpidCalculator.calculateLdrpid(me7Data, buildDegenerateKfldrlMap(), buildDegenerateKfldimxMap())

        // Calculator should derive axes from log data, producing a multi-row/col output
        assertTrue(result.nonLinearOutput.yAxis.size >= 4,
            "Should derive ≥4 RPM rows from log data, got ${result.nonLinearOutput.yAxis.size}")
        assertTrue(result.nonLinearOutput.xAxis.size >= 4,
            "Should derive ≥4 duty columns, got ${result.nonLinearOutput.xAxis.size}")

        // Derived RPM axis should cover the WOT data range (4425-8208 RPM)
        val derivedMinRpm = result.nonLinearOutput.yAxis.first()
        val derivedMaxRpm = result.nonLinearOutput.yAxis.last()
        assertTrue(derivedMinRpm <= 5000.0,
            "Derived RPM axis should start ≤5000 RPM, got $derivedMinRpm")
        assertTrue(derivedMaxRpm >= 7000.0,
            "Derived RPM axis should extend ≥7000 RPM, got $derivedMaxRpm")

        // Should have meaningful boost data, not degenerate 0.10/0.11/0.12 filler
        val rowsWithRealBoost = result.nonLinearOutput.zAxis.count { row -> row.any { it > 1.0 } }
        assertTrue(rowsWithRealBoost >= 2,
            "Degenerate map recovery should still produce ≥2 rows with real boost data")
    }
}
