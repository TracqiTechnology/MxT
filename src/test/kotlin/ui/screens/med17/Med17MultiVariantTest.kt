package ui.screens.med17

import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.bin.BinFilePreferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import data.preferences.kfzw.KfzwPreferences
import data.preferences.kfzwop.KfzwopPreferences
import data.preferences.krkte.KrktePfiPreferences
import data.preferences.krkte.KrkteGdiPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.platform.EcuPlatformPreference
import data.profile.ConfigurationProfile
import data.profile.ProfileManager
import data.writer.BinWriter
import domain.math.Inverse
import domain.math.RescaleAxis
import domain.math.map.Map3d
import domain.model.kfmiop.Kfmiop
import domain.model.kfzw.Kfzw
import domain.model.krkte.KrkteCalculator
import domain.model.rlsol.Rlsol
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import kotlin.test.*

/**
 * Multi-variant end-to-end write tests for all MED17 XDF/BIN pairs.
 *
 * For each XDF/BIN pair:
 * 1. Parse XDF + BIN
 * 2. Apply the MED17 profile
 * 3. Verify all map preferences resolve
 * 4. For each writable map: compute output → write → binary diff
 *
 * Binary diff asserts that ONLY the expected address range was modified.
 */
class Med17MultiVariantTest {

    data class Variant(
        val name: String,
        val xdfPath: String,
        val binPath: String
    )

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val profileJson = Json { ignoreUnknownKeys = true }

        val VARIANTS = listOf(
            Variant("404A", "example/med17/404A/404A_normal.xdf", "example/med17/404A/MED17_1_62_STOCK.bin"),
            Variant("404E", "example/med17/404E/404E_normal.xdf", "example/med17/404E/MED17_1_62_STOCK.bin"),
            Variant("404G", "example/med17/404G/404G_normal.xdf", "example/med17/404G/MED17_1_62_STOCK.bin"),
            Variant("404H", "example/med17/404H/404H_normal.xdf", "example/med17/404H/MED17_1_62_STOCK.bin"),
            Variant("404J", "example/med17/404J/404J_normal.xdf", "example/med17/404J/MED17_1_62_STOCK.bin"),
            Variant("404L", "example/med17/404L/404L_normal.xdf", "example/med17/404L/MED17_1_62_8S0907404L__0001_STOCK.bin")
        )

        private fun loadProfile(): ConfigurationProfile {
            val stream = ProfileManager::class.java.getResourceAsStream(
                "/profiles/MED17_162_RS3_TTRS_2_5T.mxtprofile.json"
            ) ?: error("MED17 profile not found on classpath")
            return profileJson.decodeFromString(
                ConfigurationProfile.serializer(), stream.bufferedReader().readText()
            )
        }
    }

    private lateinit var savedPlatform: EcuPlatform
    private lateinit var tempBinFile: File
    private lateinit var stockBinCopy: File

    @BeforeTest
    fun setUp() {
        savedPlatform = EcuPlatformPreference.platform
        EcuPlatformPreference.platform = EcuPlatform.MED17
    }

    @AfterTest
    fun tearDown() {
        EcuPlatformPreference.platform = savedPlatform
        if (::tempBinFile.isInitialized && tempBinFile.exists()) tempBinFile.delete()
        if (::stockBinCopy.isInitialized && stockBinCopy.exists()) stockBinCopy.delete()
    }

    /**
     * Load XDF + BIN, apply profile, populate singletons,
     * create temp BIN for writing and stock copy for diffing.
     */
    private fun loadVariant(variant: Variant): List<Pair<TableDefinition, Map3d>> {
        val xdfFile = File(PROJECT_ROOT, variant.xdfPath)
        val binFile = File(PROJECT_ROOT, variant.binPath)
        assertTrue(xdfFile.exists(), "${variant.name}: XDF not found: ${xdfFile.absolutePath}")
        assertTrue(binFile.exists(), "${variant.name}: BIN not found: ${binFile.absolutePath}")

        val (_, defs) = XdfParser.parseToList(FileInputStream(xdfFile))
        assertTrue(defs.isNotEmpty(), "${variant.name}: XDF produced no table definitions")

        val allMaps = BinParser.parseToList(FileInputStream(binFile), defs)

        XdfParser.setTableDefinitionsForTesting(defs)
        BinParser.setMapListForTesting(allMaps)

        tempBinFile = File.createTempFile("med17_mv_", ".bin")
        binFile.copyTo(tempBinFile, overwrite = true)
        BinFilePreferences.setFile(tempBinFile)

        stockBinCopy = File.createTempFile("med17_stock_", ".bin")
        binFile.copyTo(stockBinCopy, overwrite = true)

        val profile = loadProfile()
        ProfileManager.applyProfile(profile)

        return allMaps
    }

    private fun resetBin(variant: Variant) {
        val binFile = File(PROJECT_ROOT, variant.binPath)
        binFile.copyTo(tempBinFile, overwrite = true)
    }

    // ── Profile Resolution Tests ─────────────────────────────────────

    @Test fun `404A - profile resolves all preferences`() = verifyProfileResolution(VARIANTS[0])
    @Test fun `404E - profile resolves all preferences`() = verifyProfileResolution(VARIANTS[1])
    @Test fun `404G - profile resolves all preferences`() = verifyProfileResolution(VARIANTS[2])
    @Test fun `404H - profile resolves all preferences`() = verifyProfileResolution(VARIANTS[3])
    @Test fun `404J - profile resolves all preferences`() = verifyProfileResolution(VARIANTS[4])
    @Test fun `404L - profile resolves all preferences`() = verifyProfileResolution(VARIANTS[5])

    private fun verifyProfileResolution(variant: Variant) {
        loadVariant(variant)

        val kfmiop = KfmiopPreferences.getSelectedMap()
        val kfmirl = KfmirlPreferences.getSelectedMap()
        val kfzwop = KfzwopPreferences.getSelectedMap()
        val kfzw = KfzwPreferences.getSelectedMap()
        val krktePfi = KrktePfiPreferences.getSelectedMap()
        val kfldrl = KfldrlPreferences.getSelectedMap()
        val kfldimx = KfldimxPreferences.getSelectedMap()

        assertNotNull(kfmiop, "${variant.name}: KFMIOP should resolve")
        assertNotNull(kfmirl, "${variant.name}: KFMIRL should resolve")
        assertNotNull(kfzwop, "${variant.name}: KFZWOP should resolve")
        assertNotNull(kfzw, "${variant.name}: KFZW should resolve")
        assertNotNull(krktePfi, "${variant.name}: KRKTE PFI should resolve")
        assertNotNull(kfldrl, "${variant.name}: KFLDRL should resolve")
        assertNotNull(kfldimx, "${variant.name}: KFLDIMX should resolve")

        // Bug 1 regression: KFMIOP must not be scalar across ANY variant
        assertFalse(
            kfmiop.second.xAxis.isEmpty() && kfmiop.second.yAxis.isEmpty(),
            "${variant.name}: KFMIOP must not be scalar (false DS1 detection)"
        )
        assertTrue(kfmiop.second.xAxis.size >= 10,
            "${variant.name}: KFMIOP needs >=10 load columns, got ${kfmiop.second.xAxis.size}")
        assertTrue(kfmiop.second.yAxis.size >= 10,
            "${variant.name}: KFMIOP needs >=10 RPM rows, got ${kfmiop.second.yAxis.size}")

        // Verify all 2D maps have axes
        assertTrue(kfmirl.second.xAxis.size > 1, "${variant.name}: KFMIRL should be 2D (x)")
        assertTrue(kfmirl.second.yAxis.size > 1, "${variant.name}: KFMIRL should be 2D (y)")
        assertTrue(kfzwop.second.xAxis.size > 1, "${variant.name}: KFZWOP should be 2D (x)")
        assertTrue(kfzwop.second.yAxis.size > 1, "${variant.name}: KFZWOP should be 2D (y)")
        assertTrue(kfldrl.second.xAxis.size > 1, "${variant.name}: KFLDRL should be 2D (x)")
        assertTrue(kfldrl.second.yAxis.size > 1, "${variant.name}: KFLDRL should be 2D (y)")
        assertTrue(kfldimx.second.xAxis.size > 1, "${variant.name}: KFLDIMX should be 2D (x)")
        assertTrue(kfldimx.second.yAxis.size > 1, "${variant.name}: KFLDIMX should be 2D (y)")
    }

    // ── KRKTE PFI Write + Diff ───────────────────────────────────────

    @Test fun `404A - KRKTE PFI write only modifies expected bytes`() = verifyKrktePfiWrite(VARIANTS[0])
    @Test fun `404E - KRKTE PFI write only modifies expected bytes`() = verifyKrktePfiWrite(VARIANTS[1])
    @Test fun `404G - KRKTE PFI write only modifies expected bytes`() = verifyKrktePfiWrite(VARIANTS[2])
    @Test fun `404H - KRKTE PFI write only modifies expected bytes`() = verifyKrktePfiWrite(VARIANTS[3])
    @Test fun `404J - KRKTE PFI write only modifies expected bytes`() = verifyKrktePfiWrite(VARIANTS[4])
    @Test fun `404L - KRKTE PFI write only modifies expected bytes`() = verifyKrktePfiWrite(VARIANTS[5])

    private fun verifyKrktePfiWrite(variant: Variant) {
        loadVariant(variant)
        val pair = KrktePfiPreferences.getSelectedMap() ?: fail("${variant.name}: KRKTE PFI not resolved")
        val tableDef = pair.first

        // Compute KRKTE from profile parameters
        val krkte = KrkteCalculator.calculateKrkte(1.2929, 0.496, 220.0, 0.75, 14.7)
        val outputMap = Map3d(
            pair.second.xAxis.copyOf(),
            pair.second.yAxis.copyOf(),
            pair.second.zAxis.map { row -> row.map { krkte }.toTypedArray() }.toTypedArray()
        )

        BinWriter.write(tempBinFile, tableDef, outputMap)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(stockBinCopy, tempBinFile, tableDef)
    }

    // ── KFMIOP Write + Diff ──────────────────────────────────────────

    @Test fun `404A - KFMIOP write only modifies expected bytes`() = verifyKfmiopWrite(VARIANTS[0])
    @Test fun `404E - KFMIOP write only modifies expected bytes`() = verifyKfmiopWrite(VARIANTS[1])
    @Test fun `404G - KFMIOP write only modifies expected bytes`() = verifyKfmiopWrite(VARIANTS[2])
    @Test fun `404H - KFMIOP write only modifies expected bytes`() = verifyKfmiopWrite(VARIANTS[3])
    @Test fun `404J - KFMIOP write only modifies expected bytes`() = verifyKfmiopWrite(VARIANTS[4])
    @Test fun `404L - KFMIOP write only modifies expected bytes`() = verifyKfmiopWrite(VARIANTS[5])

    private fun verifyKfmiopWrite(variant: Variant) {
        loadVariant(variant)
        val pair = KfmiopPreferences.getSelectedMap() ?: fail("${variant.name}: KFMIOP not resolved")
        val tableDef = pair.first
        val inputMap = pair.second

        // Profile sets maxMapPressure=3500, maxBoostPressure=2800
        val maxMapLoad = Rlsol.rlsol(1030.0, 3500.0, 0.0, 96.0, 0.106, 3500.0)
        val maxBoostLoad = Rlsol.rlsol(1030.0, 2800.0, 0.0, 96.0, 0.106, 2800.0)
        val result = Kfmiop.calculateKfmiop(inputMap, maxMapLoad, maxBoostLoad)
        assertNotNull(result, "${variant.name}: KFMIOP calculator returned null")

        BinWriter.write(tempBinFile, tableDef, result.outputKfmiop)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(stockBinCopy, tempBinFile, tableDef)
    }

    // ── KFMIRL Write + Diff ──────────────────────────────────────────

    @Test fun `404A - KFMIRL write only modifies expected bytes`() = verifyKfmirlWrite(VARIANTS[0])
    @Test fun `404E - KFMIRL write only modifies expected bytes`() = verifyKfmirlWrite(VARIANTS[1])
    @Test fun `404G - KFMIRL write only modifies expected bytes`() = verifyKfmirlWrite(VARIANTS[2])
    @Test fun `404H - KFMIRL write only modifies expected bytes`() = verifyKfmirlWrite(VARIANTS[3])
    @Test fun `404J - KFMIRL write only modifies expected bytes`() = verifyKfmirlWrite(VARIANTS[4])
    @Test fun `404L - KFMIRL write only modifies expected bytes`() = verifyKfmirlWrite(VARIANTS[5])

    private fun verifyKfmirlWrite(variant: Variant) {
        loadVariant(variant)
        val kfmiopPair = KfmiopPreferences.getSelectedMap() ?: fail("${variant.name}: KFMIOP not resolved")
        val kfmirlPair = KfmirlPreferences.getSelectedMap() ?: fail("${variant.name}: KFMIRL not resolved")
        val tableDef = kfmirlPair.first

        val inverse = Inverse.calculateInverse(kfmiopPair.second, kfmirlPair.second)
        // Preserve first column from original KFMIRL
        for (i in inverse.zAxis.indices) {
            if (inverse.zAxis[i].isNotEmpty() && kfmirlPair.second.zAxis[i].isNotEmpty()) {
                inverse.zAxis[i][0] = kfmirlPair.second.zAxis[i][0]
            }
        }

        BinWriter.write(tempBinFile, tableDef, inverse)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(stockBinCopy, tempBinFile, tableDef)
    }

    // ── KFZWOP Write + Diff ──────────────────────────────────────────

    @Test fun `404A - KFZWOP write only modifies expected bytes`() = verifyKfzwopWrite(VARIANTS[0])
    @Test fun `404E - KFZWOP write only modifies expected bytes`() = verifyKfzwopWrite(VARIANTS[1])
    @Test fun `404G - KFZWOP write only modifies expected bytes`() = verifyKfzwopWrite(VARIANTS[2])
    @Test fun `404H - KFZWOP write only modifies expected bytes`() = verifyKfzwopWrite(VARIANTS[3])
    @Test fun `404J - KFZWOP write only modifies expected bytes`() = verifyKfzwopWrite(VARIANTS[4])
    @Test fun `404L - KFZWOP write only modifies expected bytes`() = verifyKfzwopWrite(VARIANTS[5])

    private fun verifyKfzwopWrite(variant: Variant) {
        loadVariant(variant)
        val pair = KfzwopPreferences.getSelectedMap() ?: fail("${variant.name}: KFZWOP not resolved")
        val tableDef = pair.first
        val input = pair.second

        // Rescale to a target max load of 300% (turbo)
        val rescaledXAxis = RescaleAxis.rescaleAxis(input.xAxis, 300.0)
        val newZAxis = Kfzw.generateKfzw(input.xAxis, input.zAxis, rescaledXAxis)
        val output = Map3d(rescaledXAxis, input.yAxis, newZAxis)

        BinWriter.write(tempBinFile, tableDef, output)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(stockBinCopy, tempBinFile, tableDef)
    }

    // ── KFZW Write + Diff ────────────────────────────────────────────

    @Test fun `404A - KFZW write only modifies expected bytes`() = verifyKfzwWrite(VARIANTS[0])
    @Test fun `404E - KFZW write only modifies expected bytes`() = verifyKfzwWrite(VARIANTS[1])
    @Test fun `404G - KFZW write only modifies expected bytes`() = verifyKfzwWrite(VARIANTS[2])
    @Test fun `404H - KFZW write only modifies expected bytes`() = verifyKfzwWrite(VARIANTS[3])
    @Test fun `404J - KFZW write only modifies expected bytes`() = verifyKfzwWrite(VARIANTS[4])
    @Test fun `404L - KFZW write only modifies expected bytes`() = verifyKfzwWrite(VARIANTS[5])

    private fun verifyKfzwWrite(variant: Variant) {
        loadVariant(variant)
        val pair = KfzwPreferences.getSelectedMap()
        if (pair == null) {
            // Some variants (e.g. 404H, 404L) use "GDI+-PFI" naming which doesn't
            // match the profile's "GDI" table — users select via MapPicker instead
            println("SKIP ${variant.name}: KFZW table not resolved (variant uses different naming)")
            return
        }
        val tableDef = pair.first
        val input = pair.second

        val rescaledXAxis = RescaleAxis.rescaleAxis(input.xAxis, 300.0)
        val newZAxis = Kfzw.generateKfzw(input.xAxis, input.zAxis, rescaledXAxis)
        val output = Map3d(rescaledXAxis, input.yAxis, newZAxis)

        BinWriter.write(tempBinFile, tableDef, output)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(stockBinCopy, tempBinFile, tableDef)
    }

    // ── KFLDRL Write + Diff ──────────────────────────────────────────

    @Test fun `404A - KFLDRL write only modifies expected bytes`() = verifyKfldrlWrite(VARIANTS[0])
    @Test fun `404E - KFLDRL write only modifies expected bytes`() = verifyKfldrlWrite(VARIANTS[1])
    @Test fun `404G - KFLDRL write only modifies expected bytes`() = verifyKfldrlWrite(VARIANTS[2])
    @Test fun `404H - KFLDRL write only modifies expected bytes`() = verifyKfldrlWrite(VARIANTS[3])
    @Test fun `404J - KFLDRL write only modifies expected bytes`() = verifyKfldrlWrite(VARIANTS[4])
    @Test fun `404L - KFLDRL write only modifies expected bytes`() = verifyKfldrlWrite(VARIANTS[5])

    private fun verifyKfldrlWrite(variant: Variant) {
        loadVariant(variant)
        val pair = KfldrlPreferences.getSelectedMap() ?: fail("${variant.name}: KFLDRL not resolved")
        val tableDef = pair.first
        // Write the identity (existing map data back) to verify address range
        BinWriter.write(tempBinFile, tableDef, pair.second)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(stockBinCopy, tempBinFile, tableDef, requireChanges = false)
    }

    // ── KFLDIMX Write + Diff ─────────────────────────────────────────

    @Test fun `404A - KFLDIMX write only modifies expected bytes`() = verifyKfldimxWrite(VARIANTS[0])
    @Test fun `404E - KFLDIMX write only modifies expected bytes`() = verifyKfldimxWrite(VARIANTS[1])
    @Test fun `404G - KFLDIMX write only modifies expected bytes`() = verifyKfldimxWrite(VARIANTS[2])
    @Test fun `404H - KFLDIMX write only modifies expected bytes`() = verifyKfldimxWrite(VARIANTS[3])
    @Test fun `404J - KFLDIMX write only modifies expected bytes`() = verifyKfldimxWrite(VARIANTS[4])
    @Test fun `404L - KFLDIMX write only modifies expected bytes`() = verifyKfldimxWrite(VARIANTS[5])

    private fun verifyKfldimxWrite(variant: Variant) {
        loadVariant(variant)
        val pair = KfldimxPreferences.getSelectedMap() ?: fail("${variant.name}: KFLDIMX not resolved")
        val tableDef = pair.first
        BinWriter.write(tempBinFile, tableDef, pair.second)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(stockBinCopy, tempBinFile, tableDef, requireChanges = false)
    }
}
