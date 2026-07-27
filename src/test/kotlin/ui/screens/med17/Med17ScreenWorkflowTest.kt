package ui.screens.med17

import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.bin.BinFilePreferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import data.preferences.kfzw.KfzwPreferences
import data.preferences.kfzw.KfzwSwitchMapPreferences
import data.preferences.kfzwop.KfzwopPreferences
import data.preferences.krkte.KrkteGdiPreferences
import data.preferences.krkte.KrktePfiPreferences
import data.preferences.platform.EcuPlatformPreference
import data.preferences.rkw.RkwPreferences
import data.profile.ConfigurationProfile
import data.profile.ProfileManager
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.krkte.KrkteCalculator
import domain.model.pfi.PfiShareCalculator
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import kotlin.test.*
import support.GlobalTestStateSnapshot

/**
 * MED17 screen-level calculation pipeline tests.
 *
 * Validates that domain calculators produce plausible values,
 * profile-driven map preferences resolve correctly from the
 * 404E XDF/BIN pair, and BinWriter identity writes produce
 * valid binary output with no stray byte mutations.
 */
class Med17ScreenWorkflowTest {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val XDF_FILE = File(PROJECT_ROOT, "example/med17/404E/404E_normal.xdf")
        private val BIN_FILE = File(PROJECT_ROOT, "example/med17/404E/MED17_1_62_STOCK.bin")
        private val profileJson = Json { ignoreUnknownKeys = true }
    }

    private lateinit var globalState: GlobalTestStateSnapshot
    private lateinit var tempBinFile: File
    private lateinit var stockBinCopy: File
    private lateinit var allMaps: List<Pair<TableDefinition, Map3d>>

    @BeforeTest
    fun setUp() {
        globalState = GlobalTestStateSnapshot.capture()
        EcuPlatformPreference.platform = EcuPlatform.MED17

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        allMaps = BinParser.parseToList(FileInputStream(BIN_FILE), defs)

        XdfParser.setTableDefinitionsForTesting(defs)
        BinParser.setMapListForTesting(allMaps)

        tempBinFile = File.createTempFile("med17_screen_", ".bin")
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
        if (::tempBinFile.isInitialized && tempBinFile.exists()) tempBinFile.delete()
        if (::stockBinCopy.isInitialized && stockBinCopy.exists()) stockBinCopy.delete()
        if (::globalState.isInitialized) globalState.restore()
    }

    private fun resetBin() {
        BIN_FILE.copyTo(tempBinFile, overwrite = true)
    }

    // ── 1. KRKTE Workflow ────────────────────────────────────────────────

    @Test
    fun `KRKTE PFI calculation produces plausible value for DNWA`() {
        // Profile: displacement=0.496, stoichAfr=14.7, gasDensity=0.755, injSize=220.0
        val krkte = KrkteCalculator.calculateKrkte(
            airDensityGramsPerDecimetersCubed = 1.293,
            cylinderDisplacementDecimetersCubed = 0.496,
            fuelInjectorSizeCubicCentimeters = 220.0,
            gasolineGramsPerCubicCentimeter = 0.755,
            stoichiometricAirFuelRatio = 14.7
        )
        assertTrue(krkte in 0.10..0.30, "PFI KRKTE=$krkte should be in 0.10-0.30 ms/%")
    }

    @Test
    fun `KRKTE GDI produces smaller value than PFI`() {
        val pfi = KrkteCalculator.calculateKrkte(1.293, 0.496, 220.0, 0.755, 14.7)
        val gdi = KrkteCalculator.calculateKrkte(1.293, 0.496, 380.0, 0.755, 14.7)
        assertTrue(gdi < pfi, "GDI KRKTE=$gdi should be < PFI KRKTE=$pfi (bigger injector)")
        assertTrue(gdi in 0.01..0.15, "GDI KRKTE=$gdi should be in 0.01-0.15 ms/%")
    }

    // ── 2. KFMIOP + KFMIRL Workflow ──────────────────────────────────────

    @Test
    fun `KFMIOP and KFMIRL preferences resolve from profile`() {
        val kfmiop = KfmiopPreferences.getSelectedMap()
        val kfmirl = KfmirlPreferences.getSelectedMap()
        assertNotNull(kfmiop, "KFMIOP should resolve from profile")
        assertNotNull(kfmirl, "KFMIRL should resolve from profile")
    }

    @Test
    fun `KFMIOP write produces valid binary output`() {
        resetBin()
        val kfmiopPair = KfmiopPreferences.getSelectedMap()
            ?: fail("KFMIOP not resolved from profile")
        // Identity write: write existing map data back
        BinWriter.write(tempBinFile, kfmiopPair.first, kfmiopPair.second)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, kfmiopPair.first, requireChanges = false
        )
    }

    @Test
    fun `KFMIRL write produces valid binary output`() {
        resetBin()
        val kfmirlPair = KfmirlPreferences.getSelectedMap()
            ?: fail("KFMIRL not resolved from profile")
        BinWriter.write(tempBinFile, kfmirlPair.first, kfmirlPair.second)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, kfmirlPair.first, requireChanges = false
        )
    }

    // ── 3. KFZWOP + KFZW Workflow ────────────────────────────────────────

    @Test
    fun `KFZWOP and KFZW preferences resolve from profile`() {
        val kfzwop = KfzwopPreferences.getSelectedMap()
        val kfzw = KfzwPreferences.getSelectedMap()
        assertNotNull(kfzwop, "KFZWOP should resolve from profile")
        assertNotNull(kfzw, "KFZW should resolve from profile")
    }

    @Test
    fun `KFZWOP timing values are within physical bounds`() {
        val kfzwopPair = KfzwopPreferences.getSelectedMap()
            ?: fail("KFZWOP not resolved from profile")
        for (row in kfzwopPair.second.zAxis) {
            for (v in row) {
                assertTrue(
                    v in -20.0..80.0,
                    "KFZWOP timing=$v should be in -20..80 grad KW"
                )
            }
        }
    }

    @Test
    fun `KFZW write produces valid binary output`() {
        resetBin()
        val kfzwPair = KfzwPreferences.getSelectedMap()
            ?: fail("KFZW not resolved from profile")
        BinWriter.write(tempBinFile, kfzwPair.first, kfzwPair.second)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, kfzwPair.first, requireChanges = false
        )
    }

    @Test
    fun `KFZW switch maps from profile resolve in XDF`() {
        val switchCount = KfzwSwitchMapPreferences.count
        assertTrue(
            switchCount >= 6,
            "Profile should load >= 6 KFZW switch maps, got $switchCount"
        )
        val switchMaps = KfzwSwitchMapPreferences.getAllSelectedMaps()
        for ((idx, pair) in switchMaps) {
            assertNotNull(pair, "Switch map $idx should resolve")
            val map = pair!!.second
            assertTrue(map.zAxis.isNotEmpty(), "Switch map $idx should have Z data")
        }
    }

    // ── 4. Dual Injection Workflow ───────────────────────────────────────

    @Test
    fun `KRKTE PFI and GDI preferences resolve from profile`() {
        val pfi = KrktePfiPreferences.getSelectedMap()
        val gdi = KrkteGdiPreferences.getSelectedMap()
        assertNotNull(pfi, "KRKTE_PFI should resolve from profile")
        assertNotNull(gdi, "KRKTE_GDI should resolve from profile")
    }

    @Test
    fun `PFI share calculator produces plausible default curve`() {
        val result = PfiShareCalculator.calculateRpmDependentShare()
        assertEquals(
            PfiShareCalculator.DEFAULT_RPM_AXIS.size,
            result.rpmAxis.size,
            "Default result should have same size as default RPM axis"
        )

        // Verify shares at key RPM points via interpolation from the result
        for (i in result.rpmAxis.indices) {
            val rpm = result.rpmAxis[i]
            val share = result.pfiSharePercent[i]
            assertTrue(
                share in 0.0..100.0,
                "PFI share at RPM=$rpm should be in 0-100%, got $share"
            )
        }

        // Check mid-range PFI share is higher than extremes (shape validation)
        val lowRpmShare = result.pfiSharePercent.first()
        val midIdx = result.rpmAxis.size / 2
        val midRpmShare = result.pfiSharePercent[midIdx]
        val highRpmShare = result.pfiSharePercent.last()
        assertTrue(
            midRpmShare > lowRpmShare,
            "Mid-range PFI share ($midRpmShare) should exceed low-RPM ($lowRpmShare)"
        )
        assertTrue(
            midRpmShare > highRpmShare,
            "Mid-range PFI share ($midRpmShare) should exceed high-RPM ($highRpmShare)"
        )
    }

    @Test
    fun `KRKTE PFI write produces valid binary output`() {
        resetBin()
        val pfiPair = KrktePfiPreferences.getSelectedMap()
            ?: fail("KRKTE_PFI not resolved from profile")
        BinWriter.write(tempBinFile, pfiPair.first, pfiPair.second)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, pfiPair.first, requireChanges = false
        )
    }

    // ── 5. RKW Workflow ──────────────────────────────────────────────────

    @Test
    fun `RKW default resolves to Gasoline 0 variant`() {
        val rkw = RkwPreferences.getSelectedMap()
        assertNotNull(rkw, "rk_w should resolve from profile")
        assertTrue(
            rkw.first.tableName.contains("Gasoline 0", ignoreCase = true),
            "Default rk_w should be Gasoline 0 variant, got: ${rkw.first.tableName}"
        )
    }

    @Test
    fun `RKW switch map table names all exist in XDF`() {
        // The profile defines 6 RKW_SWITCH variants — verify they all exist in the XDF
        val expectedNames = listOf(
            "Rel fuel mass fac inj type corr HO1 Gasoline 0",
            "Rel fuel mass fac inj type corr HO1 Ethanol 0",
            "Rel fuel mass fac inj type corr HO1 Gasoline 1",
            "Rel fuel mass fac inj type corr HO1 Ethanol 1",
            "Rel fuel mass fac inj type corr HO1 Gasoline 2",
            "Rel fuel mass fac inj type corr HO1 Ethanol 2"
        )
        for (name in expectedNames) {
            val found = allMaps.any { (def, _) ->
                def.tableName.contains(name, ignoreCase = true)
            }
            assertTrue(found, "XDF should contain rk_w variant: $name")
        }
    }
}
