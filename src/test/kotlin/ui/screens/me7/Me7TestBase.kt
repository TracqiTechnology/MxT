package ui.screens.me7

import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.bin.BinFilePreferences
import data.preferences.platform.EcuPlatformPreference
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
 * Shared base for ME7 end-to-end tests.
 *
 * Parses the stock ME7 XDF + BIN, sets EcuPlatform to ME7,
 * populates BinParser / XdfParser singletons, applies the MBox profile,
 * and sets up temp BIN copies for write verification.
 */
abstract class Me7TestBase(
    private val xdfRelPath: String = STOCK_XDF_REL,
    private val binRelPath: String = STOCK_BIN_REL,
    private val profileResource: String = "/profiles/MBox.mxtprofile.json"
) {

    companion object {
        val PROJECT_ROOT: File = File(System.getProperty("user.dir"))

        const val STOCK_XDF_REL = "example/me7/xdf/8D0907551M-20190711.xdf"
        const val STOCK_BIN_REL = "example/me7/bin/8D0907551M-0002.bin"

        const val XDF_16BIT = "example/me7/xdf/8D0907551M-20170411-16bit-kfzw.xdf"
        const val BIN_16BIT = "example/me7/bin/8D0907551M-0002 (16Bit KFZW).bin"
        const val BIN_5120  = "example/me7/bin/8D0907551M-0002 (stock MAP 5120).bin"
        const val BIN_NYET  = "example/me7/bin/8D0907551M-002_5120-stock_map-nyet.bin"
        const val BIN_5120_16BIT = "example/me7/xdf/8D0907551M-0002 (stock MAP 5120 16Bit KFZW).bin"

        val profileJson = Json { ignoreUnknownKeys = true }
    }

    protected lateinit var savedPlatform: EcuPlatform
    protected lateinit var tableDefs: List<TableDefinition>
    protected lateinit var allMaps: List<Pair<TableDefinition, Map3d>>
    protected lateinit var tempBinFile: File
    protected lateinit var stockBinCopy: File
    protected lateinit var profile: ConfigurationProfile

    private val xdfFile get() = File(PROJECT_ROOT, xdfRelPath)
    protected val binFile get() = File(PROJECT_ROOT, binRelPath)

    @BeforeTest
    open fun setUp() {
        savedPlatform = EcuPlatformPreference.platform
        EcuPlatformPreference.platform = EcuPlatform.ME7

        assertTrue(xdfFile.exists(), "XDF not found: ${xdfFile.absolutePath}")
        assertTrue(binFile.exists(), "BIN not found: ${binFile.absolutePath}")

        val (_, defs) = XdfParser.parseToList(FileInputStream(xdfFile))
        tableDefs = defs
        assertTrue(tableDefs.isNotEmpty(), "XDF produced no table definitions")

        allMaps = BinParser.parseToList(FileInputStream(binFile), tableDefs)

        XdfParser.setTableDefinitionsForTesting(tableDefs)
        BinParser.setMapListForTesting(allMaps)

        tempBinFile = File.createTempFile("me7_test_", ".bin")
        binFile.copyTo(tempBinFile, overwrite = true)
        BinFilePreferences.setFile(tempBinFile)

        stockBinCopy = File.createTempFile("me7_stock_", ".bin")
        binFile.copyTo(stockBinCopy, overwrite = true)

        val stream = ProfileManager::class.java.getResourceAsStream(profileResource)
            ?: error("Profile not found: $profileResource")
        profile = profileJson.decodeFromString(
            ConfigurationProfile.serializer(), stream.bufferedReader().readText()
        )
        ProfileManager.applyProfile(profile)
    }

    @AfterTest
    open fun tearDown() {
        EcuPlatformPreference.platform = savedPlatform
        if (::tempBinFile.isInitialized && tempBinFile.exists()) tempBinFile.delete()
        if (::stockBinCopy.isInitialized && stockBinCopy.exists()) stockBinCopy.delete()
    }

    protected fun findMap(title: String): Pair<TableDefinition, Map3d>? =
        allMaps.find { it.first.tableName == title }

    protected fun findMapStartsWith(prefix: String): Pair<TableDefinition, Map3d>? =
        allMaps.find { it.first.tableName.startsWith(prefix, ignoreCase = true) }

    protected fun findMapContaining(keyword: String): Pair<TableDefinition, Map3d>? =
        allMaps.find { it.first.tableName.contains(keyword, ignoreCase = true) }

    /** Reset the temp BIN to stock state. */
    protected fun resetBin() {
        binFile.copyTo(tempBinFile, overwrite = true)
    }
}
