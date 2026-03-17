package ui.screens.med9

import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.bin.BinFilePreferences
import data.preferences.platform.EcuPlatformPreference
import data.preferences.xdf.XdfFilePreferences
import data.profile.ConfigurationProfile
import data.profile.ProfileManager
import domain.math.map.Map3d
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertTrue

/**
 * Shared base for MED9 screen tests.
 *
 * Parses the real 1K090711S XDF + stock BIN, sets EcuPlatform to MED9,
 * populates BinParser / XdfParser singletons, applies the MED9 profile,
 * and sets up a temp BIN copy for write verification.
 */
abstract class Med9ScreenTestBase {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        val XDF_FILE = File(PROJECT_ROOT, "example/med9/1K090711S.xdf")
        val BIN_FILE = File(PROJECT_ROOT, "example/med9/MED9_STOCK.bin")

        // MED9 XDF map titles (from enhanced 1K090711S.xdf)
        const val KFMIOP_TITLE = "Optimal Engine Torque (KFMIOP)"
        const val KFMIRL_TITLE = "Specified Load (KFMIRL)"
        const val KFLDRL_TITLE = "Boost Linearization (KFLDRL)"
        const val KFLDHBN_TITLE = "Max Compressor Pressure Ratio (KFLDHBN)"
        const val KFLDIMX_TITLE = "Boost I-Regulator Limit (KFLDIMX)"
        const val KFVPDKLD_TITLE = "Max Permissible Pressure Ratio (KFVPDKLD)"
        const val KRKATE_TITLE = "Conversion of Relative Fuel Mass into Effective Injection time (KRKATE)"
        const val KFDPLGU_TITLE = "Boost Altitude Correction (KFDPLGU)"
        const val KFZWOP_TITLE = "Optimal Ignition Angle (KFZWOP)"
        const val WDKUGDN_TITLE = "Throttle Angle No-Restriction (WDKUGDN)"

        private val profileJson = Json { ignoreUnknownKeys = true }
    }

    protected lateinit var savedPlatform: EcuPlatform
    protected lateinit var savedXdfFile: File
    protected lateinit var tableDefs: List<TableDefinition>
    protected lateinit var allMaps: List<Pair<TableDefinition, Map3d>>
    protected lateinit var tempBinFile: File
    protected lateinit var stockBinCopy: File
    protected lateinit var profile: ConfigurationProfile

    @BeforeTest
    open fun setUp() {
        savedPlatform = EcuPlatformPreference.platform
        savedXdfFile = XdfFilePreferences.getStoredFile()
        EcuPlatformPreference.platform = EcuPlatform.MED9

        assertTrue(XDF_FILE.exists(), "XDF not found: ${XDF_FILE.absolutePath}")
        assertTrue(BIN_FILE.exists(), "BIN not found: ${BIN_FILE.absolutePath}")

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        tableDefs = defs
        assertTrue(tableDefs.isNotEmpty(), "XDF produced no table definitions")

        allMaps = BinParser.parseToList(FileInputStream(BIN_FILE), tableDefs)

        XdfParser.setTableDefinitionsForTesting(tableDefs)
        BinParser.setMapListForTesting(allMaps)

        tempBinFile = File.createTempFile("med9_test_", ".bin")
        BIN_FILE.copyTo(tempBinFile, overwrite = true)
        BinFilePreferences.setFile(tempBinFile)
        XdfFilePreferences.setFile(XDF_FILE)

        stockBinCopy = File.createTempFile("med9_stock_", ".bin")
        BIN_FILE.copyTo(stockBinCopy, overwrite = true)

        val stream = ProfileManager::class.java.getResourceAsStream(
            "/profiles/MED9_Golf_GTI_2_0_TFSI.mxtprofile.json"
        ) ?: error("MED9 profile not found on classpath")
        profile = profileJson.decodeFromString(
            ConfigurationProfile.serializer(), stream.bufferedReader().readText()
        )
        ProfileManager.applyProfile(profile)
    }

    @AfterTest
    open fun tearDown() {
        EcuPlatformPreference.platform = savedPlatform
        XdfFilePreferences.setFile(savedXdfFile)
        if (::tempBinFile.isInitialized && tempBinFile.exists()) {
            tempBinFile.delete()
        }
        if (::stockBinCopy.isInitialized && stockBinCopy.exists()) {
            stockBinCopy.delete()
        }
    }

    protected fun findMap(title: String): Pair<TableDefinition, Map3d>? =
        allMaps.find { it.first.tableName == title }

    protected fun findMapContaining(keyword: String): Pair<TableDefinition, Map3d>? =
        allMaps.find { it.first.tableName.contains(keyword, ignoreCase = true) }

    protected fun findLargestMapContaining(keyword: String): Pair<TableDefinition, Map3d>? =
        allMaps
            .filter { it.first.tableName.contains(keyword, ignoreCase = true) }
            .maxByOrNull { it.second.xAxis.size * it.second.yAxis.size }

    protected fun readBinBytes(address: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        java.io.RandomAccessFile(tempBinFile, "r").use { raf ->
            raf.seek(address)
            raf.readFully(bytes)
        }
        return bytes
    }
}
