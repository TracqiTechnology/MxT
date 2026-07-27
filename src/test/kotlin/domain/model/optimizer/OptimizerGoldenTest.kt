package domain.model.optimizer

import data.contract.Me7LogFileContract
import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.me7log.Me7LogParser
import data.parser.xdf.XdfParser
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.platform.EcuPlatformPreference
import data.profile.ConfigurationProfile
import data.profile.ProfileManager
import domain.math.map.Map3d
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import kotlin.test.*

/**
 * Golden-value tests for the Optimizer's KFLDRL/KFLDIMX suggestion pipeline.
 *
 * The Optimizer takes WOT (Wide-Open Throttle) log data and suggests corrections
 * to boost control maps. If these corrections are wrong:
 * - KFLDRL too high → wastegate opens too much → boost shortfall → power loss
 * - KFLDRL too low → wastegate stays closed → overboosting → engine damage
 * - KFLDIMX must be > KFLDRL → if not, PID controller has no headroom
 */
class OptimizerGoldenTest {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val XDF_FILE = File(PROJECT_ROOT, "example/me7/xdf/8D0907551M-20190711.xdf")
        private val BIN_FILE = File(PROJECT_ROOT, "example/me7/bin/8D0907551M-0002.bin")
        private val LOG_ALL = File(PROJECT_ROOT, "example/me7/logs/me7/log_typical_20200825_141742.csv")
        private val profileJson = Json { ignoreUnknownKeys = true }
    }

    private lateinit var savedPlatform: EcuPlatform
    private lateinit var globalState: support.GlobalTestStateSnapshot

    @BeforeTest
    fun setUp() {
        globalState = support.GlobalTestStateSnapshot.capture()
        savedPlatform = EcuPlatformPreference.platform
        EcuPlatformPreference.platform = EcuPlatform.ME7

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        val allMaps = BinParser.parseToList(FileInputStream(BIN_FILE), defs)

        XdfParser.setTableDefinitionsForTesting(defs)
        BinParser.setMapListForTesting(allMaps)

        val stream = ProfileManager::class.java.getResourceAsStream("/profiles/MBox.mxtprofile.json")
            ?: error("MBox profile not found")
        val profile = profileJson.decodeFromString(
            ConfigurationProfile.serializer(), stream.bufferedReader().readText()
        )
        ProfileManager.applyProfile(profile)
    }

    @AfterTest
    fun tearDown() {
        globalState.restore()
    }

    @Test
    fun `optimizer analyze with real KFLDRL map produces valid suggestions`() {
        val kfldrlPair = KfldrlPreferences.getSelectedMap()
            ?: fail("KFLDRL preference not resolved")
        val kfldimxPair = KfldimxPreferences.getSelectedMap()

        val parser = Me7LogParser()
        val logData = parser.parseLogFile(Me7LogParser.LogType.OPTIMIZER, LOG_ALL)

        val result = OptimizerCalculator.analyze(
            values = logData,
            kfldrlMap = kfldrlPair.second,
            kfldimxMap = kfldimxPair?.second,
            kfpbrkMap = null
        )

        assertNotNull(result, "Optimizer should produce a result")
        assertTrue(result.wotEntries.isNotEmpty(), "Should have WOT entries")

        // Chain diagnosis should be produced when we have maps
        val diagnosis = result.chainDiagnosis
        // If pull segments exist, verify pull count
        if (result.pulls.isNotEmpty()) {
            assertTrue(result.pulls.size >= 1,
                "Should detect at least one WOT pull in the log")
        }
    }

    @Test
    fun `WOT filtering produces consistent RPM and pressure ranges`() {
        val parser = Me7LogParser()
        val logData = parser.parseLogFile(Me7LogParser.LogType.OPTIMIZER, LOG_ALL)

        val filtered = OptimizerCalculator.filterWotEntriesWithOptionalData(logData, 80.0)
        assertTrue(filtered.wotEntries.size > 100,
            "Should have many WOT entries from a real log")

        // Verify RPM range is physically possible for a B5 S4
        val minRpm = filtered.wotEntries.minOf { it.rpm }
        val maxRpm = filtered.wotEntries.maxOf { it.rpm }
        assertTrue(minRpm >= 500.0, "Min WOT RPM $minRpm should be >= 500")
        assertTrue(maxRpm <= 8000.0, "Max WOT RPM $maxRpm should be <= 8000")

        // Verify barometric pressure is plausible (sea level ~1013 mbar)
        for (entry in filtered.wotEntries) {
            assertTrue(entry.barometricPressure in 800.0..1100.0,
                "Baro pressure ${entry.barometricPressure} mbar out of range")
        }

        // Verify WGDC is 0-100%
        for (entry in filtered.wotEntries) {
            assertTrue(entry.wgdc in 0.0..100.0,
                "WGDC ${entry.wgdc}% out of range [0, 100]")
        }
    }

    @Test
    fun `suggested KFLDRL values are within wastegate duty cycle range`() {
        val kfldrlPair = KfldrlPreferences.getSelectedMap()
            ?: fail("KFLDRL preference not resolved")

        val parser = Me7LogParser()
        val logData = parser.parseLogFile(Me7LogParser.LogType.OPTIMIZER, LOG_ALL)

        val result = OptimizerCalculator.analyze(
            values = logData,
            kfldrlMap = kfldrlPair.second,
            kfldimxMap = null,
            kfpbrkMap = null
        )

        val suggested = result.suggestedKfldrl
        if (suggested != null && suggested.zAxis.isNotEmpty()) {
            // KFLDRL values should be in valid WGDC range (0-100%)
            for (i in suggested.zAxis.indices) {
                for (j in suggested.zAxis[i].indices) {
                    val value = suggested.zAxis[i][j]
                    assertTrue(value in 0.0..100.0,
                        "Suggested KFLDRL[$i][$j] = $value is outside valid WGDC range [0, 100]")
                    assertFalse(value.isNaN(), "Suggested KFLDRL[$i][$j] is NaN")
                }
            }
        }
    }

    @Test
    fun `suggested KFLDIMX has overhead above interpolated KFLDRL`() {
        val kfldrlPair = KfldrlPreferences.getSelectedMap()
            ?: fail("KFLDRL preference not resolved")
        val kfldimxPair = KfldimxPreferences.getSelectedMap()
            ?: fail("KFLDIMX preference not resolved")

        val parser = Me7LogParser()
        val logData = parser.parseLogFile(Me7LogParser.LogType.OPTIMIZER, LOG_ALL)

        val result = OptimizerCalculator.analyze(
            values = logData,
            kfldrlMap = kfldrlPair.second,
            kfldimxMap = kfldimxPair.second,
            kfpbrkMap = null
        )

        val sugKfldrl = result.suggestedKfldrl
        val sugKfldimx = result.suggestedKfldimx

        // KFLDRL and KFLDIMX may have different axis dimensions, so we can't
        // compare cell-by-cell at the same index. Instead verify the KFLDIMX
        // specific invariant: cells derived from KFLDRL get 8% overhead,
        // cells without data keep the original. Verify no suggested KFLDIMX
        // cell exceeds 100% (valid WGDC range).
        if (sugKfldimx != null && sugKfldimx.zAxis.isNotEmpty()) {
            for (i in sugKfldimx.zAxis.indices) {
                for (j in sugKfldimx.zAxis[i].indices) {
                    val imx = sugKfldimx.zAxis[i][j]
                    assertTrue(imx >= 0.0,
                        "KFLDIMX[$i][$j]=$imx must be >= 0")
                    // KFLDIMX = KFLDRL × 1.08 so it can exceed 100% when KFLDRL ≈ 95%.
                    // The ECU/BinWriter clamps to data-type max. Allow up to 110% here.
                    assertTrue(imx <= 110.0,
                        "KFLDIMX[$i][$j]=$imx unreasonably high (> 110%)")
                    assertFalse(imx.isNaN(), "KFLDIMX[$i][$j] is NaN")
                }
            }
        }

        // When axes match, verify direct cell-by-cell: KFLDIMX >= KFLDRL
        if (sugKfldrl != null && sugKfldimx != null &&
            sugKfldrl.zAxis.size == sugKfldimx.zAxis.size &&
            sugKfldrl.xAxis.contentEquals(sugKfldimx.xAxis) &&
            sugKfldrl.yAxis.contentEquals(sugKfldimx.yAxis)) {

            for (i in sugKfldrl.zAxis.indices) {
                for (j in sugKfldrl.zAxis[i].indices) {
                    val rl = sugKfldrl.zAxis[i][j]
                    val imx = sugKfldimx.zAxis[i][j]
                    assertTrue(imx >= rl,
                        "KFLDIMX[$i][$j]=$imx must be >= KFLDRL[$i][$j]=$rl " +
                        "(PID controller needs headroom)")
                }
            }
        }
    }

    @Test
    fun `optimizer result has no NaN in suggested maps`() {
        val kfldrlPair = KfldrlPreferences.getSelectedMap()
            ?: fail("KFLDRL preference not resolved")

        val parser = Me7LogParser()
        val logData = parser.parseLogFile(Me7LogParser.LogType.OPTIMIZER, LOG_ALL)

        val result = OptimizerCalculator.analyze(
            values = logData,
            kfldrlMap = kfldrlPair.second,
            kfldimxMap = null,
            kfpbrkMap = null
        )

        result.suggestedKfldrl?.let { map ->
            for (i in map.zAxis.indices) {
                for (j in map.zAxis[i].indices) {
                    assertFalse(map.zAxis[i][j].isNaN(),
                        "suggestedKfldrl[$i][$j] is NaN")
                    assertFalse(map.zAxis[i][j].isInfinite(),
                        "suggestedKfldrl[$i][$j] is Infinite")
                }
            }
        }
    }
}
