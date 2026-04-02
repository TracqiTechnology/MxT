package ui.screens.axisrescaler

import domain.math.AxisRescaler
import domain.math.map.Map3d
import kotlin.test.*

class AxisRescalerScreenTest {

    // ── TSV Parsing ───────────────────────────────────────────────────

    @Test
    fun `parseTsv extracts correct Map3d`() {
        val tsv = """
            	10	20	30	40
            1000	1.0	2.0	3.0	4.0
            2000	5.0	6.0	7.0	8.0
            3000	9.0	10.0	11.0	12.0
        """.trimIndent()

        val map = MapClipboardParser.parseTsv(tsv)

        assertNotNull(map)
        assertArrayEquals(arrayOf(10.0, 20.0, 30.0, 40.0), map.xAxis)
        assertArrayEquals(arrayOf(1000.0, 2000.0, 3000.0), map.yAxis)

        assertEquals(3, map.zAxis.size, "3 rows")
        assertEquals(4, map.zAxis[0].size, "4 cols")

        assertEquals(1.0, map.zAxis[0][0], 1e-9)
        assertEquals(4.0, map.zAxis[0][3], 1e-9)
        assertEquals(5.0, map.zAxis[1][0], 1e-9)
        assertEquals(8.0, map.zAxis[1][3], 1e-9)
        assertEquals(12.0, map.zAxis[2][3], 1e-9)
    }

    @Test
    fun `parseTsv handles empty input`() {
        assertNull(MapClipboardParser.parseTsv(""))
        assertNull(MapClipboardParser.parseTsv("   "))
        assertNull(MapClipboardParser.parseTsv("\n\n"))
    }

    @Test
    fun `parseTsv handles single row`() {
        val tsv = """
            	10	20	30
            1000	1.0	2.0	3.0
        """.trimIndent()

        val map = MapClipboardParser.parseTsv(tsv)

        assertNotNull(map)
        assertArrayEquals(arrayOf(10.0, 20.0, 30.0), map.xAxis)
        assertArrayEquals(arrayOf(1000.0), map.yAxis)
        assertEquals(1, map.zAxis.size)
        assertEquals(1.0, map.zAxis[0][0], 1e-9)
        assertEquals(3.0, map.zAxis[0][2], 1e-9)
    }

    @Test
    fun `parseTsv handles single column`() {
        val tsv = """
            	10
            1000	1.0
            2000	4.0
            3000	7.0
        """.trimIndent()

        val map = MapClipboardParser.parseTsv(tsv)

        assertNotNull(map)
        assertArrayEquals(arrayOf(10.0), map.xAxis)
        assertArrayEquals(arrayOf(1000.0, 2000.0, 3000.0), map.yAxis)
        assertEquals(3, map.zAxis.size)
        assertEquals(1, map.zAxis[0].size)
        assertEquals(1.0, map.zAxis[0][0], 1e-9)
        assertEquals(4.0, map.zAxis[1][0], 1e-9)
        assertEquals(7.0, map.zAxis[2][0], 1e-9)
    }

    @Test
    fun `parseTsv with empty top-left cell`() {
        // Common format: empty string in top-left
        val tsv = "\t10\t20\t30\n1000\t1.0\t2.0\t3.0\n2000\t4.0\t5.0\t6.0"

        val map = MapClipboardParser.parseTsv(tsv)

        assertNotNull(map)
        assertArrayEquals(arrayOf(10.0, 20.0, 30.0), map.xAxis)
        assertArrayEquals(arrayOf(1000.0, 2000.0), map.yAxis)
        assertEquals(1.0, map.zAxis[0][0], 1e-9)
        assertEquals(6.0, map.zAxis[1][2], 1e-9)
    }

    @Test
    fun `parseTsv returns null for header-only input`() {
        val tsv = "\t10\t20\t30"
        assertNull(MapClipboardParser.parseTsv(tsv))
    }

    // ── Rescale Integration ───────────────────────────────────────────

    @Test
    fun `rescale with new X-axis produces correct output`() {
        val original = buildTestMap()
        val newXAxis = arrayOf(10.0, 15.0, 20.0, 25.0, 30.0)

        val result = AxisRescaler.rescaleMap(original, newXAxis = newXAxis)
        val map = result.rescaledMap

        assertArrayEquals(newXAxis, map.xAxis)
        assertArrayEquals(original.yAxis, map.yAxis)

        // Row 0: original [1,2,3] — at x=15, bilinear → 1.5; at x=25 → 2.5
        assertEquals(1.0, map.zAxis[0][0], 1e-9, "x=10")
        assertEquals(1.5, map.zAxis[0][1], 1e-9, "x=15 (interpolated)")
        assertEquals(2.0, map.zAxis[0][2], 1e-9, "x=20")
        assertEquals(2.5, map.zAxis[0][3], 1e-9, "x=25 (interpolated)")
        assertEquals(3.0, map.zAxis[0][4], 1e-9, "x=30")
    }

    @Test
    fun `rescale with new Y-axis produces correct output`() {
        val original = buildTestMap()
        val newYAxis = arrayOf(1000.0, 1500.0, 2000.0, 2500.0, 3000.0)

        val result = AxisRescaler.rescaleMap(original, newYAxis = newYAxis)
        val map = result.rescaledMap

        assertArrayEquals(original.xAxis, map.xAxis)
        assertArrayEquals(newYAxis, map.yAxis)

        // Col 0: original y=[1000,2000,3000] z=[1,4,7] — at y=1500 → 2.5
        assertEquals(1.0, map.zAxis[0][0], 1e-9, "y=1000")
        assertEquals(2.5, map.zAxis[1][0], 1e-9, "y=1500 (interpolated)")
        assertEquals(4.0, map.zAxis[2][0], 1e-9, "y=2000")
        assertEquals(5.5, map.zAxis[3][0], 1e-9, "y=2500 (interpolated)")
        assertEquals(7.0, map.zAxis[4][0], 1e-9, "y=3000")
    }

    @Test
    fun `extrapolated cells flagged correctly in output`() {
        val original = buildTestMap()
        val result = AxisRescaler.rescaleMap(
            original,
            newXAxis = arrayOf(5.0, 20.0, 35.0),
            newYAxis = arrayOf(500.0, 2000.0, 3500.0)
        )

        // Outside both axes
        assertTrue(result.extrapolatedCells[0][0], "Top-left: below both")
        assertTrue(result.extrapolatedCells[0][2], "Top-right: above X, below Y")
        assertTrue(result.extrapolatedCells[2][0], "Bottom-left: below X, above Y")
        assertTrue(result.extrapolatedCells[2][2], "Bottom-right: above both")

        // Outside one axis
        assertTrue(result.extrapolatedCells[0][1], "Top-center: below Y only")
        assertTrue(result.extrapolatedCells[1][0], "Middle-left: below X only")
        assertTrue(result.extrapolatedCells[1][2], "Middle-right: above X only")
        assertTrue(result.extrapolatedCells[2][1], "Bottom-center: above Y only")

        // Center is within bounds
        assertFalse(result.extrapolatedCells[1][1], "Center: within bounds")

        assertEquals(8, result.extrapolatedCount)
        assertEquals(9, result.totalCells)
    }

    @Test
    fun `parseTsv roundtrip with rescale produces valid output`() {
        val tsv = """
            	10	20	30
            1000	1.0	2.0	3.0
            2000	4.0	5.0	6.0
            3000	7.0	8.0	9.0
        """.trimIndent()

        val parsed = MapClipboardParser.parseTsv(tsv)
        assertNotNull(parsed)

        val result = AxisRescaler.rescaleMap(
            parsed,
            newXAxis = arrayOf(10.0, 15.0, 20.0, 25.0, 30.0)
        )

        assertEquals(15, result.totalCells, "5 cols × 3 rows = 15")
        assertEquals(0, result.extrapolatedCount, "All within bounds")
        assertEquals(1.5, result.rescaledMap.zAxis[0][1], 1e-9, "Interpolated at x=15, y=1000")
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun buildTestMap(): Map3d = Map3d(
        arrayOf(10.0, 20.0, 30.0),
        arrayOf(1000.0, 2000.0, 3000.0),
        arrayOf(
            arrayOf(1.0, 2.0, 3.0),
            arrayOf(4.0, 5.0, 6.0),
            arrayOf(7.0, 8.0, 9.0)
        )
    )

    private fun assertArrayEquals(expected: Array<Double>, actual: Array<Double>) {
        assertEquals(expected.size, actual.size, "Array size mismatch")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 1e-9, "Value mismatch at index $i")
        }
    }
}
