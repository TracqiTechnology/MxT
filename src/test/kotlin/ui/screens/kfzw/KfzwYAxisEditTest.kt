package ui.screens.kfzw

import domain.math.AxisRescaler
import domain.math.map.Map3d
import domain.model.kfzw.Kfzw
import kotlin.test.*

/**
 * Tests for Y-axis (RPM) editing on KFZW and KFZWOP screens.
 *
 * Validates the computation pipeline:
 *   1. X-axis rescaling via [Kfzw.generateKfzw] (row-wise linear interpolation)
 *   2. Y-axis rescaling via [AxisRescaler.rescaleMap] (bilinear interpolation)
 *   3. Combined X+Y rescaling
 *   4. Extrapolation diagnostics for out-of-range RPM breakpoints
 */
class KfzwYAxisEditTest {

    /**
     * 4×3 ignition map (4 RPM rows × 3 load columns).
     * X (load): [20, 40, 60]   Y (RPM): [1000, 2000, 3000, 4000]
     * Z (degrees):
     *   RPM\Load  20    40    60
     *   1000     10.0  12.0  14.0
     *   2000     15.0  18.0  20.0
     *   3000     20.0  24.0  26.0
     *   4000     22.0  26.0  28.0
     */
    private fun buildKfzwMap(): Map3d = Map3d(
        arrayOf(20.0, 40.0, 60.0),
        arrayOf(1000.0, 2000.0, 3000.0, 4000.0),
        arrayOf(
            arrayOf(10.0, 12.0, 14.0),
            arrayOf(15.0, 18.0, 20.0),
            arrayOf(20.0, 24.0, 26.0),
            arrayOf(22.0, 26.0, 28.0)
        )
    )

    // ── KFZW: Y-axis RPM edit triggers rescale ─────────────────────────

    @Test
    fun `Y-axis RPM edit triggers rescale on KFZW`() {
        val original = buildKfzwMap()

        // Keep original X-axis, rescale Y from [1000,2000,3000,4000] to [1000,1500,2000,3000,4000]
        val newYAxis = arrayOf(1000.0, 1500.0, 2000.0, 3000.0, 4000.0)
        val result = AxisRescaler.rescaleMap(original, newYAxis = newYAxis)
        val map = result.rescaledMap

        assertEquals(5, map.yAxis.size, "Output should have 5 RPM rows")
        assertEquals(3, map.xAxis.size, "X-axis should remain unchanged")

        // Row at RPM=1000 (original): exact match
        assertEquals(10.0, map.zAxis[0][0], 1e-9, "RPM=1000, Load=20")
        assertEquals(12.0, map.zAxis[0][1], 1e-9, "RPM=1000, Load=40")
        assertEquals(14.0, map.zAxis[0][2], 1e-9, "RPM=1000, Load=60")

        // Row at RPM=1500 (interpolated between 1000 and 2000):
        assertEquals(12.5, map.zAxis[1][0], 1e-9, "RPM=1500, Load=20 (midpoint 10,15)")
        assertEquals(15.0, map.zAxis[1][1], 1e-9, "RPM=1500, Load=40 (midpoint 12,18)")
        assertEquals(17.0, map.zAxis[1][2], 1e-9, "RPM=1500, Load=60 (midpoint 14,20)")

        // Row at RPM=2000 (original): exact match
        assertEquals(15.0, map.zAxis[2][0], 1e-9, "RPM=2000, Load=20")
        assertEquals(18.0, map.zAxis[2][1], 1e-9, "RPM=2000, Load=40")
        assertEquals(20.0, map.zAxis[2][2], 1e-9, "RPM=2000, Load=60")

        // No extrapolation — all within original bounds
        assertEquals(0, result.extrapolatedCount)
    }

    // ── KFZWOP: Y-axis RPM edit triggers rescale ───────────────────────

    @Test
    fun `Y-axis RPM edit triggers rescale on KFZWOP`() {
        val original = buildKfzwMap()

        // Simulate KFZWOP pipeline: first do X-axis via generateKfzw, then Y via AxisRescaler
        val newXAxis = original.xAxis.copyOf() // keep same X
        val newYAxis = arrayOf(1000.0, 2500.0, 4000.0) // coarser RPM grid

        // Step 1: X-axis rescale (identity in this case)
        val xRescaledZ = Kfzw.generateKfzw(original.xAxis, original.zAxis, newXAxis)
        val xRescaledMap = Map3d(newXAxis, original.yAxis, xRescaledZ)

        // Step 2: Y-axis rescale via AxisRescaler
        val result = AxisRescaler.rescaleMap(xRescaledMap, newYAxis = newYAxis)
        val map = result.rescaledMap

        assertEquals(3, map.yAxis.size, "Output should have 3 RPM rows")
        assertEquals(3, map.xAxis.size, "X-axis unchanged")

        // RPM=1000: exact match to original first row
        assertEquals(10.0, map.zAxis[0][0], 1e-9, "RPM=1000, Load=20")
        assertEquals(12.0, map.zAxis[0][1], 1e-9, "RPM=1000, Load=40")
        assertEquals(14.0, map.zAxis[0][2], 1e-9, "RPM=1000, Load=60")

        // RPM=2500: midpoint between RPM=2000 and RPM=3000
        assertEquals(17.5, map.zAxis[1][0], 1e-9, "RPM=2500, Load=20 (midpoint 15,20)")
        assertEquals(21.0, map.zAxis[1][1], 1e-9, "RPM=2500, Load=40 (midpoint 18,24)")
        assertEquals(23.0, map.zAxis[1][2], 1e-9, "RPM=2500, Load=60 (midpoint 20,26)")

        // RPM=4000: exact match to original last row
        assertEquals(22.0, map.zAxis[2][0], 1e-9, "RPM=4000, Load=20")
        assertEquals(26.0, map.zAxis[2][1], 1e-9, "RPM=4000, Load=40")
        assertEquals(28.0, map.zAxis[2][2], 1e-9, "RPM=4000, Load=60")

        assertEquals(0, result.extrapolatedCount, "All within bounds")
    }

    // ── Combined X+Y rescaling ─────────────────────────────────────────

    @Test
    fun `combined X+Y rescaling works`() {
        val original = buildKfzwMap()

        // New X-axis: same size as original [20,40,60] but shifted to [25,45,65]
        val newXAxis = arrayOf(25.0, 45.0, 65.0)

        // Step 1: X-axis rescale via Kfzw.generateKfzw (preserves dimensions)
        val xRescaledZ = Kfzw.generateKfzw(original.xAxis, original.zAxis, newXAxis)
        val xRescaledMap = Map3d(newXAxis, original.yAxis, xRescaledZ)

        assertEquals(3, xRescaledZ[0].size, "generateKfzw preserves column count")
        assertEquals(4, xRescaledZ.size, "generateKfzw preserves row count")

        // Step 2: Y-axis rescale - add 1500 RPM between 1000 and 2000
        val newYAxis = arrayOf(1000.0, 1500.0, 2000.0, 3000.0, 4000.0)
        val result = AxisRescaler.rescaleMap(xRescaledMap, newYAxis = newYAxis)
        val map = result.rescaledMap

        assertEquals(3, map.xAxis.size)
        assertEquals(5, map.yAxis.size)
        assertEquals(15, result.totalCells)

        // Y-interpolated row at RPM=1500 should be midpoint between RPM=1000 and RPM=2000 rows
        val rpm1000 = map.zAxis[0]
        val rpm1500 = map.zAxis[1]
        val rpm2000 = map.zAxis[2]
        for (c in 0 until 3) {
            val expected = (rpm1000[c] + rpm2000[c]) / 2.0
            assertEquals(expected, rpm1500[c], 1e-9, "RPM=1500 col $c is midpoint")
        }

        // Original RPM endpoints should still be intact
        assertEquals(map.zAxis[0][0], xRescaledMap.zAxis[0][0], 1e-9, "RPM=1000 preserved")
        assertEquals(map.zAxis[4][2], xRescaledMap.zAxis[3][2], 1e-9, "RPM=4000 preserved")
    }

    // ── Extrapolation warnings for out-of-range RPM ────────────────────

    @Test
    fun `extrapolation warnings for out-of-range RPM`() {
        val original = buildKfzwMap()

        // Extend RPM below and above original range [1000,4000]
        val newYAxis = arrayOf(500.0, 1000.0, 2000.0, 3000.0, 4000.0, 5000.0)
        val result = AxisRescaler.rescaleMap(original, newYAxis = newYAxis)
        val map = result.rescaledMap

        assertEquals(6, map.yAxis.size)

        // RPM=500 (below range): clamped to RPM=1000 values
        assertEquals(10.0, map.zAxis[0][0], 1e-9, "RPM=500 clamped → RPM=1000, Load=20")
        assertEquals(12.0, map.zAxis[0][1], 1e-9, "RPM=500 clamped → RPM=1000, Load=40")
        assertEquals(14.0, map.zAxis[0][2], 1e-9, "RPM=500 clamped → RPM=1000, Load=60")

        // RPM=5000 (above range): clamped to RPM=4000 values
        assertEquals(22.0, map.zAxis[5][0], 1e-9, "RPM=5000 clamped → RPM=4000, Load=20")
        assertEquals(26.0, map.zAxis[5][1], 1e-9, "RPM=5000 clamped → RPM=4000, Load=40")
        assertEquals(28.0, map.zAxis[5][2], 1e-9, "RPM=5000 clamped → RPM=4000, Load=60")

        // Extrapolation count: row 0 (3 cells) + row 5 (3 cells) = 6
        assertEquals(6, result.extrapolatedCount, "2 out-of-range RPM rows × 3 columns = 6")

        // Flags: first and last rows are extrapolated
        for (c in 0 until 3) {
            assertTrue(result.extrapolatedCells[0][c], "Row 0 (RPM=500) col $c should be extrapolated")
            assertTrue(result.extrapolatedCells[5][c], "Row 5 (RPM=5000) col $c should be extrapolated")
        }
        // Middle rows should not be extrapolated
        for (r in 1..4) {
            for (c in 0 until 3) {
                assertFalse(result.extrapolatedCells[r][c], "Row $r col $c should NOT be extrapolated")
            }
        }
    }

    // ── Edge case: Y-axis identity preserves original ──────────────────

    @Test
    fun `Y-axis identity rescale preserves original values`() {
        val original = buildKfzwMap()
        val result = AxisRescaler.rescaleMap(original, newYAxis = original.yAxis.copyOf())

        for (r in original.zAxis.indices) {
            for (c in original.zAxis[r].indices) {
                assertEquals(original.zAxis[r][c], result.rescaledMap.zAxis[r][c], 1e-9,
                    "Value preserved at [$r][$c]")
            }
        }
        assertEquals(0, result.extrapolatedCount)
        assertEquals(original.xAxis.size * original.yAxis.size, result.exactMatchCount)
    }

    // ── Edge case: X-axis edit alone still works (regression) ──────────

    @Test
    fun `X-axis only edit still works without Y-axis change`() {
        val original = buildKfzwMap()

        // generateKfzw preserves array dimensions — new X values, same count
        val newXAxis = arrayOf(25.0, 45.0, 65.0) // shifted from [20,40,60]
        val xRescaledZ = Kfzw.generateKfzw(original.xAxis, original.zAxis, newXAxis)

        assertEquals(4, xRescaledZ.size, "Row count preserved")
        assertEquals(3, xRescaledZ[0].size, "Column count preserved (same as original)")

        // Verify interpolated values at RPM=1000 (row 0): original [10,12,14] at loads [20,40,60]
        // Load=25: interpolated between 10 and 12 at (25-20)/(40-20) = 0.25 → 10.5
        assertEquals(10.5, xRescaledZ[0][0], 1e-9, "Load=25, interpolated")
        // Load=45: interpolated between 12 and 14 at (45-40)/(60-40) = 0.25 → 12.5
        assertEquals(12.5, xRescaledZ[0][1], 1e-9, "Load=45, interpolated")
        // Load=65: extrapolated beyond Load=60 (clamped by -13.5 min)
        // LinearExtrapolation from [40,60] with values [12,14]: slope=0.1, at 65 → 14.5
        assertEquals(14.5, xRescaledZ[0][2], 1e-9, "Load=65, extrapolated")
    }
}
