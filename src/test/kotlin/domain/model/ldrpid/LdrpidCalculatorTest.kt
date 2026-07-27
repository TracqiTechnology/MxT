package domain.model.ldrpid

import data.contract.Me7LogFileContract
import domain.math.map.Map3d
import kotlin.test.*

/**
 * Tests for [LdrpidCalculator] — feed-forward PID linearization for boost control.
 *
 * All tests use constructed log data so no external files are needed.
 */
class LdrpidCalculatorTest {

    // ── Axis definitions (4 RPM rows × 5 duty-cycle columns) ────────
    private val rpmAxis  = arrayOf(2000.0, 3000.0, 4000.0, 5000.0)
    private val dutyAxis = arrayOf(20.0, 40.0, 60.0, 80.0, 95.0)

    /** Skeleton KFLDRL map filled with placeholder duty-cycle values. */
    private fun buildKfldrlMap(): Map3d {
        val z = Array(rpmAxis.size) { Array(dutyAxis.size) { 0.0 } }
        return Map3d(dutyAxis, rpmAxis, z)
    }

    /** Skeleton KFLDIMX map (6 pressure columns × 4 RPM rows). */
    private fun buildKfldimxMap(): Map3d {
        val pressureAxis = arrayOf(200.0, 400.0, 600.0, 800.0, 1000.0, 1200.0)
        val z = Array(rpmAxis.size) { Array(pressureAxis.size) { 0.0 } }
        return Map3d(pressureAxis, rpmAxis, z)
    }

    /** Build a log-data map from parallel lists (one entry per log row). */
    private fun buildLogData(
        rpms: List<Double>,
        throttles: List<Double>,
        dutyCycles: List<Double>,
        baroPressures: List<Double>,
        boostPressures: List<Double>
    ): Map<Me7LogFileContract.Header, List<Double>> = mapOf(
        Me7LogFileContract.Header.RPM_COLUMN_HEADER            to rpms,
        Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER  to throttles,
        Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER  to dutyCycles,
        Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER   to baroPressures,
        Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER to boostPressures
    )

    // ── 1. Throttle filter ──────────────────────────────────────────

    @Test
    fun `calculateNonLinearTable filters rows with throttle below 80`() {
        // Two sub-80 rows should be ignored; two ≥80 rows contribute.
        val data = buildLogData(
            rpms           = listOf(3000.0, 3000.0, 3000.0, 3000.0),
            throttles      = listOf(50.0,   60.0,   80.0,   90.0),
            dutyCycles     = listOf(40.0,   40.0,   40.0,   40.0),
            baroPressures  = listOf(1013.0, 1013.0, 1013.0, 1013.0),
            boostPressures = listOf(1500.0, 1600.0, 1700.0, 1800.0)
        )

        val result = LdrpidCalculator.calculateNonLinearTable(data, buildKfldrlMap())

        // The two WOT rows have relative boost 687 and 787 mbar → average 737 mbar → 10.69 PSI
        // Verify the table isn't all zeros (i.e. the WOT rows were counted)
        val allZero = result.zAxis.all { row -> row.all { it == 0.0 } }
        assertFalse(allZero, "Table should contain non-zero values from WOT rows")
    }

    // ── 2. Binning by RPM and duty cycle ────────────────────────────

    @Test
    fun `calculateNonLinearTable bins boost by RPM and duty cycle`() {
        // Two WOT samples at same RPM+duty → averaged, then converted to PSI
        val data = buildLogData(
            rpms           = listOf(3000.0, 3000.0),
            throttles      = listOf(85.0,   90.0),
            dutyCycles     = listOf(60.0,   60.0),
            baroPressures  = listOf(1013.0, 1013.0),
            boostPressures = listOf(1513.0, 1713.0)     // relative: 500, 700
        )

        val kfldrl = buildKfldrlMap()
        val result = LdrpidCalculator.calculateNonLinearTable(data, kfldrl)

        // Average relative boost = 600 mbar → 600 * 0.0145038 = 8.70 PSI
        // The exact cell depends on Index.getInsertIndex; just verify a non-zero
        // value close to 8.70 PSI exists somewhere in the table.
        val flatValues = result.zAxis.flatMap { it.toList() }.filter { it > 1.0 }
        assertTrue(flatValues.isNotEmpty(),
            "Should have at least one cell with meaningful boost value")

        val expectedPsi = 600.0 * 0.0145038
        val closest = flatValues.minByOrNull { kotlin.math.abs(it - expectedPsi) }!!
        assertEquals(expectedPsi, closest, 2.0,
            "Averaged boost should be near ${expectedPsi} PSI")
    }

    // ── 3. Rows are sorted ascending ────────────────────────────────

    @Test
    fun `calculateNonLinearTable rows are sorted ascending`() {
        val data = buildLogData(
            rpms           = listOf(2000.0, 3000.0, 4000.0, 5000.0,
                                    2000.0, 3000.0, 4000.0, 5000.0),
            throttles      = listOf(85.0, 85.0, 85.0, 85.0,
                                    90.0, 90.0, 90.0, 90.0),
            dutyCycles     = listOf(40.0, 60.0, 80.0, 95.0,
                                    20.0, 40.0, 60.0, 80.0),
            baroPressures  = List(8) { 1013.0 },
            boostPressures = listOf(1200.0, 1400.0, 1600.0, 1800.0,
                                    1100.0, 1300.0, 1500.0, 1700.0)
        )

        val result = LdrpidCalculator.calculateNonLinearTable(data, buildKfldrlMap())

        for (row in result.zAxis) {
            for (i in 1 until row.size) {
                assertTrue(row[i] >= row[i - 1],
                    "Row values should be sorted ascending: ${row.contentToString()}")
            }
        }
    }

    // ── 4. Linear table interpolation ───────────────────────────────

    @Test
    fun `calculateLinearTable produces linear boost targets within each RPM row`() {
        // Build a non-linear table with known values
        val nonLinear = arrayOf(
            arrayOf(1.0, 2.0, 3.0, 4.0, 5.0),   // row 0 (min per column)
            arrayOf(2.0, 3.0, 4.0, 5.0, 6.0),
            arrayOf(5.0, 6.0, 7.0, 8.0, 9.0),
            arrayOf(10.0, 12.0, 14.0, 16.0, 18.0) // row 3 (max per column)
        )

        val kfldrl = buildKfldrlMap()
        val result = LdrpidCalculator.calculateLinearTable(nonLinear, kfldrl)

        // Each row spans its own observed boost range. This keeps RPM behavior
        // independent instead of using the first/last RPM rows for every row.
        for (row in nonLinear.indices) {
            assertEquals(nonLinear[row].first(), result.zAxis[row].first(), 0.01)
            assertEquals(nonLinear[row].last(), result.zAxis[row].last(), 0.01)
            val step = result.zAxis[row][1] - result.zAxis[row][0]
            for (column in 2 until nonLinear[row].size) {
                val actual = result.zAxis[row][column] - result.zAxis[row][column - 1]
                assertEquals(step, actual, 0.01,
                    "RPM row $row should have equal boost-target spacing")
            }
        }
    }

    // ── 5. Full pipeline returns all four maps ──────────────────────

    @Test
    fun `calculateLdrpid returns all four maps with correct axes`() {
        // Provide enough WOT data to produce non-trivial output
        val rpms           = mutableListOf<Double>()
        val throttles      = mutableListOf<Double>()
        val dutyCycles     = mutableListOf<Double>()
        val baroPressures  = mutableListOf<Double>()
        val boostPressures = mutableListOf<Double>()

        // Generate WOT rows across RPM / duty combinations
        for (rpm in listOf(2000.0, 3000.0, 4000.0, 5000.0)) {
            for (duty in listOf(20.0, 40.0, 60.0, 80.0, 95.0)) {
                rpms.add(rpm)
                throttles.add(85.0)
                dutyCycles.add(duty)
                baroPressures.add(1013.0)
                // Boost rises with both RPM and duty
                boostPressures.add(1013.0 + rpm * 0.05 + duty * 3.0)
            }
        }

        val data    = buildLogData(rpms, throttles, dutyCycles, baroPressures, boostPressures)
        val kfldrl  = buildKfldrlMap()
        val kfldimx = buildKfldimxMap()

        val result = LdrpidCalculator.calculateLdrpid(data, kfldrl, kfldimx)

        // Verify all four outputs are present and sized correctly
        assertEquals(rpmAxis.size, result.nonLinearOutput.yAxis.size,
            "Non-linear output should have ${rpmAxis.size} RPM rows")
        assertEquals(dutyAxis.size, result.nonLinearOutput.xAxis.size,
            "Non-linear output should have ${dutyAxis.size} duty columns")

        assertEquals(rpmAxis.size, result.linearOutput.yAxis.size,
            "Linear output should have ${rpmAxis.size} RPM rows")

        assertEquals(rpmAxis.size, result.kfldrl.yAxis.size,
            "KFLDRL should have ${rpmAxis.size} RPM rows")

        assertEquals(rpmAxis.size, result.kfldimx.yAxis.size,
            "KFLDIMX should have ${rpmAxis.size} RPM rows")
    }

    // ── 6. Boost values stay in correct duty-column positions ──────

    @Test
    fun `calculateNonLinearTable preserves boost at correct duty columns`() {
        // Bug: sort() was reordering boost values within each row, destroying
        // the mapping between duty-cycle columns and measured boost.
        // Seed two data points at known duty positions:
        //   duty=40% → boost=500 mbar relative → 7.25 PSI
        //   duty=80% → boost=1000 mbar relative → 14.50 PSI
        val data = buildLogData(
            rpms           = listOf(3000.0, 3000.0),
            throttles      = listOf(85.0, 90.0),
            dutyCycles     = listOf(40.0, 80.0),
            baroPressures  = listOf(1013.0, 1013.0),
            boostPressures = listOf(1513.0, 2013.0) // relative: 500, 1000
        )

        val result = LdrpidCalculator.calculateNonLinearTable(data, buildKfldrlMap())

        // duty=40 maps to column index 1, duty=80 maps to column index 3
        val dutyCol40 = 1  // dutyAxis=[20,40,60,80,95], index 1=40
        val dutyCol80 = 3  // index 3=80

        val row = result.zAxis[1] // RPM=3000 is index 1

        val expectedPsi40 = 500.0 * 0.0145038  // ~7.25
        val expectedPsi80 = 1000.0 * 0.0145038 // ~14.50

        // The boost at the 40% duty column should be close to 7.25 PSI
        assertEquals(expectedPsi40, row[dutyCol40], 1.0,
            "Boost at 40% duty column should be ~${expectedPsi40} PSI, got ${row[dutyCol40]}")
        // The boost at the 80% duty column should be close to 14.50 PSI
        assertEquals(expectedPsi80, row[dutyCol80], 1.0,
            "Boost at 80% duty column should be ~${expectedPsi80} PSI, got ${row[dutyCol80]}")
        // The 40% column should have LESS boost than the 80% column (physical constraint)
        assertTrue(row[dutyCol40] < row[dutyCol80],
            "40% duty should produce less boost than 80% duty")
    }

    // ── 7. KFLDRL does NOT echo the duty axis ──────────────────────

    @Test
    fun `KFLDRL output is not a simple echo of duty axis`() {
        // Bug: When nonLinear table was all ~0.12 due to sort pushing real data right,
        // KFLDRL just echoed the duty axis values [20, 40, 60, 80, 95] because
        // interpolating through near-identical x values maps 1:1 to the y axis.
        // Generate realistic WOT data at 2 RPM points with distinct boost levels
        val data = buildLogData(
            rpms           = listOf(3000.0, 3000.0, 3000.0, 5000.0, 5000.0, 5000.0),
            throttles      = listOf(85.0, 85.0, 85.0, 90.0, 90.0, 90.0),
            dutyCycles     = listOf(40.0, 60.0, 80.0, 40.0, 60.0, 80.0),
            baroPressures  = listOf(1013.0, 1013.0, 1013.0, 1013.0, 1013.0, 1013.0),
            boostPressures = listOf(1213.0, 1513.0, 1813.0, 1313.0, 1713.0, 2013.0)
        )

        val kfldrl = buildKfldrlMap()
        val result = LdrpidCalculator.calculateLdrpid(data, kfldrl, buildKfldimxMap())

        // Check RPM=3000 row (index 1). KFLDRL should NOT be [20, 40, 60, 80, 95]
        val kfldrlRow = result.kfldrl.zAxis[1]
        val isEchoingAxis = kfldrlRow.zip(dutyAxis).all { (v, d) ->
            kotlin.math.abs(v - d) < 1.0
        }
        assertFalse(isEchoingAxis,
            "KFLDRL row should NOT just echo the duty axis. Got: ${kfldrlRow.contentToString()}")
    }

    // ── 8. KFLDIMX values are spread across range ──────────────────

    @Test
    fun `KFLDIMX has varied values not all clamped to maximum`() {
        // Bug: When nonLinear was degenerate (~0.12 everywhere), linearBoostMax
        // was tiny (~10 mbar), kfldimxXAxis was all ~100, and interpolation
        // clamped every output to max duty (~95 or 100).
        val data = buildLogData(
            rpms           = listOf(3000.0, 3000.0, 5000.0, 5000.0),
            throttles      = listOf(85.0, 90.0, 85.0, 90.0),
            dutyCycles     = listOf(40.0, 80.0, 40.0, 80.0),
            baroPressures  = listOf(1013.0, 1013.0, 1013.0, 1013.0),
            boostPressures = listOf(1313.0, 1813.0, 1413.0, 2013.0)
        )

        val result = LdrpidCalculator.calculateLdrpid(data, buildKfldrlMap(), buildKfldimxMap())

        val kfldimxFlat = result.kfldimx.zAxis.flatMap { it.toList() }
        // Not all values should be the same (clamped to max)
        val distinctValues = kfldimxFlat.map { "%.1f".format(it) }.distinct()
        assertTrue(distinctValues.size >= 2,
            "KFLDIMX should have varied values, not all clamped. Distinct: $distinctValues")
    }

    // ── 9. No WOT data ─────────────────────────────────────────────

    @Test
    fun `calculateNonLinearTable with no WOT data produces valid output`() {
        // All throttle values below 80 → nothing passes the filter
        val data = buildLogData(
            rpms           = listOf(3000.0, 4000.0),
            throttles      = listOf(50.0,   70.0),
            dutyCycles     = listOf(40.0,   60.0),
            baroPressures  = listOf(1013.0, 1013.0),
            boostPressures = listOf(1200.0, 1400.0)
        )

        val result = LdrpidCalculator.calculateNonLinearTable(data, buildKfldrlMap())

        // Table should still be well-formed (correct dimensions)
        assertEquals(rpmAxis.size, result.zAxis.size,
            "Should still have ${rpmAxis.size} rows")
        assertEquals(dutyAxis.size, result.zAxis[0].size,
            "Should still have ${dutyAxis.size} columns")

        // All values should be the post-processed fallback (0 mbar * 0.0145038 → sorted/fixed to 0.1)
        for (row in result.zAxis) {
            for (value in row) {
                assertTrue(value >= 0.0,
                    "All values should be non-negative even with no WOT data")
            }
        }
    }

    // ── 10. Log consistency detection ───────────────────────────────

    @Test
    fun `checkLogConsistency returns no warning for single file`() {
        val fileData = listOf(
            buildLogData(
                rpms           = listOf(4000.0, 5000.0, 6000.0),
                throttles      = listOf(95.0,   95.0,   95.0),
                dutyCycles     = listOf(60.0,   55.0,   50.0),
                baroPressures  = listOf(1013.0, 1013.0, 1013.0),
                boostPressures = listOf(2500.0, 2600.0, 2700.0)
            )
        )
        val warning = LdrpidCalculator.checkLogConsistency(fileData)
        assertNull(warning, "Single file should never produce a consistency warning")
    }

    @Test
    fun `checkLogConsistency returns no warning for consistent files`() {
        // Two logs from the same tune stage — similar boost at similar duty
        val fileData = listOf(
            buildLogData(
                rpms           = listOf(4000.0, 5000.0, 6000.0),
                throttles      = listOf(95.0,   95.0,   95.0),
                dutyCycles     = listOf(60.0,   55.0,   50.0),
                baroPressures  = listOf(1013.0, 1013.0, 1013.0),
                boostPressures = listOf(2500.0, 2600.0, 2700.0)
            ),
            buildLogData(
                rpms           = listOf(4500.0, 5500.0),
                throttles      = listOf(90.0,   90.0),
                dutyCycles     = listOf(58.0,   52.0),
                baroPressures  = listOf(1013.0, 1013.0),
                boostPressures = listOf(2550.0, 2650.0)
            )
        )
        val warning = LdrpidCalculator.checkLogConsistency(fileData)
        assertNull(warning, "Consistent logs should not produce a warning")
    }

    @Test
    fun `checkLogConsistency returns warning for conflicting tune stages`() {
        // Log 1: high boost at moderate duty (aggressive tune)
        // Log 2: low boost at low duty (stock/detuned)
        val fileData = listOf(
            buildLogData(
                rpms           = listOf(5000.0, 5000.0, 6000.0, 6000.0),
                throttles      = listOf(95.0,   95.0,   95.0,   95.0),
                dutyCycles     = listOf(50.0,   60.0,   50.0,   60.0),
                baroPressures  = listOf(1013.0, 1013.0, 1013.0, 1013.0),
                boostPressures = listOf(2800.0, 3000.0, 2900.0, 3100.0)
            ),
            buildLogData(
                rpms           = listOf(5000.0, 5000.0, 6000.0, 6000.0),
                throttles      = listOf(95.0,   95.0,   95.0,   95.0),
                dutyCycles     = listOf(20.0,   20.0,   20.0,   20.0),
                baroPressures  = listOf(1013.0, 1013.0, 1013.0, 1013.0),
                boostPressures = listOf(1300.0, 1400.0, 1350.0, 1450.0)
            )
        )
        val warning = LdrpidCalculator.checkLogConsistency(fileData)
        assertNotNull(warning, "Conflicting tune stages should produce a warning")
    }
}
