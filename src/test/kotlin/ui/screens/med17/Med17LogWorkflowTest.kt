package ui.screens.med17

import data.contract.Med17LogFileContract
import data.contract.Me7LogFileContract
import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.med17log.Med17LogAdapter
import data.parser.med17log.Med17LogParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.bin.BinFilePreferences
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.platform.EcuPlatformPreference
import data.preferences.rkw.RkwPreferences
import data.profile.ConfigurationProfile
import data.profile.ProfileManager
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.fueltrim.FuelTrimAnalyzer
import domain.model.ldrpid.LdrpidCalculator
import domain.model.optimizer.OptimizerCalculator
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.test.*

/**
 * End-to-end log-driven workflow tests for MED17.
 *
 * For each of the 3 real MED17 logs:
 * - Fuel Trim: parse → analyze → apply corrections to rk_w → write → binary diff
 * - LDRPID: parse → calculate → write KFLDRL + KFLDIMX → binary diff
 *
 * Uses 404E as the primary XDF/BIN pair.
 */
class Med17LogWorkflowTest {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val XDF_FILE = File(PROJECT_ROOT, "example/med17/404E/404E_normal.xdf")
        private val BIN_FILE = File(PROJECT_ROOT, "example/med17/404E/MED17_1_62_STOCK.bin")
        private val profileJson = Json { ignoreUnknownKeys = true }

        val LOG_FILES = listOf(
            "2023-05-19_21.08.33_log.csv",
            "2023-05-19_21.31.12_log.csv",
            "2025-01-21_16.24.32_log(1).csv"
        )
    }

    private lateinit var savedPlatform: EcuPlatform
    private lateinit var tempBinFile: File
    private lateinit var stockBinCopy: File
    private lateinit var allMaps: List<Pair<TableDefinition, Map3d>>

    @BeforeTest
    fun setUp() {
        savedPlatform = EcuPlatformPreference.platform
        EcuPlatformPreference.platform = EcuPlatform.MED17

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        allMaps = BinParser.parseToList(FileInputStream(BIN_FILE), defs)

        XdfParser.setTableDefinitionsForTesting(defs)
        BinParser.setMapListForTesting(allMaps)

        tempBinFile = File.createTempFile("med17_log_", ".bin")
        BIN_FILE.copyTo(tempBinFile, overwrite = true)
        BinFilePreferences.setFile(tempBinFile)

        stockBinCopy = File.createTempFile("med17_stock_", ".bin")
        BIN_FILE.copyTo(stockBinCopy, overwrite = true)

        val stream = ProfileManager::class.java.getResourceAsStream(
            "/profiles/MED17_162_RS3_TTRS_2_5T.mxtprofile.json"
        ) ?: error("MED17 profile not found")
        val profile = profileJson.decodeFromString(
            ConfigurationProfile.serializer(), stream.bufferedReader().readText()
        )
        ProfileManager.applyProfile(profile)
    }

    @AfterTest
    fun tearDown() {
        EcuPlatformPreference.platform = savedPlatform
        if (::tempBinFile.isInitialized && tempBinFile.exists()) tempBinFile.delete()
        if (::stockBinCopy.isInitialized && stockBinCopy.exists()) stockBinCopy.delete()
    }

    private fun resetBin() {
        BIN_FILE.copyTo(tempBinFile, overwrite = true)
    }

    private fun loadLog(name: String): File {
        val url = javaClass.classLoader.getResource("logs/$name")
            ?: fail("Log file not found on classpath: logs/$name")
        return File(url.toURI())
    }

    // ── Fuel Trim: log → analyze → correct → write → diff ───────────

    @Test
    fun `log1 - fuel trim corrections applied and written to BIN`() =
        verifyFuelTrimWorkflow(LOG_FILES[0])

    @Test
    fun `log2 - fuel trim corrections applied and written to BIN`() =
        verifyFuelTrimWorkflow(LOG_FILES[1])

    @Test
    fun `log3 - fuel trim corrections applied and written to BIN`() =
        verifyFuelTrimWorkflow(LOG_FILES[2])

    private fun verifyFuelTrimWorkflow(logName: String) {
        resetBin()
        val logFile = loadLog(logName)
        val rkwPair = RkwPreferences.getSelectedMap()
            ?: fail("rk_w preference not resolved")
        val inputRkw = rkwPair.second

        // 1. Parse log
        val parser = Med17LogParser()
        val logData = parser.parseLogFile(Med17LogParser.LogType.FUEL_TRIM, logFile)
        val rpm = logData[Med17LogFileContract.Header.RPM_COLUMN_HEADER]
        assertNotNull(rpm, "Log should contain RPM data")
        assertTrue(rpm.isNotEmpty(), "RPM data should not be empty")

        // 2. Analyze fuel trims using rk_w table axes
        val result = FuelTrimAnalyzer.analyzeMed17Trims(
            logData,
            inputRkw.yAxis.map { it }.toDoubleArray(),
            inputRkw.xAxis.map { it }.toDoubleArray()
        )
        assertTrue(result.warnings.isNotEmpty(), "Analysis should produce diagnostics")

        // 3. Apply corrections to rk_w
        val corrections = result.toCorrectionsMap3d()
        val output = Map3d(inputRkw)
        for (r in output.zAxis.indices) {
            val rpmVal = if (r < output.yAxis.size) output.yAxis[r] else 0.0
            for (c in output.zAxis[r].indices) {
                val loadVal = if (c < output.xAxis.size) output.xAxis[c] else 0.0
                val correctionPct = corrections.lookup(loadVal, rpmVal)
                output.zAxis[r][c] = inputRkw.zAxis[r][c] * (1.0 + correctionPct / 100.0)
            }
        }

        // Verify corrections are in a sane range
        for (r in output.zAxis.indices) {
            for (c in output.zAxis[r].indices) {
                val ratio = if (inputRkw.zAxis[r][c] != 0.0) {
                    output.zAxis[r][c] / inputRkw.zAxis[r][c]
                } else 1.0
                assertTrue(
                    ratio in 0.5..2.0,
                    "Correction ratio at [$r][$c] = $ratio is out of sane range (0.5–2.0)"
                )
            }
        }

        // 4. Write to BIN
        BinWriter.write(tempBinFile, rkwPair.first, output)

        // 5. Binary diff — only rk_w bytes should change
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, rkwPair.first
        )
    }

    // ── LDRPID: log → calculate → write KFLDRL + KFLDIMX → diff ─────

    @Test
    fun `log1 - LDRPID KFLDRL calculated and written to BIN`() =
        verifyLdrpidKfldrlWorkflow(LOG_FILES[0])

    @Test
    fun `log2 - LDRPID KFLDRL calculated and written to BIN`() =
        verifyLdrpidKfldrlWorkflow(LOG_FILES[1])

    @Test
    fun `log3 - LDRPID KFLDRL calculated and written to BIN`() =
        verifyLdrpidKfldrlWorkflow(LOG_FILES[2])

    @Test
    fun `log1 - LDRPID KFLDIMX calculated and written to BIN`() =
        verifyLdrpidKfldimxWorkflow(LOG_FILES[0])

    @Test
    fun `log2 - LDRPID KFLDIMX calculated and written to BIN`() =
        verifyLdrpidKfldimxWorkflow(LOG_FILES[1])

    @Test
    fun `log3 - LDRPID KFLDIMX calculated and written to BIN`() =
        verifyLdrpidKfldimxWorkflow(LOG_FILES[2])

    private fun parseLdrpidFromLog(logName: String): Map<Me7LogFileContract.Header, List<Double>> {
        val logFile = loadLog(logName)
        val parser = Med17LogParser()
        val med17Values = parser.parseLogFile(Med17LogParser.LogType.LDRPID, logFile)
        return Med17LogAdapter.toMe7LdrpidFormat(med17Values)
    }

    private fun verifyLdrpidKfldrlWorkflow(logName: String) {
        resetBin()
        val kfldrlPair = KfldrlPreferences.getSelectedMap()
            ?: fail("KFLDRL preference not resolved")
        val kfldimxPair = KfldimxPreferences.getSelectedMap()
            ?: fail("KFLDIMX preference not resolved")

        val values = parseLdrpidFromLog(logName)
        val result = LdrpidCalculator.calculateLdrpid(values, kfldrlPair.second, kfldimxPair.second)

        // KFLDRL output should have same dimensions
        assertEquals(
            kfldrlPair.second.yAxis.size, result.kfldrl.yAxis.size,
            "KFLDRL output y-axis should match input"
        )

        BinWriter.write(tempBinFile, kfldrlPair.first, result.kfldrl)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, kfldrlPair.first
        )
    }

    private fun verifyLdrpidKfldimxWorkflow(logName: String) {
        resetBin()
        val kfldrlPair = KfldrlPreferences.getSelectedMap()
            ?: fail("KFLDRL preference not resolved")
        val kfldimxPair = KfldimxPreferences.getSelectedMap()
            ?: fail("KFLDIMX preference not resolved")

        val values = parseLdrpidFromLog(logName)
        val result = LdrpidCalculator.calculateLdrpid(values, kfldrlPair.second, kfldimxPair.second)

        BinWriter.write(tempBinFile, kfldimxPair.first, result.kfldimx)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, kfldimxPair.first
        )
    }

    // ── Optimizer: log → adapt → analyze → plausibility checks ───────

    @Test
    fun `log1 - optimizer parses and analyzes MED17 log`() =
        verifyOptimizerWorkflow(LOG_FILES[0], expectWot = false)

    @Test
    fun `log2 - optimizer parses and analyzes MED17 log with WOT`() =
        verifyOptimizerWorkflow(LOG_FILES[1], expectWot = true)

    @Test
    fun `log3 - optimizer parses and analyzes MED17 log with WOT`() =
        verifyOptimizerWorkflow(LOG_FILES[2], expectWot = true)

    private fun verifyOptimizerWorkflow(logName: String, expectWot: Boolean) {
        val logFile = loadLog(logName)
        val parser = Med17LogParser()
        val med17Values = parser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile)

        // Verify diagnostics — all required headers should be found
        val diag = parser.lastDiagnostics
        assertNotNull(diag, "Diagnostics should be populated after parse")
        assertTrue(
            diag.missingHeaders.isEmpty(),
            "$logName OPTIMIZER missing headers: ${diag.missingHeaders}"
        )

        // Adapt to ME7 optimizer format
        val me7Values = Med17LogAdapter.toMe7OptimizerFormat(med17Values)

        // Resolve maps from preferences (may be null if profile doesn't map them)
        val kfldrlMap = KfldrlPreferences.getSelectedMap()?.second
        val kfldimxMap = KfldimxPreferences.getSelectedMap()?.second
        val kfmiopMap = KfmiopPreferences.getSelectedMap()?.second
        val kfmirlMap = KfmirlPreferences.getSelectedMap()?.second

        val result = OptimizerCalculator.analyzeMed17(
            me7Values, kfldrlMap, kfldimxMap, kfmiopMap, kfmirlMap
        )

        if (expectWot) {
            assertTrue(
                result.wotEntries.isNotEmpty(),
                "$logName should contain WOT entries"
            )
            // Plausibility: RPM in [1000, 9000], boost (actualMap) in [500, 5000] hPa
            for (entry in result.wotEntries) {
                assertTrue(
                    entry.rpm in 1000.0..9000.0,
                    "WOT RPM ${entry.rpm} outside plausible range [1000, 9000]"
                )
                assertTrue(
                    entry.actualMap in 500.0..5000.0,
                    "WOT boost ${entry.actualMap} hPa outside plausible range [500, 5000]"
                )
            }
        }
        // Cruise-only log may have empty wotEntries — that's acceptable
    }

    // ── PLSOL: log → parse → WOT filter → plausibility ──────────────

    @Test
    fun `log1 - PLSOL parses cruise-only log`() =
        verifyPlsolWorkflow(LOG_FILES[0], expectWot = false)

    @Test
    fun `log2 - PLSOL parses WOT log with boost data`() =
        verifyPlsolWorkflow(LOG_FILES[1], expectWot = true)

    @Test
    fun `log3 - PLSOL parses WOT log with boost data`() =
        verifyPlsolWorkflow(LOG_FILES[2], expectWot = true)

    private fun verifyPlsolWorkflow(logName: String, expectWot: Boolean) {
        val logFile = loadLog(logName)
        val parser = Med17LogParser()
        val logData = parser.parseLogFile(Med17LogParser.LogType.PLSOL, logFile)

        val diag = parser.lastDiagnostics
        assertNotNull(diag, "Diagnostics should be populated after parse")
        assertTrue(
            diag.missingHeaders.isEmpty(),
            "$logName PLSOL missing headers: ${diag.missingHeaders}"
        )

        // Extract WOT-filtered points: throttle > 90%, collect (load, boost)
        val throttle = logData[Med17LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER] ?: emptyList()
        val load = logData[Med17LogFileContract.Header.ENGINE_LOAD_HEADER] ?: emptyList()
        val boost = logData[Med17LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER] ?: emptyList()

        val minSize = minOf(throttle.size, load.size, boost.size)
        val wotPoints = (0 until minSize).filter { throttle[it] > 90.0 }

        if (expectWot) {
            assertTrue(wotPoints.isNotEmpty(), "$logName should have WOT points (throttle > 90%)")
            for (i in wotPoints) {
                assertTrue(
                    boost[i] in 900.0..5000.0,
                    "WOT boost ${boost[i]} hPa at index $i outside plausible range [900, 5000]"
                )
            }
        }
        // Cruise-only log: zero WOT points is acceptable

        // Verify fupsrls_w is extracted (optional signal, may be empty for some logs)
        val fupsrls = logData[Med17LogFileContract.Header.FUPSRLS_HEADER]
        assertNotNull(fupsrls, "$logName PLSOL should initialize fupsrls_w list")
    }

    // ── Parser Diagnostics: all logs x all log types ─────────────────

    @Test
    fun `all logs parse successfully for all log types`() {
        for (logName in LOG_FILES) {
            for (logType in Med17LogParser.LogType.entries) {
                val parser = Med17LogParser()
                val logFile = loadLog(logName)
                parser.parseLogFile(logType, logFile)
                val diag = parser.lastDiagnostics
                assertNotNull(diag, "$logName / $logType should produce diagnostics")
                assertTrue(
                    diag.missingHeaders.isEmpty(),
                    "$logName / $logType missing headers: ${diag.missingHeaders}"
                )
            }
        }
    }
}
