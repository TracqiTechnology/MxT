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
}
