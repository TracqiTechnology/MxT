package ui.screens.me7

import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import data.preferences.kfpbrk.KfpbrkPreferences
import data.preferences.kfpbrknw.KfpbrknwPreferences
import data.preferences.kfvpdksd.KfvpdksdPreferences
import data.preferences.kfwdkmsn.KfwdkmsnPreferences
import data.preferences.kfzw.KfzwPreferences
import data.preferences.kfzwop.KfzwopPreferences
import data.preferences.krkte.KrktePreferences
import data.preferences.mlhfm.MlhfmPreferences
import data.preferences.wdkugdn.WdkugdnPreferences
import data.preferences.bin.BinFilePreferences
import data.preferences.platform.EcuPlatformPreference
import data.model.EcuPlatform
import data.profile.ProfileManager
import data.writer.BinWriter
import domain.math.map.Map3d
import ui.screens.med17.BinaryDiffHelper
import java.io.File
import java.io.FileInputStream
import kotlin.test.*

/**
 * Multi-variant end-to-end write tests for all ME7 XDF/BIN pairs.
 *
 * For each XDF/BIN pair:
 * 1. Parse XDF + BIN
 * 2. Apply MBox profile
 * 3. Verify all 13 map preferences resolve
 * 4. For each writable map: identity-write → binary diff
 *
 * Binary diff asserts that ONLY the expected address range was modified.
 */
class Me7MultiVariantTest {

    data class Variant(
        val name: String,
        val xdfPath: String,
        val binPath: String
    )

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))

        val VARIANTS = listOf(
            Variant("stock", Me7TestBase.STOCK_XDF_REL, Me7TestBase.STOCK_BIN_REL),
            Variant("16bit-kfzw", Me7TestBase.XDF_16BIT, Me7TestBase.BIN_16BIT),
            Variant("5120", Me7TestBase.XDF_16BIT, Me7TestBase.BIN_5120),
            Variant("5120-nyet", Me7TestBase.XDF_16BIT, Me7TestBase.BIN_NYET),
            Variant("5120-16bit", Me7TestBase.XDF_16BIT, Me7TestBase.BIN_5120_16BIT)
        )

        /** All map preferences the MBox profile should populate. */
        val MAP_PREFERENCES = listOf(
            "KRKTE" to { KrktePreferences.getSelectedMap() },
            "MLHFM" to { MlhfmPreferences.getSelectedMap() },
            "KFMIOP" to { KfmiopPreferences.getSelectedMap() },
            "KFMIRL" to { KfmirlPreferences.getSelectedMap() },
            "KFZWOP" to { KfzwopPreferences.getSelectedMap() },
            "KFZW" to { KfzwPreferences.getSelectedMap() },
            "KFVPDKSD" to { KfvpdksdPreferences.getSelectedMap() },
            "WDKUGDN" to { WdkugdnPreferences.getSelectedMap() },
            "KFWDKMSN" to { KfwdkmsnPreferences.getSelectedMap() },
            "KFLDRL" to { KfldrlPreferences.getSelectedMap() },
            "KFLDIMX" to { KfldimxPreferences.getSelectedMap() },
            "KFPBRK" to { KfpbrkPreferences.getSelectedMap() },
            "KFPBRKNW" to { KfpbrknwPreferences.getSelectedMap() },
        )
    }

    private lateinit var savedPlatform: EcuPlatform
    private lateinit var globalState: support.GlobalTestStateSnapshot
    private lateinit var tempBinFile: File
    private lateinit var stockBinCopy: File

    @BeforeTest
    fun setUp() {
        globalState = support.GlobalTestStateSnapshot.capture()
        savedPlatform = EcuPlatformPreference.platform
    }

    @AfterTest
    fun tearDown() {
        globalState.restore()
        if (::tempBinFile.isInitialized && tempBinFile.exists()) tempBinFile.delete()
        if (::stockBinCopy.isInitialized && stockBinCopy.exists()) stockBinCopy.delete()
    }

    private fun loadVariant(variant: Variant) {
        EcuPlatformPreference.platform = EcuPlatform.ME7

        val xdfFile = File(PROJECT_ROOT, variant.xdfPath)
        val binFile = File(PROJECT_ROOT, variant.binPath)
        assertTrue(xdfFile.exists(), "XDF not found: ${xdfFile.absolutePath}")
        assertTrue(binFile.exists(), "BIN not found: ${binFile.absolutePath}")

        val (_, defs) = XdfParser.parseToList(FileInputStream(xdfFile))
        val allMaps = BinParser.parseToList(FileInputStream(binFile), defs)
        XdfParser.setTableDefinitionsForTesting(defs)
        BinParser.setMapListForTesting(allMaps)

        tempBinFile = File.createTempFile("me7_${variant.name}_", ".bin")
        binFile.copyTo(tempBinFile, overwrite = true)
        BinFilePreferences.setFile(tempBinFile)

        stockBinCopy = File.createTempFile("me7_stock_${variant.name}_", ".bin")
        binFile.copyTo(stockBinCopy, overwrite = true)

        val stream = ProfileManager::class.java.getResourceAsStream("/profiles/MBox.mxtprofile.json")
            ?: error("MBox profile not found")
        val profile = Me7TestBase.profileJson.decodeFromString(
            data.profile.ConfigurationProfile.serializer(), stream.bufferedReader().readText()
        )
        ProfileManager.applyProfile(profile)
    }

    /**
     * Identity-write a resolved map and verify only its bytes changed.
     * Some maps may not differ from stock after identity write (equation rounding).
     */
    private fun verifyIdentityWrite(mapKey: String, getter: () -> Pair<TableDefinition, Map3d>?, requireChanges: Boolean = false) {
        val pair = getter() ?: fail("$mapKey preference not resolved")
        val (def, map) = pair

        // Skip degenerate maps (no z-axis data or address=0)
        if (map.zAxis.isEmpty() || def.zAxis.address == 0) return

        BinWriter.write(tempBinFile, def, map)
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, def, requireChanges = requireChanges
        )

        // Reset for next write
        stockBinCopy.copyTo(tempBinFile, overwrite = true)
    }

    private fun verifyIncompatibleMapWriteRejected(
        variantName: String,
        mapKey: String,
        getter: () -> Pair<TableDefinition, Map3d>?
    ) {
        loadVariant(VARIANTS.first { it.name == variantName })
        val (definition, map) = getter() ?: fail("$mapKey preference not resolved")
        val before = tempBinFile.readBytes()

        val error = assertFailsWith<IllegalArgumentException> {
            BinWriter.write(tempBinFile, definition, map)
        }

        assertTrue(error.message.orEmpty().contains("strictly increasing"))
        assertContentEquals(before, tempBinFile.readBytes(), "Rejected write must leave BIN byte-identical")
    }

    // ── Stock variant ──────────────────────────────────────────────────

    @Test fun `stock - all map preferences resolve`() = verifyAllPreferences("stock")
    @Test fun `stock - write KRKTE`() = verifyWrite("stock", "KRKTE") { KrktePreferences.getSelectedMap() }
    @Test fun `stock - write MLHFM`() = verifyWrite("stock", "MLHFM") { MlhfmPreferences.getSelectedMap() }
    @Test fun `stock - write KFMIOP`() = verifyWrite("stock", "KFMIOP") { KfmiopPreferences.getSelectedMap() }
    @Test fun `stock - write KFMIRL`() = verifyWrite("stock", "KFMIRL") { KfmirlPreferences.getSelectedMap() }
    @Test fun `stock - write KFZWOP`() = verifyWrite("stock", "KFZWOP") { KfzwopPreferences.getSelectedMap() }
    @Test fun `stock - write KFZW`() = verifyWrite("stock", "KFZW") { KfzwPreferences.getSelectedMap() }
    @Test fun `stock - write KFVPDKSD`() = verifyWrite("stock", "KFVPDKSD") { KfvpdksdPreferences.getSelectedMap() }
    @Test fun `stock - write WDKUGDN`() = verifyWrite("stock", "WDKUGDN") { WdkugdnPreferences.getSelectedMap() }
    @Test fun `stock - write KFWDKMSN`() = verifyWrite("stock", "KFWDKMSN") { KfwdkmsnPreferences.getSelectedMap() }
    @Test fun `stock - write KFLDRL`() = verifyWrite("stock", "KFLDRL") { KfldrlPreferences.getSelectedMap() }
    @Test fun `stock - write KFLDIMX`() = verifyWrite("stock", "KFLDIMX") { KfldimxPreferences.getSelectedMap() }
    @Test fun `stock - write KFPBRK`() = verifyWrite("stock", "KFPBRK") { KfpbrkPreferences.getSelectedMap() }
    @Test fun `stock - write KFPBRKNW`() = verifyWrite("stock", "KFPBRKNW") { KfpbrknwPreferences.getSelectedMap() }

    // ── 16-bit KFZW variant ─────────────────────────────────────────

    @Test fun `16bit-kfzw - all map preferences resolve`() = verifyAllPreferences("16bit-kfzw")
    @Test fun `16bit-kfzw - write KRKTE`() = verifyWrite("16bit-kfzw", "KRKTE") { KrktePreferences.getSelectedMap() }
    @Test fun `16bit-kfzw - write MLHFM`() = verifyWrite("16bit-kfzw", "MLHFM") { MlhfmPreferences.getSelectedMap() }
    @Test fun `16bit-kfzw - write KFMIOP`() = verifyWrite("16bit-kfzw", "KFMIOP") { KfmiopPreferences.getSelectedMap() }
    @Test fun `16bit-kfzw - write KFMIRL`() = verifyWrite("16bit-kfzw", "KFMIRL") { KfmirlPreferences.getSelectedMap() }
    @Test fun `16bit-kfzw - write KFZWOP`() = verifyWrite("16bit-kfzw", "KFZWOP") { KfzwopPreferences.getSelectedMap() }
    @Test fun `16bit-kfzw - write KFZW`() = verifyWrite("16bit-kfzw", "KFZW") { KfzwPreferences.getSelectedMap() }
    @Test fun `16bit-kfzw - write KFLDRL`() = verifyWrite("16bit-kfzw", "KFLDRL") { KfldrlPreferences.getSelectedMap() }
    @Test fun `16bit-kfzw - write KFLDIMX`() = verifyWrite("16bit-kfzw", "KFLDIMX") { KfldimxPreferences.getSelectedMap() }
    @Test fun `16bit-kfzw - write KFPBRK`() = verifyWrite("16bit-kfzw", "KFPBRK") { KfpbrkPreferences.getSelectedMap() }

    // ── 5120 variant ────────────────────────────────────────────────

    @Test fun `5120 - all map preferences resolve`() = verifyAllPreferences("5120")
    @Test fun `5120 - write KRKTE`() = verifyWrite("5120", "KRKTE") { KrktePreferences.getSelectedMap() }
    @Test fun `5120 - write KFMIOP`() = verifyWrite("5120", "KFMIOP") { KfmiopPreferences.getSelectedMap() }
    @Test fun `5120 - incompatible 16-bit KFZW is rejected safely`() =
        verifyIncompatibleMapWriteRejected("5120", "KFZW") { KfzwPreferences.getSelectedMap() }
    @Test fun `5120 - write KFLDRL`() = verifyWrite("5120", "KFLDRL") { KfldrlPreferences.getSelectedMap() }
    @Test fun `5120 - write KFLDIMX`() = verifyWrite("5120", "KFLDIMX") { KfldimxPreferences.getSelectedMap() }

    // ── 5120-nyet variant ───────────────────────────────────────────

    @Test fun `5120-nyet - all map preferences resolve`() = verifyAllPreferences("5120-nyet")
    @Test fun `5120-nyet - write KRKTE`() = verifyWrite("5120-nyet", "KRKTE") { KrktePreferences.getSelectedMap() }
    @Test fun `5120-nyet - write KFMIOP`() = verifyWrite("5120-nyet", "KFMIOP") { KfmiopPreferences.getSelectedMap() }
    @Test fun `5120-nyet - incompatible 16-bit KFZW is rejected safely`() =
        verifyIncompatibleMapWriteRejected("5120-nyet", "KFZW") { KfzwPreferences.getSelectedMap() }
    @Test fun `5120-nyet - write KFLDRL`() = verifyWrite("5120-nyet", "KFLDRL") { KfldrlPreferences.getSelectedMap() }

    // ── 5120-16bit variant ──────────────────────────────────────────

    @Test fun `5120-16bit - all map preferences resolve`() = verifyAllPreferences("5120-16bit")
    @Test fun `5120-16bit - write KRKTE`() = verifyWrite("5120-16bit", "KRKTE") { KrktePreferences.getSelectedMap() }
    @Test fun `5120-16bit - write KFMIOP`() = verifyWrite("5120-16bit", "KFMIOP") { KfmiopPreferences.getSelectedMap() }
    @Test fun `5120-16bit - write KFZW`() = verifyWrite("5120-16bit", "KFZW") { KfzwPreferences.getSelectedMap() }
    @Test fun `5120-16bit - write KFLDRL`() = verifyWrite("5120-16bit", "KFLDRL") { KfldrlPreferences.getSelectedMap() }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun verifyAllPreferences(variantName: String) {
        val variant = VARIANTS.first { it.name == variantName }
        loadVariant(variant)

        for ((key, getter) in MAP_PREFERENCES) {
            val pair = getter()
            assertNotNull(pair, "MBox profile should resolve '$key' for variant ${variant.name}")
            assertTrue(pair.first.tableName.isNotEmpty(), "'$key' table name should be non-empty")
        }
    }

    private fun verifyWrite(variantName: String, mapKey: String, getter: () -> Pair<TableDefinition, Map3d>?) {
        val variant = VARIANTS.first { it.name == variantName }
        loadVariant(variant)
        verifyIdentityWrite(mapKey, getter)
    }
}
