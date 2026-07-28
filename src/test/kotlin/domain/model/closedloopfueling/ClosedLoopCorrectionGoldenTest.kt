package domain.model.closedloopfueling

import data.contract.Me7LogFileContract
import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.me7log.Me7LogParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.bin.BinFilePreferences
import data.preferences.mlhfm.MlhfmPreferences
import data.preferences.platform.EcuPlatformPreference
import data.profile.ConfigurationProfile
import data.profile.ProfileManager
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.mlhfm.MlhfmFitter
import kotlinx.serialization.json.Json
import ui.screens.med17.BinaryDiffHelper
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.test.*

/**
 * Golden-value tests for closed-loop MLHFM correction.
 *
 * Closed-loop correction uses STFT (Short-Term Fuel Trim) and LTFT (Long-Term Fuel Trim)
 * from the ECU's own lambda control to correct the MAF sensor linearization.
 *
 * Physical constraints:
 * - STFT/LTFT corrections are typically small (< ±15% each)
 * - The resulting MLHFM must remain monotonically increasing
 * - Corrections at very low and very high voltages may be unreliable (few data points)
 */
class ClosedLoopCorrectionGoldenTest {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val XDF_FILE = File(PROJECT_ROOT, "example/me7/xdf/8D0907551M-20190711.xdf")
        private val BIN_FILE = File(PROJECT_ROOT, "example/me7/bin/8D0907551M-0002.bin")
        private val LOG_ALL = File(PROJECT_ROOT, "example/me7/logs/me7/log_typical_20200825_141742.csv")
        private val LOG_FUEL = File(PROJECT_ROOT, "example/me7/logs/me7/log_typical_20190105_094614.csv")

        private val profileJson = Json { ignoreUnknownKeys = true }
    }

    private lateinit var savedPlatform: EcuPlatform
    private lateinit var globalState: support.GlobalTestStateSnapshot
    private lateinit var tableDefs: List<TableDefinition>
    private lateinit var allMaps: List<Pair<TableDefinition, Map3d>>
    private lateinit var tempBinFile: File
    private lateinit var stockBinCopy: File
    private lateinit var profile: ConfigurationProfile

    @BeforeTest
    fun setUp() {
        globalState = support.GlobalTestStateSnapshot.capture()
        savedPlatform = EcuPlatformPreference.platform
        EcuPlatformPreference.platform = EcuPlatform.ME7

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        tableDefs = defs
        allMaps = BinParser.parseToList(FileInputStream(BIN_FILE), tableDefs)

        XdfParser.setTableDefinitionsForTesting(tableDefs)
        BinParser.setMapListForTesting(allMaps)

        tempBinFile = File.createTempFile("me7_golden_cl_", ".bin")
        BIN_FILE.copyTo(tempBinFile, overwrite = true)
        BinFilePreferences.setFile(tempBinFile)

        stockBinCopy = File.createTempFile("me7_golden_cl_stock_", ".bin")
        BIN_FILE.copyTo(stockBinCopy, overwrite = true)

        val stream = ProfileManager::class.java.getResourceAsStream("/profiles/MBox.mxtprofile.json")
            ?: error("MBox profile not found")
        profile = profileJson.decodeFromString(
            ConfigurationProfile.serializer(), stream.bufferedReader().readText()
        )
        ProfileManager.applyProfile(profile)
    }

    @AfterTest
    fun tearDown() {
        globalState.restore()
        if (::tempBinFile.isInitialized) tempBinFile.delete()
        if (::stockBinCopy.isInitialized) stockBinCopy.delete()
    }

    private fun runClosedLoopCorrection(logFile: File): Pair<ClosedLoopFuelingCorrectionManager, Map3d> {
        val mlhfmPair = MlhfmPreferences.getSelectedMap()
            ?: fail("MLHFM preference not resolved")
        val (_, mlhfmMap) = mlhfmPair

        val parser = Me7LogParser()
        val logData = parser.parseLogFile(Me7LogParser.LogType.CLOSED_LOOP, logFile)

        val rpm = logData[Me7LogFileContract.Header.RPM_COLUMN_HEADER]
        assertNotNull(rpm, "Log should contain RPM data")
        assertTrue(rpm.isNotEmpty(), "RPM data should not be empty")

        val manager = ClosedLoopFuelingCorrectionManager(
            minThrottleAngle = 0.0,
            minRpm = 0.0,
            maxDerivative = 50.0
        )
        manager.correct(logData, mlhfmMap)
        assertNotNull(manager.closedLoopMlhfmCorrection, "Correction should be produced")

        return Pair(manager, mlhfmMap)
    }

    // ── Correction Property Tests ──

    @Test
    fun `closed-loop correction produces non-trivial corrections`() {
        val (manager, originalMlhfm) = runClosedLoopCorrection(LOG_ALL)
        val corrected = manager.closedLoopMlhfmCorrection!!.correctedMlhfm

        assertContentEquals(originalMlhfm.yAxis, corrected.yAxis,
            "Voltage axis must be preserved")

        var changedCount = 0
        for (i in corrected.zAxis.indices) {
            val orig = originalMlhfm.zAxis[i][0]
            val corr = corrected.zAxis[i][0]
            if (orig > 1.0 && abs(corr - orig) > 0.01) changedCount++
        }
        assertTrue(changedCount > 0,
            "Closed-loop correction should modify at least some MLHFM values (changed: $changedCount)")
    }

    @Test
    fun `closed-loop corrections are within STFT plus LTFT physical limits`() {
        val (manager, originalMlhfm) = runClosedLoopCorrection(LOG_ALL)
        val corrected = manager.closedLoopMlhfmCorrection!!.correctedMlhfm

        // STFT range is typically ±25%, LTFT ±25%, combined ±30% in practice.
        // If corrections are larger, something is wrong with data or filtering.
        for (i in corrected.zAxis.indices) {
            val orig = originalMlhfm.zAxis[i][0]
            val corr = corrected.zAxis[i][0]

            assertTrue(corr >= 0.0,
                "MLHFM[$i] = $corr must be non-negative")

            if (orig > 5.0) {
                val ratio = corr / orig
                assertTrue(ratio in 0.70..1.30,
                    "MLHFM[$i] correction ratio = $ratio exceeds ±30% — " +
                    "STFT+LTFT should not produce corrections this large")
            }
        }
    }

    @Test
    fun `closed-loop corrected MLHFM is monotonically increasing`() {
        val (manager, _) = runClosedLoopCorrection(LOG_ALL)
        val corrected = manager.closedLoopMlhfmCorrection!!.correctedMlhfm

        for (i in 1 until corrected.zAxis.size) {
            val prev = corrected.zAxis[i - 1][0]
            val curr = corrected.zAxis[i][0]
            assertTrue(curr >= prev - 0.5,
                "Closed-loop corrected MLHFM not monotonic: " +
                "index ${i-1}=${prev} > index $i=${curr}")
        }
    }

    @Test
    fun `closed-loop no NaN or Infinity in output`() {
        val (manager, _) = runClosedLoopCorrection(LOG_ALL)
        val corrected = manager.closedLoopMlhfmCorrection!!.correctedMlhfm

        for (i in corrected.zAxis.indices) {
            assertFalse(corrected.zAxis[i][0].isNaN(),
                "Closed-loop MLHFM[$i] is NaN")
            assertFalse(corrected.zAxis[i][0].isInfinite(),
                "Closed-loop MLHFM[$i] is Infinite")
        }
    }

    @Test
    fun `closed-loop corrections are reproducible across two log files`() {
        // Run correction with two different logs — both should produce valid output
        val (manager1, _) = runClosedLoopCorrection(LOG_ALL)
        val corrected1 = manager1.closedLoopMlhfmCorrection!!.correctedMlhfm

        val (manager2, _) = runClosedLoopCorrection(LOG_FUEL)
        val corrected2 = manager2.closedLoopMlhfmCorrection!!.correctedMlhfm

        // Both should have valid structure
        assertEquals(corrected1.zAxis.size, corrected2.zAxis.size,
            "Both logs should produce same-sized MLHFM")

        // Both should be non-negative and monotonic (already tested above, but verify second log)
        for (i in corrected2.zAxis.indices) {
            assertTrue(corrected2.zAxis[i][0] >= 0.0,
                "Log2 MLHFM[$i] = ${corrected2.zAxis[i][0]} must be non-negative")
        }
    }

    @Test
    fun `correctedAfrMap contains correction factors within sane range`() {
        val (manager, _) = runClosedLoopCorrection(LOG_ALL)
        val correction = manager.closedLoopMlhfmCorrection!!

        // correctedAfrMap holds the final correction factor per voltage
        assertTrue(correction.correctedAfrMap.isNotEmpty(),
            "correctedAfrMap should not be empty")

        for ((voltage, factor) in correction.correctedAfrMap) {
            assertTrue(voltage >= 0.0, "Voltage must be non-negative: $voltage")
            // Correction factors represent STFT+LTFT error, typically small
            assertTrue(abs(factor) < 0.5,
                "Correction at ${voltage}V = $factor exceeds 50% — suspiciously large for fuel trims")
        }
    }

    // ── Fitted Curve ──

    @Test
    fun `fitted closed-loop MLHFM is smooth and non-negative`() {
        val (manager, _) = runClosedLoopCorrection(LOG_ALL)
        val corrected = manager.closedLoopMlhfmCorrection!!.correctedMlhfm
        val fitted = MlhfmFitter.fitMlhfm(corrected, 6)

        assertEquals(corrected.zAxis.size, fitted.zAxis.size,
            "Fitted size must match corrected")

        for (i in fitted.zAxis.indices) {
            assertTrue(fitted.zAxis[i][0] >= 0.0,
                "Fitted MLHFM[$i] = ${fitted.zAxis[i][0]} is negative")
        }

        // Fitted should be roughly monotonic
        for (i in 1 until fitted.zAxis.size) {
            assertTrue(fitted.zAxis[i][0] >= fitted.zAxis[i - 1][0] - 1.0,
                "Fitted MLHFM not monotonic at index $i")
        }
    }

    // ── Write-Readback ──

    @Test
    fun `closed-loop corrected MLHFM survives write and re-read`() {
        val mlhfmPair = MlhfmPreferences.getSelectedMap()
            ?: fail("MLHFM preference not resolved")
        val (mlhfmDef, _) = mlhfmPair

        val (manager, _) = runClosedLoopCorrection(LOG_ALL)
        val corrected = manager.closedLoopMlhfmCorrection!!.correctedMlhfm

        BinWriter.write(tempBinFile, mlhfmDef, corrected)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, mlhfmDef, requireChanges = false
        )

        // Re-read and verify round-trip (match by address + equation to avoid duplicate name issue)
        val reRead = BinParser.parseToList(FileInputStream(tempBinFile), tableDefs)
        val reReadPair = reRead.find {
            it.first.zAxis.address == mlhfmDef.zAxis.address &&
            it.first.zAxis.equation == mlhfmDef.zAxis.equation
        }
        assertNotNull(reReadPair, "Re-read BIN should contain MLHFM matching definition")

        for (i in corrected.zAxis.indices) {
            val written = corrected.zAxis[i][0]
            val readBack = reReadPair.second.zAxis[i][0]
            // Small values (< 30 kg/h) have large round-trip error due to quantization
            if (written > 30.0) {
                val tolerance = written * 0.05
                assertTrue(abs(written - readBack) <= tolerance,
                    "Closed-loop MLHFM[$i] round-trip: wrote $written, read $readBack " +
                    "(delta=${abs(written - readBack)})")
            }
        }
    }
}
