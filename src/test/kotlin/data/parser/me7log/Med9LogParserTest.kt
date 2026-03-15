package data.parser.me7log

import data.contract.Me7LogFileContract
import data.contract.Me7LogFileContract.Header as H
import java.io.File
import kotlin.test.*

/**
 * Tests that Me7LogParser correctly parses MED9 ME7Logger-format CSV logs
 * when header overrides are applied (simulating MED9 profile loading).
 *
 * MED9 signal naming differences from ME7:
 *   - RPM: "nmot_w" (ME7: "nmot")
 *   - Injection time: "ti_w" (ME7: "ti_b1")
 *   - Other signals use identical names
 *
 * The profile's logHeaders override Me7LogFileContract.Header.header values
 * so the parser matches MED9-specific column names in CSV files.
 */
class Med9LogParserTest {

    private val parser = Me7LogParser()

    private val openLoopLog = File("example/med9/logs/open_loop_log.csv")
    private val closedLoopLog = File("example/med9/logs/closed_loop_log.csv")
    private val ldrpidLog = File("example/med9/logs/ldrpid_log.csv")

    // Store original header values for cleanup
    private val originalHeaders = mutableMapOf<H, String>()

    @BeforeTest
    fun setup() {
        assertTrue(openLoopLog.exists(), "Open loop log fixture not found at ${openLoopLog.absolutePath}")
        assertTrue(closedLoopLog.exists(), "Closed loop log fixture not found")
        assertTrue(ldrpidLog.exists(), "LDRPID log fixture not found")

        // Save original header values
        for (header in H.entries) {
            originalHeaders[header] = header.header
        }

        // Apply MED9 profile header overrides (simulates ProfileManager.applyProfile)
        H.RPM_COLUMN_HEADER.header = "nmot_w"
        H.FUEL_INJECTOR_ON_TIME_HEADER.header = "ti_w"
    }

    @AfterTest
    fun teardown() {
        // Restore original header values to avoid polluting other tests
        for ((header, original) in originalHeaders) {
            header.header = original
        }
    }

    // ── Open Loop Tests ────────────────────────────────────────────────

    @Test
    fun `open loop parses MED9 log with nmot_w header`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.OPEN_LOOP, openLoopLog)

        assertTrue(result.containsKey(H.RPM_COLUMN_HEADER), "RPM data should be parsed from nmot_w column")
        val rpm = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpm.isNotEmpty(), "RPM data should not be empty")
        assertTrue(rpm.all { it in 1500.0..7000.0 }, "RPM values should be in MED9 range: ${rpm.min()}-${rpm.max()}")
    }

    @Test
    fun `open loop parses ti_w injection time`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.OPEN_LOOP, openLoopLog)

        assertTrue(result.containsKey(H.FUEL_INJECTOR_ON_TIME_HEADER), "Injection time should be parsed from ti_w column")
        val ti = result[H.FUEL_INJECTOR_ON_TIME_HEADER]!!
        assertTrue(ti.isNotEmpty(), "Injection time data should not be empty")
        // MED9 ti_w is in seconds (not ms like ME7 ti_b1)
        assertTrue(ti.all { it in 0.0..0.05 }, "Injection time should be reasonable: ${ti.min()}-${ti.max()}")
    }

    @Test
    fun `open loop has correct signal count`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.OPEN_LOOP, openLoopLog)

        // Open loop requires: time, rpm, stft, ltft, mafVoltage, mafGps, throttle, lambdaActive, requestedLambda, injectorTime
        assertTrue(result.containsKey(H.TIME_STAMP_COLUMN_HEADER))
        assertTrue(result.containsKey(H.RPM_COLUMN_HEADER))
        assertTrue(result.containsKey(H.STFT_COLUMN_HEADER))
        assertTrue(result.containsKey(H.LTFT_COLUMN_HEADER))
        assertTrue(result.containsKey(H.MAF_VOLTAGE_HEADER))
        assertTrue(result.containsKey(H.MAF_GRAMS_PER_SECOND_HEADER))
        assertTrue(result.containsKey(H.THROTTLE_PLATE_ANGLE_HEADER))
        assertTrue(result.containsKey(H.LAMBDA_CONTROL_ACTIVE_HEADER))
        assertTrue(result.containsKey(H.REQUESTED_LAMBDA_HEADER))
        assertTrue(result.containsKey(H.FUEL_INJECTOR_ON_TIME_HEADER))
    }

    @Test
    fun `open loop lambda goes rich under boost`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.OPEN_LOOP, openLoopLog)

        val lambda = result[H.REQUESTED_LAMBDA_HEADER]!!
        val rpm = result[H.RPM_COLUMN_HEADER]!!

        // At high RPM (>3500), lambda should be enriched (< 1.0)
        val highRpmIndices = rpm.indices.filter { rpm[it] > 3500 }
        assertTrue(highRpmIndices.isNotEmpty(), "Should have some high-RPM data points")
        val richPoints = highRpmIndices.count { lambda[it] < 0.9 }
        assertTrue(richPoints > 0, "Should have rich lambda points at high RPM")
    }

    @Test
    fun `open loop RPM progression is increasing`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.OPEN_LOOP, openLoopLog)

        val rpm = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpm.first() < rpm.last(), "RPM should increase over the pull: ${rpm.first()} → ${rpm.last()}")
        assertTrue(rpm.first() < 2200, "Pull should start below 2200 RPM")
        assertTrue(rpm.last() > 4000, "Pull should reach above 4000 RPM")
    }

    // ── Closed Loop Tests ──────────────────────────────────────────────

    @Test
    fun `closed loop parses MED9 fuel trim log`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.CLOSED_LOOP, closedLoopLog)

        assertTrue(result.containsKey(H.RPM_COLUMN_HEADER))
        assertTrue(result.containsKey(H.STFT_COLUMN_HEADER))
        assertTrue(result.containsKey(H.LTFT_COLUMN_HEADER))
        assertTrue(result.containsKey(H.MAF_VOLTAGE_HEADER))
        assertTrue(result.containsKey(H.THROTTLE_PLATE_ANGLE_HEADER))
        assertTrue(result.containsKey(H.LAMBDA_CONTROL_ACTIVE_HEADER))
        assertTrue(result.containsKey(H.ENGINE_LOAD_HEADER))
    }

    @Test
    fun `closed loop fuel trims are near 1_0`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.CLOSED_LOOP, closedLoopLog)

        val stft = result[H.STFT_COLUMN_HEADER]!!
        val ltft = result[H.LTFT_COLUMN_HEADER]!!

        // Closed loop: STFT should oscillate around 1.0, LTFT should be stable near 0.975
        assertTrue(stft.average() in 0.95..1.05, "STFT average should be near 1.0: ${stft.average()}")
        assertTrue(ltft.average() in 0.90..1.10, "LTFT average should be near 1.0: ${ltft.average()}")
    }

    @Test
    fun `closed loop lambda control is active`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.CLOSED_LOOP, closedLoopLog)

        val blr = result[H.LAMBDA_CONTROL_ACTIVE_HEADER]!!
        // All samples should be in closed loop (B_lr = 1)
        assertTrue(blr.all { it == 1.0 }, "Lambda control should be active for all samples")
    }

    @Test
    fun `closed loop idle RPM is stable`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.CLOSED_LOOP, closedLoopLog)

        val rpm = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpm.average() in 700.0..850.0, "Idle RPM should be ~760: ${rpm.average()}")
        val stddev = Math.sqrt(rpm.map { (it - rpm.average()).let { d -> d * d } }.average())
        assertTrue(stddev < 50, "Idle RPM should be stable (stddev=${stddev})")
    }

    // ── LDRPID Tests ───────────────────────────────────────────────────

    @Test
    fun `ldrpid parses MED9 boost control log`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.LDRPID, ldrpidLog)

        assertTrue(result.containsKey(H.RPM_COLUMN_HEADER))
        assertTrue(result.containsKey(H.THROTTLE_PLATE_ANGLE_HEADER))
        assertTrue(result.containsKey(H.WASTEGATE_DUTY_CYCLE_HEADER))
        assertTrue(result.containsKey(H.BAROMETRIC_PRESSURE_HEADER))
        assertTrue(result.containsKey(H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER))
        assertTrue(result.containsKey(H.SELECTED_GEAR_HEADER))
    }

    @Test
    fun `ldrpid boost pressure increases with RPM`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.LDRPID, ldrpidLog)

        val rpm = result[H.RPM_COLUMN_HEADER]!!
        val boost = result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!

        // At low RPM, boost is below atmospheric; at high RPM, it should be above
        val lowRpmBoost = rpm.indices.filter { rpm[it] < 3000 }.map { boost[it] }.average()
        val highRpmBoost = rpm.indices.filter { rpm[it] > 5000 }.map { boost[it] }.average()

        assertTrue(highRpmBoost > lowRpmBoost, "Boost should increase with RPM: low=$lowRpmBoost, high=$highRpmBoost")
        assertTrue(highRpmBoost > 1400, "Peak boost should exceed 1400 hPa (K03 turbo)")
    }

    @Test
    fun `ldrpid wastegate duty cycle increases under boost`() {
        val result = parser.parseLogFile(Me7LogParser.LogType.LDRPID, ldrpidLog)

        val rpm = result[H.RPM_COLUMN_HEADER]!!
        val wgdc = result[H.WASTEGATE_DUTY_CYCLE_HEADER]!!

        val highRpmWgdc = rpm.indices.filter { rpm[it] > 5000 }.map { wgdc[it] }.average()
        assertTrue(highRpmWgdc > 20, "WGDC should be >20% at high RPM: $highRpmWgdc")
    }

    // ── Cross-LogType Tests ────────────────────────────────────────────

    @Test
    fun `all logs have valid timestamps`() {
        for ((logFile, logType) in listOf(
            openLoopLog to Me7LogParser.LogType.OPEN_LOOP,
            closedLoopLog to Me7LogParser.LogType.CLOSED_LOOP,
            ldrpidLog to Me7LogParser.LogType.LDRPID
        )) {
            val result = parser.parseLogFile(logType, logFile)
            val timestamps = result[H.TIME_STAMP_COLUMN_HEADER]!!

            assertTrue(timestamps.isNotEmpty(), "Timestamps should not be empty for $logType")
            assertTrue(timestamps.first() >= 0.0, "First timestamp should be >= 0")
            // Timestamps should be monotonically increasing
            for (i in 1 until timestamps.size) {
                assertTrue(timestamps[i] >= timestamps[i-1],
                    "Timestamps should be monotonic at index $i: ${timestamps[i-1]} > ${timestamps[i]}")
            }
        }
    }

    @Test
    fun `start time is extracted from all logs`() {
        for ((logFile, logType) in listOf(
            openLoopLog to Me7LogParser.LogType.OPEN_LOOP,
            closedLoopLog to Me7LogParser.LogType.CLOSED_LOOP,
            ldrpidLog to Me7LogParser.LogType.LDRPID
        )) {
            val result = parser.parseLogFile(logType, logFile)
            assertTrue(result.containsKey(H.START_TIME_HEADER), "Start time should be extracted for $logType")
        }
    }
}
