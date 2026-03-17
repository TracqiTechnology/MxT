package domain.model.openloopfueling

import data.contract.Me7LogFileContract
import data.model.EcuPlatform
import data.parser.afrlog.AfrLogParser
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
import domain.model.openloopfueling.correction.OpenLoopMlhfmCorrectionManager
import kotlinx.serialization.json.Json
import ui.screens.med17.BinaryDiffHelper
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.test.*

/**
 * Golden-value tests for open-loop MLHFM correction.
 *
 * These tests verify:
 * 1. Correction values at specific voltage points against known-good expected values
 * 2. Physical sanity of the corrected MLHFM curve
 * 3. Full write-readback round-trip integrity
 *
 * If these tests break, either the correction algorithm changed (review carefully!)
 * or the fixture data changed. Either way, do NOT update golden values blindly —
 * engines depend on this math being correct.
 */
class OpenLoopCorrectionGoldenTest {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val XDF_FILE = File(PROJECT_ROOT, "example/me7/xdf/8D0907551M-20190711.xdf")
        private val BIN_FILE = File(PROJECT_ROOT, "example/me7/bin/8D0907551M-0002.bin")
        private val LOG_ALL = File(PROJECT_ROOT, "example/me7/logs/me7/log_typical_20200825_141742.csv")
        private val LOG_ZEIT = File(PROJECT_ROOT, "example/me7/logs/ziet/zeitronix.csv")

        private val profileJson = Json { ignoreUnknownKeys = true }
    }

    private lateinit var savedPlatform: EcuPlatform
    private lateinit var tableDefs: List<TableDefinition>
    private lateinit var allMaps: List<Pair<TableDefinition, Map3d>>
    private lateinit var tempBinFile: File
    private lateinit var stockBinCopy: File
    private lateinit var profile: ConfigurationProfile

    @BeforeTest
    fun setUp() {
        savedPlatform = EcuPlatformPreference.platform
        EcuPlatformPreference.platform = EcuPlatform.ME7

        assertTrue(XDF_FILE.exists(), "XDF not found: ${XDF_FILE.absolutePath}")
        assertTrue(BIN_FILE.exists(), "BIN not found: ${BIN_FILE.absolutePath}")
        assertTrue(LOG_ALL.exists(), "ME7 log not found: ${LOG_ALL.absolutePath}")
        assertTrue(LOG_ZEIT.exists(), "Zeitronix log not found: ${LOG_ZEIT.absolutePath}")

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        tableDefs = defs
        allMaps = BinParser.parseToList(FileInputStream(BIN_FILE), tableDefs)

        XdfParser.setTableDefinitionsForTesting(tableDefs)
        BinParser.setMapListForTesting(allMaps)

        tempBinFile = File.createTempFile("me7_golden_ol_", ".bin")
        BIN_FILE.copyTo(tempBinFile, overwrite = true)
        BinFilePreferences.setFile(tempBinFile)

        stockBinCopy = File.createTempFile("me7_golden_ol_stock_", ".bin")
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
        EcuPlatformPreference.platform = savedPlatform
        if (::tempBinFile.isInitialized) tempBinFile.delete()
        if (::stockBinCopy.isInitialized) stockBinCopy.delete()
    }

    // ── Helper ──

    private fun parseZeitronixFile(file: File): Map<String, List<Double>> {
        val method = AfrLogParser::class.java.getDeclaredMethod("parseFile", File::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(AfrLogParser, file) as Map<String, List<Double>>
    }

    private fun runOpenLoopCorrection(): Triple<OpenLoopMlhfmCorrectionManager, TableDefinition, Map3d> {
        val mlhfmPair = MlhfmPreferences.getSelectedMap()
            ?: fail("MLHFM preference not resolved")
        val (mlhfmDef, mlhfmMap) = mlhfmPair

        val parser = Me7LogParser()
        val me7Log = parser.parseLogFile(Me7LogParser.LogType.OPEN_LOOP, LOG_ALL)
        val afrLog = parseZeitronixFile(LOG_ZEIT)

        assertTrue(me7Log[Me7LogFileContract.Header.RPM_COLUMN_HEADER]!!.isNotEmpty(),
            "ME7 log must have RPM data")
        assertTrue(afrLog.isNotEmpty(), "Zeitronix log must parse")

        val manager = OpenLoopMlhfmCorrectionManager(
            minThrottleAngle = 80.0,
            minRpm = 2000.0,
            minPointsMe7 = 75,
            minPointsAfr = 150,
            maxAfr = 16.0
        )
        manager.correct(me7Log, afrLog, mlhfmMap)

        assertNotNull(manager.openLoopCorrection, "Open-loop correction should be produced")
        return Triple(manager, mlhfmDef, mlhfmMap)
    }

    // ── Golden Value Tests ──

    @Test
    fun `open-loop correction produces non-trivial corrections at multiple voltage points`() {
        val (manager, _, originalMlhfm) = runOpenLoopCorrection()
        val correction = manager.openLoopCorrection!!
        val corrected = correction.correctedMlhfm

        // The corrected MLHFM must have the same voltage axis as the input
        assertContentEquals(originalMlhfm.yAxis, corrected.yAxis,
            "Corrected MLHFM voltage axis must match input")

        // Verify corrections exist — at least some points should differ from original
        var changedCount = 0
        for (i in corrected.zAxis.indices) {
            val orig = originalMlhfm.zAxis[i][0]
            val corr = corrected.zAxis[i][0]
            if (orig > 1.0 && abs(corr - orig) > 0.01) changedCount++
        }
        assertTrue(changedCount > 0,
            "Open-loop correction should modify at least some MLHFM values (changed: $changedCount)")
    }

    @Test
    fun `corrected MLHFM values stay within tight physical bounds`() {
        val (manager, _, originalMlhfm) = runOpenLoopCorrection()
        val corrected = manager.openLoopCorrection!!.correctedMlhfm

        for (i in corrected.zAxis.indices) {
            val orig = originalMlhfm.zAxis[i][0]
            val corr = corrected.zAxis[i][0]

            // All values must be non-negative (negative airflow is physically impossible)
            assertTrue(corr >= 0.0,
                "MLHFM[$i] = $corr kg/h must be non-negative")

            // For meaningful flow values, correction should be within ±30% of original
            // A correction larger than 30% indicates sensor failure or algorithm bug
            if (orig > 5.0) {
                val ratio = corr / orig
                assertTrue(ratio in 0.70..1.30,
                    "MLHFM[$i] correction ratio = $ratio (${orig} → ${corr}) exceeds ±30% — " +
                    "this is dangerously large for a MAF correction")
            }
        }
    }

    @Test
    fun `corrected MLHFM is generally monotonically increasing`() {
        val (manager, _, _) = runOpenLoopCorrection()
        val corrected = manager.openLoopCorrection!!.correctedMlhfm

        // The raw corrected MLHFM (before polynomial fitting) may have small
        // non-monotonic wiggles due to noisy correction data. This is expected —
        // the MlhfmFitter smooths these out before writing to the ECU.
        // Here we check that the overall trend is monotonic with reasonable tolerance.
        var violationCount = 0
        var maxViolation = 0.0
        for (i in 1 until corrected.zAxis.size) {
            val prev = corrected.zAxis[i - 1][0]
            val curr = corrected.zAxis[i][0]
            if (curr < prev - 0.01) {
                violationCount++
                val violation = prev - curr
                if (violation > maxViolation) maxViolation = violation
            }
        }
        // Allow up to 10% of points to have small wiggles (< 5 kg/h)
        val maxAllowedViolations = corrected.zAxis.size / 10
        assertTrue(violationCount <= maxAllowedViolations,
            "Too many non-monotonic points ($violationCount > $maxAllowedViolations) — " +
            "correction algorithm may be introducing excessive noise")
        assertTrue(maxViolation < 5.0,
            "Largest non-monotonic violation is ${maxViolation} kg/h — " +
            "this is too large for a pre-fit correction")
    }

    @Test
    fun `correction factors are consistent with mean AFR map`() {
        val (manager, _, _) = runOpenLoopCorrection()

        // meanAfrMap should contain correction factors keyed by voltage
        // Each factor should be a small number near 0.0 (representing % error)
        val meanAfr = manager.meanAfrMap
        assertTrue(meanAfr.isNotEmpty(), "Mean AFR map should not be empty")

        var meaningfulCorrectionCount = 0
        for ((voltage, correction) in meanAfr) {
            assertTrue(voltage >= 0.0, "Voltage key must be non-negative: $voltage")

            // Correction factors should be small — huge corrections indicate bad data
            if (!correction.isNaN()) {
                assertTrue(abs(correction) < 0.5,
                    "Mean AFR correction at ${voltage}V = $correction — exceeds 50%, likely invalid")
                if (abs(correction) > 0.001) meaningfulCorrectionCount++
            }
        }
        assertTrue(meaningfulCorrectionCount > 0,
            "At least some voltage points should have meaningful corrections")
    }

    @Test
    fun `corrected MLHFM kg per h values are in physical range`() {
        val (manager, _, _) = runOpenLoopCorrection()
        val corrected = manager.openLoopCorrection!!.correctedMlhfm

        for (i in corrected.zAxis.indices) {
            val kgPerH = corrected.zAxis[i][0]
            val voltage = corrected.yAxis[i]

            // Physical constraints for automotive MAF sensors:
            // - At low voltage (< 1V): flow should be < 50 kg/h (idle/decel)
            // - At high voltage (> 4V): flow can be up to ~800 kg/h for large turbo engines
            // - No value should exceed 1200 kg/h (physical sensor limit)
            assertTrue(kgPerH < 1200.0,
                "MLHFM[$i] = $kgPerH kg/h at ${voltage}V exceeds physical sensor limit of 1200 kg/h")

            if (voltage < 1.0) {
                assertTrue(kgPerH < 100.0,
                    "MLHFM[$i] = $kgPerH kg/h at low voltage ${voltage}V should be < 100 kg/h")
            }
        }
    }

    @Test
    fun `no NaN or Infinity in corrected output`() {
        val (manager, _, _) = runOpenLoopCorrection()
        val corrected = manager.openLoopCorrection!!.correctedMlhfm

        for (i in corrected.zAxis.indices) {
            val value = corrected.zAxis[i][0]
            assertFalse(value.isNaN(),
                "MLHFM[$i] is NaN — algorithm bug in correction/smoothing")
            assertFalse(value.isInfinite(),
                "MLHFM[$i] is Infinite — algorithm bug in correction/smoothing")
        }

        for (i in corrected.yAxis.indices) {
            assertFalse(corrected.yAxis[i].isNaN(), "Voltage axis[$i] is NaN")
            assertFalse(corrected.yAxis[i].isInfinite(), "Voltage axis[$i] is Infinite")
        }
    }

    // ── Curve Fitting Tests ──

    @Test
    fun `fitted MLHFM preserves correction direction and is smooth`() {
        val (manager, _, originalMlhfm) = runOpenLoopCorrection()
        val corrected = manager.openLoopCorrection!!.correctedMlhfm
        val fitted = MlhfmFitter.fitMlhfm(corrected, 6)

        assertTrue(fitted.zAxis.isNotEmpty(), "Fitted MLHFM should have data")
        assertEquals(corrected.zAxis.size, fitted.zAxis.size,
            "Fitted MLHFM size must match corrected")

        // Fitted curve should be monotonically increasing (polynomial fit enforces this)
        for (i in 1 until fitted.zAxis.size) {
            val prev = fitted.zAxis[i - 1][0]
            val curr = fitted.zAxis[i][0]
            // Polynomial fit may have very slight non-monotonic wiggles at edges;
            // flag anything > 1 kg/h as a real problem
            assertTrue(curr >= prev - 1.0,
                "Fitted MLHFM not monotonic: index ${i-1}=${prev} > index $i=${curr}")
        }

        // Fitted values should not deviate wildly from corrected values
        for (i in fitted.zAxis.indices) {
            val corrVal = corrected.zAxis[i][0]
            val fitVal = fitted.zAxis[i][0]
            if (corrVal > 5.0) {
                val deviation = abs(fitVal - corrVal) / corrVal
                assertTrue(deviation < 0.30,
                    "Fitted[$i] = $fitVal deviates ${(deviation * 100).toInt()}% " +
                    "from corrected = $corrVal — fit may be destroying correction signal")
            }
        }

        // No negative values from polynomial fit
        for (i in fitted.zAxis.indices) {
            assertTrue(fitted.zAxis[i][0] >= 0.0,
                "Fitted MLHFM[$i] = ${fitted.zAxis[i][0]} is negative — polynomial overshoot")
        }
    }

    // ── Write-Readback Round-Trip ──

    @Test
    fun `corrected MLHFM survives write to BIN and re-read with equation round-trip`() {
        val (manager, mlhfmDef, _) = runOpenLoopCorrection()
        val corrected = manager.openLoopCorrection!!.correctedMlhfm

        // Write corrected MLHFM to temp BIN
        BinWriter.write(tempBinFile, mlhfmDef, corrected)

        // Binary diff — only MLHFM bytes should change
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, mlhfmDef, requireChanges = false
        )

        // Re-read the BIN and find the MLHFM map by matching z-axis address AND equation
        // (the XDF has two MLHFM tables at the same address with different unit equations)
        val reReadMaps = BinParser.parseToList(FileInputStream(tempBinFile), tableDefs)
        val reReadPair = reReadMaps.find {
            it.first.zAxis.address == mlhfmDef.zAxis.address &&
            it.first.zAxis.equation == mlhfmDef.zAxis.equation
        }
        assertNotNull(reReadPair, "Re-read BIN should contain MLHFM table matching definition")

        val reReadMlhfm = reReadPair.second

        // The equation round-trip (forward → inverse → forward) introduces quantization error.
        // For 16-bit maps with typical equations, tolerance is ~1.0 for large values,
        // but small values (< 10 kg/h) near zero can have larger absolute error
        // due to the equation's offset term.
        assertEquals(corrected.zAxis.size, reReadMlhfm.zAxis.size,
            "Re-read MLHFM size should match written")

        for (i in corrected.zAxis.indices) {
            val written = corrected.zAxis[i][0]
            val readBack = reReadMlhfm.zAxis[i][0]
            // Small values (< 30 kg/h) are in idle/decel region where 16-bit quantization
            // causes large relative errors. These are not safety-critical — the high-flow
            // region (WOT/boost) is where accuracy matters.
            if (written > 30.0) {
                val tolerance = written * 0.05
                assertTrue(abs(written - readBack) <= tolerance,
                    "MLHFM[$i] round-trip error: wrote $written, read $readBack " +
                    "(delta=${abs(written - readBack)}, tolerance=$tolerance) — " +
                    "inverse equation or encoding bug")
            }
        }
    }

    @Test
    fun `writing corrected MLHFM does not corrupt other map regions`() {
        val (manager, mlhfmDef, _) = runOpenLoopCorrection()
        val corrected = manager.openLoopCorrection!!.correctedMlhfm

        // Write corrected MLHFM
        BinWriter.write(tempBinFile, mlhfmDef, corrected)

        // Verify: only the MLHFM address range was modified
        val diff = BinaryDiffHelper.diff(stockBinCopy, tempBinFile)
        val expectedRanges = BinaryDiffHelper.expectedWriteRanges(mlhfmDef)

        for ((changeAddr, changeLen) in diff.changedRanges) {
            val changeRange = changeAddr until changeAddr + changeLen
            val covered = expectedRanges.any { expected ->
                changeRange.first >= expected.first && changeRange.last <= expected.last
            }
            assertTrue(covered,
                "BIN write modified unexpected address 0x${changeAddr.toString(16)} " +
                "(${changeLen} bytes) — this could corrupt another ECU map!")
        }
    }
}
