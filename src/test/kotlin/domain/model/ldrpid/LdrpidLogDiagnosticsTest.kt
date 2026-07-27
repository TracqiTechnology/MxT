package domain.model.ldrpid

import data.contract.Me7LogFileContract.Header as H
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LdrpidLogDiagnosticsTest {
    @Test
    fun `diagnostics report measured log error and coverage`() {
        val values = mapOf(
            H.RPM_COLUMN_HEADER to listOf(3000.0, 3000.0, 4000.0),
            H.THROTTLE_PLATE_ANGLE_HEADER to listOf(90.0, 90.0, 90.0),
            H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER to listOf(2050.0, 2200.0, 1900.0),
            H.REQUESTED_PRESSURE_HEADER to listOf(2000.0, 2000.0, 2000.0)
        )
        val rows = LdrpidCalculator.analyzeLogDiagnostics(
            values,
            rpmAxis = arrayOf(3000.0, 4000.0),
            nonLinearCounts = arrayOf(intArrayOf(2, 0, 1), intArrayOf(0, 1, 0))
        )

        assertEquals(2, rows[0].sampleCount)
        assertEquals(2, rows[0].measuredDutyCells)
        assertEquals(125.0, rows[0].averageAbsolutePressureErrorMbar!!, 1e-9)
        assertEquals(200.0, rows[0].maximumOvershootMbar!!, 1e-9)
        assertEquals(50.0, rows[0].percentWithinTolerance!!, 1e-9)
        assertTrue(rows[1].averageAbsolutePressureErrorMbar!! > 0.0)
    }

    @Test
    fun `missing requested pressure is reported as unavailable`() {
        val values = mapOf(
            H.RPM_COLUMN_HEADER to listOf(3000.0),
            H.THROTTLE_PLATE_ANGLE_HEADER to listOf(90.0),
            H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER to listOf(2050.0)
        )
        val row = LdrpidCalculator.analyzeLogDiagnostics(values, arrayOf(3000.0)).single()

        assertEquals(1, row.sampleCount)
        assertNull(row.averageAbsolutePressureErrorMbar)
        assertNull(row.maximumOvershootMbar)
        assertNull(row.percentWithinTolerance)
    }
}
