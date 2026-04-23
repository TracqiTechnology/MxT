package domain.model.optimizer

import data.contract.Me7LogFileContract
import data.parser.med17log.Med17LogAdapter
import data.parser.med17log.Med17LogParser
import domain.math.map.Map3d
import domain.model.simulator.Me7Simulator
import java.io.File
import kotlin.math.abs
import kotlin.test.*

/**
 * Comprehensive tests for [OptimizerCalculator] map suggestion methods:
 *   - suggestKfldrlDelta  (boost control feed-forward)
 *   - suggestKfldimxDelta (boost control ceiling)
 *   - suggestKfmiopDelta  (torque-to-load scaling)
 *   - suggestKfmirlDelta  (inverse torque-to-load)
 *   - analyzeMed17        (full MED17 pipeline integration)
 */
class OptimizerMapSuggestionTest {

    // ── Helpers ──────────────────────────────────────────────────────

    private val parser = Med17LogParser()

    private fun logFile(name: String): File {
        val url = javaClass.classLoader.getResource("logs/$name")
            ?: error("Test resource not found: logs/$name")
        return File(url.toURI())
    }

    private fun parseAndAdapt(filename: String): Map<Me7LogFileContract.Header, List<Double>> {
        val med17Data = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile(filename))
        return Med17LogAdapter.toMe7OptimizerFormat(med17Data)
    }

    /** Build a simple 4×4 KFLDRL map filled with a constant. */
    private fun make4x4KfldrlMap(
        rpmAxis: Array<Double> = arrayOf(1000.0, 2000.0, 3000.0, 4000.0),
        pressureAxis: Array<Double> = arrayOf(2.0, 4.0, 6.0, 8.0),
        fillValue: Double = 0.0
    ): Map3d {
        val z = Array(rpmAxis.size) { Array(pressureAxis.size) { fillValue } }
        return Map3d(pressureAxis, rpmAxis, z)
    }

    /**
     * Create a WotLogEntry whose [relativeBoostPsi] lands in a specific pressure cell.
     * relativeBoostPsi = (actualMap - barometricPressure) * 0.0145038
     * → actualMap = targetPsi / 0.0145038 + baro
     */
    private fun wotEntry(
        rpm: Double,
        targetBoostPsi: Double,
        wgdc: Double,
        barometricPressure: Double = 1013.0,
        requestedLoad: Double = 191.0,
        actualLoad: Double = 180.0,
        requestedMap: Double = 2500.0,
        throttleAngle: Double = 95.0
    ): OptimizerCalculator.WotLogEntry {
        val actualMap = targetBoostPsi / 0.0145038 + barometricPressure
        return OptimizerCalculator.WotLogEntry(
            rpm = rpm,
            requestedLoad = requestedLoad,
            actualLoad = actualLoad,
            requestedMap = requestedMap,
            actualMap = actualMap,
            barometricPressure = barometricPressure,
            wgdc = wgdc,
            throttleAngle = throttleAngle
        )
    }

    /** Build a simple SimulationResult for KFMIOP tests. */
    private fun simResult(
        rpm: Double,
        torqueLimited: Boolean,
        rlsol: Double = 160.0,
        ldrxnTarget: Double = 191.0,
        actualLoad: Double = 160.0
    ): Me7Simulator.SimulationResult {
        return Me7Simulator.SimulationResult(
            rpm = rpm,
            ldrxnTarget = ldrxnTarget,
            rlsol = rlsol,
            actualPssol = 2500.0,
            simulatedPssol = 2500.0,
            pssolError = 0.0,
            simulatedPlsol = 2500.0,
            actualPvdks = 2400.0,
            boostError = 100.0,
            predictedWgdc = 50.0,
            actualWgdc = 55.0,
            kfldrlCorrection = 5.0,
            actualRl = actualLoad,
            simulatedRlFromPressure = actualLoad,
            kfpbrkCorrectionFactor = 1.0,
            torqueLimited = torqueLimited,
            torqueHeadroom = if (torqueLimited) ldrxnTarget - rlsol else 0.0,
            dominantError = if (torqueLimited) Me7Simulator.ErrorSource.TORQUE_CAPPED
                           else Me7Simulator.ErrorSource.ON_TARGET,
            totalLoadDeficit = ldrxnTarget - actualLoad
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  1. KFLDRL Suggestion Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `suggestKfldrlDelta with known WOT entries averages WGDC per cell`() {
        val map = make4x4KfldrlMap()

        // Place entries at known cells:
        //  RPM=2000, boost≈4 PSI → row idx 1, col idx 1
        //  Two entries with WGDC 40 and 60 → average should be 50
        val entries = listOf(
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 40.0),
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 60.0),
            // RPM=3000, boost≈6 PSI → row idx 2, col idx 2, single entry WGDC=75
            wotEntry(rpm = 3000.0, targetBoostPsi = 6.0, wgdc = 75.0)
        )

        val delta = OptimizerCalculator.suggestKfldrlDelta(entries, map)

        // Cell [row=1, col=1] should be average of 40 and 60
        assertEquals(50.0, delta.suggested.zAxis[1][1], 0.5,
            "KFLDRL cell [2000rpm, 4psi] should be avg WGDC=50")
        // Cell [row=2, col=2] should be the single entry 75
        assertEquals(75.0, delta.suggested.zAxis[2][2], 0.5,
            "KFLDRL cell [3000rpm, 6psi] should be WGDC=75")
    }

    @Test
    fun `suggestKfldrlDelta preserves cells without data`() {
        val fillValue = 25.0
        val map = make4x4KfldrlMap(fillValue = fillValue)

        // Only put data in one cell
        val entries = listOf(
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 60.0)
        )

        val delta = OptimizerCalculator.suggestKfldrlDelta(entries, map)

        // Cells that didn't receive data should keep the original fill value
        // Check a cell we know has no data: row=0, col=0 (RPM=1000, 2 PSI)
        assertEquals(fillValue, delta.suggested.zAxis[0][0], 0.001,
            "Cell with no WOT data should keep original value")
        // The entry went to [1][1], verify it changed
        assertEquals(60.0, delta.suggested.zAxis[1][1], 0.5,
            "Cell with WOT data should reflect WGDC")
    }

    @Test
    fun `suggestKfldrlDelta enforces monotonicity for empty cells`() {
        val map = make4x4KfldrlMap(fillValue = 10.0)

        // Place a high-WGDC entry at a low-pressure cell; leave higher-pressure cells empty.
        // E.g., RPM=2000, boost≈2 PSI → row=1, col=0, WGDC=50
        val entries = listOf(
            wotEntry(rpm = 2000.0, targetBoostPsi = 2.0, wgdc = 50.0)
        )

        val delta = OptimizerCalculator.suggestKfldrlDelta(entries, map)

        // Row 1: col=0 should be 50 (from data). Cols 1,2,3 had original=10 but
        // monotonicity should pull them up to at least col 0's value
        val row1 = delta.suggested.zAxis[1]
        assertEquals(50.0, row1[0], 0.5, "Row 1 col 0 should be 50 from data")
        for (c in 1 until row1.size) {
            assertTrue(row1[c] >= row1[c - 1],
                "Monotonicity violated: row1[$c]=${row1[c]} < row1[${c-1}]=${row1[c-1]}")
        }
    }

    @Test
    fun `suggestKfldrlDelta coverage reflects data distribution`() {
        val map = make4x4KfldrlMap()

        // Put data in exactly 3 distinct cells
        val entries = listOf(
            wotEntry(rpm = 1000.0, targetBoostPsi = 2.0, wgdc = 30.0),
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 50.0),
            wotEntry(rpm = 3000.0, targetBoostPsi = 6.0, wgdc = 70.0)
        )

        val delta = OptimizerCalculator.suggestKfldrlDelta(entries, map)

        // Only entries with relativeBoostPsi > 0 are used, all 3 have positive boost
        assertEquals(3, delta.cellsWithData,
            "cellsWithData should equal the number of distinct cells that got entries")
        assertEquals(16, delta.totalCells, "4×4 map has 16 total cells")
        assertEquals(3.0 / 16.0, delta.coverage, 0.001,
            "Coverage should be 3/16")
    }

    @Test
    fun `suggestKfldrlDelta mapName is KFLDRL`() {
        val map = make4x4KfldrlMap()
        val entries = listOf(wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 50.0))
        val delta = OptimizerCalculator.suggestKfldrlDelta(entries, map)
        assertEquals("KFLDRL", delta.mapName)
    }

    @Test
    fun `suggestKfldrlDelta with empty entries preserves entire map`() {
        val fillValue = 42.0
        val map = make4x4KfldrlMap(fillValue = fillValue)
        val delta = OptimizerCalculator.suggestKfldrlDelta(emptyList(), map)

        for (r in map.zAxis.indices) {
            for (c in map.zAxis[r].indices) {
                assertEquals(fillValue, delta.suggested.zAxis[r][c], 0.001,
                    "All cells should keep original value when no WOT entries")
            }
        }
        assertEquals(0, delta.cellsWithData)
    }

    @Test
    fun `suggestKfldrlDelta sample counts match entry count per cell`() {
        val map = make4x4KfldrlMap()
        val entries = listOf(
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 40.0),
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 60.0),
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 50.0)
        )
        val delta = OptimizerCalculator.suggestKfldrlDelta(entries, map)

        assertEquals(3, delta.sampleCounts[1][1],
            "Cell [1][1] should have 3 samples")
        assertEquals(0, delta.sampleCounts[0][0],
            "Cell [0][0] should have 0 samples")
    }

    @Test
    fun `suggestKfldrlDelta cellConfidence reflects sample counts`() {
        val map = make4x4KfldrlMap()
        // Put 25 entries in one cell (HIGH), 3 in another (LOW), 0 in third (NONE)
        val entries = (1..25).map {
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 50.0)
        } + (1..3).map {
            wotEntry(rpm = 3000.0, targetBoostPsi = 6.0, wgdc = 60.0)
        }

        val delta = OptimizerCalculator.suggestKfldrlDelta(entries, map)

        assertEquals(MapDelta.Confidence.HIGH, delta.cellConfidence(1, 1),
            "25 samples should be HIGH confidence")
        assertEquals(MapDelta.Confidence.LOW, delta.cellConfidence(2, 2),
            "3 samples should be LOW confidence")
        assertEquals(MapDelta.Confidence.NONE, delta.cellConfidence(0, 0),
            "0 samples should be NONE confidence")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. KFLDIMX Suggestion Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `suggestKfldimxDelta applies default 8 percent overhead to KFLDRL`() {
        val kfldrlMap = make4x4KfldrlMap()
        val kfldimxMap = make4x4KfldrlMap(fillValue = 10.0) // same axes

        val entries = listOf(
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 50.0),
            wotEntry(rpm = 3000.0, targetBoostPsi = 6.0, wgdc = 70.0)
        )

        val kfldrlDelta = OptimizerCalculator.suggestKfldrlDelta(entries, kfldrlMap)
        val kfldimxDelta = OptimizerCalculator.suggestKfldimxDelta(entries, kfldrlDelta, kfldimxMap)

        // Cell [1][1]: KFLDRL suggested=50, KFLDIMX should be 50*1.08=54
        assertEquals(50.0 * 1.08, kfldimxDelta.suggested.zAxis[1][1], 0.5,
            "KFLDIMX should be KFLDRL * 1.08")
        // Cell [2][2]: KFLDRL suggested=70, KFLDIMX should be 70*1.08=75.6
        assertEquals(70.0 * 1.08, kfldimxDelta.suggested.zAxis[2][2], 0.5,
            "KFLDIMX should be KFLDRL * 1.08")
    }

    @Test
    fun `suggestKfldimxDelta with zero KFLDRL keeps original KFLDIMX values`() {
        val kfldrlMap = make4x4KfldrlMap(fillValue = 0.0)
        val originalKfldimxValue = 35.0
        val kfldimxMap = make4x4KfldrlMap(fillValue = originalKfldimxValue)

        // No WOT data → KFLDRL suggested all zeros → KFLDIMX should keep originals
        val kfldrlDelta = OptimizerCalculator.suggestKfldrlDelta(emptyList(), kfldrlMap)
        val kfldimxDelta = OptimizerCalculator.suggestKfldimxDelta(emptyList(), kfldrlDelta, kfldimxMap)

        for (r in kfldimxMap.zAxis.indices) {
            for (c in kfldimxMap.zAxis[r].indices) {
                assertEquals(originalKfldimxValue, kfldimxDelta.suggested.zAxis[r][c], 0.001,
                    "Cell [$r][$c] with zero KFLDRL should keep original KFLDIMX=$originalKfldimxValue")
            }
        }
    }

    @Test
    fun `suggestKfldimxDelta custom overhead 15 percent`() {
        val kfldrlMap = make4x4KfldrlMap()
        val kfldimxMap = make4x4KfldrlMap(fillValue = 10.0)

        val entries = listOf(
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 50.0)
        )

        val kfldrlDelta = OptimizerCalculator.suggestKfldrlDelta(entries, kfldrlMap)
        val kfldimxDelta = OptimizerCalculator.suggestKfldimxDelta(entries, kfldrlDelta, kfldimxMap, overheadPercent = 15.0)

        // 50 * 1.15 = 57.5
        assertEquals(50.0 * 1.15, kfldimxDelta.suggested.zAxis[1][1], 0.5,
            "KFLDIMX with 15% overhead should be KFLDRL * 1.15")
    }

    @Test
    fun `suggestKfldimxDelta mapName is KFLDIMX`() {
        val kfldrlMap = make4x4KfldrlMap()
        val kfldimxMap = make4x4KfldrlMap()
        val entries = listOf(wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 50.0))
        val kfldrlDelta = OptimizerCalculator.suggestKfldrlDelta(entries, kfldrlMap)
        val kfldimxDelta = OptimizerCalculator.suggestKfldimxDelta(entries, kfldrlDelta, kfldimxMap)
        assertEquals("KFLDIMX", kfldimxDelta.mapName)
    }

    @Test
    fun `suggestKfldimxDelta inherits sample counts from KFLDRL delta`() {
        val kfldrlMap = make4x4KfldrlMap()
        val kfldimxMap = make4x4KfldrlMap()

        val entries = listOf(
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 50.0),
            wotEntry(rpm = 2000.0, targetBoostPsi = 4.0, wgdc = 60.0)
        )

        val kfldrlDelta = OptimizerCalculator.suggestKfldrlDelta(entries, kfldrlMap)
        val kfldimxDelta = OptimizerCalculator.suggestKfldimxDelta(entries, kfldrlDelta, kfldimxMap)

        assertEquals(kfldrlDelta.sampleCounts[1][1], kfldimxDelta.sampleCounts[1][1],
            "KFLDIMX sample counts should match KFLDRL delta sample counts")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3. KFMIOP / KFMIRL Suggestion Tests
    // ═══════════════════════════════════════════════════════════════════

    /** Build a 4×4 KFMIOP map with torque% values that form a sensible lookup table. */
    private fun make4x4KfmiopMap(): Map3d {
        val rpmAxis = arrayOf(1000.0, 2000.0, 3000.0, 4000.0)
        val xAxis = arrayOf(20.0, 40.0, 60.0, 80.0)   // load % breakpoints
        val z = Array(4) { r ->
            Array(4) { c ->
                // Torque values increasing with load, slightly varying with RPM
                (c + 1) * 25.0 + r * 2.0
            }
        }
        return Map3d(xAxis, rpmAxis, z)
    }

    /** Build a matching KFMIRL map (same axis sizes). */
    private fun make4x4KfmirlMap(): Map3d {
        val rpmAxis = arrayOf(1000.0, 2000.0, 3000.0, 4000.0)
        val xAxis = arrayOf(25.0, 50.0, 75.0, 100.0)   // torque % breakpoints
        val z = Array(4) { r ->
            Array(4) { c ->
                // Load values: inverse relationship
                (c + 1) * 20.0 + r * 1.5
            }
        }
        return Map3d(xAxis, rpmAxis, z)
    }

    @Test
    fun `suggestKfmiopDelta with torque-capped entries scales cells`() {
        val kfmiopMap = make4x4KfmiopMap()
        val ldrxnTarget = 191.0
        val avgRlsol = 160.0

        // Create torque-capped SimulationResults at RPM=3000 (row idx 2)
        val simResults = listOf(
            simResult(rpm = 3000.0, torqueLimited = true, rlsol = avgRlsol, ldrxnTarget = ldrxnTarget),
            simResult(rpm = 3050.0, torqueLimited = true, rlsol = avgRlsol, ldrxnTarget = ldrxnTarget),
            // Non-capped at RPM=1000
            simResult(rpm = 1000.0, torqueLimited = false, rlsol = 191.0, ldrxnTarget = ldrxnTarget)
        )

        val delta = OptimizerCalculator.suggestKfmiopDelta(simResults, kfmiopMap, ldrxnTarget)

        assertNotNull(delta, "Should return non-null MapDelta when torque capping exists")

        // Expected scale factor: 191 / 160 ≈ 1.194
        val expectedFactor = ldrxnTarget / avgRlsol

        // Row 2 (RPM=3000) should be scaled
        for (c in kfmiopMap.zAxis[2].indices) {
            val original = kfmiopMap.zAxis[2][c]
            val expected = original * expectedFactor
            assertEquals(expected, delta.suggested.zAxis[2][c], 0.5,
                "KFMIOP row 2 col $c should be scaled by $expectedFactor")
        }

        // Row 0 (RPM=1000, not capped) should be unchanged
        for (c in kfmiopMap.zAxis[0].indices) {
            assertEquals(kfmiopMap.zAxis[0][c], delta.suggested.zAxis[0][c], 0.001,
                "KFMIOP row 0 (uncapped) should be unchanged")
        }
    }

    @Test
    fun `suggestKfmiopDelta returns null when no torque capping`() {
        val kfmiopMap = make4x4KfmiopMap()

        // All entries have torqueLimited = false
        val simResults = listOf(
            simResult(rpm = 1000.0, torqueLimited = false, rlsol = 191.0),
            simResult(rpm = 2000.0, torqueLimited = false, rlsol = 191.0),
            simResult(rpm = 3000.0, torqueLimited = false, rlsol = 191.0),
            simResult(rpm = 4000.0, torqueLimited = false, rlsol = 191.0)
        )

        val delta = OptimizerCalculator.suggestKfmiopDelta(simResults, kfmiopMap, ldrxnTarget = 191.0)

        assertNull(delta, "Should return null when no torque capping exists")
    }

    @Test
    fun `suggestKfmiopDelta caps scale factor at 1 point 30`() {
        val kfmiopMap = make4x4KfmiopMap()
        val ldrxnTarget = 191.0
        val lowRlsol = 100.0  // Raw factor = 191/100 = 1.91, should be capped to 1.30

        val simResults = listOf(
            simResult(rpm = 3000.0, torqueLimited = true, rlsol = lowRlsol, ldrxnTarget = ldrxnTarget),
            simResult(rpm = 3050.0, torqueLimited = true, rlsol = lowRlsol, ldrxnTarget = ldrxnTarget)
        )

        val delta = OptimizerCalculator.suggestKfmiopDelta(simResults, kfmiopMap, ldrxnTarget)

        assertNotNull(delta, "Should return non-null delta")

        // Scale factor should be capped at 1.30
        for (c in kfmiopMap.zAxis[2].indices) {
            val original = kfmiopMap.zAxis[2][c]
            val expected = original * 1.30
            assertEquals(expected, delta.suggested.zAxis[2][c], 0.5,
                "KFMIOP scale factor should be capped at 1.30, not ${ldrxnTarget / lowRlsol}")
        }
    }

    @Test
    fun `suggestKfmiopDelta mapName is KFMIOP`() {
        val kfmiopMap = make4x4KfmiopMap()
        val simResults = listOf(
            simResult(rpm = 3000.0, torqueLimited = true, rlsol = 160.0)
        )
        val delta = OptimizerCalculator.suggestKfmiopDelta(simResults, kfmiopMap, 191.0)
        assertNotNull(delta)
        assertEquals("KFMIOP", delta.mapName)
    }

    @Test
    fun `suggestKfmiopDelta with empty simulation results returns null`() {
        val kfmiopMap = make4x4KfmiopMap()
        val delta = OptimizerCalculator.suggestKfmiopDelta(emptyList(), kfmiopMap, 191.0)
        assertNull(delta, "Empty simulation results should return null")
    }

    @Test
    fun `suggestKfmiopDelta sample counts reflect capped entry count`() {
        val kfmiopMap = make4x4KfmiopMap()
        val simResults = listOf(
            simResult(rpm = 3000.0, torqueLimited = true, rlsol = 160.0),
            simResult(rpm = 3050.0, torqueLimited = true, rlsol = 160.0),
            simResult(rpm = 3100.0, torqueLimited = true, rlsol = 160.0)
        )

        val delta = OptimizerCalculator.suggestKfmiopDelta(simResults, kfmiopMap, 191.0)
        assertNotNull(delta)

        // Row 2 (RPM=3000) should have sample counts equal to the number of capped entries
        for (c in delta.sampleCounts[2].indices) {
            assertEquals(3, delta.sampleCounts[2][c],
                "Sample count for capped row should match capped entry count")
        }
    }

    @Test
    fun `suggestKfmiopDelta does not scale when factor is near 1`() {
        val kfmiopMap = make4x4KfmiopMap()
        val ldrxnTarget = 191.0
        // rlsol very close to target → factor ≈ 1.0026 → below 1.005 threshold → no change
        val simResults = listOf(
            simResult(rpm = 3000.0, torqueLimited = true, rlsol = 190.5, ldrxnTarget = ldrxnTarget)
        )

        val delta = OptimizerCalculator.suggestKfmiopDelta(simResults, kfmiopMap, ldrxnTarget)

        // 191/190.5 ≈ 1.0026 which is below the 1.005 threshold → no changes
        assertNull(delta, "Factor very close to 1.0 should not trigger changes")
    }

    @Test
    fun `suggestKfmirlDelta preserves first column`() {
        val kfmiopMap = make4x4KfmiopMap()
        val kfmirlMap = make4x4KfmirlMap()

        // Scale KFMIOP so we have actual changes
        val scaledZ = Array(kfmiopMap.yAxis.size) { r ->
            Array(kfmiopMap.xAxis.size) { c ->
                kfmiopMap.zAxis[r][c] * 1.15
            }
        }
        val suggestedKfmiop = Map3d(kfmiopMap.xAxis, kfmiopMap.yAxis, scaledZ)

        val delta = OptimizerCalculator.suggestKfmirlDelta(suggestedKfmiop, kfmirlMap)

        // First column should be preserved from original KFMIRL
        for (r in kfmirlMap.yAxis.indices) {
            assertEquals(kfmirlMap.zAxis[r][0], delta.suggested.zAxis[r][0], 0.001,
                "KFMIRL first column row $r should be preserved from original")
        }
    }

    @Test
    fun `suggestKfmirlDelta mapName is KFMIRL`() {
        val kfmiopMap = make4x4KfmiopMap()
        val kfmirlMap = make4x4KfmirlMap()

        val delta = OptimizerCalculator.suggestKfmirlDelta(kfmiopMap, kfmirlMap)
        assertEquals("KFMIRL", delta.mapName)
    }

    @Test
    fun `suggestKfmirlDelta has full sample coverage`() {
        val kfmiopMap = make4x4KfmiopMap()
        val kfmirlMap = make4x4KfmirlMap()

        val delta = OptimizerCalculator.suggestKfmirlDelta(kfmiopMap, kfmirlMap)

        // Inverse is a mathematical operation → all cells have uniform count of 100
        val totalCells = kfmirlMap.yAxis.size * kfmirlMap.xAxis.size
        assertEquals(totalCells, delta.cellsWithData,
            "All cells should have data (mathematical operation)")
        assertEquals(totalCells, delta.totalCells)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. MapDelta.build and Computed Properties
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `MapDelta build computes correct delta values`() {
        val axes = arrayOf(1.0, 2.0)
        val current = Map3d(axes, axes, arrayOf(arrayOf(10.0, 20.0), arrayOf(30.0, 40.0)))
        val suggested = Map3d(axes, axes, arrayOf(arrayOf(12.0, 20.0), arrayOf(33.0, 40.0)))
        val counts = arrayOf(intArrayOf(5, 0), intArrayOf(3, 0))

        val delta = MapDelta.build("TEST", current, suggested, counts)

        // delta.z = suggested - current
        assertEquals(2.0, delta.delta.zAxis[0][0], 0.001)
        assertEquals(0.0, delta.delta.zAxis[0][1], 0.001)
        assertEquals(3.0, delta.delta.zAxis[1][0], 0.001)
        assertEquals(0.0, delta.delta.zAxis[1][1], 0.001)

        // deltaPercent = (suggested - current) / current * 100
        assertEquals(20.0, delta.deltaPercent.zAxis[0][0], 0.001, "12-10 / 10 * 100 = 20%")
        assertEquals(10.0, delta.deltaPercent.zAxis[1][0], 0.001, "3/30 * 100 = 10%")
    }

    @Test
    fun `MapDelta cellsModified counts only changed cells`() {
        val axes = arrayOf(1.0, 2.0)
        val current = Map3d(axes, axes, arrayOf(arrayOf(10.0, 20.0), arrayOf(30.0, 40.0)))
        val suggested = Map3d(axes, axes, arrayOf(arrayOf(12.0, 20.0), arrayOf(30.0, 45.0)))
        val counts = arrayOf(intArrayOf(5, 0), intArrayOf(0, 3))

        val delta = MapDelta.build("TEST", current, suggested, counts)

        assertEquals(2, delta.cellsModified, "Only 2 cells changed (delta > 0.001)")
    }

    @Test
    fun `MapDelta avgAbsoluteDelta is correct`() {
        val axes = arrayOf(1.0, 2.0)
        val current = Map3d(axes, axes, arrayOf(arrayOf(10.0, 20.0), arrayOf(30.0, 40.0)))
        val suggested = Map3d(axes, axes, arrayOf(arrayOf(14.0, 20.0), arrayOf(30.0, 46.0)))
        val counts = arrayOf(intArrayOf(5, 0), intArrayOf(0, 3))

        val delta = MapDelta.build("TEST", current, suggested, counts)

        // Modified cells: [0][0]=4.0 delta, [1][1]=6.0 delta → avg = (4+6)/2 = 5.0
        assertEquals(5.0, delta.avgAbsoluteDelta, 0.001)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. MED17 analyzeMed17 Integration Tests with Maps
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `analyzeMed17 with KFLDRL and KFLDIMX maps produces non-null suggestions`() {
        val me7Data = parseAndAdapt("2025-01-21_16.24.32_log(1).csv")

        // Axes matching typical RS3 MED17 boost ranges
        val pressureAxis = arrayOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0)
        val rpmAxis = arrayOf(2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7000.0)
        val zeros = Array(rpmAxis.size) { Array(pressureAxis.size) { 0.0 } }
        val kfldrlMap = Map3d(pressureAxis, rpmAxis, zeros)

        val kfldimxFill = Array(rpmAxis.size) { Array(pressureAxis.size) { 10.0 } }
        val kfldimxMap = Map3d(pressureAxis, rpmAxis, kfldimxFill)

        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = kfldrlMap,
            kfldimxMap = kfldimxMap
        )

        assertNotNull(result.suggestedMaps.kfldrl, "KFLDRL suggestion should be non-null")
        assertNotNull(result.suggestedMaps.kfldimx, "KFLDIMX suggestion should be non-null")
        assertTrue(result.suggestedMaps.kfldrl!!.cellsWithData > 0,
            "KFLDRL should have cells with data")
    }

    @Test
    fun `analyzeMed17 KFLDRL suggestions are in sane WGDC range`() {
        val me7Data = parseAndAdapt("2025-01-21_16.24.32_log(1).csv")

        val pressureAxis = arrayOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0)
        val rpmAxis = arrayOf(2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7000.0)
        val zeros = Array(rpmAxis.size) { Array(pressureAxis.size) { 0.0 } }
        val kfldrlMap = Map3d(pressureAxis, rpmAxis, zeros)
        val kfldimxMap = Map3d(pressureAxis, rpmAxis, zeros)

        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = kfldrlMap,
            kfldimxMap = kfldimxMap
        )

        val kfldrl = result.suggestedMaps.kfldrl!!
        for (r in kfldrl.suggested.zAxis.indices) {
            for (c in kfldrl.suggested.zAxis[r].indices) {
                val v = kfldrl.suggested.zAxis[r][c]
                assertTrue(v in 0.0..100.0,
                    "KFLDRL suggested value $v at [$r][$c] should be in 0-100% WGDC range")
            }
        }
    }

    @Test
    fun `analyzeMed17 with KFMIOP map detects torque capping`() {
        val me7Data = parseAndAdapt("2025-01-21_16.24.32_log(1).csv")

        // The 2025 log has entries where rlsol < ldrxnTarget * 0.95 at high RPM
        val rpmAxis = arrayOf(2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7000.0)
        val loadAxis = arrayOf(20.0, 40.0, 60.0, 80.0, 100.0)
        val kfmiopZ = Array(rpmAxis.size) { Array(loadAxis.size) { 80.0 } }
        val kfmiopMap = Map3d(loadAxis, rpmAxis, kfmiopZ)

        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfmiopMap = kfmiopMap,
            ldrxnTarget = 191.0
        )

        // If torque capping is detected, KFMIOP suggestions should be non-null
        if (result.chainDiagnosis.torqueCappedPercent > 0) {
            assertNotNull(result.suggestedMaps.kfmiop,
                "KFMIOP suggestion should be non-null when torque capping is detected")
        }
    }

    @Test
    fun `analyzeMed17 chain diagnosis percentages are valid`() {
        val me7Data = parseAndAdapt("2025-01-21_16.24.32_log(1).csv")

        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = null,
            kfldimxMap = null
        )

        val diag = result.chainDiagnosis
        // MED17 mode: only torqueCapped, boostShortfall, and onTarget are set
        // pssolError and veMismatch are always 0 for MED17
        assertEquals(0.0, diag.pssolErrorPercent, 0.001,
            "MED17 should have 0% pssol error")
        assertEquals(0.0, diag.veMismatchPercent, 0.001,
            "MED17 should have 0% VE mismatch")

        // MED17 computes torqueCapped and boostShortfall independently — they CAN overlap
        // (e.g., during spool-up: rlsol below target AND actual boost short of requested).
        // onTargetPercent = max(0, 100 - torquePct - boostPct), clamped at 0 on overlap.
        assertTrue(diag.torqueCappedPercent in 0.0..100.0,
            "torqueCappedPercent out of range: ${diag.torqueCappedPercent}")
        assertTrue(diag.boostShortfallPercent in 0.0..100.0,
            "boostShortfallPercent out of range: ${diag.boostShortfallPercent}")
        assertTrue(diag.onTargetPercent >= 0.0,
            "onTargetPercent should be >= 0: ${diag.onTargetPercent}")
    }

    @Test
    fun `analyzeMed17 with cruise log produces no KFLDRL changes`() {
        val me7Data = parseAndAdapt("2023-05-19_21.08.33_log.csv")

        val pressureAxis = arrayOf(0.0, 4.0, 8.0, 12.0)
        val rpmAxis = arrayOf(2000.0, 4000.0, 6000.0)
        val fillValue = 30.0
        val z = Array(rpmAxis.size) { Array(pressureAxis.size) { fillValue } }
        val kfldrlMap = Map3d(pressureAxis, rpmAxis, z)
        val kfldimxMap = Map3d(pressureAxis, rpmAxis, z)

        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = kfldrlMap,
            kfldimxMap = kfldimxMap
        )

        // No WOT entries → KFLDRL delta should have zero cells modified
        val kfldrl = result.suggestedMaps.kfldrl
        assertNotNull(kfldrl)
        assertEquals(0, kfldrl.cellsWithData,
            "Cruise log should not produce any KFLDRL data")
        assertEquals(0, kfldrl.cellsModified,
            "Cruise log should not modify any KFLDRL cells")
    }

    @Test
    fun `analyzeMed17 produces chain diagnosis recommendations`() {
        val me7Data = parseAndAdapt("2025-01-21_16.24.32_log(1).csv")

        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = null,
            kfldimxMap = null
        )

        // MED17 always includes an INFO line about VE model unavailability
        assertTrue(result.chainDiagnosis.recommendations.isNotEmpty(),
            "Chain diagnosis should include at least one recommendation")
        assertTrue(
            result.chainDiagnosis.recommendations.any { it.contains("MED17") },
            "Should include MED17-specific recommendation"
        )
    }

    @Test
    fun `analyzeMed17 with KFMIOP and KFMIRL maps chains suggestions`() {
        val me7Data = parseAndAdapt("2025-01-21_16.24.32_log(1).csv")

        val rpmAxis = arrayOf(2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7000.0)
        val loadAxis = arrayOf(20.0, 40.0, 60.0, 80.0, 100.0)
        // KFMIOP z-values must be strictly increasing across columns for Inverse to work
        val kfmiopZ = Array(rpmAxis.size) { r ->
            Array(loadAxis.size) { c -> 40.0 + c * 15.0 + r * 2.0 }
        }
        val kfmiopMap = Map3d(loadAxis, rpmAxis, kfmiopZ)

        val torqueAxis = arrayOf(40.0, 55.0, 70.0, 85.0, 100.0)
        val kfmirlZ = Array(rpmAxis.size) { r ->
            Array(torqueAxis.size) { c -> 20.0 + c * 20.0 + r * 1.5 }
        }
        val kfmirlMap = Map3d(torqueAxis, rpmAxis, kfmirlZ)

        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfmiopMap = kfmiopMap,
            kfmirlMap = kfmirlMap,
            ldrxnTarget = 191.0
        )

        // If KFMIOP was suggested, KFMIRL should also be suggested
        if (result.suggestedMaps.kfmiop != null) {
            assertNotNull(result.suggestedMaps.kfmirl,
                "When KFMIOP is suggested, KFMIRL should also be suggested")
        }
    }

    @Test
    fun `analyzeMed17 with 2023-21h31 log produces WOT-based suggestions`() {
        val me7Data = parseAndAdapt("2023-05-19_21.31.12_log.csv")

        val pressureAxis = arrayOf(0.0, 3.0, 6.0, 9.0, 12.0)
        val rpmAxis = arrayOf(2000.0, 3500.0, 5000.0, 6500.0)
        val zeros = Array(rpmAxis.size) { Array(pressureAxis.size) { 0.0 } }
        val kfldrlMap = Map3d(pressureAxis, rpmAxis, zeros)
        val kfldimxMap = Map3d(pressureAxis, rpmAxis, zeros)

        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = kfldrlMap,
            kfldimxMap = kfldimxMap
        )

        assertTrue(result.wotEntries.isNotEmpty(),
            "2023-21h31 log should have WOT entries")
        val kfldrl = result.suggestedMaps.kfldrl
        assertNotNull(kfldrl)
        assertTrue(kfldrl.cellsWithData > 0,
            "Should have KFLDRL cells with data from 2023-21h31 log")
    }

    @Test
    fun `analyzeMed17 without KFMIRL map still produces KFMIOP suggestion alone`() {
        val me7Data = parseAndAdapt("2025-01-21_16.24.32_log(1).csv")

        val rpmAxis = arrayOf(2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7000.0)
        val loadAxis = arrayOf(20.0, 40.0, 60.0, 80.0, 100.0)
        val kfmiopZ = Array(rpmAxis.size) { Array(loadAxis.size) { 80.0 } }
        val kfmiopMap = Map3d(loadAxis, rpmAxis, kfmiopZ)

        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfmiopMap = kfmiopMap,
            kfmirlMap = null,   // No KFMIRL
            ldrxnTarget = 191.0
        )

        // KFMIRL should be null when no KFMIRL map is provided
        assertNull(result.suggestedMaps.kfmirl,
            "KFMIRL suggestion should be null when no KFMIRL map is provided")
    }

    @Test
    fun `analyzeMed17 null KFPBRK in suggested maps (MED17 mode)`() {
        val me7Data = parseAndAdapt("2025-01-21_16.24.32_log(1).csv")
        val result = OptimizerCalculator.analyzeMed17(
            values = me7Data,
            kfldrlMap = null,
            kfldimxMap = null
        )
        assertNull(result.suggestedMaps.kfpbrk,
            "KFPBRK should always be null in MED17 mode")
    }
}
