package ui.screens.me7

import data.model.EcuPlatform
import data.parser.bin.BinParser
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
import data.preferences.platform.EcuPlatformPreference
import data.preferences.wdkugdn.WdkugdnPreferences
import data.profile.ConfigurationProfile
import data.profile.ProfileManager
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import kotlin.test.*

/**
 * Cross-profile validation tests for ME7.
 *
 * Verifies that each ME7 profile compatible with the 551M ECU:
 * 1. Loads without error
 * 2. Has all expected map definition keys
 * 3. Resolves all map definitions against the stock XDF
 */
class Me7ProfileTest {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val XDF_FILE = File(PROJECT_ROOT, Me7TestBase.STOCK_XDF_REL)
        private val BIN_FILE = File(PROJECT_ROOT, Me7TestBase.STOCK_BIN_REL)

        private val profileJson = Json { ignoreUnknownKeys = true }

        /** All ME7 profiles compatible with 551M ECU. */
        val PROFILES = listOf(
            "/profiles/MBox.mxtprofile.json" to "MBox",
            "/profiles/ABox.mxtprofile.json" to "ABox",
            "/profiles/ME7_27T_A4_A6.mxtprofile.json" to "ME7_27T"
        )

        /** Common map keys all 551M profiles should define. */
        val REQUIRED_MAP_KEYS = listOf(
            "KRKTE", "MLHFM", "KFMIOP", "KFMIRL", "KFZWOP", "KFZW",
            "KFVPDKSD", "WDKUGDN", "KFWDKMSN", "KFLDRL", "KFLDIMX", "KFPBRK"
        )

        /** Preference getters by key. */
        val PREFERENCE_GETTERS = mapOf(
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

    @BeforeTest
    fun setUp() {
        savedPlatform = EcuPlatformPreference.platform
        EcuPlatformPreference.platform = EcuPlatform.ME7

        assertTrue(XDF_FILE.exists(), "XDF not found: ${XDF_FILE.absolutePath}")
        assertTrue(BIN_FILE.exists(), "BIN not found: ${BIN_FILE.absolutePath}")

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        val allMaps = BinParser.parseToList(FileInputStream(BIN_FILE), defs)
        XdfParser.setTableDefinitionsForTesting(defs)
        BinParser.setMapListForTesting(allMaps)
    }

    @AfterTest
    fun tearDown() {
        EcuPlatformPreference.platform = savedPlatform
    }

    private fun loadProfile(resourcePath: String): ConfigurationProfile {
        val stream = ProfileManager::class.java.getResourceAsStream(resourcePath)
            ?: error("Profile not found: $resourcePath")
        return profileJson.decodeFromString(
            ConfigurationProfile.serializer(), stream.bufferedReader().readText()
        )
    }

    // ── MBox profile ────────────────────────────────────────────────

    @Test
    fun `MBox - profile has all required map definition keys`() {
        val profile = loadProfile("/profiles/MBox.mxtprofile.json")
        for (key in REQUIRED_MAP_KEYS) {
            assertTrue(
                profile.mapDefinitions.containsKey(key),
                "MBox profile missing map key: $key"
            )
        }
    }

    @Test
    fun `MBox - all map preferences resolve against stock XDF`() {
        val profile = loadProfile("/profiles/MBox.mxtprofile.json")
        ProfileManager.applyProfile(profile)
        for (key in REQUIRED_MAP_KEYS) {
            val getter = PREFERENCE_GETTERS[key] ?: continue
            val pair = getter()
            assertNotNull(pair, "MBox: '$key' should resolve against stock XDF")
            assertTrue(pair.second.zAxis.isNotEmpty(), "MBox: '$key' should have non-empty z-axis data")
        }
    }

    @Test
    fun `MBox - primary fueling config has sane values`() {
        val profile = loadProfile("/profiles/MBox.mxtprofile.json")
        assertTrue(profile.primaryFueling.displacement > 0, "Displacement should be > 0")
        assertTrue(profile.primaryFueling.numCylinders > 0, "Cylinders should be > 0")
        assertTrue(profile.primaryFueling.fuelInjectorSize > 0, "Injector size should be > 0")
        assertTrue(profile.primaryFueling.stoichiometricAfr in 10.0..20.0, "AFR should be 10-20")
    }

    // ── ABox profile ────────────────────────────────────────────────

    @Test
    fun `ABox - profile has all required map definition keys`() {
        val profile = loadProfile("/profiles/ABox.mxtprofile.json")
        for (key in REQUIRED_MAP_KEYS) {
            assertTrue(
                profile.mapDefinitions.containsKey(key),
                "ABox profile missing map key: $key"
            )
        }
    }

    @Test
    fun `ABox - all map preferences resolve against stock XDF`() {
        val profile = loadProfile("/profiles/ABox.mxtprofile.json")
        ProfileManager.applyProfile(profile)
        for (key in REQUIRED_MAP_KEYS) {
            val getter = PREFERENCE_GETTERS[key] ?: continue
            val pair = getter()
            assertNotNull(pair, "ABox: '$key' should resolve against stock XDF")
        }
    }

    @Test
    fun `ABox - primary fueling config has sane values`() {
        val profile = loadProfile("/profiles/ABox.mxtprofile.json")
        assertTrue(profile.primaryFueling.displacement > 0, "Displacement should be > 0")
        assertTrue(profile.primaryFueling.numCylinders > 0, "Cylinders should be > 0")
        assertTrue(profile.primaryFueling.fuelInjectorSize > 0, "Injector size should be > 0")
    }

    // ── ME7_27T_A4_A6 profile ───────────────────────────────────────

    @Test
    fun `ME7_27T - profile has all required map definition keys`() {
        val profile = loadProfile("/profiles/ME7_27T_A4_A6.mxtprofile.json")
        for (key in REQUIRED_MAP_KEYS) {
            assertTrue(
                profile.mapDefinitions.containsKey(key),
                "ME7_27T profile missing map key: $key"
            )
        }
    }

    @Test
    fun `ME7_27T - all map preferences resolve against stock XDF`() {
        val profile = loadProfile("/profiles/ME7_27T_A4_A6.mxtprofile.json")
        ProfileManager.applyProfile(profile)
        for (key in REQUIRED_MAP_KEYS) {
            val getter = PREFERENCE_GETTERS[key] ?: continue
            val pair = getter()
            assertNotNull(pair, "ME7_27T: '$key' should resolve against stock XDF")
        }
    }

    @Test
    fun `ME7_27T - primary fueling config has sane values`() {
        val profile = loadProfile("/profiles/ME7_27T_A4_A6.mxtprofile.json")
        assertTrue(profile.primaryFueling.displacement > 0, "Displacement should be > 0")
        assertTrue(profile.primaryFueling.numCylinders > 0, "Cylinders should be > 0")
        assertTrue(profile.primaryFueling.fuelInjectorSize > 0, "Injector size should be > 0")
    }
}
