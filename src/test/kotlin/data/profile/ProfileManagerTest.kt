package data.profile

import data.contract.Med17LogFileContract
import data.contract.Me7LogFileContract
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Tests for [ProfileManager] covering MED17 profile loading, ME7 regression,
 * JSON round-trip serialisation, and bundled-profile integrity.
 */
class ProfileManagerTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun loadProfile(resourceName: String): ConfigurationProfile {
        val stream = this::class.java.getResourceAsStream("/profiles/$resourceName")
            ?: error("Resource not found: /profiles/$resourceName")
        return json.decodeFromString(
            ConfigurationProfile.serializer(),
            stream.bufferedReader().readText()
        )
    }

    // ── 1. MED17 profile loading and header population ──────────────

    @Test
    fun `MED17 profile med17LogHeaders is not empty`() {
        val profile = loadProfile("MED17_162_RS3_TTRS_2_5T.mxtprofile.json")
        assertTrue(profile.med17LogHeaders.isNotEmpty(), "med17LogHeaders should not be empty")
    }

    @Test
    fun `MED17 profile key headers have correct signal names`() {
        val profile = loadProfile("MED17_162_RS3_TTRS_2_5T.mxtprofile.json")
        val h = profile.med17LogHeaders

        assertEquals("nmot_w", h["RPM_COLUMN_HEADER"], "RPM → nmot_w")
        assertEquals("tvldste_w", h["WASTEGATE_DUTY_CYCLE_HEADER"], "WGDC → tvldste_w")
        assertEquals("psrg_w", h["ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER"], "MAP actual → psrg_w")
        assertEquals("pu_w", h["BAROMETRIC_PRESSURE_HEADER"], "Baro → pu_w")
        assertEquals("InjSys_facPrtnPfiTar", h["PFI_SPLIT_FACTOR_HEADER"], "PFI split → InjSys_facPrtnPfiTar")
    }

    @Test
    fun `MED17 profile has entries for all Med17 Header enum values`() {
        val profile = loadProfile("MED17_162_RS3_TTRS_2_5T.mxtprofile.json")
        val headerKeys = profile.med17LogHeaders.keys

        for (header in Med17LogFileContract.Header.entries) {
            assertTrue(
                header.name in headerKeys,
                "Missing MED17 header mapping for ${header.name}"
            )
        }
    }

    @Test
    fun `MED17 profile has med17LogHeaders matching enum size`() {
        val profile = loadProfile("MED17_162_RS3_TTRS_2_5T.mxtprofile.json")
        assertEquals(
            Med17LogFileContract.Header.entries.size,
            profile.med17LogHeaders.size,
            "med17LogHeaders count should match Header enum size"
        )
    }

    // ── 2. MED17 dual injection config ──────────────────────────────

    @Test
    fun `MED17 profile dualInjection has expected values`() {
        val profile = loadProfile("MED17_162_RS3_TTRS_2_5T.mxtprofile.json")
        val di = profile.dualInjection

        assertEquals(220.0, di.portInjectorFlowRateCcMin, "portInjectorFlowRateCcMin")
        assertEquals(380.0, di.directInjectorFlowRateCcMin, "directInjectorFlowRateCcMin")
        assertEquals(240.0, di.directInjectorFuelPressureBar, "directInjectorFuelPressureBar")
        assertEquals(30.0, di.portSharePercentDefault, "portSharePercentDefault")
        assertEquals(5, di.numPortInjectors, "numPortInjectors")
        assertEquals(5, di.numDirectInjectors, "numDirectInjectors")
    }

    @Test
    fun `MED17 profile ecuPlatform is MED17`() {
        val profile = loadProfile("MED17_162_RS3_TTRS_2_5T.mxtprofile.json")
        assertEquals("MED17", profile.ecuPlatform)
    }

    @Test
    fun `MED17 profile has critical map definitions`() {
        val profile = loadProfile("MED17_162_RS3_TTRS_2_5T.mxtprofile.json")
        val maps = profile.mapDefinitions.keys
        val required = listOf("KFMIOP", "KFMIRL", "KFZWOP", "KFZW", "KFLDRL", "KFLDIMX")
        for (key in required) {
            assertTrue(key in maps, "Missing critical map definition: $key")
        }
    }

    // ── 3. ME7 MBox profile regression ──────────────────────────────

    @Test
    fun `MBox profile logHeaders is not empty`() {
        val profile = loadProfile("MBox.mxtprofile.json")
        assertTrue(profile.logHeaders.isNotEmpty(), "logHeaders should not be empty")
    }

    @Test
    fun `MBox profile key ME7 headers have correct signal names`() {
        val profile = loadProfile("MBox.mxtprofile.json")
        val h = profile.logHeaders

        assertEquals("nmot", h["RPM_COLUMN_HEADER"], "RPM → nmot")
        assertEquals("ldtvm", h["WASTEGATE_DUTY_CYCLE_HEADER"], "WGDC → ldtvm")
        assertEquals("pvdks_w", h["ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER"], "Boost actual → pvdks_w")
    }

    @Test
    fun `MBox profile med17LogHeaders is empty`() {
        val profile = loadProfile("MBox.mxtprofile.json")
        assertTrue(profile.med17LogHeaders.isEmpty(), "ME7 profiles should not have MED17 headers")
    }

    @Test
    fun `MBox profile ecuPlatform defaults to ME7`() {
        val profile = loadProfile("MBox.mxtprofile.json")
        assertEquals("ME7", profile.ecuPlatform)
    }

    // ── 4. Profile round-trip ───────────────────────────────────────

    @Test
    fun `ConfigurationProfile survives JSON round-trip`() {
        val original = ConfigurationProfile(
            name = "Round-Trip Test",
            description = "Test profile for serialisation round-trip",
            ecuPlatform = "MED17",
            ecuPartNumbers = listOf("06F906070AA", "06F906070AB"),
            mapDefinitions = mapOf(
                "KRKTE" to MapDefinitionRef("KRKTE", "Primary fueling constant", "ms/%"),
                "KFMIOP" to MapDefinitionRef("KFMIOP", "Optimal torque map", "%")
            ),
            primaryFueling = PrimaryFuelingConfig(
                airDensity = 1.225,
                displacement = 0.496,
                numCylinders = 5,
                stoichiometricAfr = 14.7,
                gasolineGramsPerCcm = 0.7,
                fuelInjectorSize = 220.0
            ),
            plsol = PlsolConfig(
                barometricPressure = 1013.0,
                intakeAirTemperature = 25.0,
                kfurl = 0.1037,
                displacement = 2.48,
                rpm = 7000
            ),
            kfmiop = KfmiopConfig(maxMapPressure = 3500.0, maxBoostPressure = 2800.0),
            kfvpdksd = KfvpdksdConfig(maxWastegateCrackingPressure = 250.0),
            wdkugdn = WdkugdnConfig(displacement = 2.48),
            closedLoopFueling = ClosedLoopFuelingConfig(
                minThrottleAngle = 5.0, minRpm = 1000.0, maxDerivative = 40.0
            ),
            openLoopFueling = OpenLoopFuelingConfig(
                minThrottleAngle = 85.0, minRpm = 2500.0, minMe7Points = 100,
                minAfrPoints = 200, maxAfr = 15.5, fuelInjectorSize = 220.0,
                gasolineGramsPerCcm = 0.7, numFuelInjectors = 5.0
            ),
            dualInjection = DualInjectionConfig(
                portInjectorFlowRateCcMin = 220.0, portInjectorFuelPressureBar = 4.0,
                directInjectorFlowRateCcMin = 160.0, directInjectorFuelPressureBar = 200.0,
                portSharePercentDefault = 30.0, numPortInjectors = 5, numDirectInjectors = 5
            ),
            logHeaders = mapOf(
                "RPM_COLUMN_HEADER" to "nmot",
                "WASTEGATE_DUTY_CYCLE_HEADER" to "ldtvm"
            ),
            med17LogHeaders = mapOf(
                "RPM_COLUMN_HEADER" to "nmot_w",
                "WASTEGATE_DUTY_CYCLE_HEADER" to "tvldste_w"
            )
        )

        val encoded = json.encodeToString(ConfigurationProfile.serializer(), original)
        val decoded = json.decodeFromString(ConfigurationProfile.serializer(), encoded)

        assertEquals(original, decoded, "Round-trip should produce identical profile")
    }

    @Test
    fun `round-trip preserves individual field values`() {
        val original = ConfigurationProfile(
            name = "Field Check",
            ecuPlatform = "MED17",
            dualInjection = DualInjectionConfig(
                portInjectorFlowRateCcMin = 333.0,
                directInjectorFuelPressureBar = 350.0,
                numPortInjectors = 4,
                numDirectInjectors = 6
            ),
            med17LogHeaders = mapOf("PFI_SPLIT_FACTOR_HEADER" to "InjSys_facPrtnPfiTar")
        )

        val encoded = json.encodeToString(ConfigurationProfile.serializer(), original)
        val decoded = json.decodeFromString(ConfigurationProfile.serializer(), encoded)

        assertEquals(333.0, decoded.dualInjection.portInjectorFlowRateCcMin)
        assertEquals(350.0, decoded.dualInjection.directInjectorFuelPressureBar)
        assertEquals(4, decoded.dualInjection.numPortInjectors)
        assertEquals(6, decoded.dualInjection.numDirectInjectors)
        assertEquals("InjSys_facPrtnPfiTar", decoded.med17LogHeaders["PFI_SPLIT_FACTOR_HEADER"])
        assertEquals("MED17", decoded.ecuPlatform)
    }

    // ── 5. All bundled profiles load without error ──────────────────

    @Test
    fun `all bundled profiles in index_txt deserialise successfully`() {
        val index = this::class.java.getResourceAsStream("/profiles/index.txt")
            ?.bufferedReader()?.readLines() ?: fail("index.txt not found")

        val profileFiles = index.filter { it.isNotBlank() && it.endsWith(".mxtprofile.json") }
        assertTrue(profileFiles.isNotEmpty(), "index.txt should list at least one profile")

        for (fileName in profileFiles) {
            val profile = loadProfile(fileName)
            assertTrue(profile.name.isNotBlank(), "$fileName should have a non-empty name")
            assertTrue(
                profile.ecuPlatform.isNotBlank(),
                "$fileName should have a non-empty ecuPlatform"
            )
        }
    }

    @Test
    fun `bundled profiles have expected count`() {
        val profiles = ProfileManager.loadBundledProfiles()
        // index.txt: MBox, ABox, ME7_27T_A4_A6, ME7_18T_A4_150hp, ME7_18T_TT_225hp, ME7_18T_Golf, MED17_162
        assertTrue(profiles.size >= 7, "Expected at least 7 bundled profiles, got ${profiles.size}")
    }

    @Test
    fun `loadBundledProfiles matches manual parse of index_txt`() {
        val profiles = ProfileManager.loadBundledProfiles()
        val index = this::class.java.getResourceAsStream("/profiles/index.txt")
            ?.bufferedReader()?.readLines() ?: fail("index.txt not found")
        val expectedCount = index.count { it.isNotBlank() && it.endsWith(".mxtprofile.json") }

        assertEquals(expectedCount, profiles.size, "loadBundledProfiles should load all indexed profiles")
    }

    // ── 6. MED17 profile resolves against real 404E XDF ─────────────

    @Test
    fun `MED17 profile resolves core maps against 404E Normal XDF`() {
        val profile = loadProfile("MED17_162_RS3_TTRS_2_5T.mxtprofile.json")
        val projectRoot = java.io.File(System.getProperty("user.dir"))
        val xdfFile = java.io.File(projectRoot, "example/med17/404E/404E_normal.xdf")
        if (!xdfFile.exists()) {
            println("SKIP: 404E XDF not available at ${xdfFile.absolutePath}")
            return
        }

        val (_, tableDefs) = data.parser.xdf.XdfParser.parseToList(java.io.FileInputStream(xdfFile))
        assertTrue(tableDefs.isNotEmpty(), "XDF should produce table definitions")

        // Core maps that must resolve against the 404E XDF; supplemental maps (PFI split,
        // injection-mode timing, launch/altitude PID, etc.) are XDF-variant dependent.
        val coreMaps = listOf(
            "KRKTE_PFI", "KRKTE_GDI", "TVUB_PFI",
            "KFMIOP", "KFMIRL", "KFZWOP", "KFZW",
            "KFLDRL", "KFLDIMX", "KFLDIOPU",
            "KFLDRQ0", "KFLDRQ1", "KFLDRQ2", "RKW"
        )

        val unresolved = mutableListOf<String>()
        for (key in coreMaps) {
            val ref = profile.mapDefinitions[key] ?: run {
                unresolved.add("$key: missing from profile")
                continue
            }
            val exact = tableDefs.firstOrNull { def ->
                ref.tableName == def.tableName &&
                    ref.tableDescription == def.tableDescription &&
                    ref.unit == def.zAxis.unit
            }
            if (exact == null) {
                unresolved.add("$key: tableName='${ref.tableName}' desc='${ref.tableDescription}' unit='${ref.unit}'")
            }
        }

        assertTrue(
            unresolved.isEmpty(),
            "All core MED17 profile maps should exact-match 404E XDF, but ${unresolved.size} failed:\n" +
                unresolved.joinToString("\n  ", prefix = "  ")
        )
    }

    @Test
    fun `MED17 profile has all expected map definition keys`() {
        val profile = loadProfile("MED17_162_RS3_TTRS_2_5T.mxtprofile.json")
        val coreKeys = listOf(
            "KRKTE_PFI", "KRKTE_GDI", "TVUB_PFI",
            "KFMIOP", "KFMIRL", "KFZWOP", "KFZW",
            "KFLDRL", "KFLDIMX", "KFLDIOPU",
            "KFLDRQ0", "KFLDRQ1", "KFLDRQ2", "RKW"
        )
        for (key in coreKeys) {
            assertTrue(key in profile.mapDefinitions, "Profile should contain core map definition '$key'")
        }
        assertTrue(profile.mapDefinitions.size >= 14, "Profile should have at least 14 map definitions (has ${profile.mapDefinitions.size})")
    }
}
