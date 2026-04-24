package data.parser.ecu

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class CfgFileParserTest {

    private val fixtureFile = File("example/med9/MED9_0261S02469_basic.cfg")

    @Test
    fun `parse configuration section`() {
        val cfg = CfgFileParser.parse(fixtureFile)
        assertEquals("MED9_0261S02469.ecu", cfg.ecuFilename)
        assertEquals(20, cfg.samplesPerSecond)
    }

    @Test
    fun `parse log variables`() {
        val cfg = CfgFileParser.parse(fixtureFile)
        assertTrue(cfg.variables.isNotEmpty(), "Should have log variables")

        val nmot = cfg.variables.find { it.name == "nmot_w" }
        assertNotNull(nmot, "nmot_w should be in variable list")
        assertEquals("EngineSpeed", nmot!!.alias)
    }

    @Test
    fun `variable count matches fixture`() {
        val cfg = CfgFileParser.parse(fixtureFile)
        // The basic .cfg has ~18 variables (counted from fixture)
        assertTrue(cfg.variables.size >= 10, "Expected at least 10 variables, got ${cfg.variables.size}")
    }

    @Test
    fun `parse variable aliases`() {
        val cfg = CfgFileParser.parse(fixtureFile)
        val varMap = cfg.variables.associateBy { it.name }

        assertEquals("SelectedGear", varMap["gangi"]?.alias)
        assertEquals("VehicleSpeed", varMap["vfil_w"]?.alias)
        assertEquals("BatteryVoltage", varMap["wub"]?.alias)
        assertEquals("CoolantTemperature", varMap["tmot"]?.alias)
    }

    @Test
    fun `parse variable comments`() {
        val cfg = CfgFileParser.parse(fixtureFile)
        val nmot = cfg.variables.find { it.name == "nmot_w" }
        assertNotNull(nmot)
        assertTrue(nmot!!.comment.isNotEmpty(), "Comment should be populated")
    }

    @Test
    fun `cross-reference variables against ecu file`() {
        val cfg = CfgFileParser.parse(fixtureFile)
        val ecu = EcuFileParser.parse(File("example/med9/MED9_0261S02469.ecu"))

        // Every .cfg variable should exist in the .ecu file
        for (cfgVar in cfg.variables) {
            assertNotNull(
                ecu.entries[cfgVar.name],
                "Variable '${cfgVar.name}' from .cfg not found in .ecu entries"
            )
        }
    }

    @Test
    fun `parse from string`() {
        val text = """
            ; Test config

            [Configuration]
            ECUCharacteristics = test.ecu
            SamplesPerSecond   = 30

            [LogVariables]
            rpm_w          ;{RPM}              ; {Engine speed}
            load_w         ;{Load}             ; {Engine load}
            boost_w        ;{Boost}            ; {Boost pressure}
        """.trimIndent()

        val cfg = CfgFileParser.parse(text)
        assertEquals("test.ecu", cfg.ecuFilename)
        assertEquals(30, cfg.samplesPerSecond)
        assertEquals(3, cfg.variables.size)

        assertEquals("rpm_w", cfg.variables[0].name)
        assertEquals("RPM", cfg.variables[0].alias)
        assertEquals("Engine speed", cfg.variables[0].comment)

        assertEquals("load_w", cfg.variables[1].name)
        assertEquals("Load", cfg.variables[1].alias)
    }

    @Test
    fun `parse full config file`() {
        val fullCfg = CfgFileParser.parse(File("example/med9/MED9_0261S02469_full.cfg"))
        assertTrue(fullCfg.variables.size > cfg_basic_count(),
            "Full config should have more variables than basic")
    }

    private fun cfg_basic_count(): Int =
        CfgFileParser.parse(fixtureFile).variables.size
}
