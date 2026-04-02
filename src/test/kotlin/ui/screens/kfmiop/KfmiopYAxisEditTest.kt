package ui.screens.kfmiop

import domain.math.AxisRescaler
import domain.math.map.Map3d
import domain.model.kfmiop.Kfmiop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Domain-level tests for KFMIOP Y-axis (RPM) editing.
 *
 * Verifies that AxisRescaler correctly recomputes Z values when the RPM
 * axis is edited, including combined X+Y rescaling and extrapolation detection.
 */
class KfmiopYAxisEditTest {

    /**
     * Standard 4×3 KFMIOP-like test map:
     *   X (load): [50, 100, 150, 200]
     *   Y (RPM):  [1000, 3000, 5000]
     *   Z (torque %):
     *     [[10, 20, 30, 40],
     *      [15, 30, 45, 60],
     *      [20, 40, 60, 80]]
     */
    private fun buildKfmiopMap(): Map3d = Map3d(
        arrayOf(50.0, 100.0, 150.0, 200.0),
        arrayOf(1000.0, 3000.0, 5000.0),
        arrayOf(
            arrayOf(10.0, 20.0, 30.0, 40.0),
            arrayOf(15.0, 30.0, 45.0, 60.0),
            arrayOf(20.0, 40.0, 60.0, 80.0)
        )
    )

    @Test
    fun `Y-axis edit triggers AxisRescaler and recomputes Z`() {
        val original = buildKfmiopMap()

        // Edit RPM axis: insert a midpoint at 2000
        val newYAxis = arrayOf(1000.0, 2000.0, 3000.0, 5000.0)
        val result = AxisRescaler.rescaleYAxis(original, newYAxis)

        val map = result.rescaledMap
        assertEquals(4, map.yAxis.size, "Output should have 4 RPM breakpoints")
        assertEquals(4, map.xAxis.size, "X-axis should be unchanged")

        // At y=2000 (midpoint of 1000-3000), x=50: interp of [10, 15] = 12.5
        assertEquals(12.5, map.zAxis[1][0], 1e-9, "Interpolated at RPM=2000, load=50")

        // At y=2000, x=200: interp of [40, 60] = 50.0
        assertEquals(50.0, map.zAxis[1][3], 1e-9, "Interpolated at RPM=2000, load=200")

        // Original breakpoints should be preserved
        assertEquals(10.0, map.zAxis[0][0], 1e-9, "Original RPM=1000, load=50")
        assertEquals(30.0, map.zAxis[2][1], 1e-9, "Original RPM=3000, load=100")
        assertEquals(80.0, map.zAxis[3][3], 1e-9, "Original RPM=5000, load=200")
    }

    @Test
    fun `combined X+Y axis rescaling works`() {
        val original = buildKfmiopMap()

        // Edit both axes simultaneously
        val newXAxis = arrayOf(50.0, 125.0, 200.0) // Coarser load axis with midpoint
        val newYAxis = arrayOf(1000.0, 2000.0, 3000.0, 4000.0, 5000.0) // Finer RPM axis

        val result = AxisRescaler.rescaleMap(original, newXAxis = newXAxis, newYAxis = newYAxis)

        val map = result.rescaledMap
        assertEquals(3, map.xAxis.size, "Output X-axis should have 3 breakpoints")
        assertEquals(5, map.yAxis.size, "Output Y-axis should have 5 breakpoints")
        assertEquals(15, result.totalCells, "Total cells = 3×5")

        // Corner points on original grid should be exact
        assertEquals(10.0, map.zAxis[0][0], 1e-9, "Corner (50, 1000)")
        assertEquals(40.0, map.zAxis[0][2], 1e-9, "Corner (200, 1000)")
        assertEquals(80.0, map.zAxis[4][2], 1e-9, "Corner (200, 5000)")

        // Bilinear interpolation at (125, 2000):
        // Original grid points: (100, 1000)=20, (150, 1000)=30, (100, 3000)=30, (150, 3000)=45
        // x=125 → xFrac=0.5 between 100 and 150
        // y=2000 → yFrac=0.5 between 1000 and 3000
        // Bilinear: (20 + 30)/2 = 25 at y=1000; (30 + 45)/2 = 37.5 at y=3000; (25+37.5)/2 = 31.25
        assertEquals(31.25, map.zAxis[1][1], 1e-9, "Bilinear at (125, 2000)")
    }

    @Test
    fun `extrapolation flags shown for out-of-range Y breakpoints`() {
        val original = buildKfmiopMap()

        // Extend RPM axis beyond original range
        val newYAxis = arrayOf(500.0, 1000.0, 3000.0, 5000.0, 6000.0)
        val result = AxisRescaler.rescaleYAxis(original, newYAxis)

        // RPM=500 and RPM=6000 are outside original range [1000, 5000]
        assertTrue(result.extrapolatedCount > 0, "Should flag extrapolated cells")

        // All cells in row 0 (y=500) should be extrapolated
        for (col in result.extrapolatedCells[0].indices) {
            assertTrue(result.extrapolatedCells[0][col], "y=500 should be extrapolated at col=$col")
        }

        // All cells in last row (y=6000) should be extrapolated
        val lastRow = result.extrapolatedCells.last()
        for (col in lastRow.indices) {
            assertTrue(lastRow[col], "y=6000 should be extrapolated at col=$col")
        }

        // Middle rows (on original grid) should NOT be extrapolated
        for (col in result.extrapolatedCells[1].indices) {
            assertTrue(!result.extrapolatedCells[1][col], "y=1000 should not be extrapolated at col=$col")
        }

        // Clamped values at y=500 should equal y=1000 values (clamped to edge)
        val map = result.rescaledMap
        assertEquals(10.0, map.zAxis[0][0], 1e-9, "Clamped y=500 → y=1000, x=50")
        assertEquals(40.0, map.zAxis[0][3], 1e-9, "Clamped y=500 → y=1000, x=200")
    }

    @Test
    fun `Y-axis rescale applied after Kfmiop pressure calculation produces correct output`() {
        // Simulate the full flow: pressure calculator → Y-axis rescale
        val baseKfmiop = buildKfmiopMap()

        // Step 1: The pressure calculator produces an output (simulated as identity here)
        val pressureOutput = Map3d(baseKfmiop) // Simulating kfmiopResult.outputKfmiop

        // Step 2: Apply Y-axis rescaling to the output
        val newYAxis = arrayOf(1000.0, 2000.0, 3000.0, 4000.0, 5000.0)
        val result = AxisRescaler.rescaleYAxis(pressureOutput, newYAxis)

        val map = result.rescaledMap
        assertNotNull(map)
        assertEquals(5, map.yAxis.size, "Rescaled output should have 5 RPM rows")
        assertEquals(4, map.xAxis.size, "X-axis should be unchanged")

        // Verify interpolated values at new RPM breakpoints
        // At RPM=4000 (midpoint of 3000-5000), load=100:
        // Original: RPM=3000 → 30, RPM=5000 → 40; interp = 35
        assertEquals(35.0, map.zAxis[3][1], 1e-9, "Interpolated at RPM=4000, load=100")
    }

    @Test
    fun `identity Y-axis rescale preserves all values`() {
        val original = buildKfmiopMap()

        // Rescale with the same Y-axis
        val result = AxisRescaler.rescaleYAxis(original, original.yAxis.copyOf())

        val map = result.rescaledMap
        assertEquals(0, result.extrapolatedCount, "No extrapolation for identity rescale")

        // All values should be preserved
        for (r in original.zAxis.indices) {
            for (c in original.zAxis[r].indices) {
                assertEquals(original.zAxis[r][c], map.zAxis[r][c], 1e-9,
                    "Value should be preserved at [$r][$c]")
            }
        }
    }
}
