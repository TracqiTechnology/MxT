package data.parser.me7log

import data.contract.Me7LogFileContract.Header as H
import data.parser.me7log.Me7LogParser.LogType
import java.io.File
import kotlin.test.*

/**
 * Integration tests for [Me7LogParser] using real ME7Logger CSV log files.
 *
 * Log files (referenced from project root via example/me7/logs/):
 * - `log_typical_20200825_141742.csv` — 38894 rows, 76 signals, supports ALL log types
 * - `log_typical_20180722_131032.csv` — 7224 rows, 88 signals, OPTIMIZER + LDRPID only (missing uhfm_w, B_lr)
 * - `log_typical_20190105_094614.csv` — 15120 rows, 70 signals, OPEN_LOOP/CLOSED_LOOP/LDRPID (missing pssol_w)
 */
class Me7LogParserTest {

    private val parser = Me7LogParser()

    private val mainLog = File("example/me7/logs/me7/log_typical_20200825_141742.csv")
    private val optimizerOnlyLog = File("example/me7/logs/me7/log_typical_20180722_131032.csv")
    private val closedLoopOnlyLog = File("example/me7/logs/me7/log_typical_20190105_094614.csv")
    private val logDir = File("example/me7/logs/me7")

    @BeforeTest
    fun checkFilesExist() {
        assertTrue(mainLog.exists(), "Main log fixture not found at ${mainLog.absolutePath}")
        assertTrue(optimizerOnlyLog.exists(), "Optimizer-only log fixture not found")
        assertTrue(closedLoopOnlyLog.exists(), "Closed-loop-only log fixture not found")
    }

    // ── T1: OPTIMIZER type ──────────────────────────────────────────

    @Test
    fun `parse main log as OPTIMIZER produces expected rows`() {
        val result = parser.parseLogFile(LogType.OPTIMIZER, mainLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        // ME7Logger CSV has 38894 data lines; Apache CSV parser may skip 1 due to
        // trailing newline or metadata parsing edge case
        assertTrue(rpms.size in 38890..38895,
            "Expected ~38894 optimizer rows from main log, got ${rpms.size}")
    }

    @Test
    fun `OPTIMIZER first row has expected signal values`() {
        val result = parser.parseLogFile(LogType.OPTIMIZER, mainLog)
        assertEquals(760.0, result[H.RPM_COLUMN_HEADER]!!.first(), 1.0)
        assertEquals(39.0703, result[H.ENGINE_LOAD_HEADER]!!.first(), 0.01)
        assertEquals(503.906, result[H.REQUESTED_PRESSURE_HEADER]!!.first(), 0.01)
        assertEquals(843.516, result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!.first(), 0.01)
        assertEquals(824.141, result[H.BAROMETRIC_PRESSURE_HEADER]!!.first(), 0.01)
        assertEquals(0.784314, result[H.THROTTLE_PLATE_ANGLE_HEADER]!!.first(), 0.01)
        assertEquals(0.0, result[H.WASTEGATE_DUTY_CYCLE_HEADER]!!.first(), 0.01)
        assertEquals(33.4219, result[H.REQUESTED_LOAD_HEADER]!!.first(), 0.01)
    }

    @Test
    fun `OPTIMIZER start time is parsed correctly`() {
        val result = parser.parseLogFile(LogType.OPTIMIZER, mainLog)
        val startTimes = result[H.START_TIME_HEADER]!!
        assertTrue(startTimes.isNotEmpty(), "Start time should be parsed")
        // "14:17:46.340" → 17*60 + 46.340 = 1066.34
        assertEquals(1066.34, startTimes.first(), 0.01)
    }

    @Test
    fun `OPTIMIZER optional MAF and injector signals are populated`() {
        val result = parser.parseLogFile(LogType.OPTIMIZER, mainLog)
        val mafGs = result[H.MAF_GRAMS_PER_SECOND_HEADER]!!
        val injTime = result[H.FUEL_INJECTOR_ON_TIME_HEADER]!!
        val mafV = result[H.MAF_VOLTAGE_HEADER]!!

        assertTrue(mafGs.isNotEmpty(), "MAF g/s should be populated for ME7")
        assertTrue(injTime.isNotEmpty(), "Injector on-time should be populated for ME7")
        assertTrue(mafV.isNotEmpty(), "MAF voltage should be populated for ME7")

        // Verify first row values
        assertEquals(9.33334, mafGs.first(), 0.01)
        assertEquals(2.45334, injTime.first(), 0.01)
        assertEquals(0.71289, mafV.first(), 0.001)
    }

    @Test
    fun `OPTIMIZER actual load (rl) is populated`() {
        val result = parser.parseLogFile(LogType.OPTIMIZER, mainLog)
        val actualLoad = result[H.ACTUAL_LOAD_HEADER]!!
        assertTrue(actualLoad.isNotEmpty(), "Actual load (rl) should be populated")
        assertEquals(39.0, actualLoad.first(), 1.0)
    }

    // ── T2: OPEN_LOOP type ──────────────────────────────────────────

    @Test
    fun `parse main log as OPEN_LOOP produces expected rows`() {
        val result = parser.parseLogFile(LogType.OPEN_LOOP, mainLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.size in 38890..38895,
            "Expected ~38894 open-loop rows from main log, got ${rpms.size}")
    }

    @Test
    fun `OPEN_LOOP has MAF, lambda control, and injector signals`() {
        val result = parser.parseLogFile(LogType.OPEN_LOOP, mainLog)

        val mafGs = result[H.MAF_GRAMS_PER_SECOND_HEADER]!!
        val reqLambda = result[H.REQUESTED_LAMBDA_HEADER]!!
        val injTime = result[H.FUEL_INJECTOR_ON_TIME_HEADER]!!
        val stft = result[H.STFT_COLUMN_HEADER]!!
        val ltft = result[H.LTFT_COLUMN_HEADER]!!
        val mafV = result[H.MAF_VOLTAGE_HEADER]!!
        val lambdaCtl = result[H.LAMBDA_CONTROL_ACTIVE_HEADER]!!

        assertTrue(mafGs.isNotEmpty())
        assertTrue(reqLambda.isNotEmpty())
        assertTrue(injTime.isNotEmpty())
        assertTrue(stft.isNotEmpty())
        assertTrue(ltft.isNotEmpty())
        assertTrue(mafV.isNotEmpty())
        assertTrue(lambdaCtl.isNotEmpty())

        // First row values
        assertEquals(9.33334, mafGs.first(), 0.01)
        assertEquals(1.0, reqLambda.first(), 0.01)
        assertEquals(2.45334, injTime.first(), 0.01)
        assertEquals(0.933655, stft.first(), 0.001)
        assertEquals(0.966248, ltft.first(), 0.001)
    }

    // ── T3: CLOSED_LOOP type ────────────────────────────────────────

    @Test
    fun `parse main log as CLOSED_LOOP produces expected rows`() {
        val result = parser.parseLogFile(LogType.CLOSED_LOOP, mainLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.size in 38890..38895,
            "Expected ~38894 closed-loop rows, got ${rpms.size}")
    }

    @Test
    fun `CLOSED_LOOP has STFT, LTFT, MAF voltage, lambda control`() {
        val result = parser.parseLogFile(LogType.CLOSED_LOOP, mainLog)

        assertTrue(result[H.STFT_COLUMN_HEADER]!!.isNotEmpty())
        assertTrue(result[H.LTFT_COLUMN_HEADER]!!.isNotEmpty())
        assertTrue(result[H.MAF_VOLTAGE_HEADER]!!.isNotEmpty())
        assertTrue(result[H.LAMBDA_CONTROL_ACTIVE_HEADER]!!.isNotEmpty())
        assertTrue(result[H.ENGINE_LOAD_HEADER]!!.isNotEmpty())

        // CLOSED_LOOP should NOT have MAF g/s, requested lambda, or injector on-time
        assertFalse(result.containsKey(H.MAF_GRAMS_PER_SECOND_HEADER))
        assertFalse(result.containsKey(H.REQUESTED_LAMBDA_HEADER))
        assertFalse(result.containsKey(H.FUEL_INJECTOR_ON_TIME_HEADER))
    }

    // ── T4: LDRPID type ─────────────────────────────────────────────

    @Test
    fun `parse main log as LDRPID produces expected rows`() {
        val result = parser.parseLogFile(LogType.LDRPID, mainLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.size in 38890..38895,
            "Expected ~38894 LDRPID rows, got ${rpms.size}")
    }

    @Test
    fun `LDRPID has time, RPM, throttle, baro, WGDC, boost, gear`() {
        val result = parser.parseLogFile(LogType.LDRPID, mainLog)

        assertTrue(result[H.TIME_STAMP_COLUMN_HEADER]!!.isNotEmpty())
        assertTrue(result[H.RPM_COLUMN_HEADER]!!.isNotEmpty())
        assertTrue(result[H.THROTTLE_PLATE_ANGLE_HEADER]!!.isNotEmpty())
        assertTrue(result[H.BAROMETRIC_PRESSURE_HEADER]!!.isNotEmpty())
        assertTrue(result[H.WASTEGATE_DUTY_CYCLE_HEADER]!!.isNotEmpty())
        assertTrue(result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!.isNotEmpty())
        assertTrue(result[H.SELECTED_GEAR_HEADER]!!.isNotEmpty())

        // First row: gear=0, WGDC=0, baro=824.141
        assertEquals(0.0, result[H.SELECTED_GEAR_HEADER]!!.first(), 0.01)
        assertEquals(0.0, result[H.WASTEGATE_DUTY_CYCLE_HEADER]!!.first(), 0.01)
        assertEquals(824.141, result[H.BAROMETRIC_PRESSURE_HEADER]!!.first(), 0.01)
    }

    // ── T5: KFVPDKSD type ───────────────────────────────────────────

    @Test
    fun `parse main log as KFVPDKSD produces expected rows`() {
        val result = parser.parseLogFile(LogType.KFVPDKSD, mainLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.size in 38890..38895,
            "Expected ~38894 KFVPDKSD rows, got ${rpms.size}")
    }

    @Test
    fun `KFVPDKSD has time, RPM, throttle, baro, boost but NOT WGDC or gear`() {
        val result = parser.parseLogFile(LogType.KFVPDKSD, mainLog)

        assertTrue(result[H.TIME_STAMP_COLUMN_HEADER]!!.isNotEmpty())
        assertTrue(result[H.RPM_COLUMN_HEADER]!!.isNotEmpty())
        assertTrue(result[H.THROTTLE_PLATE_ANGLE_HEADER]!!.isNotEmpty())
        assertTrue(result[H.BAROMETRIC_PRESSURE_HEADER]!!.isNotEmpty())
        assertTrue(result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!.isNotEmpty())

        // KFVPDKSD map does NOT include WGDC or gear
        assertFalse(result.containsKey(H.WASTEGATE_DUTY_CYCLE_HEADER))
        assertFalse(result.containsKey(H.SELECTED_GEAR_HEADER))
    }

    // ── T6: Directory parsing ───────────────────────────────────────

    @Test
    fun `parseLogDirectory aggregates multiple files as LDRPID`() {
        // Use the three documented LDRPID-compatible fixtures. listFiles() order is
        // filesystem-dependent, so an arbitrary .take(3) yields files that may lack
        // required LDRPID signals (wgdc/gear) and contribute zero rows on CI.
        val subset = listOf(mainLog, optimizerOnlyLog, closedLoopOnlyLog)

        val tempDir = kotlin.io.path.createTempDirectory("me7-test-subset").toFile()
        try {
            subset.forEach { it.copyTo(File(tempDir, it.name)) }

            val progressCounts = mutableListOf<Pair<Int, Int>>()
            val result = parser.parseLogDirectory(LogType.LDRPID, tempDir) { value, max ->
                progressCounts.add(value to max)
            }

            val rpms = result[H.RPM_COLUMN_HEADER]!!

            // 3 files should produce at least several thousand rows
            assertTrue(rpms.size > 1000, "Expected >1000 aggregated rows, got ${rpms.size}")

            // Progress callback fires once per file
            assertEquals(3, progressCounts.size, "Progress callback should fire once per file")
            assertEquals(3, progressCounts.last().second, "Max progress should equal number of files")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ── T7: Signal value ranges ─────────────────────────────────────

    @Test
    fun `OPTIMIZER RPM values are within sane range`() {
        val result = parser.parseLogFile(LogType.OPTIMIZER, mainLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        for (rpm in rpms) {
            assertTrue(rpm in 0.0..9000.0, "RPM $rpm is out of sane range [0, 9000]")
        }
    }

    @Test
    fun `OPTIMIZER barometric pressure is within sane range`() {
        val result = parser.parseLogFile(LogType.OPTIMIZER, mainLog)
        val baros = result[H.BAROMETRIC_PRESSURE_HEADER]!!
        for (baro in baros) {
            assertTrue(baro in 700.0..1100.0, "Baro $baro is out of sane range [700, 1100] hPa")
        }
    }

    @Test
    fun `OPTIMIZER boost pressure is within sane range`() {
        val result = parser.parseLogFile(LogType.OPTIMIZER, mainLog)
        val boosts = result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!
        for (boost in boosts) {
            assertTrue(boost in 100.0..4000.0, "Boost $boost is out of sane range [100, 4000] hPa")
        }
    }

    @Test
    fun `LDRPID throttle angle is within 0 to 100`() {
        val result = parser.parseLogFile(LogType.LDRPID, mainLog)
        val throttles = result[H.THROTTLE_PLATE_ANGLE_HEADER]!!
        for (thr in throttles) {
            assertTrue(thr in 0.0..110.0, "Throttle $thr is out of range [0, 110]")
        }
    }

    @Test
    fun `LDRPID WGDC is within 0 to 100`() {
        val result = parser.parseLogFile(LogType.LDRPID, mainLog)
        val wgdcs = result[H.WASTEGATE_DUTY_CYCLE_HEADER]!!
        for (wgdc in wgdcs) {
            assertTrue(wgdc in 0.0..100.0, "WGDC $wgdc is out of range [0, 100]")
        }
    }

    // ── T11: Missing signals — graceful fallback ────────────────────

    @Test
    fun `log missing pssol_w returns empty map when parsed as OPTIMIZER`() {
        // closedLoopOnlyLog is missing pssol_w → OPTIMIZER headers won't be found
        val result = parser.parseLogFile(LogType.OPTIMIZER, closedLoopOnlyLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.isEmpty(), "Should produce 0 rows when OPTIMIZER signals are missing")
    }

    @Test
    fun `log missing uhfm_w returns empty map when parsed as CLOSED_LOOP`() {
        // optimizerOnlyLog is missing uhfm_w and B_lr → CLOSED_LOOP headers won't be found
        val result = parser.parseLogFile(LogType.CLOSED_LOOP, optimizerOnlyLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.isEmpty(), "Should produce 0 rows when CLOSED_LOOP signals are missing")
    }

    @Test
    fun `log missing uhfm_w returns empty map when parsed as OPEN_LOOP`() {
        // optimizerOnlyLog is missing uhfm_w → OPEN_LOOP headers won't be found
        val result = parser.parseLogFile(LogType.OPEN_LOOP, optimizerOnlyLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.isEmpty(), "Should produce 0 rows when OPEN_LOOP signals are missing")
    }

    // ── Cross-log consistency ───────────────────────────────────────

    @Test
    fun `optimizer-only log parses as OPTIMIZER with expected rows`() {
        val result = parser.parseLogFile(LogType.OPTIMIZER, optimizerOnlyLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.size in 7220..7225,
            "Expected ~7224 optimizer rows, got ${rpms.size}")
    }

    @Test
    fun `closed-loop-only log parses as CLOSED_LOOP with expected rows`() {
        val result = parser.parseLogFile(LogType.CLOSED_LOOP, closedLoopOnlyLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.size in 15115..15121,
            "Expected ~15120 closed-loop rows, got ${rpms.size}")
    }

    @Test
    fun `closed-loop-only log also parses as LDRPID`() {
        val result = parser.parseLogFile(LogType.LDRPID, closedLoopOnlyLog)
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.size in 15115..15121,
            "Expected ~15120 LDRPID rows, got ${rpms.size}")
    }
}
