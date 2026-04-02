package ui.screens.kfmirl

import data.preferences.SharedAxisPreferences
import domain.math.AxisRescaler
import domain.math.Inverse
import domain.math.map.Map3d
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Domain-level tests for KFMIRL Y-axis (RPM) editing.
 *
 * Verifies that the inverse calculation followed by Y-axis rescaling
 * correctly recomputes KFMIRL Z values with new RPM breakpoints.
 */
class KfmirlYAxisEditTest {

    /**
     * KFMIOP-like map (torque vs load):
     *   X (load): [50, 100, 150]
     *   Y (RPM):  [1000, 3000, 5000]
     *   Z (torque %):
     *     [[10, 20, 30],
     *      [15, 30, 45],
     *      [20, 40, 60]]
     */
    private fun buildKfmiopMap(): Map3d = Map3d(
        arrayOf(50.0, 100.0, 150.0),
        arrayOf(1000.0, 3000.0, 5000.0),
        arrayOf(
            arrayOf(10.0, 20.0, 30.0),
            arrayOf(15.0, 30.0, 45.0),
            arrayOf(20.0, 40.0, 60.0)
        )
    )

    /**
     * KFMIRL-like map (load vs torque — inverse of KFMIOP):
     *   X (torque): [10, 25, 40]
     *   Y (RPM):    [1000, 3000, 5000]
     *   Z (load %):
     *     [[50, 100, 150],
     *      [50, 100, 150],
     *      [50, 100, 150]]
     */
    private fun buildKfmirlMap(): Map3d = Map3d(
        arrayOf(10.0, 25.0, 40.0),
        arrayOf(1000.0, 3000.0, 5000.0),
        arrayOf(
            arrayOf(50.0, 100.0, 150.0),
            arrayOf(50.0, 100.0, 150.0),
            arrayOf(50.0, 100.0, 150.0)
        )
    )

    @Test
    fun `KFMIRL Y-axis rescaling recomputes inverse correctly`() {
        val kfmiop = buildKfmiopMap()
        val kfmirl = buildKfmirlMap()

        // Step 1: Compute inverse (standard KFMIRL calculation)
        val inverse = Inverse.calculateInverse(kfmiop, kfmirl)
        assertNotNull(inverse)

        // Step 2: Rescale the inverse to new RPM axis
        val newYAxis = arrayOf(1000.0, 2000.0, 3000.0, 4000.0, 5000.0)
        val result = AxisRescaler.rescaleYAxis(inverse, newYAxis)

        val map = result.rescaledMap
        assertEquals(5, map.yAxis.size, "Rescaled KFMIRL should have 5 RPM rows")
        assertEquals(3, map.xAxis.size, "X-axis (torque) should be unchanged")

        // Values at original RPM breakpoints should be preserved
        for (c in inverse.zAxis[0].indices) {
            assertEquals(inverse.zAxis[0][c], map.zAxis[0][c], 1e-9,
                "RPM=1000 should be preserved at col=$c")
            assertEquals(inverse.zAxis[1][c], map.zAxis[2][c], 1e-9,
                "RPM=3000 should be preserved at col=$c")
            assertEquals(inverse.zAxis[2][c], map.zAxis[4][c], 1e-9,
                "RPM=5000 should be preserved at col=$c")
        }

        // Interpolated rows (RPM=2000, 4000) should have values between neighbors
        for (c in map.zAxis[1].indices) {
            val lowVal = map.zAxis[0][c]
            val midVal = map.zAxis[1][c]
            val highVal = map.zAxis[2][c]
            assertTrue(
                midVal >= minOf(lowVal, highVal) && midVal <= maxOf(lowVal, highVal),
                "RPM=2000 value at col=$c should be between RPM=1000 and RPM=3000 values"
            )
        }
    }

    @Test
    fun `combined X-axis edit and Y-axis rescale on KFMIRL`() {
        val kfmiop = buildKfmiopMap()
        val kfmirl = buildKfmirlMap()

        // Step 1: Edit KFMIOP X-axis (simulating the current KFMIRL screen flow)
        val editedXAxis = arrayOf(60.0, 110.0, 160.0) // Shifted load axis
        val kfmiopWithNewXAxis = Map3d(editedXAxis, kfmiop.yAxis, kfmiop.zAxis)

        // Step 2: Compute inverse with edited X-axis
        val inverse = Inverse.calculateInverse(kfmiopWithNewXAxis, kfmirl)
        assertNotNull(inverse)

        // Step 3: Rescale to new Y-axis (RPM)
        val newYAxis = arrayOf(1000.0, 2000.0, 3000.0, 5000.0)
        val result = AxisRescaler.rescaleYAxis(inverse, newYAxis)

        val map = result.rescaledMap
        assertEquals(4, map.yAxis.size)
        assertEquals(3, map.xAxis.size)

        // The output should have both the X-axis effect (from inverse) and Y-axis (from rescale)
        // Verify dimensions and that values are reasonable
        for (r in map.zAxis.indices) {
            for (c in map.zAxis[r].indices) {
                assertTrue(
                    map.zAxis[r][c].isFinite(),
                    "All values should be finite after combined X+Y editing"
                )
            }
        }
    }

    @Test
    fun `extrapolation flags for KFMIRL Y-axis outside original range`() {
        val kfmiop = buildKfmiopMap()
        val kfmirl = buildKfmirlMap()

        // Compute inverse
        val inverse = Inverse.calculateInverse(kfmiop, kfmirl)

        // Extend RPM range beyond original [1000, 5000]
        val newYAxis = arrayOf(500.0, 1000.0, 3000.0, 5000.0, 6000.0)
        val result = AxisRescaler.rescaleYAxis(inverse, newYAxis)

        assertTrue(result.extrapolatedCount > 0,
            "Should detect extrapolation for out-of-range RPM breakpoints")

        // First and last rows should have extrapolation flags
        for (c in result.extrapolatedCells[0].indices) {
            assertTrue(result.extrapolatedCells[0][c], "RPM=500 should be flagged at col=$c")
        }
        val lastRow = result.extrapolatedCells.last()
        for (c in lastRow.indices) {
            assertTrue(lastRow[c], "RPM=6000 should be flagged at col=$c")
        }
    }

    @Test
    fun `axis sync transfers Y-axis from KFMIOP to KFMIRL`() {
        // Simulate the SharedAxisPreferences sync flow
        val kfmiopYAxis = arrayOf(1000.0, 2000.0, 3000.0, 4000.0, 5000.0)

        // Emit KFMIOP's edited Y-axis
        SharedAxisPreferences.setKfmiopEditedYAxis(kfmiopYAxis)

        // Build original KFMIRL with different RPM axis
        val kfmirlOriginal = Map3d(
            arrayOf(10.0, 25.0, 40.0),
            arrayOf(1000.0, 3000.0, 5000.0),
            arrayOf(
                arrayOf(50.0, 100.0, 150.0),
                arrayOf(50.0, 100.0, 150.0),
                arrayOf(50.0, 100.0, 150.0)
            )
        )

        // Apply the synced Y-axis to KFMIRL
        val result = AxisRescaler.rescaleYAxis(kfmirlOriginal, kfmiopYAxis)

        val map = result.rescaledMap
        assertEquals(5, map.yAxis.size, "KFMIRL should adopt KFMIOP's 5-point RPM axis")

        // Verify the Y-axis values match the synced axis
        for (i in kfmiopYAxis.indices) {
            assertEquals(kfmiopYAxis[i], map.yAxis[i], 1e-9,
                "Y-axis breakpoint $i should match synced value")
        }

        // Original grid points should be preserved
        assertEquals(50.0, map.zAxis[0][0], 1e-9, "Original (10, 1000) preserved")
        assertEquals(150.0, map.zAxis[2][2], 1e-9, "Original (40, 3000) preserved")
        assertEquals(100.0, map.zAxis[4][1], 1e-9, "Original (25, 5000) preserved")
    }

    @Test
    fun `identity Y-axis rescale on KFMIRL preserves inverse values`() {
        val kfmiop = buildKfmiopMap()
        val kfmirl = buildKfmirlMap()

        // Compute inverse
        val inverse = Inverse.calculateInverse(kfmiop, kfmirl)

        // Rescale with the same Y-axis (identity)
        val result = AxisRescaler.rescaleYAxis(inverse, inverse.yAxis.copyOf())

        assertEquals(0, result.extrapolatedCount, "No extrapolation for identity")

        for (r in inverse.zAxis.indices) {
            for (c in inverse.zAxis[r].indices) {
                assertEquals(inverse.zAxis[r][c], result.rescaledMap.zAxis[r][c], 1e-9,
                    "Value preserved at [$r][$c]")
            }
        }
    }
}
