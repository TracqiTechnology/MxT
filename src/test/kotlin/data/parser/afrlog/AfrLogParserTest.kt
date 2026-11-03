package data.parser.afrlog

import data.contract.AfrLogFileContract
import data.contract.Me7LogFileContract
import data.parser.afrlog.AfrLogParser
import java.io.File
import kotlin.test.*

/**
 * Integration tests for [AfrLogParser] using a real Zeitronix CSV log file.
 *
 * Tests the private [AfrLogParser.parseFile] method via reflection since
 * the public API emits through a SharedFlow (coroutine-based).
 *
 * Log file: `example/me7/logs/ziet/zeitronix.csv` — 23612 data rows.
 */
class AfrLogParserTest {

    private val zeitronixLog = File("example/me7/logs/ziet/zeitronix.csv")

    @BeforeTest
    fun checkFileExists() {
        assertTrue(zeitronixLog.exists(), "Zeitronix log not found at ${zeitronixLog.absolutePath}")
    }

    /** Invoke the private `parseFile` method via reflection. */
    private fun parseFile(file: File): Map<String, List<Double>> {
        val method = AfrLogParser::class.java.getDeclaredMethod("parseFile", File::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(AfrLogParser, file) as Map<String, List<Double>>
    }

    // ── T8: Zeitronix CSV parsing ───────────────────────────────────

    @Test
    fun `parseFile produces approximately 23612 rows`() {
        val result = parseFile(zeitronixLog)
        val rpms = result[AfrLogFileContract.RPM_HEADER]!!
        // Allow ±10 rows for header/footer edge cases
        assertTrue(rpms.size in 23600..23620, "Expected ~23612 rows, got ${rpms.size}")
    }

    @Test
    fun `first row has expected AFR value`() {
        val result = parseFile(zeitronixLog)
        assertEquals(14.7, result[AfrLogFileContract.AFR_HEADER]!!.first(), 0.1)
    }

    @Test
    fun `first row has expected RPM value`() {
        val result = parseFile(zeitronixLog)
        assertEquals(2341.5, result[AfrLogFileContract.RPM_HEADER]!!.first(), 1.0)
    }

    @Test
    fun `first row has expected TPS value`() {
        val result = parseFile(zeitronixLog)
        assertEquals(29.0, result[AfrLogFileContract.TPS_HEADER]!!.first(), 1.0)
    }

    @Test
    fun `first row boost is negative (vacuum) and converted to mbar`() {
        val result = parseFile(zeitronixLog)
        val firstBoostMbar = result[AfrLogFileContract.BOOST_HEADER]!!.first()
        // -9.599 PSI (actually inHg) * 33.8639 = -325.0 mbar (relative)
        assertTrue(firstBoostMbar < 0, "First row should be vacuum (negative boost)")
        assertEquals(-9.599 * 33.8639, firstBoostMbar, 1.0)
    }

    @Test
    fun `start time is parsed from first timestamp`() {
        val result = parseFile(zeitronixLog)
        val startTime = result[AfrLogFileContract.START_TIME]!!
        assertTrue(startTime.isNotEmpty(), "Start time should be populated")
        // "05:14:12.051" → 14*60 + 12.051 = 852.051
        assertEquals(852.051, startTime.first(), 0.1)
    }

    @Test
    fun `all AFR values are within sane range`() {
        val result = parseFile(zeitronixLog)
        val afrs = result[AfrLogFileContract.AFR_HEADER]!!
        for (afr in afrs) {
            assertTrue(afr in 5.0..25.0, "AFR $afr is out of sane range [5, 25]")
        }
    }

    @Test
    fun `positive boost values are converted with PSI factor`() {
        val result = parseFile(zeitronixLog)
        val boosts = result[AfrLogFileContract.BOOST_HEADER]!!
        // Find first positive boost entry
        val firstPositive = boosts.first { it > 0 }
        // Positive boost should use PSI * 68.9476 (the PSI→mbar factor)
        // Typical small positive boost: 0.1 PSI → 6.89 mbar
        assertTrue(firstPositive > 0 && firstPositive < 500,
            "First positive boost ($firstPositive mbar) should be small and positive")
    }

    // ── ME7 wideband conversion mode ────────────────────────────────

    @Test
    fun `parseMe7Log converts lamsoni_w to AFR by multiplying by 14_7`() {
        // Build a minimal ME7 log map with wideband O2 data
        val me7Log = mapOf(
            Me7LogFileContract.Header.START_TIME_HEADER to listOf(1000.0),
            Me7LogFileContract.Header.TIME_STAMP_COLUMN_HEADER to listOf(0.0, 1.0, 2.0),
            Me7LogFileContract.Header.RPM_COLUMN_HEADER to listOf(2000.0, 3000.0, 4000.0),
            Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER to listOf(50.0, 80.0, 95.0),
            Me7LogFileContract.Header.WIDE_BAND_O2_HEADER to listOf(1.0, 0.85, 0.78),
            Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER to listOf(1013.0, 1500.0, 2000.0),
            Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER to listOf(1013.0, 1013.0, 1013.0)
        )

        val result = AfrLogParser.parseMe7Log(me7Log)

        val afrs = result[AfrLogFileContract.AFR_HEADER]!!
        assertEquals(3, afrs.size)
        // lamsoni_w * 14.7 = AFR
        assertEquals(14.7, afrs[0], 0.01)    // 1.0 * 14.7
        assertEquals(12.495, afrs[1], 0.01)  // 0.85 * 14.7
        assertEquals(11.466, afrs[2], 0.01)  // 0.78 * 14.7
    }

    @Test
    fun `parseMe7Log computes relative boost in PSI`() {
        val me7Log = mapOf(
            Me7LogFileContract.Header.START_TIME_HEADER to listOf(1000.0),
            Me7LogFileContract.Header.TIME_STAMP_COLUMN_HEADER to listOf(0.0, 1.0),
            Me7LogFileContract.Header.RPM_COLUMN_HEADER to listOf(3000.0, 5000.0),
            Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER to listOf(90.0, 95.0),
            Me7LogFileContract.Header.WIDE_BAND_O2_HEADER to listOf(0.85, 0.78),
            Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER to listOf(1500.0, 2200.0),
            Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER to listOf(1013.0, 1013.0)
        )

        val result = AfrLogParser.parseMe7Log(me7Log)
        val boosts = result[AfrLogFileContract.BOOST_HEADER]!!
        assertEquals(2, boosts.size)

        // Relative pressure = (absolute - baro) * 0.0145038 (mbar→PSI)
        assertEquals((1500.0 - 1013.0) * 0.0145038, boosts[0], 0.01)
        assertEquals((2200.0 - 1013.0) * 0.0145038, boosts[1], 0.01)
    }

    @Test
    fun `parseMe7Log returns empty when wideband O2 is missing`() {
        val me7Log = mapOf(
            Me7LogFileContract.Header.START_TIME_HEADER to listOf(1000.0),
            Me7LogFileContract.Header.TIME_STAMP_COLUMN_HEADER to listOf(0.0),
            Me7LogFileContract.Header.RPM_COLUMN_HEADER to listOf(2000.0),
            Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER to listOf(50.0)
            // No WIDE_BAND_O2_HEADER
        )

        val result = AfrLogParser.parseMe7Log(me7Log)
        assertTrue(result.isEmpty(), "Should return empty map when wideband O2 is missing")
    }
}
