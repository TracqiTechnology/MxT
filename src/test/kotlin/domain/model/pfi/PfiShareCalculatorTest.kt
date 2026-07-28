package domain.model.pfi

import data.contract.Med17LogFileContract.Header
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.InputStreamReader
import kotlin.test.*

/**
 * Tests for [PfiShareCalculator] — RPM-dependent PFI share curve generation
 * and log-based refinement for MED17 dual-injection systems.
 */
class PfiShareCalculatorTest {

    // ── Default curve shape ─────────────────────────────────────────────

    @Test
    fun `default curve has correct axis length`() {
        val result = PfiShareCalculator.calculateRpmDependentShare()
        assertEquals(
            PfiShareCalculator.DEFAULT_RPM_AXIS.size,
            result.rpmAxis.size
        )
        assertEquals(result.rpmAxis.size, result.pfiSharePercent.size)
    }

    @Test
    fun `default curve ramps up to torque peak`() {
        val result = PfiShareCalculator.calculateRpmDependentShare()
        val rpm = result.rpmAxis
        val pfi = result.pfiSharePercent

        // Share at 1000 RPM (idle) should be the lowest non-zero region
        val idleIdx = rpm.indexOfFirst { it >= 1000.0 }
        val peakIdx = rpm.indexOfFirst { it >= 4500.0 }
        assertTrue(idleIdx >= 0 && peakIdx >= 0, "Must have idle and peak RPM points")
        assertTrue(
            pfi[peakIdx] > pfi[idleIdx],
            "PFI share should ramp up: idle=${pfi[idleIdx]}% < peak=${pfi[peakIdx]}%"
        )
    }

    @Test
    fun `default curve holds steady through mid-range`() {
        val result = PfiShareCalculator.calculateRpmDependentShare()
        val rpm = result.rpmAxis
        val pfi = result.pfiSharePercent

        // 4500–5500 RPM band should be within 5% of each other (plateau)
        val plateauIndices = rpm.indices.filter { rpm[it] in 4500.0..5500.0 }
        assertTrue(plateauIndices.size >= 2, "Need at least 2 points in plateau")
        val min = plateauIndices.minOf { pfi[it] }
        val max = plateauIndices.maxOf { pfi[it] }
        assertTrue(
            max - min <= 5.0,
            "Plateau band should be within 5%: min=$min max=$max"
        )
    }

    @Test
    fun `default curve declines toward redline`() {
        val result = PfiShareCalculator.calculateRpmDependentShare()
        val rpm = result.rpmAxis
        val pfi = result.pfiSharePercent

        val peakIdx = rpm.indexOfFirst { it >= 4500.0 }
        val redlineIdx = rpm.indexOfLast { it <= 7000.0 }
        assertTrue(peakIdx >= 0 && redlineIdx > peakIdx)
        assertTrue(
            pfi[redlineIdx] < pfi[peakIdx],
            "PFI share should decline: peak=${pfi[peakIdx]}% > redline=${pfi[redlineIdx]}%"
        )
    }

    @Test
    fun `default curve values are all in 0-100 range`() {
        val result = PfiShareCalculator.calculateRpmDependentShare()
        result.pfiSharePercent.forEachIndexed { i, v ->
            assertTrue(v in 0.0..100.0, "PFI share at ${result.rpmAxis[i]} RPM = $v, out of range")
        }
    }

    @Test
    fun `default result has no logged data`() {
        val result = PfiShareCalculator.calculateRpmDependentShare()
        assertNull(result.loggedRpmAxis)
        assertNull(result.loggedPfiPercent)
    }

    // ── Custom RPM axis with default interpolation ──────────────────────

    @Test
    fun `custom RPM axis interpolates default curve`() {
        val customAxis = doubleArrayOf(1500.0, 3500.0, 5000.0, 6500.0)
        val result = PfiShareCalculator.calculateRpmDependentShare(rpmAxis = customAxis)

        assertEquals(4, result.rpmAxis.size)
        assertContentEquals(customAxis, result.rpmAxis)

        // 1500 RPM: interpolated between 1000→30% and 2000→40% = 35%
        assertEquals(35.0, result.pfiSharePercent[0], 1e-9)
        // 5000 RPM: exact hit on default axis = 60%
        assertEquals(60.0, result.pfiSharePercent[2], 1e-9)
    }

    @Test
    fun `RPM below axis minimum clamps to first value`() {
        val axis = doubleArrayOf(500.0, 7500.0)
        val result = PfiShareCalculator.calculateRpmDependentShare(rpmAxis = axis)

        // 500 RPM < 1000 RPM (first default point) → clamps to 30%
        assertEquals(30.0, result.pfiSharePercent[0], 1e-9)
    }

    @Test
    fun `RPM above axis maximum clamps to last value`() {
        val axis = doubleArrayOf(1000.0, 8000.0)
        val result = PfiShareCalculator.calculateRpmDependentShare(rpmAxis = axis)

        // 8000 RPM > 7000 RPM (last default point) → clamps to 20%
        assertEquals(20.0, result.pfiSharePercent[1], 1e-9)
    }

    // ── Explicit target PFI share ───────────────────────────────────────

    @Test
    fun `explicit target share is returned as-is`() {
        val axis = doubleArrayOf(1000.0, 3000.0, 5000.0, 7000.0)
        val target = doubleArrayOf(10.0, 40.0, 70.0, 25.0)
        val result = PfiShareCalculator.calculateRpmDependentShare(axis, target)

        assertContentEquals(axis, result.rpmAxis)
        assertContentEquals(target, result.pfiSharePercent)
    }

    @Test
    fun `target share values outside 0-100 are rejected`() {
        val axis = doubleArrayOf(1000.0, 5000.0)
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.calculateRpmDependentShare(
                axis,
                doubleArrayOf(-10.0, 150.0)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.calculateRpmDependentShare(
                axis,
                doubleArrayOf(10.0, Double.NaN)
            )
        }
    }

    @Test
    fun `duplicate and non-finite RPM breakpoints are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.calculateRpmDependentShare(
                doubleArrayOf(1000.0, 1000.0),
                doubleArrayOf(30.0, 40.0)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.calculateRpmDependentShare(
                doubleArrayOf(1000.0, Double.POSITIVE_INFINITY),
                doubleArrayOf(30.0, 40.0)
            )
        }
    }

    @Test
    fun `mismatched axis and target length throws`() {
        val axis = doubleArrayOf(1000.0, 5000.0, 7000.0)
        val target = doubleArrayOf(30.0, 60.0)
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.calculateRpmDependentShare(axis, target)
        }
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `single RPM point`() {
        val result = PfiShareCalculator.calculateRpmDependentShare(
            rpmAxis = doubleArrayOf(4500.0),
            targetPfiShare = doubleArrayOf(60.0)
        )
        assertEquals(1, result.rpmAxis.size)
        assertEquals(60.0, result.pfiSharePercent[0], 1e-9)
    }

    @Test
    fun `empty RPM axis throws`() {
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.calculateRpmDependentShare(rpmAxis = doubleArrayOf())
        }
    }

    @Test
    fun `unsorted RPM axis throws`() {
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.calculateRpmDependentShare(
                rpmAxis = doubleArrayOf(5000.0, 3000.0, 7000.0)
            )
        }
    }

    @Test
    fun `all-GDI curve (0 percent PFI)`() {
        val axis = doubleArrayOf(1000.0, 4000.0, 7000.0)
        val target = doubleArrayOf(0.0, 0.0, 0.0)
        val result = PfiShareCalculator.calculateRpmDependentShare(axis, target)

        result.pfiSharePercent.forEach { assertEquals(0.0, it, 1e-9) }
    }

    @Test
    fun `all-PFI curve (100 percent PFI)`() {
        val axis = doubleArrayOf(1000.0, 4000.0, 7000.0)
        val target = doubleArrayOf(100.0, 100.0, 100.0)
        val result = PfiShareCalculator.calculateRpmDependentShare(axis, target)

        result.pfiSharePercent.forEach { assertEquals(100.0, it, 1e-9) }
    }

    // ── refineFromLog — synthetic data ──────────────────────────────────

    @Test
    fun `refineFromLog with synthetic ramp data`() {
        // Simulate 100 samples: RPM from 2000→7000, PFI factor ramps 0.3→0.6→0.2
        val rpmList = mutableListOf<Double>()
        val pfiList = mutableListOf<Double>()
        for (i in 0 until 100) {
            val rpm = 2000.0 + i * 50.0  // 2000→6950
            rpmList.add(rpm)
            // Simple triangle: peak at rpm=4500
            val factor = if (rpm <= 4500.0) {
                0.3 + (rpm - 2000.0) / (4500.0 - 2000.0) * 0.3
            } else {
                0.6 - (rpm - 4500.0) / (7000.0 - 4500.0) * 0.4
            }
            pfiList.add(factor.coerceIn(0.0, 1.0))
        }

        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to rpmList,
            Header.PFI_SPLIT_FACTOR_HEADER to pfiList
        )

        val result = PfiShareCalculator.refineFromLog(logData)

        // Logged overlay should be populated
        assertNotNull(result.loggedRpmAxis)
        assertNotNull(result.loggedPfiPercent)
        assertTrue(result.loggedRpmAxis!!.isNotEmpty())
        assertEquals(result.loggedRpmAxis!!.size, result.loggedPfiPercent!!.size)

        // Logged values should be in percent (0–100)
        result.loggedPfiPercent!!.forEach { pct ->
            assertTrue(pct in 0.0..100.0, "Logged PFI $pct should be 0–100%")
        }

        // Default curve should still be present
        assertContentEquals(PfiShareCalculator.DEFAULT_RPM_AXIS, result.rpmAxis)
    }

    @Test
    fun `refineFromLog with empty log returns default`() {
        val logData = emptyMap<Header, List<Double>>()
        val result = PfiShareCalculator.refineFromLog(logData)

        assertNull(result.loggedRpmAxis)
        assertNull(result.loggedPfiPercent)
        assertContentEquals(PfiShareCalculator.DEFAULT_RPM_AXIS, result.rpmAxis)
    }

    @Test
    fun `refineFromLog with missing PFI column returns default`() {
        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(3000.0, 4000.0, 5000.0)
        )
        val result = PfiShareCalculator.refineFromLog(logData)
        assertNull(result.loggedRpmAxis)
    }

    @Test
    fun `refineFromLog with missing RPM column returns default`() {
        val logData = mapOf(
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(0.5, 0.6, 0.7)
        )
        val result = PfiShareCalculator.refineFromLog(logData)
        assertNull(result.loggedRpmAxis)
    }

    @Test
    fun `refineFromLog with all-zero RPM returns default`() {
        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(0.0, 0.0, 0.0),
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(0.5, 0.6, 0.7)
        )
        val result = PfiShareCalculator.refineFromLog(logData)
        assertNull(result.loggedRpmAxis)
    }

    @Test
    fun `refineFromLog with single valid row`() {
        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(4500.0),
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(0.55)
        )
        val result = PfiShareCalculator.refineFromLog(logData)

        assertNotNull(result.loggedRpmAxis)
        assertEquals(1, result.loggedRpmAxis!!.size)
        assertEquals(4500.0, result.loggedRpmAxis!![0], 1e-9)
        assertEquals(55.0, result.loggedPfiPercent!![0], 1e-9)
    }

    @Test
    fun `refineFromLog converts factor to percent`() {
        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(3000.0, 3000.0, 3000.0),
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(0.4, 0.5, 0.6)
        )
        val result = PfiShareCalculator.refineFromLog(logData)

        assertNotNull(result.loggedPfiPercent)
        // Average factor = 0.5 → 50%
        assertEquals(50.0, result.loggedPfiPercent!![0], 1e-9)
    }

    @Test
    fun `refineFromLog rejects out-of-range factor values instead of averaging them`() {
        val logData = mapOf(
            Header.RPM_COLUMN_HEADER to listOf(5000.0, 5000.0),
            Header.PFI_SPLIT_FACTOR_HEADER to listOf(1.5, -0.3)  // avg = 0.6 → 60%
        )
        val result = PfiShareCalculator.refineFromLog(logData)

        assertNull(result.loggedPfiPercent)
        assertNull(result.loggedRpmAxis)
        assertContentEquals(PfiShareCalculator.DEFAULT_PFI_SHARE, result.pfiSharePercent)
    }

    // ── Real log integration ────────────────────────────────────────────

    @Test
    fun `refineFromLog with real log file extracts PFI data`() {
        val logPath = "/logs/2025-01-21_16.24.32_log(1).csv"
        val stream = javaClass.getResourceAsStream(logPath)
            ?: run {
                println("SKIP: real log not found at $logPath")
                return
            }

        val logData = parseScorpionLogForTest(stream)

        val rpmData = logData[Header.RPM_COLUMN_HEADER]
        val pfiData = logData[Header.PFI_SPLIT_FACTOR_HEADER]

        assertNotNull(rpmData, "Log must contain RPM column")
        assertNotNull(pfiData, "Log must contain PFI_SPLIT_FACTOR column")
        assertTrue(rpmData.size > 100, "Log should have substantial data, got ${rpmData.size} rows")

        val result = PfiShareCalculator.refineFromLog(logData)

        assertNotNull(result.loggedRpmAxis, "Logged RPM axis must be populated")
        assertNotNull(result.loggedPfiPercent, "Logged PFI percent must be populated")
        assertTrue(
            result.loggedRpmAxis!!.size >= 2,
            "Should have at least 2 RPM buckets, got ${result.loggedRpmAxis!!.size}"
        )

        // Verify logged values are in valid range
        result.loggedPfiPercent!!.forEach { pct ->
            assertTrue(pct in 0.0..100.0, "Logged PFI $pct out of range")
        }

        // Default curve overlay should also be present
        assertContentEquals(PfiShareCalculator.DEFAULT_RPM_AXIS, result.rpmAxis)
    }

    // ── Helper: minimal Dyno Spectrum CSV parser for tests ──────────────

    /**
     * Parses a Dyno Spectrum (DS1) log CSV into a map keyed by [Header].
     *
     * The log format is:
     *   Line 1 — metadata (firmware, VIN, ECU type)
     *   Line 2 — column headers: "Description(signal_name) (unit)"
     *   Line 3+ — numeric data
     */
    private fun parseScorpionLogForTest(
        stream: java.io.InputStream
    ): Map<Header, List<Double>> {
        val reader = InputStreamReader(stream)
        val lines = reader.readLines()
        if (lines.size < 3) return emptyMap()

        // Parse header line to find signal name → column index
        val headerLine = lines[1]
        val headers = headerLine.split(",")

        // Build signal-name → Header mapping from the enum
        val signalToHeader = Header.entries.associateBy { it.header }

        // Map column index → Header for columns we recognise
        val colToHeader = mutableMapOf<Int, Header>()
        for ((idx, col) in headers.withIndex()) {
            // Extract signal name from "Description(signal_name) (unit)"
            val openParen = col.indexOf('(')
            val closeParen = col.indexOf(')')
            if (openParen >= 0 && closeParen > openParen) {
                val signal = col.substring(openParen + 1, closeParen)
                signalToHeader[signal]?.let { colToHeader[idx] = it }
            }
        }

        // Accumulate data columns
        val result = mutableMapOf<Header, MutableList<Double>>()
        for (h in colToHeader.values) {
            result[h] = mutableListOf()
        }

        for (lineIdx in 2 until lines.size) {
            val fields = lines[lineIdx].split(",")
            for ((colIdx, header) in colToHeader) {
                val value = fields.getOrNull(colIdx)?.toDoubleOrNull() ?: 0.0
                result[header]!!.add(value)
            }
        }

        return result
    }
}
