package domain.model.pfi

import data.contract.Med17LogFileContract.Header
import kotlin.test.*

/**
 * Tests for [PfiShareCalculator.refineFromLog2d] — 2D RPM × Load PFI share binning.
 */
class PfiShare2dTest {

    // ── 2D binning ──────────────────────────────────────────────────────

    @Test
    fun `refineFromLog2d bins by RPM and load`() {
        // Synthetic data: known RPM/load/PFI values
        val rpmList = listOf(2000.0, 2000.0, 4500.0, 4500.0)
        val loadList = listOf(40.0, 40.0, 140.0, 140.0)
        val pfiList = listOf(0.30, 0.40, 0.70, 0.80)

        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to rpmList,
            Header.PFI_SPLIT_FACTOR_HEADER to pfiList,
            Header.ENGINE_LOAD_HEADER to loadList
        )

        val result = PfiShareCalculator.refineFromLog2d(logData)

        // Find the bin indices for RPM=2000, load=40
        val rpmIdx2k = PfiShareCalculator.nearestBinIndex(2000.0, result.rpmAxis)
        val loadIdx40 = PfiShareCalculator.nearestBinIndex(40.0, result.loadAxis)

        // Average of 0.30 and 0.40 = 0.35 → 35%
        assertEquals(35.0, result.pfiSharePercent2d[rpmIdx2k][loadIdx40], 1e-9)
        assertEquals(2, result.sampleCounts[rpmIdx2k][loadIdx40])

        // Find bin for RPM=4500, load=140
        val rpmIdx4500 = PfiShareCalculator.nearestBinIndex(4500.0, result.rpmAxis)
        val loadIdx140 = PfiShareCalculator.nearestBinIndex(140.0, result.loadAxis)

        // Average of 0.70 and 0.80 = 0.75 → 75%
        assertEquals(75.0, result.pfiSharePercent2d[rpmIdx4500][loadIdx140], 1e-9)
        assertEquals(2, result.sampleCounts[rpmIdx4500][loadIdx140])
    }

    @Test
    fun `refineFromLog2d handles missing load column gracefully`() {
        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(3000.0, 4000.0, 5000.0),
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(0.5, 0.6, 0.5)
            // No ENGINE_LOAD_HEADER
        )

        val result = PfiShareCalculator.refineFromLog2d(logData)

        // Should fall back to 1D: all load columns identical
        assertNotNull(result)
        assertEquals(result.rpmAxis.size, result.pfiSharePercent2d.size)

        // All columns at a given RPM row should be identical (replicated from 1D)
        for (r in result.pfiSharePercent2d.indices) {
            val firstVal = result.pfiSharePercent2d[r][0]
            for (l in 1 until result.loadAxis.size) {
                assertEquals(
                    firstVal, result.pfiSharePercent2d[r][l], 1e-9,
                    "Fallback: all load columns at RPM[${result.rpmAxis[r]}] should be identical"
                )
            }
        }

        // rpmOnlyCurve should still be populated with logged data
        assertNotNull(result.rpmOnlyCurve.loggedRpmAxis)
    }

    @Test
    fun `refineFromLog2d with empty data returns default`() {
        val logData = emptyMap<Header, List<Double>>()
        val result = PfiShareCalculator.refineFromLog2d(logData)

        assertEquals(0, result.totalSamples)
        assertNull(result.rpmOnlyCurve.loggedRpmAxis)
        // All sample counts should be zero
        for (r in result.sampleCounts.indices) {
            for (l in result.sampleCounts[r].indices) {
                assertEquals(0, result.sampleCounts[r][l])
            }
        }
    }

    // ── nearestBinIndex ─────────────────────────────────────────────────

    @Test
    fun `nearestBinIndex finds exact match`() {
        val bins = doubleArrayOf(1000.0, 2000.0, 3000.0, 4000.0)
        assertEquals(0, PfiShareCalculator.nearestBinIndex(1000.0, bins))
        assertEquals(2, PfiShareCalculator.nearestBinIndex(3000.0, bins))
        assertEquals(3, PfiShareCalculator.nearestBinIndex(4000.0, bins))
    }

    @Test
    fun `nearestBinIndex finds nearest between bins`() {
        val bins = doubleArrayOf(1000.0, 2000.0, 3000.0, 4000.0)
        // 1600 is closer to 2000 than 1000
        assertEquals(1, PfiShareCalculator.nearestBinIndex(1600.0, bins))
        // 1400 is closer to 1000 than 2000
        assertEquals(0, PfiShareCalculator.nearestBinIndex(1400.0, bins))
        // 2500 is equidistant — first match wins
        assertEquals(1, PfiShareCalculator.nearestBinIndex(2500.0, bins))
    }

    @Test
    fun `nearestBinIndex handles below range`() {
        val bins = doubleArrayOf(1000.0, 2000.0, 3000.0)
        assertEquals(0, PfiShareCalculator.nearestBinIndex(500.0, bins))
    }

    @Test
    fun `nearestBinIndex handles above range`() {
        val bins = doubleArrayOf(1000.0, 2000.0, 3000.0)
        assertEquals(2, PfiShareCalculator.nearestBinIndex(9000.0, bins))
    }

    // ── Empty cell interpolation ────────────────────────────────────────

    @Test
    fun `empty cells interpolated from neighbors`() {
        // 3×3 grid: center is empty, corners are filled
        val grid = arrayOf(
            doubleArrayOf(40.0, Double.NaN, 60.0),
            doubleArrayOf(Double.NaN, Double.NaN, Double.NaN),
            doubleArrayOf(20.0, Double.NaN, 80.0)
        )
        val counts = arrayOf(
            intArrayOf(5, 0, 5),
            intArrayOf(0, 0, 0),
            intArrayOf(5, 0, 5)
        )

        PfiShareCalculator.interpolateEmptyCells(grid, counts)

        // Center cell [1][1] has no direct cardinal neighbours with data → 50% default
        // (its cardinal neighbours are [0][1], [2][1], [1][0], [1][2] — all empty)
        assertEquals(50.0, grid[1][1], 1e-9)

        // [0][1] has neighbours [0][0]=40 and [0][2]=60 → average = 50
        assertEquals(50.0, grid[0][1], 1e-9)

        // [1][0] has neighbours [0][0]=40 and [2][0]=20 → average = 30
        assertEquals(30.0, grid[1][0], 1e-9)

        // [1][2] has neighbours [0][2]=60 and [2][2]=80 → average = 70
        assertEquals(70.0, grid[1][2], 1e-9)

        // [2][1] has neighbours [2][0]=20 and [2][2]=80 → average = 50
        assertEquals(50.0, grid[2][1], 1e-9)
    }

    @Test
    fun `empty cells with no neighbors get default 50 percent`() {
        val grid = arrayOf(
            doubleArrayOf(Double.NaN, Double.NaN),
            doubleArrayOf(Double.NaN, Double.NaN)
        )
        val counts = arrayOf(
            intArrayOf(0, 0),
            intArrayOf(0, 0)
        )

        PfiShareCalculator.interpolateEmptyCells(grid, counts)

        for (r in grid.indices) {
            for (l in grid[r].indices) {
                assertEquals(50.0, grid[r][l], 1e-9)
            }
        }
    }

    // ── Sample counts ───────────────────────────────────────────────────

    @Test
    fun `sample counts track per-cell`() {
        val rpmList = listOf(2000.0, 2000.0, 2000.0, 5000.0)
        val loadList = listOf(100.0, 100.0, 100.0, 100.0)
        val pfiList = listOf(0.5, 0.5, 0.5, 0.7)

        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to rpmList,
            Header.PFI_SPLIT_FACTOR_HEADER to pfiList,
            Header.ENGINE_LOAD_HEADER to loadList
        )

        val result = PfiShareCalculator.refineFromLog2d(logData)

        val rpmIdx2k = PfiShareCalculator.nearestBinIndex(2000.0, result.rpmAxis)
        val loadIdx100 = PfiShareCalculator.nearestBinIndex(100.0, result.loadAxis)

        assertEquals(3, result.sampleCounts[rpmIdx2k][loadIdx100])
        assertEquals(4, result.totalSamples)

        val rpmIdx5k = PfiShareCalculator.nearestBinIndex(5000.0, result.rpmAxis)
        assertEquals(1, result.sampleCounts[rpmIdx5k][loadIdx100])
    }

    // ── 2D eliminates load-mixing dips ──────────────────────────────────

    @Test
    fun `2D eliminates load-mixing dips`() {
        // Same RPM (3000), but two very different loads with different PFI shares.
        // In 1D RPM-only binning, these would average together → a misleading "dip".
        // In 2D, they should be in separate bins.
        val rpmList = mutableListOf<Double>()
        val loadList = mutableListOf<Double>()
        val pfiList = mutableListOf<Double>()

        // 20 samples at 3000 RPM / 40% load → low PFI share (0.20)
        repeat(20) {
            rpmList.add(3000.0)
            loadList.add(40.0)
            pfiList.add(0.20)
        }
        // 20 samples at 3000 RPM / 160% load → high PFI share (0.70)
        repeat(20) {
            rpmList.add(3000.0)
            loadList.add(160.0)
            pfiList.add(0.70)
        }

        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to rpmList,
            Header.PFI_SPLIT_FACTOR_HEADER to pfiList,
            Header.ENGINE_LOAD_HEADER to loadList
        )

        val result = PfiShareCalculator.refineFromLog2d(logData)

        val rpmIdx = PfiShareCalculator.nearestBinIndex(3000.0, result.rpmAxis)
        val loadIdxLow = PfiShareCalculator.nearestBinIndex(40.0, result.loadAxis)
        val loadIdxHigh = PfiShareCalculator.nearestBinIndex(160.0, result.loadAxis)

        // In 2D, these should be separate and preserve their distinct values
        val lowLoadPfi = result.pfiSharePercent2d[rpmIdx][loadIdxLow]
        val highLoadPfi = result.pfiSharePercent2d[rpmIdx][loadIdxHigh]

        assertEquals(20.0, lowLoadPfi, 1e-9, "Low load PFI should be 20%")
        assertEquals(70.0, highLoadPfi, 1e-9, "High load PFI should be 70%")

        // The difference must be preserved (not averaged out like 1D would)
        assertTrue(
            highLoadPfi - lowLoadPfi > 40.0,
            "2D should separate loads: diff=${highLoadPfi - lowLoadPfi}"
        )

        // Meanwhile, the 1D curve would have averaged them at ~45%
        val rpm1d = result.rpmOnlyCurve
        if (rpm1d.loggedRpmAxis != null) {
            val idx3k = rpm1d.loggedRpmAxis!!.indexOfFirst { it >= 2500.0 && it <= 3500.0 }
            if (idx3k >= 0) {
                val avg1d = rpm1d.loggedPfiPercent!![idx3k]
                // 1D average would be (0.20 + 0.70) / 2 * 100 = 45%
                assertEquals(45.0, avg1d, 1e-9, "1D should average both loads together")
            }
        }
    }

    // ── Backward compatibility ───────────────────────────────────────────

    @Test
    fun `rpmOnlyCurve is populated with default curve`() {
        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(3000.0),
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(0.5),
            Header.ENGINE_LOAD_HEADER to listOf(100.0)
        )

        val result = PfiShareCalculator.refineFromLog2d(logData)

        // rpmOnlyCurve should match default axis
        assertContentEquals(PfiShareCalculator.DEFAULT_RPM_AXIS, result.rpmOnlyCurve.rpmAxis)
    }

    @Test
    fun `existing refineFromLog still works unchanged`() {
        val rpmList = listOf(2000.0, 3000.0, 4000.0, 5000.0)
        val pfiList = listOf(0.3, 0.5, 0.6, 0.55)

        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to rpmList,
            Header.PFI_SPLIT_FACTOR_HEADER to pfiList
        )

        val result1d = PfiShareCalculator.refineFromLog(logData)
        assertNotNull(result1d.loggedRpmAxis)
        assertNotNull(result1d.loggedPfiPercent)
        assertContentEquals(PfiShareCalculator.DEFAULT_RPM_AXIS, result1d.rpmAxis)
    }

    // ── Grid dimensions ─────────────────────────────────────────────────

    @Test
    fun `2D result has correct grid dimensions`() {
        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(3000.0),
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(0.5),
            Header.ENGINE_LOAD_HEADER to listOf(100.0)
        )

        val result = PfiShareCalculator.refineFromLog2d(logData)

        assertEquals(PfiShareCalculator.DEFAULT_2D_RPM_BINS.size, result.rpmAxis.size)
        assertEquals(PfiShareCalculator.DEFAULT_2D_LOAD_BINS.size, result.loadAxis.size)
        assertEquals(result.rpmAxis.size, result.pfiSharePercent2d.size)
        for (row in result.pfiSharePercent2d) {
            assertEquals(result.loadAxis.size, row.size)
        }
        assertEquals(result.rpmAxis.size, result.sampleCounts.size)
        for (row in result.sampleCounts) {
            assertEquals(result.loadAxis.size, row.size)
        }
    }

    @Test
    fun `all 2D values are in 0-100 range`() {
        val rpmList = mutableListOf<Double>()
        val loadList = mutableListOf<Double>()
        val pfiList = mutableListOf<Double>()
        for (rpm in 1000..7000 step 500) {
            for (load in 20..200 step 20) {
                rpmList.add(rpm.toDouble())
                loadList.add(load.toDouble())
                pfiList.add((rpm.toDouble() / 10000.0).coerceIn(0.0, 1.0))
            }
        }

        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to rpmList,
            Header.PFI_SPLIT_FACTOR_HEADER to pfiList,
            Header.ENGINE_LOAD_HEADER to loadList
        )

        val result = PfiShareCalculator.refineFromLog2d(logData)

        for (r in result.pfiSharePercent2d.indices) {
            for (l in result.pfiSharePercent2d[r].indices) {
                val v = result.pfiSharePercent2d[r][l]
                assertTrue(v in 0.0..100.0, "PFI[${result.rpmAxis[r]}][${result.loadAxis[l]}] = $v out of range")
                assertFalse(v.isNaN(), "PFI[${result.rpmAxis[r]}][${result.loadAxis[l]}] should not be NaN")
            }
        }
    }

    @Test
    fun `custom bins are respected`() {
        val customRpm = doubleArrayOf(2000.0, 4000.0, 6000.0)
        val customLoad = doubleArrayOf(50.0, 100.0, 150.0)

        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(4000.0),
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(0.6),
            Header.ENGINE_LOAD_HEADER to listOf(100.0)
        )

        val result = PfiShareCalculator.refineFromLog2d(logData, customRpm, customLoad)

        assertEquals(3, result.rpmAxis.size)
        assertEquals(3, result.loadAxis.size)
        assertContentEquals(customRpm, result.rpmAxis)
        assertContentEquals(customLoad, result.loadAxis)
    }

    @Test
    fun `zero RPM and zero load rows are skipped`() {
        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(0.0, 3000.0, 3000.0),
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(0.99, 0.5, 0.6),
            Header.ENGINE_LOAD_HEADER to listOf(100.0, 0.0, 100.0)
        )

        val result = PfiShareCalculator.refineFromLog2d(logData)

        // Only the last row (RPM=3000, load=100) should count
        val rpmIdx = PfiShareCalculator.nearestBinIndex(3000.0, result.rpmAxis)
        val loadIdx = PfiShareCalculator.nearestBinIndex(100.0, result.loadAxis)
        assertEquals(1, result.sampleCounts[rpmIdx][loadIdx])
        assertEquals(60.0, result.pfiSharePercent2d[rpmIdx][loadIdx], 1e-9)
    }
}
