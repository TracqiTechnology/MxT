package ui.screens.med17

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
 * Shared base for MED17 Compose UI screen tests.
 *
 * Parses the real 404E XDF + stock BIN, sets EcuPlatform to MED17,
 * populates BinParser / XdfParser singletons, applies the MED17 profile,
 * and sets up a temp BIN copy for write verification.
 */
abstract class Med17ScreenTestBase {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        val XDF_FILE = File(PROJECT_ROOT, "example/med17/404E/404E_normal.xdf")
        val BIN_FILE = File(PROJECT_ROOT, "example/med17/404E/MED17_1_62_STOCK.bin")

        // 404E Normal XDF map titles
        const val KFMIOP_TITLE = "Opt eng tq"
        const val KFMIRL_TITLE = "Tgt filling"
        const val KFLDRL_TITLE = "KF to linearize boost pressure = fTV"
        const val KFLDIMX_TITLE = "LDR I controller limitation map"
        const val KFZWOP_TITLE = "Opt model ref ignition"
        const val KFZW_TITLE = "Ignition GDI ex cam control/std valve lift (MAIN) Gasoline 0"
        const val KRKTE_GDI_TITLE = "Conv rel fuel mass rk into effective inj time te"
        const val KRKTE_PFI_TITLE = "Conv rel fuel mass rk to effective inj time te or intake man inj PFI"
        const val WASTEGATE_TITLE = "Precontrol wastegate for LDR inactive"

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
        EcuPlatformPreference.platform = EcuPlatform.MED17

        assertTrue(XDF_FILE.exists(), "XDF not found: ${XDF_FILE.absolutePath}")
        assertTrue(BIN_FILE.exists(), "BIN not found: ${BIN_FILE.absolutePath}")

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        tableDefs = defs
        assertTrue(tableDefs.isNotEmpty(), "XDF produced no table definitions")

        allMaps = BinParser.parseToList(FileInputStream(BIN_FILE), tableDefs)

        // Populate singletons so screens can collect from them
        XdfParser.setTableDefinitionsForTesting(tableDefs)
        BinParser.setMapListForTesting(allMaps)

        // Create a temp BIN copy for write verification and a stock copy for diffing
        tempBinFile = File.createTempFile("med17_test_", ".bin")
        BIN_FILE.copyTo(tempBinFile, overwrite = true)
        BinFilePreferences.setFile(tempBinFile)
        XdfFilePreferences.setFile(XDF_FILE)

        stockBinCopy = File.createTempFile("med17_stock_", ".bin")
        BIN_FILE.copyTo(stockBinCopy, overwrite = true)

        // Load and apply MED17 profile
        val stream = ProfileManager::class.java.getResourceAsStream(
            "/profiles/MED17_162_RS3_TTRS_2_5T.mxtprofile.json"
        ) ?: error("MED17 profile not found on classpath")
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

    /** Find a map by exact table name. */
    protected fun findMap(title: String): Pair<TableDefinition, Map3d>? =
        allMaps.find { it.first.tableName == title }

    /** Find a map by partial title match (case-insensitive). */
    protected fun findMapContaining(keyword: String): Pair<TableDefinition, Map3d>? =
        allMaps.find { it.first.tableName.contains(keyword, ignoreCase = true) }

    /**
     * Find maps matching a keyword and return the largest one (by total cell count).
     * Useful for finding the "main" ignition or load table among several variants.
     */
    protected fun findLargestMapContaining(keyword: String): Pair<TableDefinition, Map3d>? =
        allMaps
            .filter { it.first.tableName.contains(keyword, ignoreCase = true) }
            .maxByOrNull { it.second.xAxis.size * it.second.yAxis.size }

    /** Read raw bytes from the temp BIN at a given address. */
    protected fun readBinBytes(address: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        java.io.RandomAccessFile(tempBinFile, "r").use { raf ->
            raf.seek(address)
            raf.readFully(bytes)
        }
        return bytes
    }
}
