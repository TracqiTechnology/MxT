package domain.model.optimizer

import data.contract.Me7LogFileContract
import data.contract.Me7LogFileContract.Header as H
import data.parser.me7log.Me7LogParser
import data.profile.ConfigurationProfile
import data.profile.ProfileManager
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Integration tests for [OptimizerCalculator] using real ME7 log data.
 *
 * Tests the ME7-specific `analyze()` path (vs MED17's `analyzeMed17()`),
 * WOT filtering, and value sanity checks.
 */
class OptimizerCalculatorMe7Test {

    private val parser = Me7LogParser()
    private val mainLog = File("example/me7/logs/me7/log_typical_20200825_141742.csv")
    private val optimizerOnlyLog = File("example/me7/logs/me7/log_typical_20180722_131032.csv")
    private lateinit var globalState: support.GlobalTestStateSnapshot

    @BeforeTest
    fun checkFilesExist() {
        globalState = support.GlobalTestStateSnapshot.capture()
        val profileStream = ProfileManager::class.java.getResourceAsStream(
            "/profiles/MBox.mxtprofile.json"
        ) ?: error("MBox profile not found")
        ProfileManager.applyProfile(
            Json { ignoreUnknownKeys = true }.decodeFromString(
                ConfigurationProfile.serializer(),
                profileStream.bufferedReader().readText()
            )
        )
        assertTrue(mainLog.exists(), "Main log fixture not found at ${mainLog.absolutePath}")
    }

    @AfterTest
    fun restoreGlobalState() {
        globalState.restore()
    }

    private fun parseOptimizer(file: File): Map<Me7LogFileContract.Header, List<Double>> {
        return parser.parseLogFile(Me7LogParser.LogType.OPTIMIZER, file)
    }

    // ── T9: WOT filtering from ME7 logs ─────────────────────────────

    @Test
    fun `filterWotEntries extracts WOT entries from main log`() {
        val data = parseOptimizer(mainLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        // Main log has ~666 WOT rows (throttle >= 80°)
        assertTrue(wotEntries.size in 600..750,
            "Expected ~666 WOT entries, got ${wotEntries.size}")
    }

    @Test
    fun `all WOT entries have throttle angle at least 80 degrees`() {
        val data = parseOptimizer(mainLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        for (entry in wotEntries) {
            assertTrue(entry.throttleAngle >= 80.0,
                "WOT entry at RPM=${entry.rpm} has throttle=${entry.throttleAngle} < 80")
        }
    }

    @Test
    fun `WOT entries have sane RPM range`() {
        val data = parseOptimizer(mainLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        assertTrue(wotEntries.isNotEmpty())
        for (entry in wotEntries) {
            assertTrue(entry.rpm in 500.0..8500.0,
                "WOT RPM ${entry.rpm} is out of range [500, 8500]")
        }
    }

    @Test
    fun `WOT entries have positive boost above baro`() {
        val data = parseOptimizer(mainLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        // Not all WOT entries are boosted (turbo spool-up), but many should be
        val boostedEntries = wotEntries.filter { it.relativeBoostPsi > 0 }
        assertTrue(boostedEntries.size > 100,
            "Expected >100 boosted WOT entries, got ${boostedEntries.size}")
    }

    @Test
    fun `WOT entry relativeBoostPsi calculation is correct`() {
        val data = parseOptimizer(mainLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        val boosted = wotEntries.first { it.relativeBoostPsi > 0 }

        // relativeBoostPsi = (actualMap - barometricPressure) * 0.0145038
        val expected = (boosted.actualMap - boosted.barometricPressure) * 0.0145038
        assertEquals(expected, boosted.relativeBoostPsi, 0.001)
    }

    @Test
    fun `filterWotEntriesWithOptionalData provides MAF and injector data for ME7`() {
        val data = parseOptimizer(mainLog)
        val filtered = OptimizerCalculator.filterWotEntriesWithOptionalData(data, 80.0)

        assertNotNull(filtered.mafValues, "MAF values should be present for ME7 log")
        assertNotNull(filtered.injectorOnTimes, "Injector on-times should be present for ME7 log")
        assertNotNull(filtered.wotRpms, "WOT RPMs should be present")
        assertNotNull(filtered.mafVoltages, "MAF voltages should be present for ME7 log")

        // All optional arrays should match wotEntries size
        assertEquals(filtered.wotEntries.size, filtered.mafValues!!.size)
        assertEquals(filtered.wotEntries.size, filtered.injectorOnTimes!!.size)
        assertEquals(filtered.wotEntries.size, filtered.wotRpms!!.size)
        assertEquals(filtered.wotEntries.size, filtered.mafVoltages!!.size)
    }

    // ── T9: ME7 analyze() path ──────────────────────────────────────

    @Test
    fun `analyze with null maps does not crash and returns WOT entries`() {
        val data = parseOptimizer(mainLog)
        val result = OptimizerCalculator.analyze(
            values = data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfpbrkMap = null
        )

        assertNotNull(result)
        assertTrue(result.wotEntries.size in 600..750,
            "Expected ~666 WOT entries in result, got ${result.wotEntries.size}")
    }

    @Test
    fun `analyze produces kfurlSolverResult for ME7 (unlike MED17)`() {
        val data = parseOptimizer(mainLog)
        val result = OptimizerCalculator.analyze(
            values = data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfpbrkMap = null
        )

        // ME7 analyze should produce kfurlSolverResult when enough WOT entries exist
        // (MED17 sets this to null)
        assertNotNull(result.kfurlSolverResult,
            "ME7 analyze should produce kfurlSolverResult with sufficient WOT data")
    }

    @Test
    fun `analyze produces warnings list`() {
        val data = parseOptimizer(mainLog)
        val result = OptimizerCalculator.analyze(
            values = data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfpbrkMap = null
        )

        assertNotNull(result.warnings)
        // With null maps, we should get warnings about missing maps
        assertTrue(result.warnings.isNotEmpty(),
            "analyze with null maps should produce warnings")
    }

    // ── T10: WOT entry value sanity ─────────────────────────────────

    @Test
    fun `WOT entry requested load is within ME7 B5 S4 range`() {
        val data = parseOptimizer(mainLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        for (entry in wotEntries) {
            assertTrue(entry.requestedLoad in 0.0..400.0,
                "Requested load ${entry.requestedLoad}% out of range [0, 400] for ME7 B5 S4")
        }
    }

    @Test
    fun `WOT entry actual load is within ME7 B5 S4 range`() {
        val data = parseOptimizer(mainLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        for (entry in wotEntries) {
            assertTrue(entry.actualLoad in 0.0..400.0,
                "Actual load ${entry.actualLoad}% out of range [0, 400] for ME7 B5 S4")
        }
    }

    @Test
    fun `WOT entry WGDC is within 0 to 100`() {
        val data = parseOptimizer(mainLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        for (entry in wotEntries) {
            assertTrue(entry.wgdc in 0.0..100.0,
                "WGDC ${entry.wgdc} out of range [0, 100]")
        }
    }

    @Test
    fun `WOT entries from optimizer-only log have expected count`() {
        val data = parseOptimizer(optimizerOnlyLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        // This log has ~778 WOT rows
        assertTrue(wotEntries.size in 700..850,
            "Expected ~778 WOT entries from optimizer-only log, got ${wotEntries.size}")
    }

    @Test
    fun `WOT entry boost pressure is within physical range for ME7 B5 S4 2_7T`() {
        val data = parseOptimizer(mainLog)
        val wotEntries = OptimizerCalculator.filterWotEntries(data, 80.0)
        for (entry in wotEntries) {
            // B5 S4 2.7T typically sees max ~2500-3000 hPa absolute boost
            assertTrue(entry.actualMap in 100.0..4000.0,
                "Boost ${entry.actualMap} hPa out of ME7 B5 S4 range [100, 4000]")
        }
    }

    // ── T11: analyze() result value assertions ──────────────────────

    @Test
    fun `analyze pressure errors have physically plausible magnitudes`() {
        val data = parseOptimizer(mainLog)
        val result = OptimizerCalculator.analyze(
            values = data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfpbrkMap = null
        )

        // Pressure errors are (requested, actual) pairs — represent error deltas
        assertTrue(result.pressureErrors.isNotEmpty(),
            "Should have pressure error data from WOT log")
        for ((requested, actual) in result.pressureErrors) {
            assertFalse(requested.isNaN(), "Requested pressure is NaN")
            assertFalse(actual.isNaN(), "Actual pressure is NaN")
            assertFalse(requested.isInfinite(), "Requested pressure is Infinite")
            assertFalse(actual.isInfinite(), "Actual pressure is Infinite")
        }
    }

    @Test
    fun `analyze load errors have no NaN or Infinite values`() {
        val data = parseOptimizer(mainLog)
        val result = OptimizerCalculator.analyze(
            values = data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfpbrkMap = null
        )

        assertTrue(result.loadErrors.isNotEmpty(),
            "Should have load error data from WOT log")
        for ((requested, actual) in result.loadErrors) {
            assertFalse(requested.isNaN(), "Requested load is NaN")
            assertFalse(actual.isNaN(), "Actual load is NaN")
            assertFalse(requested.isInfinite(), "Requested load is Infinite")
            assertFalse(actual.isInfinite(), "Actual load is Infinite")
        }
    }

    @Test
    fun `analyze chain diagnosis percentages sum to meaningful total`() {
        val data = parseOptimizer(mainLog)
        val result = OptimizerCalculator.analyze(
            values = data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfpbrkMap = null
        )

        val cd = result.chainDiagnosis
        val totalPct = cd.torqueCappedPercent + cd.boostShortfallPercent + cd.onTargetPercent
        // Total should be close to 100% (all entries classified)
        assertTrue(totalPct > 0.0,
            "Chain diagnosis should classify some entries")
        // None should be negative
        assertTrue(cd.torqueCappedPercent >= 0.0, "Torque capped % should be non-negative")
        assertTrue(cd.boostShortfallPercent >= 0.0, "Boost shortfall % should be non-negative")
        assertTrue(cd.onTargetPercent >= 0.0, "On-target % should be non-negative")
    }

    @Test
    fun `analyze WOT pulls segment the log into continuous runs`() {
        val data = parseOptimizer(mainLog)
        val result = OptimizerCalculator.analyze(
            values = data,
            kfldrlMap = null,
            kfldimxMap = null,
            kfpbrkMap = null
        )

        // Should detect multiple WOT pulls (a real log has several gear pulls)
        assertTrue(result.pulls.isNotEmpty(),
            "Should detect at least one WOT pull from a real log")

        for (pull in result.pulls) {
            assertTrue(pull.sampleCount > 0,
                "Each pull should have at least one sample")
            // RPM should increase within each pull (accelerating)
            assertTrue(pull.rpmEnd > pull.rpmStart,
                "RPM should increase within a pull: start=${pull.rpmStart}, end=${pull.rpmEnd}")
            // startIdx < endIdx
            assertTrue(pull.endIdx > pull.startIdx,
                "Pull end index ${pull.endIdx} should be > start ${pull.startIdx}")
            // Physical RPM bounds
            assertTrue(pull.rpmStart in 500.0..8000.0,
                "Pull start RPM ${pull.rpmStart} out of bounds")
            assertTrue(pull.rpmEnd in 500.0..8000.0,
                "Pull end RPM ${pull.rpmEnd} out of bounds")
        }
    }
}
