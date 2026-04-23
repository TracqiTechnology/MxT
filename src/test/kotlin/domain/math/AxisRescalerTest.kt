package domain.math

import domain.math.map.Map3d
import kotlin.test.*

class AxisRescalerTest {

    /**
     * Standard 3×3 test map with a linear surface:
     *   X: [10, 20, 30]   Y: [1000, 2000, 3000]
     *   Z: [[1, 2, 3],
     *        [4, 5, 6],
     *        [7, 8, 9]]
     */
    private fun buildTestMap(): Map3d = Map3d(
        arrayOf(10.0, 20.0, 30.0),
        arrayOf(1000.0, 2000.0, 3000.0),
        arrayOf(
            arrayOf(1.0, 2.0, 3.0),
            arrayOf(4.0, 5.0, 6.0),
            arrayOf(7.0, 8.0, 9.0)
        )
    )

    // ── Identity rescale ──────────────────────────────────────────────

    @Test
    fun `identity rescale returns same values`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(original)

        assertZAxisEquals(original.zAxis, result.rescaledMap.zAxis)
        assertEquals(0, result.extrapolatedCount, "No cells should be extrapolated")
        assertEquals(9, result.exactMatchCount, "All cells should be exact matches")
        assertEquals(9, result.totalCells)
    }

    @Test
    fun `identity rescale with explicit same axes returns same values`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = original.xAxis.copyOf(),
            newYAxis = original.yAxis.copyOf()
        )

        assertZAxisEquals(original.zAxis, result.rescaledMap.zAxis)
        assertEquals(9, result.exactMatchCount)
    }

    // ── X-axis only rescale ───────────────────────────────────────────

    @Test
    fun `rescale X-axis interpolates Z values`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleXAxis(original, arrayOf(10.0, 15.0, 20.0, 30.0))

        val map = result.rescaledMap
        assertArrayEquals(arrayOf(10.0, 15.0, 20.0, 30.0), map.xAxis)
        assertArrayEquals(arrayOf(1000.0, 2000.0, 3000.0), map.yAxis)

        // Row 0: original [1,2,3], at x=15 → 1.5
        assertEquals(1.0, map.zAxis[0][0], 1e-9, "Row 0, x=10")
        assertEquals(1.5, map.zAxis[0][1], 1e-9, "Row 0, x=15 (interpolated)")
        assertEquals(2.0, map.zAxis[0][2], 1e-9, "Row 0, x=20")
        assertEquals(3.0, map.zAxis[0][3], 1e-9, "Row 0, x=30")
    }

    @Test
    fun `rescaleXAxis convenience matches rescaleMap`() {
        val original = buildTestMap()
        val newX = arrayOf(10.0, 15.0, 25.0, 30.0)
        val viaConvenience = AxisRescaler.rescaleXAxis(original, newX)
        val viaDirect = AxisRescaler.rescaleMap(original, newXAxis = newX)

        assertZAxisEquals(viaConvenience.rescaledMap.zAxis, viaDirect.rescaledMap.zAxis)
    }

    // ── Y-axis only rescale ───────────────────────────────────────────

    @Test
    fun `rescale Y-axis interpolates Z values`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleYAxis(original, arrayOf(1000.0, 1500.0, 2000.0, 3000.0))

        val map = result.rescaledMap
        assertArrayEquals(arrayOf(1000.0, 1500.0, 2000.0, 3000.0), map.yAxis)
        assertArrayEquals(arrayOf(10.0, 20.0, 30.0), map.xAxis)

        // Col 0: original y=[1000,2000,3000] z=[1,4,7], at y=1500 → 2.5
        assertEquals(1.0, map.zAxis[0][0], 1e-9, "y=1000, x=10")
        assertEquals(2.5, map.zAxis[1][0], 1e-9, "y=1500, x=10 (interpolated)")
        assertEquals(4.0, map.zAxis[2][0], 1e-9, "y=2000, x=10")
        assertEquals(7.0, map.zAxis[3][0], 1e-9, "y=3000, x=10")
    }

    @Test
    fun `rescaleYAxis convenience matches rescaleMap`() {
        val original = buildTestMap()
        val newY = arrayOf(1000.0, 1500.0, 2500.0, 3000.0)
        val viaConvenience = AxisRescaler.rescaleYAxis(original, newY)
        val viaDirect = AxisRescaler.rescaleMap(original, newYAxis = newY)

        assertZAxisEquals(viaConvenience.rescaledMap.zAxis, viaDirect.rescaledMap.zAxis)
    }

    // ── Both axes rescale ─────────────────────────────────────────────

    @Test
    fun `rescale both axes simultaneously`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(10.0, 15.0, 20.0, 25.0, 30.0),
            newYAxis = arrayOf(1000.0, 1500.0, 2000.0, 2500.0, 3000.0)
        )

        val map = result.rescaledMap
        assertEquals(5, map.xAxis.size)
        assertEquals(5, map.yAxis.size)
        assertEquals(25, result.totalCells)

        // Center point: x=20, y=2000 is on original grid → exact value 5.0
        assertEquals(5.0, map.zAxis[2][2], 1e-9, "Center point on original grid")

        // Midpoint: x=15, y=1500 → bilinear interp of [1,2,4,5] = 3.0
        assertEquals(3.0, map.zAxis[1][1], 1e-9, "Midpoint x=15, y=1500")

        // Midpoint: x=25, y=2500 → bilinear interp of [5,6,8,9] = 7.0
        assertEquals(7.0, map.zAxis[3][3], 1e-9, "Midpoint x=25, y=2500")
    }

    // ── Exact match preservation ──────────────────────────────────────

    @Test
    fun `exact matches preserve original Z values`() {
        val original = buildTestMap()
        // Include all original breakpoints plus some new ones
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(10.0, 15.0, 20.0, 25.0, 30.0),
            newYAxis = arrayOf(1000.0, 2000.0, 3000.0)
        )

        val map = result.rescaledMap
        // Original grid points should be preserved exactly
        assertEquals(1.0, map.zAxis[0][0], 1e-9, "Original (10,1000)")
        assertEquals(2.0, map.zAxis[0][2], 1e-9, "Original (20,1000)")
        assertEquals(3.0, map.zAxis[0][4], 1e-9, "Original (30,1000)")
        assertEquals(5.0, map.zAxis[1][2], 1e-9, "Original (20,2000)")
        assertEquals(9.0, map.zAxis[2][4], 1e-9, "Original (30,3000)")
    }

    // ── Extrapolation clamping ────────────────────────────────────────

    @Test
    fun `extrapolation beyond X range clamps to edge`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleXAxis(original, arrayOf(5.0, 10.0, 30.0, 35.0))

        val map = result.rescaledMap
        // x=5 is below original min (10) → clamp to x=10 values
        assertEquals(1.0, map.zAxis[0][0], 1e-9, "Clamped x=5 → x=10, y=1000")
        assertEquals(4.0, map.zAxis[1][0], 1e-9, "Clamped x=5 → x=10, y=2000")
        // x=35 is above original max (30) → clamp to x=30 values
        assertEquals(3.0, map.zAxis[0][3], 1e-9, "Clamped x=35 → x=30, y=1000")
        assertEquals(9.0, map.zAxis[2][3], 1e-9, "Clamped x=35 → x=30, y=3000")
    }

    @Test
    fun `extrapolation beyond Y range clamps to edge`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleYAxis(original, arrayOf(500.0, 1000.0, 3000.0, 3500.0))

        val map = result.rescaledMap
        // y=500 below min → clamp to y=1000
        assertEquals(1.0, map.zAxis[0][0], 1e-9, "Clamped y=500 → y=1000, x=10")
        assertEquals(3.0, map.zAxis[0][2], 1e-9, "Clamped y=500 → y=1000, x=30")
        // y=3500 above max → clamp to y=3000
        assertEquals(7.0, map.zAxis[3][0], 1e-9, "Clamped y=3500 → y=3000, x=10")
        assertEquals(9.0, map.zAxis[3][2], 1e-9, "Clamped y=3500 → y=3000, x=30")
    }

    @Test
    fun `extrapolatedCells flags are set correctly`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(5.0, 20.0, 35.0),
            newYAxis = arrayOf(500.0, 2000.0, 3500.0)
        )

        // Corners outside both axes
        assertTrue(result.extrapolatedCells[0][0], "Top-left: below X and Y")
        assertTrue(result.extrapolatedCells[0][2], "Top-right: above X, below Y")
        assertTrue(result.extrapolatedCells[2][0], "Bottom-left: below X, above Y")
        assertTrue(result.extrapolatedCells[2][2], "Bottom-right: above X and Y")

        // Edges outside one axis
        assertTrue(result.extrapolatedCells[0][1], "Top-center: below Y only")
        assertTrue(result.extrapolatedCells[1][0], "Middle-left: below X only")
        assertTrue(result.extrapolatedCells[1][2], "Middle-right: above X only")
        assertTrue(result.extrapolatedCells[2][1], "Bottom-center: above Y only")

        // Center is within bounds
        assertFalse(result.extrapolatedCells[1][1], "Center: within bounds")
    }

    // ── Monotonicity validation ───────────────────────────────────────

    @Test
    fun `non-monotonic X-axis throws IllegalArgumentException`() {
        val original = buildTestMap()
        assertFailsWith<IllegalArgumentException>("X-axis must be strictly monotonically increasing") {
            AxisRescaler.rescaleXAxis(original, arrayOf(10.0, 30.0, 20.0))
        }
    }

    @Test
    fun `non-monotonic Y-axis throws IllegalArgumentException`() {
        val original = buildTestMap()
        assertFailsWith<IllegalArgumentException>("Y-axis must be strictly monotonically increasing") {
            AxisRescaler.rescaleYAxis(original, arrayOf(3000.0, 2000.0, 1000.0))
        }
    }

    @Test
    fun `duplicate X-axis values throw IllegalArgumentException`() {
        val original = buildTestMap()
        assertFailsWith<IllegalArgumentException> {
            AxisRescaler.rescaleXAxis(original, arrayOf(10.0, 20.0, 20.0, 30.0))
        }
    }

    @Test
    fun `duplicate Y-axis values throw IllegalArgumentException`() {
        val original = buildTestMap()
        assertFailsWith<IllegalArgumentException> {
            AxisRescaler.rescaleYAxis(original, arrayOf(1000.0, 2000.0, 2000.0, 3000.0))
        }
    }

    // ── Finer grid ────────────────────────────────────────────────────

    @Test
    fun `rescaling to finer grid interpolates between original points`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(10.0, 15.0, 20.0, 25.0, 30.0),
            newYAxis = arrayOf(1000.0, 1500.0, 2000.0, 2500.0, 3000.0)
        )

        val map = result.rescaledMap
        assertEquals(5, map.xAxis.size)
        assertEquals(5, map.yAxis.size)

        // Verify the full surface — bilinear interpolation on the linear surface
        // Original: z = 1 + (x-10)/10 + 3*(y-1000)/1000
        for (r in map.yAxis.indices) {
            for (c in map.xAxis.indices) {
                val x = map.xAxis[c]
                val y = map.yAxis[r]
                val expected = 1.0 + (x - 10.0) / 10.0 + 3.0 * (y - 1000.0) / 1000.0
                assertEquals(expected, map.zAxis[r][c], 1e-9,
                    "Finer grid at x=$x, y=$y")
            }
        }
    }

    // ── Coarser grid ──────────────────────────────────────────────────

    @Test
    fun `rescaling to coarser grid picks correct interpolated values`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(10.0, 30.0),
            newYAxis = arrayOf(1000.0, 3000.0)
        )

        val map = result.rescaledMap
        assertEquals(2, map.xAxis.size)
        assertEquals(2, map.yAxis.size)

        // Corners of original map
        assertEquals(1.0, map.zAxis[0][0], 1e-9, "Corner (10,1000)")
        assertEquals(3.0, map.zAxis[0][1], 1e-9, "Corner (30,1000)")
        assertEquals(7.0, map.zAxis[1][0], 1e-9, "Corner (10,3000)")
        assertEquals(9.0, map.zAxis[1][1], 1e-9, "Corner (30,3000)")
    }

    @Test
    fun `coarser grid with midpoints picks bilinear values`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(15.0, 25.0),
            newYAxis = arrayOf(1500.0, 2500.0)
        )

        val map = result.rescaledMap
        // All midpoints: bilinear interpolation
        // (15,1500) → interp of [1,2,4,5] = 3.0
        assertEquals(3.0, map.zAxis[0][0], 1e-9, "Midpoint (15,1500)")
        // (25,1500) → interp of [2,3,5,6] = 4.0
        assertEquals(4.0, map.zAxis[0][1], 1e-9, "Midpoint (25,1500)")
        // (15,2500) → interp of [4,5,7,8] = 6.0
        assertEquals(6.0, map.zAxis[1][0], 1e-9, "Midpoint (15,2500)")
        // (25,2500) → interp of [5,6,8,9] = 7.0
        assertEquals(7.0, map.zAxis[1][1], 1e-9, "Midpoint (25,2500)")
    }

    // ── Single-row / single-column maps ───────────────────────────────

    @Test
    fun `single row map (1xN) rescale X-axis`() {
        val original = Map3d(
            arrayOf(10.0, 20.0, 30.0),
            arrayOf(1000.0),
            arrayOf(arrayOf(1.0, 2.0, 3.0))
        )

        val result = AxisRescaler.rescaleXAxis(original, arrayOf(10.0, 15.0, 20.0, 25.0, 30.0))
        val map = result.rescaledMap

        assertEquals(1, map.yAxis.size)
        assertEquals(5, map.xAxis.size)
        assertEquals(1.0, map.zAxis[0][0], 1e-9)
        assertEquals(1.5, map.zAxis[0][1], 1e-9)
        assertEquals(2.0, map.zAxis[0][2], 1e-9)
        assertEquals(2.5, map.zAxis[0][3], 1e-9)
        assertEquals(3.0, map.zAxis[0][4], 1e-9)
    }

    @Test
    fun `single column map (Nx1) rescale Y-axis`() {
        val original = Map3d(
            arrayOf(10.0),
            arrayOf(1000.0, 2000.0, 3000.0),
            arrayOf(
                arrayOf(1.0),
                arrayOf(4.0),
                arrayOf(7.0)
            )
        )

        val result = AxisRescaler.rescaleYAxis(original, arrayOf(1000.0, 1500.0, 2000.0, 2500.0, 3000.0))
        val map = result.rescaledMap

        assertEquals(5, map.yAxis.size)
        assertEquals(1, map.xAxis.size)
        assertEquals(1.0, map.zAxis[0][0], 1e-9)
        assertEquals(2.5, map.zAxis[1][0], 1e-9)
        assertEquals(4.0, map.zAxis[2][0], 1e-9)
        assertEquals(5.5, map.zAxis[3][0], 1e-9)
        assertEquals(7.0, map.zAxis[4][0], 1e-9)
    }

    @Test
    fun `single cell map (1x1) identity rescale`() {
        val original = Map3d(
            arrayOf(10.0),
            arrayOf(1000.0),
            arrayOf(arrayOf(42.0))
        )

        val result = AxisRescaler.rescaleMap(original)
        assertEquals(42.0, result.rescaledMap.zAxis[0][0], 1e-9)
        assertEquals(1, result.totalCells)
        assertEquals(1, result.exactMatchCount)
        assertEquals(0, result.extrapolatedCount)
    }

    // ── Diagnostics accuracy ──────────────────────────────────────────

    @Test
    fun `diagnostics totalCells is correct`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(10.0, 15.0, 20.0, 25.0, 30.0),
            newYAxis = arrayOf(1000.0, 1500.0, 2000.0, 2500.0, 3000.0)
        )

        assertEquals(25, result.totalCells)
    }

    @Test
    fun `diagnostics exactMatchCount counts only grid intersections`() {
        val original = buildTestMap()
        // 3 original X breakpoints × 3 original Y breakpoints = 9 exact matches in 5×5 grid
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(10.0, 15.0, 20.0, 25.0, 30.0),
            newYAxis = arrayOf(1000.0, 1500.0, 2000.0, 2500.0, 3000.0)
        )

        assertEquals(9, result.exactMatchCount, "3 X breakpoints × 3 Y breakpoints = 9")
    }

    @Test
    fun `diagnostics extrapolatedCount for fully within bounds`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(15.0, 20.0, 25.0),
            newYAxis = arrayOf(1500.0, 2000.0, 2500.0)
        )

        assertEquals(0, result.extrapolatedCount, "All points within original bounds")
    }

    @Test
    fun `diagnostics extrapolatedCount for partially outside bounds`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(5.0, 20.0, 35.0),
            newYAxis = arrayOf(500.0, 2000.0, 3500.0)
        )

        // All but center cell (1,1) are outside
        assertEquals(8, result.extrapolatedCount, "8 of 9 cells are extrapolated")
        assertEquals(9, result.totalCells)
    }

    @Test
    fun `diagnostics extrapolatedCells dimensions match output map`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(10.0, 20.0, 30.0, 40.0),
            newYAxis = arrayOf(1000.0, 2000.0)
        )

        assertEquals(2, result.extrapolatedCells.size, "Rows match Y-axis size")
        assertEquals(4, result.extrapolatedCells[0].size, "Cols match X-axis size")
    }

    // ── Non-linear surface ────────────────────────────────────────────

    @Test
    fun `rescale non-linear surface interpolates correctly`() {
        // Non-linear Z values on a 2×2 grid (cubic degenerates to bilinear here)
        val original = Map3d(
            arrayOf(0.0, 10.0),
            arrayOf(0.0, 10.0),
            arrayOf(
                arrayOf(0.0, 10.0),
                arrayOf(10.0, 40.0)
            )
        )

        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(0.0, 5.0, 10.0),
            newYAxis = arrayOf(0.0, 5.0, 10.0)
        )

        val map = result.rescaledMap
        // Midpoint at (5, 5): (0+10+10+40)/4 = 15.0
        assertEquals(15.0, map.zAxis[1][1], 1e-9, "Midpoint of non-linear surface")

        // Edge midpoints
        assertEquals(5.0, map.zAxis[0][1], 1e-9, "Top edge midpoint")
        assertEquals(25.0, map.zAxis[1][2], 1e-9, "Right edge midpoint")
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun assertZAxisEquals(expected: Array<Array<Double>>, actual: Array<Array<Double>>) {
        assertEquals(expected.size, actual.size, "Row count mismatch")
        for (r in expected.indices) {
            assertEquals(expected[r].size, actual[r].size, "Column count mismatch in row $r")
            for (c in expected[r].indices) {
                assertEquals(expected[r][c], actual[r][c], 1e-9,
                    "Z value mismatch at [$r][$c]")
            }
        }
    }

    private fun assertArrayEquals(expected: Array<Double>, actual: Array<Double>) {
        assertEquals(expected.size, actual.size, "Array size mismatch")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 1e-9, "Value mismatch at index $i")
        }
    }
}
