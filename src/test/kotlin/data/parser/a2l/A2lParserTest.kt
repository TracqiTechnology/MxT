package data.parser.a2l

import data.writer.EcuWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File

class A2lParserTest {

    // Synthetic A2L snippet for deterministic unit tests
    private val SYNTHETIC_A2L = """
        /begin COMPU_METHOD CM_nmot_w "Engine speed conversion" RAT_FUNC "%6.1" "1/min" COEFFS 0 4.0 0.0 0 0 1.0
        /end COMPU_METHOD

        /begin COMPU_METHOD CM_rl_w "Load conversion" RAT_FUNC "%5.1" "%" COEFFS 0 100.0 0.0 0 0 1.0
        /end COMPU_METHOD

        /begin COMPU_METHOD CM_tmot "Temp conversion" RAT_FUNC "%5.1" "°C" COEFFS 0 1.0 -48.0 0 0 0.75
        /end COMPU_METHOD

        /begin COMPU_METHOD CM_inverse "Inverse example" RAT_FUNC "%5.2" "ms" COEFFS 0 0 100.0 0 2.0 0
        /end COMPU_METHOD

        /begin COMPU_METHOD NO_COMPU_METHOD "identity" RAT_FUNC "%5.0" "" COEFFS 0 1 0 0 0 1
        /end COMPU_METHOD

        /begin MEASUREMENT nmot_w "Engine speed" UWORD CM_nmot_w 0 0 0 65535
            ECU_ADDRESS 0xD001B244
        /end MEASUREMENT

        /begin MEASUREMENT rl_w "Engine load" UWORD CM_rl_w 0 0 0 65535
            ECU_ADDRESS 0xD001B300
        /end MEASUREMENT

        /begin MEASUREMENT tmot "Coolant temperature" SBYTE CM_tmot 0 0 -48 150
            ECU_ADDRESS 0xD0018100
        /end MEASUREMENT

        /begin MEASUREMENT test_inv "Inverse test" UWORD CM_inverse 0 0 0 65535
            ECU_ADDRESS 0xD0019000
        /end MEASUREMENT

        /begin MEASUREMENT dwkrz "Knock retard per cyl" UBYTE NO_COMPU_METHOD 0 0 0 255
            ECU_ADDRESS 0x7FC215
            ARRAY_SIZE 8
        /end MEASUREMENT

        /begin MEASUREMENT no_addr "Missing address signal" UWORD CM_rl_w 0 0 0 100
        /end MEASUREMENT
    """.trimIndent()

    @Test
    fun `parse COMPU_METHODs from synthetic A2L`() {
        val result = A2lParser.parse(SYNTHETIC_A2L)
        assertEquals(5, result.compuMethods.size)

        val nmotCm = result.compuMethods["CM_nmot_w"]!!
        assertEquals("Engine speed conversion", nmotCm.description)
        assertEquals("1/min", nmotCm.unit)
        assertArrayEquals(doubleArrayOf(0.0, 4.0, 0.0, 0.0, 0.0, 1.0), nmotCm.coeffs)
    }

    @Test
    fun `parse MEASUREMENTs from synthetic A2L`() {
        val result = A2lParser.parse(SYNTHETIC_A2L)
        // 5 base + 4 expanded from dwkrz array - 1 (no_addr skipped) = 8
        // nmot_w, rl_w, tmot, test_inv, dwkrz_0, dwkrz_1, dwkrz_2, dwkrz_3
        assertEquals(8, result.measurements.size)

        val nmot = result.measurements.first { it.name == "nmot_w" }
        assertEquals("UWORD", nmot.dataType)
        assertEquals("CM_nmot_w", nmot.compuMethodName)
        assertEquals(0xD001B244L, nmot.ecuAddress)
    }

    @Test
    fun `skip measurements without ECU_ADDRESS`() {
        val result = A2lParser.parse(SYNTHETIC_A2L)
        assertFalse(result.measurements.any { it.name == "no_addr" })
    }

    @Test
    fun `expand ARRAY_SIZE entries for per-cylinder signals`() {
        val result = A2lParser.parse(SYNTHETIC_A2L)
        val expanded = result.measurements.filter { it.name.startsWith("dwkrz_") }
        assertEquals(4, expanded.size)  // Only expand 4 (cylinders), not 8

        assertEquals("dwkrz_0", expanded[0].name)
        assertEquals(0x7FC215L, expanded[0].ecuAddress)
        assertEquals("dwkrz_1", expanded[1].name)
        assertEquals(0x7FC216L, expanded[1].ecuAddress)  // +1 byte for UBYTE
        assertEquals("dwkrz_2", expanded[2].name)
        assertEquals(0x7FC217L, expanded[2].ecuAddress)
        assertEquals("dwkrz_3", expanded[3].name)
        assertEquals(0x7FC218L, expanded[3].ecuAddress)

        // Descriptions should mention cylinder number
        assertTrue(expanded[0].description.contains("Zyl 1"))
        assertTrue(expanded[3].description.contains("Zyl 4"))
    }

    @Test
    fun `linear COMPU_METHOD conversion`() {
        // CM_nmot_w: COEFFS 0 4 0 0 0 1 -> Factor = 1/4 = 0.25, Offset = 0
        val cm = A2lCompuMethod("test", "", "rpm", doubleArrayOf(0.0, 4.0, 0.0, 0.0, 0.0, 1.0))
        val conv = A2lParser.compuToEcuConversion(cm)
        assertEquals(0.25, conv.factor, 1e-10)
        assertEquals(0.0, conv.offset, 1e-10)
        assertEquals(0, conv.inverse)
    }

    @Test
    fun `linear COMPU_METHOD with offset`() {
        // CM_tmot: COEFFS 0 1 -48 0 0 0.75 -> Factor = 0.75/1 = 0.75, Offset = -48/1 = -48
        val cm = A2lCompuMethod("test", "", "deg C", doubleArrayOf(0.0, 1.0, -48.0, 0.0, 0.0, 0.75))
        val conv = A2lParser.compuToEcuConversion(cm)
        assertEquals(0.75, conv.factor, 1e-10)
        assertEquals(-48.0, conv.offset, 1e-10)
        assertEquals(0, conv.inverse)
    }

    @Test
    fun `inverse COMPU_METHOD conversion`() {
        // CM_inverse: COEFFS 0 0 100 0 2 0 -> Factor = 100/2 = 50, Offset = 0
        val cm = A2lCompuMethod("test", "", "ms", doubleArrayOf(0.0, 0.0, 100.0, 0.0, 2.0, 0.0))
        val conv = A2lParser.compuToEcuConversion(cm)
        assertEquals(50.0, conv.factor, 1e-10)
        assertEquals(0.0, conv.offset, 1e-10)
        assertEquals(1, conv.inverse)
    }

    @Test
    fun `identity COMPU_METHOD conversion`() {
        // NO_COMPU_METHOD: COEFFS 0 1 0 0 0 1 -> Factor = 1, Offset = 0
        val cm = A2lCompuMethod("test", "", "", doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        val conv = A2lParser.compuToEcuConversion(cm)
        assertEquals(1.0, conv.factor, 1e-10)
        assertEquals(0.0, conv.offset, 1e-10)
        assertEquals(0, conv.inverse)
    }

    @Test
    fun `build ECU entries resolves aliases`() {
        val result = A2lParser.parse(SYNTHETIC_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)

        val nmot = entries.first { it.name == "nmot_w" }
        assertEquals("EngineSpeed", nmot.alias)
        assertEquals(2, nmot.size)
        assertEquals(0, nmot.signed)

        val tmot = entries.first { it.name == "tmot" }
        assertEquals("CoolantTemperature", tmot.alias)
        assertEquals(1, tmot.size)
        assertEquals(1, tmot.signed)

        // Array element aliases
        val cyl1 = entries.first { it.name == "dwkrz_0" }
        assertEquals("IgnitionRetardCyl1", cyl1.alias)
    }

    @Test
    fun `ECU entries are sorted by address`() {
        val result = A2lParser.parse(SYNTHETIC_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        for (i in 1 until entries.size) {
            assertTrue(entries[i].address >= entries[i - 1].address,
                "Entries not sorted: ${entries[i-1].name}@${entries[i-1].address} > ${entries[i].name}@${entries[i].address}")
        }
    }

    @Test
    fun `unit extraction picks correct quoted string`() {
        val result = A2lParser.parse(SYNTHETIC_A2L)
        val nmotCm = result.compuMethods["CM_nmot_w"]!!
        assertEquals("1/min", nmotCm.unit)  // Unit, not format "%6.1"

        val tmotCm = result.compuMethods["CM_tmot"]!!
        // The A2L uses °C but encoding may vary - just verify it's not the format string
        assertNotEquals("%5.1", tmotCm.unit)
        assertTrue(tmotCm.unit.contains("C"), "Unit should contain 'C', got: ${tmotCm.unit}")
    }

    // Tests that use the real MED9 DAMOS A2L file (large, ~600K lines)
    companion object {
        private val DAMOS_A2L = File("/tmp/med9_damos/Golf V 2.0 GTI TFSI 0261S02469 387445 Damos.A2L")

        @JvmStatic
        fun damosAvailable(): Boolean = DAMOS_A2L.exists()
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `parse real MED9 DAMOS - COMPU_METHODs`() {
        val result = A2lParser.parse(DAMOS_A2L)
        assertTrue(result.compuMethods.size > 500, "Expected 500+ COMPU_METHODs, got ${result.compuMethods.size}")
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `parse real MED9 DAMOS - MEASUREMENTs`() {
        val result = A2lParser.parse(DAMOS_A2L)
        assertTrue(result.measurements.size > 14000, "Expected 14000+ measurements, got ${result.measurements.size}")

        // Verify key signals exist
        val names = result.measurements.map { it.name }.toSet()
        val keySignals = listOf("nmot_w", "rl_w", "mshfm_w", "pvdks_w", "ldtvm", "fr_w", "fra_w",
            "ti_w", "wdkba", "tmot", "tans", "wub", "vfil_w", "lamsbg_w", "lamsoni_w",
            "zwist", "prist_w", "prsoll_w", "wped_w", "gangi")

        for (signal in keySignals) {
            assertTrue(signal in names, "Missing key signal: $signal")
        }
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `real DAMOS generates 14K+ ECU entries`() {
        val result = A2lParser.parse(DAMOS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        assertTrue(entries.size > 14000, "Expected 14000+ entries, got ${entries.size}")

        // All aliased entries should have non-empty aliases
        val aliased = entries.filter { A2lParser.ALIASES.containsKey(it.name) }
        for (e in aliased) {
            assertTrue(e.alias.isNotEmpty(), "Entry ${e.name} should have alias but doesn't")
        }
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `real DAMOS nmot_w has correct conversion`() {
        val result = A2lParser.parse(DAMOS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        val nmot = entries.first { it.name == "nmot_w" }
        assertEquals("EngineSpeed", nmot.alias)
        assertEquals(2, nmot.size)
        assertEquals(0, nmot.signed)
        assertTrue(nmot.factor > 0, "nmot_w factor should be positive")
    }
}

class EcuWriterTest {

    @Test
    fun `write and read ECU file round-trip`() {
        val entries = listOf(
            EcuEntry("nmot_w", "EngineSpeed", 0xD001B244, 2, 0xFFFF, "1/min", 0, 0, 0.25, 0.0, "Engine speed"),
            EcuEntry("tmot", "CoolantTemperature", 0xD0018100, 1, 0x00FF, "deg C", 1, 0, 0.75, -48.0, "Coolant temp"),
        )

        val tmpFile = File.createTempFile("test_", ".ecu")
        tmpFile.deleteOnExit()

        EcuWriter.writeEcuFile(entries, tmpFile, partNumber = "TEST123", swNumber = "SW001", engineId = "Test Engine")

        val content = tmpFile.readText(Charsets.ISO_8859_1)

        // Verify sections exist
        assertTrue(content.contains("[Version]"))
        assertTrue(content.contains("[Communication]"))
        assertTrue(content.contains("[Identification]"))
        assertTrue(content.contains("[Measurements]"))

        // Verify metadata
        assertTrue(content.contains("TEST123"))
        assertTrue(content.contains("SW001"))
        assertTrue(content.contains("Test Engine"))

        // Verify entries
        assertTrue(content.contains("nmot_w"))
        assertTrue(content.contains("{EngineSpeed}"))
        assertTrue(content.contains("0xD001B2"))
        assertTrue(content.contains("tmot"))
        assertTrue(content.contains("{CoolantTemperature}"))
    }

    @Test
    fun `write CFG file round-trip`() {
        val tmpFile = File.createTempFile("test_", ".cfg")
        tmpFile.deleteOnExit()

        EcuWriter.writeCfgFile(
            tmpFile,
            ecuFilename = "test.ecu",
            variables = EcuWriter.BASIC_VARIABLES,
            samplesPerSecond = 20,
            description = "Test config"
        )

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        assertTrue(content.contains("[Configuration]"))
        assertTrue(content.contains("ECUCharacteristics = test.ecu"))
        assertTrue(content.contains("SamplesPerSecond   = 20"))
        assertTrue(content.contains("[LogVariables]"))
        assertTrue(content.contains("nmot_w"))
        assertTrue(content.contains("rl_w"))
    }

    @Test
    fun `basic variables list contains essential signals`() {
        val names = EcuWriter.BASIC_VARIABLES.map { it.first }.toSet()
        assertTrue("nmot_w" in names, "Missing nmot_w")
        assertTrue("rl_w" in names, "Missing rl_w")
        assertTrue("pvdks_w" in names, "Missing pvdks_w")
        assertTrue("fr_w" in names, "Missing fr_w")
        assertTrue("zwist" in names, "Missing zwist")
    }

    @Test
    fun `LDRPID variables list contains boost signals`() {
        val names = EcuWriter.LDRPID_VARIABLES.map { it.first }.toSet()
        assertTrue("ldtvm" in names, "Missing ldtvm")
        assertTrue("plsol_w" in names, "Missing plsol_w")
        assertTrue("pvdks_w" in names, "Missing pvdks_w")
    }
}
