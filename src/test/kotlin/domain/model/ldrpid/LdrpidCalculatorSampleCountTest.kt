package domain.model.ldrpid

import data.contract.Me7LogFileContract
import domain.math.map.Map3d
import kotlin.test.*

/**
 * Tests for per-cell sample count tracking in [LdrpidCalculator].
 *
 * Verifies that the new `calculateNonLinearTableWithCounts` and `calculateWithCounts`
 * methods correctly track how many log samples contributed to each cell,
 * propagate counts through KFLDRL and KFLDIMX derivation, and mark
 * interpolated cells with count=0.
 */
class LdrpidCalculatorSampleCountTest {

    // ── Axis definitions (4 RPM rows × 5 duty-cycle columns) ────────
    private val rpmAxis = arrayOf(2000.0, 3000.0, 4000.0, 5000.0)
    private val dutyAxis = arrayOf(20.0, 40.0, 60.0, 80.0, 95.0)

    private fun buildKfldrlMap(): Map3d {
        val z = Array(rpmAxis.size) { Array(dutyAxis.size) { 0.0 } }
        return Map3d(dutyAxis, rpmAxis, z)
    }

    private fun buildKfldimxMap(): Map3d {
        val pressureAxis = arrayOf(200.0, 400.0, 600.0, 800.0, 1000.0, 1200.0)
        val z = Array(rpmAxis.size) { Array(pressureAxis.size) { 0.0 } }
        return Map3d(pressureAxis, rpmAxis, z)
    }

    private fun buildLogData(
        rpms: List<Double>,
        throttles: List<Double>,
        dutyCycles: List<Double>,
        baroPressures: List<Double>,
        boostPressures: List<Double>
    ): Map<Me7LogFileContract.Header, List<Double>> = mapOf(
        Me7LogFileContract.Header.RPM_COLUMN_HEADER to rpms,
        Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER to throttles,
        Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER to dutyCycles,
        Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER to baroPressures,
        Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER to boostPressures
    )

    // ── 1. Sample counts match known input ──────────────────────────

    @Test
    fun `calculateNonLinearTableWithCounts returns correct sample counts`() {
        // Place exactly 3 samples at RPM=3000 duty=60, and 1 sample at RPM=5000 duty=80
        val data = buildLogData(
            rpms = listOf(3000.0, 3000.0, 3000.0, 5000.0),
            throttles = listOf(85.0, 90.0, 85.0, 95.0),
            dutyCycles = listOf(60.0, 60.0, 60.0, 80.0),
            baroPressures = listOf(1013.0, 1013.0, 1013.0, 1013.0),
            boostPressures = listOf(1513.0, 1613.0, 1713.0, 2013.0)
        )

        val (_, counts) = LdrpidCalculator.calculateNonLinearTableWithCounts(data, buildKfldrlMap())

        // RPM=3000 → index 1, duty=60 → index 2
        assertEquals(3, counts[1][2], "3 samples at RPM=3000, duty=60")
        // RPM=5000 → index 3, duty=80 → index 3
        assertEquals(1, counts[3][3], "1 sample at RPM=5000, duty=80")
    }

    // ── 2. Interpolated cells have count=0 ──────────────────────────

    @Test
    fun `cells with zero samples are marked as interpolated`() {
        // Only populate one cell — all others should be count=0
        val data = buildLogData(
            rpms = listOf(3000.0),
            throttles = listOf(90.0),
            dutyCycles = listOf(60.0),
            baroPressures = listOf(1013.0),
            boostPressures = listOf(1513.0)
        )

        val (map3d, counts) = LdrpidCalculator.calculateNonLinearTableWithCounts(data, buildKfldrlMap())

        // The populated cell should have count=1
        assertEquals(1, counts[1][2], "Populated cell should have count=1")

        // All other cells should be 0 (interpolated)
        var zeroCellCount = 0
        for (r in counts.indices) {
            for (c in counts[r].indices) {
                if (r == 1 && c == 2) continue
                assertEquals(0, counts[r][c],
                    "Cell [$r][$c] should be 0 (interpolated)")
                zeroCellCount++
            }
        }
        assertTrue(zeroCellCount > 0, "There should be interpolated cells")

        // Unsupported RPM rows stay zero instead of receiving synthetic 0.10,
        // 0.11... placeholder values.
        assertTrue(map3d.zAxis[0].all { it == 0.0 })
        assertTrue(map3d.zAxis[2].all { it == 0.0 })
        assertTrue(map3d.zAxis[3].all { it == 0.0 })
        assertTrue(map3d.zAxis[1].all { it > 0.0 }, "The measured RPM row may interpolate across duty")
    }

    // ── 3. KFLDRL counts propagate from nonLinearTable ──────────────

    @Test
    fun `kfldrl sample counts propagate from nonLinearTable`() {
        // Populate multiple cells across duty range
        val data = buildLogData(
            rpms = listOf(3000.0, 3000.0, 3000.0, 3000.0, 3000.0, 3000.0,
                5000.0, 5000.0, 5000.0),
            throttles = listOf(85.0, 85.0, 90.0, 90.0, 85.0, 85.0,
                85.0, 90.0, 85.0),
            dutyCycles = listOf(40.0, 40.0, 60.0, 60.0, 80.0, 80.0,
                40.0, 60.0, 80.0),
            baroPressures = List(9) { 1013.0 },
            boostPressures = listOf(1213.0, 1313.0, 1513.0, 1613.0, 1813.0, 1913.0,
                1313.0, 1713.0, 2013.0)
        )

        val kfldrlDef = buildKfldrlMap()
        val (nonLinearMap, nonLinearCounts) = LdrpidCalculator.calculateNonLinearTableWithCounts(data, kfldrlDef)
        val linearTable = LdrpidCalculator.calculateLinearTable(nonLinearMap.zAxis, kfldrlDef)
        val kfldrlCounts = LdrpidCalculator.deriveKfldrlSampleCounts(
            nonLinearMap.zAxis, linearTable.zAxis, nonLinearCounts, kfldrlDef
        )

        // KFLDRL should have same row count as nonLinear
        assertEquals(nonLinearCounts.size, kfldrlCounts.size,
            "KFLDRL counts should have same number of RPM rows")

        // RPM=3000 row should have non-zero counts (cells that had data)
        val rpm3000Row = kfldrlCounts[1]
        val hasNonZero = rpm3000Row.any { it > 0 }
        assertTrue(hasNonZero,
            "KFLDRL RPM=3000 row should have propagated non-zero counts")

        // Row with no original data should have all zeros
        // RPM=2000 (index 0) had no samples
        val rpm2000Row = kfldrlCounts[0]
        assertTrue(rpm2000Row.all { it == 0 },
            "KFLDRL RPM=2000 row should have all zero counts (no source data)")
    }

    // ── 4. Full pipeline integration ────────────────────────────────

    @Test
    fun `calculateWithCounts integrates all tables`() {
        // Generate WOT data across RPM/duty combinations
        val rpms = mutableListOf<Double>()
        val throttles = mutableListOf<Double>()
        val dutyCycles = mutableListOf<Double>()
        val baroPressures = mutableListOf<Double>()
        val boostPressures = mutableListOf<Double>()

        for (rpm in listOf(2000.0, 3000.0, 4000.0, 5000.0)) {
            for (duty in listOf(20.0, 40.0, 60.0, 80.0, 95.0)) {
                rpms.add(rpm)
                throttles.add(85.0)
                dutyCycles.add(duty)
                baroPressures.add(1013.0)
                boostPressures.add(1013.0 + rpm * 0.05 + duty * 3.0)
            }
        }

        val data = buildLogData(rpms, throttles, dutyCycles, baroPressures, boostPressures)
        val result = LdrpidCalculator.calculateWithCounts(data, buildKfldrlMap(), buildKfldimxMap())

        // All four maps should be non-empty
        assertTrue(result.nonLinearOutput.zAxis.isNotEmpty(), "Non-linear map should be non-empty")
        assertTrue(result.linearOutput.zAxis.isNotEmpty(), "Linear map should be non-empty")
        assertTrue(result.kfldrl.zAxis.isNotEmpty(), "KFLDRL should be non-empty")
        assertTrue(result.kfldimx.zAxis.isNotEmpty(), "KFLDIMX should be non-empty")

        // Sample counts should have matching dimensions
        assertEquals(result.nonLinearOutput.zAxis.size, result.nonLinearSampleCounts.size,
            "Non-linear sample count rows should match map rows")
        assertEquals(result.nonLinearOutput.zAxis[0].size, result.nonLinearSampleCounts[0].size,
            "Non-linear sample count cols should match map cols")

        assertEquals(result.kfldrl.zAxis.size, result.kfldrlSampleCounts.size,
            "KFLDRL sample count rows should match map rows")
        assertEquals(result.kfldrl.zAxis[0].size, result.kfldrlSampleCounts[0].size,
            "KFLDRL sample count cols should match map cols")

        assertEquals(result.kfldimx.zAxis.size, result.kfldimxSampleCounts.size,
            "KFLDIMX sample count rows should match map rows")
        assertEquals(result.kfldimx.zAxis[0].size, result.kfldimxSampleCounts[0].size,
            "KFLDIMX sample count cols should match map cols")

        // Since we provided 1 sample per cell, all nonLinear counts should be 1
        val totalNonLinearSamples = result.nonLinearSampleCounts.sumOf { it.sum() }
        assertEquals(20, totalNonLinearSamples,
            "Total non-linear samples should equal input count (4 RPM × 5 duty = 20)")

        // KFLDRL and KFLDIMX should also have non-zero counts
        val totalKfldrlSamples = result.kfldrlSampleCounts.sumOf { it.sum() }
        assertTrue(totalKfldrlSamples > 0, "KFLDRL should have propagated sample counts")

        val totalKfldimxSamples = result.kfldimxSampleCounts.sumOf { it.sum() }
        assertTrue(totalKfldimxSamples > 0, "KFLDIMX should have propagated sample counts")
    }

    // ── 5. Backward compatibility ───────────────────────────────────

    @Test
    fun `calculateNonLinearTable still returns Map3d without counts`() {
        val data = buildLogData(
            rpms = listOf(3000.0),
            throttles = listOf(85.0),
            dutyCycles = listOf(60.0),
            baroPressures = listOf(1013.0),
            boostPressures = listOf(1513.0)
        )

        val result = LdrpidCalculator.calculateNonLinearTable(data, buildKfldrlMap())
        assertIs<Map3d>(result, "calculateNonLinearTable should still return Map3d")
        assertTrue(result.zAxis.isNotEmpty(), "Should produce valid output")
    }

    // ── 6. LdrpidResult default counts are empty ────────────────────

    @Test
    fun `LdrpidResult default sample counts are empty arrays`() {
        val emptyMap = Map3d()
        val result = LdrpidCalculator.LdrpidResult(
            nonLinearOutput = emptyMap,
            linearOutput = emptyMap,
            kfldrl = emptyMap,
            kfldimx = emptyMap
        )
        assertTrue(result.nonLinearSampleCounts.isEmpty(), "Default nonLinear counts should be empty")
        assertTrue(result.kfldrlSampleCounts.isEmpty(), "Default kfldrl counts should be empty")
        assertTrue(result.kfldimxSampleCounts.isEmpty(), "Default kfldimx counts should be empty")
    }

    // ── 7. No WOT data produces all-zero counts ─────────────────────

    @Test
    fun `calculateWithCounts with no WOT data produces all-zero counts`() {
        val data = buildLogData(
            rpms = listOf(3000.0, 4000.0),
            throttles = listOf(50.0, 70.0),
            dutyCycles = listOf(40.0, 60.0),
            baroPressures = listOf(1013.0, 1013.0),
            boostPressures = listOf(1200.0, 1400.0)
        )

        val result = LdrpidCalculator.calculateWithCounts(data, buildKfldrlMap(), buildKfldimxMap())

        val totalSamples = result.nonLinearSampleCounts.sumOf { it.sum() }
        assertEquals(0, totalSamples, "No WOT data should produce all-zero sample counts")
    }
}
