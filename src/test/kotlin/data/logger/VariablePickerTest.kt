package data.logger

import data.logger.uds.*
import data.logger.kwp2000.Kwp2000NativeLogger
import data.logger.protocol.SerialPortProvider
import data.parser.ecu.CfgFileParser
import data.parser.ecu.EcuFileParser
import data.writer.EcuWriter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for the variable picker feature: custom variable selection
 * via LoggerConfig.selectedVariableNames, preset intersection logic,
 * temp .cfg generation, and .cfg round-trip.
 */
class VariablePickerTest {

    /** Minimal .ecu file with known variables for testing. */
    private fun createTestEcuFile(dir: File): File {
        val entries = listOf(
            makeEntry("nmot_w", "EngineSpeed", 0xF00100, "1/min"),
            makeEntry("rl_w", "EngineLoad", 0xF00200, "%"),
            makeEntry("pvdks_w", "BoostPressureActual", 0xF00300, "hPa"),
            makeEntry("plsol_w", "BoostPressureSpecified", 0xF00400, "hPa"),
            makeEntry("ldtvm", "WastegateDutyCycle", 0xF00500, "%TV"),
            makeEntry("fr_w", "LambdaControl", 0xF00600, "-"),
            makeEntry("zwist", "IgnitionTimingAngle", 0xF00700, "grad KW"),
            makeEntry("tmot", "CoolantTemperature", 0xF00800, "°C"),
            makeEntry("vfil_w", "VehicleSpeed", 0xF00900, "km/h"),
            makeEntry("gangi", "SelectedGear", 0xF00A00, "-"),
            makeEntry("extra_var1", "", 0xF00B00, "V"),
            makeEntry("extra_var2", "", 0xF00C00, "A"),
        )
        val ecuFile = File(dir, "test.ecu")
        EcuWriter.writeEcuFile(entries, ecuFile, "TEST", "0001", "Test Engine")
        return ecuFile
    }

    private fun makeEntry(name: String, alias: String, address: Long, unit: String) =
        data.parser.a2l.EcuEntry(
            name = name,
            alias = alias,
            address = address,
            size = 2,
            bitmask = 0,
            unit = unit,
            signed = 0,
            inverse = 0,
            factor = 1.0,
            offset = 0.0,
            comment = "$name test entry"
        )

    // ── selectedVariableNames via UDS logger ─────────────────────────────

    @Test
    fun `UDS logger resolves custom selectedVariableNames over cfg and fallback`(@TempDir tempDir: Path) = runBlocking {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)
        val transport = FakeCanTransport()
        val logger = UdsNativeLogger(transport)

        // Connect with custom variable selection (no .cfg)
        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            selectedVariableNames = listOf("rl_w", "pvdks_w", "ldtvm")
        ))

        val vars = logger.variables.value
        assertEquals(3, vars.size)
        assertEquals("rl_w", vars[0].name)
        assertEquals("pvdks_w", vars[1].name)
        assertEquals("ldtvm", vars[2].name)
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
    }

    @Test
    fun `UDS logger selectedVariableNames takes priority over cfg file`(@TempDir tempDir: Path) = runBlocking {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)

        // Write a .cfg that selects different variables
        val cfgFile = File(dir, "test.cfg")
        EcuWriter.writeCfgFile(cfgFile, ecuFile.name,
            listOf(Triple("nmot_w", "", ""), Triple("tmot", "", "")),
            description = "test cfg"
        )

        val transport = FakeCanTransport()
        val logger = UdsNativeLogger(transport)

        // Connect with BOTH .cfg and selectedVariableNames — picker wins
        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            cfgFile = cfgFile.absolutePath,
            selectedVariableNames = listOf("zwist", "vfil_w")
        ))

        val vars = logger.variables.value
        assertEquals(2, vars.size)
        assertEquals("zwist", vars[0].name)
        assertEquals("vfil_w", vars[1].name)
    }

    @Test
    fun `UDS logger falls back to cfg when selectedVariableNames is empty`(@TempDir tempDir: Path) = runBlocking {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)
        val cfgFile = File(dir, "test.cfg")
        EcuWriter.writeCfgFile(cfgFile, ecuFile.name,
            listOf(Triple("nmot_w", "", ""), Triple("tmot", "", "")),
            description = "test cfg"
        )

        val transport = FakeCanTransport()
        val logger = UdsNativeLogger(transport)

        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            cfgFile = cfgFile.absolutePath,
            selectedVariableNames = emptyList()
        ))

        val vars = logger.variables.value
        assertEquals(2, vars.size)
        assertEquals("nmot_w", vars[0].name)
        assertEquals("tmot", vars[1].name)
    }

    @Test
    fun `UDS logger falls back to first 20 when no cfg and no selection`(@TempDir tempDir: Path) = runBlocking {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)
        val transport = FakeCanTransport()
        val logger = UdsNativeLogger(transport)

        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath
        ))

        val vars = logger.variables.value
        assertEquals(12, vars.size)  // All 12 test entries (< 20 limit)
    }

    @Test
    fun `UDS logger skips unknown variable names gracefully`(@TempDir tempDir: Path) = runBlocking {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)
        val transport = FakeCanTransport()
        val logger = UdsNativeLogger(transport)

        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            selectedVariableNames = listOf("nmot_w", "NONEXISTENT_VAR", "rl_w")
        ))

        val vars = logger.variables.value
        assertEquals(2, vars.size)
        assertEquals("nmot_w", vars[0].name)
        assertEquals("rl_w", vars[1].name)
    }

    // ── Me7LoggerProcess temp .cfg generation ────────────────────────────

    @Test
    fun `Me7LoggerProcess generates temp cfg from selectedVariableNames`(@TempDir tempDir: Path) = runBlocking {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)

        // Create a fake ME7Logger.exe (just needs to exist for validation)
        val me7loggerExe = File(dir, "ME7Logger.exe")
        me7loggerExe.writeText("fake")

        val logger = Me7LoggerProcess()
        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.ME7LOGGER_EXE,
            me7loggerPath = me7loggerExe.absolutePath,
            ecuFile = ecuFile.absolutePath,
            cfgFile = "",  // No .cfg file
            selectedVariableNames = listOf("nmot_w", "rl_w", "ldtvm")
        ))

        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        // The status message should reference a temp .cfg file
        assertTrue(logger.statusMessage.value.contains("Ready"))

        // Verify the temp .cfg was created and is valid
        val cmd = logger.buildCommand(logger.currentConfig()!!)
        val cfgPath = cmd.last()
        val tempCfg = File(cfgPath)
        assertTrue(tempCfg.exists(), "Temp .cfg file should exist")

        val parsed = CfgFileParser.parse(tempCfg)
        assertEquals(3, parsed.variables.size)
        assertEquals("nmot_w", parsed.variables[0].name)
        assertEquals("rl_w", parsed.variables[1].name)
        assertEquals("ldtvm", parsed.variables[2].name)
        assertEquals(ecuFile.name, parsed.ecuFilename)

        logger.disconnect()
    }

    @Test
    fun `Me7LoggerProcess uses existing cfg when provided even with selectedVariableNames`(@TempDir tempDir: Path) = runBlocking {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)
        val me7loggerExe = File(dir, "ME7Logger.exe")
        me7loggerExe.writeText("fake")
        val cfgFile = File(dir, "existing.cfg")
        EcuWriter.writeCfgFile(cfgFile, ecuFile.name,
            listOf(Triple("tmot", "", "")),
            description = "existing cfg"
        )

        val logger = Me7LoggerProcess()
        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.ME7LOGGER_EXE,
            me7loggerPath = me7loggerExe.absolutePath,
            ecuFile = ecuFile.absolutePath,
            cfgFile = cfgFile.absolutePath,
            selectedVariableNames = listOf("nmot_w", "rl_w")
        ))

        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        // Should use existing .cfg, not generate temp
        val cmd = logger.buildCommand(logger.currentConfig()!!)
        assertEquals(cfgFile.absolutePath, cmd.last())
    }

    @Test
    fun `Me7LoggerProcess errors when no cfg and no selectedVariableNames`(@TempDir tempDir: Path) = runBlocking {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)
        val me7loggerExe = File(dir, "ME7Logger.exe")
        me7loggerExe.writeText("fake")

        val logger = Me7LoggerProcess()
        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.ME7LOGGER_EXE,
            me7loggerPath = me7loggerExe.absolutePath,
            ecuFile = ecuFile.absolutePath,
            cfgFile = "",
            selectedVariableNames = emptyList()
        ))

        assertEquals(LoggerStatus.ERROR, logger.status.value)
        assertTrue(logger.statusMessage.value.contains("No .cfg file or variables selected"))
    }

    // ── Preset intersection logic ────────────────────────────────────────

    @Test
    fun `BASIC_VARIABLES preset intersects correctly with available entries`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)
        val parsed = EcuFileParser.parse(ecuFile)
        val availableNames = parsed.entries.keys

        val basicNames = EcuWriter.BASIC_VARIABLES.map { it.first }.toSet()
        val intersection = basicNames.intersect(availableNames)

        // Our test .ecu has: nmot_w, rl_w, pvdks_w, plsol_w, ldtvm, fr_w, zwist, tmot, vfil_w
        // From BASIC: nmot_w, rl_w, pvdks_w, plsol_w, ldtvm, fr_w, zwist, tmot, vfil_w = 9
        assertEquals(9, intersection.size)
        assertTrue("nmot_w" in intersection)
        assertTrue("rl_w" in intersection)
        assertTrue("pvdks_w" in intersection)
        assertTrue("plsol_w" in intersection)
        assertTrue("ldtvm" in intersection)
        assertTrue("fr_w" in intersection)
        assertTrue("zwist" in intersection)
        assertTrue("tmot" in intersection)
        assertTrue("vfil_w" in intersection)
        // Not in our test .ecu:
        assertFalse("mshfm_w" in intersection)
        assertFalse("fra_w" in intersection)
    }

    @Test
    fun `LDRPID_VARIABLES preset intersects correctly with available entries`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)
        val parsed = EcuFileParser.parse(ecuFile)
        val availableNames = parsed.entries.keys

        val ldrpidNames = EcuWriter.LDRPID_VARIABLES.map { it.first }.toSet()
        val intersection = ldrpidNames.intersect(availableNames)

        // Our test .ecu has: nmot_w, rl_w, pvdks_w, plsol_w, ldtvm, zwist, vfil_w, gangi = 8 from LDRPID
        assertEquals(8, intersection.size)
        assertTrue("nmot_w" in intersection)
        assertTrue("gangi" in intersection)
        assertFalse("pssol_w" in intersection)
    }

    // ── .cfg round-trip (save and reload) ────────────────────────────────

    @Test
    fun `save selection as cfg and reload preserves variable names`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val selectedNames = listOf("nmot_w", "rl_w", "ldtvm", "zwist")
        val cfgFile = File(dir, "custom.cfg")

        EcuWriter.writeCfgFile(
            outputFile = cfgFile,
            ecuFilename = "test.ecu",
            variables = selectedNames.map { Triple(it, "", "") },
            description = "Custom log configuration"
        )

        assertTrue(cfgFile.exists())
        val parsed = CfgFileParser.parse(cfgFile)
        assertEquals("test.ecu", parsed.ecuFilename)
        assertEquals(4, parsed.variables.size)
        assertEquals(selectedNames, parsed.variables.map { it.name })
    }

    @Test
    fun `save selection as cfg preserves aliases from ecu entries`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val ecuFile = createTestEcuFile(dir)
        val parsed = EcuFileParser.parse(ecuFile)

        val selectedNames = listOf("nmot_w", "ldtvm")
        val triples = selectedNames.map { name ->
            val entry = parsed.entries[name]!!
            Triple(name, entry.alias, entry.comment)
        }

        val cfgFile = File(dir, "with_aliases.cfg")
        EcuWriter.writeCfgFile(cfgFile, ecuFile.name, triples, description = "With aliases")

        val reloaded = CfgFileParser.parse(cfgFile)
        assertEquals(2, reloaded.variables.size)
        assertEquals("EngineSpeed", reloaded.variables[0].alias)
        assertEquals("WastegateDutyCycle", reloaded.variables[1].alias)
    }

    // ── Real .ecu file test (uses generated default .ecu) ────────────────

    @Test
    fun `variable selection works with real MED17 ecu file`() {
        val ecuPath = File("example/med17/MED17_D17162A01C000.ecu")
        if (!ecuPath.exists()) return  // Skip if not available

        val parsed = EcuFileParser.parse(ecuPath)
        assertTrue(parsed.entries.size > 1000, "MED17 .ecu should have many entries")

        // Verify key variables are present
        val keyVars = listOf("nmot_w", "rl_w", "ldtvm", "frm_w", "plsol_w")
        for (name in keyVars) {
            assertNotNull(parsed.entries[name], "MED17 .ecu should contain $name")
        }

        // Verify BASIC_VARIABLES preset intersection
        val basicNames = EcuWriter.BASIC_VARIABLES.map { it.first }.toSet()
        val basicIntersection = basicNames.intersect(parsed.entries.keys)
        assertTrue(basicIntersection.size >= 10,
            "At least 10 BASIC vars should be in MED17 .ecu, got ${basicIntersection.size}")
    }
}
