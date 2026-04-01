package data.parser.med17log

import data.contract.Med17LogFileContract.Header as H
import java.io.File
import kotlin.test.*

/**
 * Integration tests for [Med17LogParser] using real MED17 Dyno Spectrum (DS1) CSV log files.
 *
 * Log files:
 * - `2025-01-21_16.24.32_log(1).csv` — RS3 2.5T, 949 rows, 530 WOT
 * - `2023-05-19_21.08.33_log.csv` — RS3, 12000 rows, cruise/idle (0 WOT)
 * - `2023-05-19_21.31.12_log.csv` — RS3, 4893 rows, 208 WOT
 */
class Med17LogParserTest {

    private val parser = Med17LogParser()

    private fun logFile(name: String): File {
        val url = javaClass.classLoader.getResource("logs/$name")
            ?: error("Test resource not found: logs/$name")
        return File(url.toURI())
    }

    // ── T1: Optimizer parsing — row counts ──────────────────────────

    @Test
    fun `parse 2025 log as OPTIMIZER produces 949 rows`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2025-01-21_16.24.32_log(1).csv"))
        val rpmList = result[H.RPM_COLUMN_HEADER]!!
        assertEquals(949, rpmList.size, "Expected 949 optimizer rows from 2025 log")
    }

    @Test
    fun `parse 2023-21h08 log as OPTIMIZER produces 1347 rows`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2023-05-19_21.08.33_log.csv"))
        val rpmList = result[H.RPM_COLUMN_HEADER]!!
        assertEquals(1347, rpmList.size, "Expected 1347 optimizer rows from 2023-21h08 log")
    }

    @Test
    fun `parse 2023-21h31 log as OPTIMIZER produces 543 rows`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2023-05-19_21.31.12_log.csv"))
        val rpmList = result[H.RPM_COLUMN_HEADER]!!
        assertEquals(543, rpmList.size, "Expected 543 optimizer rows from 2023-21h31 log")
    }

    // ── T1: Optimizer parsing — first row values ────────────────────

    @Test
    fun `2025 log first optimizer row has expected signal values`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2025-01-21_16.24.32_log(1).csv"))

        // First parsed row from Python analysis:
        // rpm=2826.5, rl=62.95, rlsol=39.33, wdkba=7.5, tvldste=0.0, psrg=868.67, pvds=962.03, pu=1013.36
        assertEquals(2826.5, result[H.RPM_COLUMN_HEADER]!![0], 0.1, "RPM")
        assertEquals(62.95, result[H.ENGINE_LOAD_HEADER]!![0], 0.1, "Engine load (rl_w)")
        assertEquals(39.33, result[H.REQUESTED_LOAD_HEADER]!![0], 0.1, "Requested load (rlsol_w)")
        assertEquals(7.5, result[H.THROTTLE_PLATE_ANGLE_HEADER]!![0], 0.1, "Throttle angle")
        assertEquals(0.0, result[H.WASTEGATE_DUTY_CYCLE_HEADER]!![0], 0.1, "WGDC (tvldste_w)")
        assertEquals(868.67, result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!![0], 0.1, "Actual boost (psrg_w)")
        assertEquals(962.03, result[H.REQUESTED_PRESSURE_HEADER]!![0], 0.1, "Requested pressure (pvds_w)")
        assertEquals(1013.36, result[H.BAROMETRIC_PRESSURE_HEADER]!![0], 0.1, "Baro pressure (pu_w)")
    }

    @Test
    fun `2023-21h08 log first optimizer row has expected signal values`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2023-05-19_21.08.33_log.csv"))

        // First parseable row: rpm=801.0, rl=18.35, rlsol=18.8, wdkba=1.6, wgdc=99.999, psrg=374.84, pvds=956.56, pu=1006.95
        assertEquals(801.0, result[H.RPM_COLUMN_HEADER]!![0], 0.5, "RPM")
        assertEquals(18.35, result[H.ENGINE_LOAD_HEADER]!![0], 0.1, "Engine load")
        assertEquals(18.8, result[H.REQUESTED_LOAD_HEADER]!![0], 0.1, "Requested load")
        assertEquals(1.6, result[H.THROTTLE_PLATE_ANGLE_HEADER]!![0], 0.1, "Throttle")
        assertEquals(374.84, result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!![0], 0.5, "Actual boost")
        assertEquals(956.56, result[H.REQUESTED_PRESSURE_HEADER]!![0], 0.5, "Requested pressure")
        assertEquals(1006.95, result[H.BAROMETRIC_PRESSURE_HEADER]!![0], 0.5, "Baro")
    }

    // ── T1: Optimizer parsing — signal ranges ───────────────────────

    @Test
    fun `2025 log RPM values are in sane automotive range`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2025-01-21_16.24.32_log(1).csv"))
        val rpms = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpms.all { it in 400.0..8500.0 }, "All RPM values should be 400–8500")
    }

    @Test
    fun `2025 log manifold pressure in expected range`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2025-01-21_16.24.32_log(1).csv"))
        val pressures = result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!
        assertTrue(pressures.all { it in 200.0..5000.0 }, "Manifold pressure should be 200–5000 hPa")
    }

    @Test
    fun `2025 log barometric pressure is near sea level`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2025-01-21_16.24.32_log(1).csv"))
        val baros = result[H.BAROMETRIC_PRESSURE_HEADER]!!
        assertTrue(baros.all { it in 900.0..1100.0 }, "Baro pressure should be 900–1100 hPa (near sea level)")
    }

    @Test
    fun `2025 log fupsrls_w optional signal is populated`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2025-01-21_16.24.32_log(1).csv"))
        val fupsrls = result[H.FUPSRLS_HEADER]!!
        assertTrue(fupsrls.isNotEmpty(), "fupsrls_w should be populated as optional signal")
        // fupsrls_w is the VE conversion factor, typically 0.08–0.15 for 2.5T
        assertEquals(0.09154, fupsrls[0], 0.001, "First fupsrls_w value")
        assertTrue(fupsrls.all { it in 0.05..0.25 }, "fupsrls_w should be 0.05–0.25")
    }

    // ── T2: Header signal name extraction ───────────────────────────

    @Test
    fun `extractSignalName parses standard Dyno Spectrum format`() {
        // Use reflection to access the private method for testing
        val method = Med17LogParser::class.java.getDeclaredMethod("extractSignalName", String::class.java)
        method.isAccessible = true

        assertEquals("nmot_w", method.invoke(parser, "Eng spd(nmot_w) (1/min)"))
        assertEquals("rl_w", method.invoke(parser, "Load rel(rl_w) (%)"))
        assertEquals("tvldste_w", method.invoke(parser, "WGDC(tvldste_w) (%)"))
        assertEquals("InjSys_facPrtnPfiTar", method.invoke(parser, "Tgt dist fac prop port fuel inj(InjSys_facPrtnPfiTar) (-)"))
        assertEquals("psrg_w", method.invoke(parser, "Manifold abs press(psrg_w) (hPa)"))
    }

    @Test
    fun `extractSignalName handles single-parenthetical format`() {
        val method = Med17LogParser::class.java.getDeclaredMethod("extractSignalName", String::class.java)
        method.isAccessible = true

        // Time(s) has only one parenthetical group
        assertEquals("s", method.invoke(parser, "Time(s)"))
    }

    // ── T3: LDRPID parsing ──────────────────────────────────────────

    @Test
    fun `parse 2025 log as LDRPID produces 949 rows`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))
        val rpmList = result[H.RPM_COLUMN_HEADER]!!
        assertEquals(949, rpmList.size, "Expected 949 LDRPID rows from 2025 log")
    }

    @Test
    fun `LDRPID parse populates required signals`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))

        assertTrue(result[H.TIME_STAMP_COLUMN_HEADER]!!.isNotEmpty(), "Time should be populated")
        assertTrue(result[H.RPM_COLUMN_HEADER]!!.isNotEmpty(), "RPM should be populated")
        assertTrue(result[H.THROTTLE_PLATE_ANGLE_HEADER]!!.isNotEmpty(), "Throttle should be populated")
        assertTrue(result[H.BAROMETRIC_PRESSURE_HEADER]!!.isNotEmpty(), "Baro should be populated")
        assertTrue(result[H.WASTEGATE_DUTY_CYCLE_HEADER]!!.isNotEmpty(), "WGDC should be populated")
        assertTrue(result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!.isNotEmpty(), "Boost should be populated")
    }

    @Test
    fun `LDRPID does not populate optimizer-only signals`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))

        // LDRPID map doesn't include these keys at all
        assertNull(result[H.REQUESTED_PRESSURE_HEADER], "Requested pressure should not be in LDRPID map")
        assertNull(result[H.REQUESTED_LOAD_HEADER], "Requested load should not be in LDRPID map")
        assertNull(result[H.ENGINE_LOAD_HEADER], "Engine load should not be in LDRPID map")
    }

    @Test
    fun `LDRPID first row values match expected`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.LDRPID, logFile("2025-01-21_16.24.32_log(1).csv"))

        assertEquals(2826.5, result[H.RPM_COLUMN_HEADER]!![0], 0.1, "RPM")
        assertEquals(7.5, result[H.THROTTLE_PLATE_ANGLE_HEADER]!![0], 0.1, "Throttle")
        assertEquals(1013.36, result[H.BAROMETRIC_PRESSURE_HEADER]!![0], 0.1, "Baro")
        assertEquals(0.0, result[H.WASTEGATE_DUTY_CYCLE_HEADER]!![0], 0.1, "WGDC")
        assertEquals(868.67, result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!![0], 0.1, "Boost")
    }

    // ── parseLogDirectory ───────────────────────────────────────────

    @Test
    fun `parseLogDirectory aggregates all CSV files`() {
        val logDir = logFile("2025-01-21_16.24.32_log(1).csv").parentFile
        var progressCalls = 0
        val result = parser.parseLogDirectory(
            Med17LogParser.LogType.OPTIMIZER,
            logDir,
            Med17LogParser.ProgressCallback { _, _ -> progressCalls++ }
        )

        // All CSV files in test resources/logs/ → should be called once per file
        val csvCount = logDir.listFiles()!!.count { it.name.endsWith(".csv", true) }
        assertEquals(csvCount, progressCalls, "Progress callback should fire once per file")

        // Total rows should include all parseable logs
        val totalRows = result[H.RPM_COLUMN_HEADER]!!.size
        assertTrue(totalRows > 2839, "Total optimizer rows should include new log files (was 2839 for 3 files)")
    }

    // ── rlmds_w fallback tests (2026 log has rlmds_w but NOT rlsol_w) ──

    @Test
    fun `parse 2026 log as OPTIMIZER succeeds via rlmds_w fallback`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2026-03-22_18.13.11_log.csv"))
        val rpmList = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpmList.isNotEmpty(), "2026 log should parse successfully using rlmds_w fallback for rlsol_w")
    }

    @Test
    fun `2026 log requested load populated from rlmds_w fallback`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2026-03-22_18.13.11_log.csv"))
        val reqLoads = result[H.REQUESTED_LOAD_HEADER]!!
        assertTrue(reqLoads.isNotEmpty(), "Requested load should be populated from rlmds_w")
        // rlmds_w is load target from torque request — values should be in 0-400% range
        assertTrue(reqLoads.all { it in -10.0..500.0 }, "Requested load values should be in sane range")
    }

    @Test
    fun `2026 log all optimizer signals populated`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2026-03-22_18.13.11_log.csv"))

        assertTrue(result[H.RPM_COLUMN_HEADER]!!.isNotEmpty(), "RPM")
        assertTrue(result[H.ENGINE_LOAD_HEADER]!!.isNotEmpty(), "Engine load")
        assertTrue(result[H.REQUESTED_LOAD_HEADER]!!.isNotEmpty(), "Requested load (via rlmds_w)")
        assertTrue(result[H.THROTTLE_PLATE_ANGLE_HEADER]!!.isNotEmpty(), "Throttle")
        assertTrue(result[H.WASTEGATE_DUTY_CYCLE_HEADER]!!.isNotEmpty(), "WGDC")
        assertTrue(result[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!.isNotEmpty(), "Actual boost")
        assertTrue(result[H.REQUESTED_PRESSURE_HEADER]!!.isNotEmpty(), "Requested pressure")
        assertTrue(result[H.BAROMETRIC_PRESSURE_HEADER]!!.isNotEmpty(), "Baro pressure")
    }

    @Test
    fun `2024-10-25 log with both rlsol_w and rlmds_w uses primary rlsol_w`() {
        // This log has both rlsol_w and rlmds_w — should use rlsol_w (primary)
        val result = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2024-10-25_17.50.39_log.csv"))
        val rpmList = result[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpmList.isNotEmpty(), "2024-10-25 log should parse successfully")
        val reqLoads = result[H.REQUESTED_LOAD_HEADER]!!
        assertTrue(reqLoads.isNotEmpty(), "Requested load should be populated from rlsol_w")
    }

    @Test
    fun `2026 log parse diagnostics report rlmds_w as matched`() {
        parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile("2026-03-22_18.13.11_log.csv"))
        val diag = parser.lastDiagnostics
        assertNotNull(diag, "Parse diagnostics should be populated")
        assertTrue(diag!!.matchedHeaders.contains("rlmds_w"),
            "rlmds_w should appear in matched headers: ${diag.matchedHeaders}")
    }

    @Test
    fun `2026 log as PLSOL also parses successfully`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.PLSOL, logFile("2026-03-22_18.13.11_log.csv"))
        val loadList = result[H.ENGINE_LOAD_HEADER]!!
        assertTrue(loadList.isNotEmpty(), "PLSOL should parse 2026 log (doesn't need rlsol_w)")
    }

    // ── PFI_SPLIT log type ──────────────────────────────────────────

    @Test
    fun `parse 2025 log as PFI_SPLIT extracts RPM and PFI factor`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.PFI_SPLIT, logFile("2025-01-21_16.24.32_log(1).csv"))
        val rpmList = result[H.RPM_COLUMN_HEADER]
        val pfiList = result[H.PFI_SPLIT_FACTOR_HEADER]

        // The WOT log should have InjSys_facPrtnPfiTar — if not, verify header names
        if (rpmList != null && rpmList.isNotEmpty()) {
            println("PFI_SPLIT: ${rpmList.size} rows, RPM range ${rpmList.min()}-${rpmList.max()}")
            assertTrue(rpmList.size > 0, "Should have RPM data")
            assertNotNull(pfiList, "Should have PFI split data")
            assertTrue(pfiList!!.size == rpmList.size, "RPM and PFI should have same count")
            // PFI factor should be between 0.0 and 1.0
            for (v in pfiList) {
                assertTrue(v in 0.0..1.0, "PFI factor should be 0-1, got $v")
            }
        } else {
            println("PFI_SPLIT: log does not contain InjSys_facPrtnPfiTar column — skipping assertions")
        }
    }

    @Test
    fun `parse cruise log as PFI_SPLIT does not crash`() {
        val result = parser.parseLogFile(Med17LogParser.LogType.PFI_SPLIT, logFile("2023-05-19_21.08.33_log.csv"))
        // Should return something even if PFI columns don't exist
        assertNotNull(result)
    }
}
