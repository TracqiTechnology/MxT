package data.logger

import data.parser.a2l.A2lParser
import data.writer.EcuWriter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File

/**
 * Validates generated .ecu and .cfg files for correctness.
 * Tests both the writer output format and cross-references with A2L parser.
 */
class EcuFileValidationTest {

    // ── ECU file format tests ──────────────────────────────────────────

    @Test
    fun `generated ECU file has all required sections`() {
        val entries = listOf(
            data.parser.a2l.EcuEntry("nmot_w", "EngineSpeed", 0xD001B244, 2, 0, "1/min", 0, 0, 0.25, 0.0, "Engine speed"),
            data.parser.a2l.EcuEntry("rl_w", "EngineLoad", 0xD001B300, 2, 0, "%", 0, 0, 0.01, 0.0, "Engine load"),
        )

        val tmpFile = File.createTempFile("ecu_sections_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile)

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        assertTrue(content.contains("[Version]"), "Missing [Version] section")
        assertTrue(content.contains("[Communication]"), "Missing [Communication] section")
        assertTrue(content.contains("[Identification]"), "Missing [Identification] section")
        assertTrue(content.contains("[Measurements]"), "Missing [Measurements] section")
    }

    @Test
    fun `ECU file version is 1_10`() {
        val entries = listOf(
            data.parser.a2l.EcuEntry("test", "", 0x1000, 1, 0, "", 0, 0, 1.0, 0.0, ""),
        )

        val tmpFile = File.createTempFile("ecu_version_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile)

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        assertTrue(content.contains("Version           = 1.10"), "Version should be 1.10")
    }

    @Test
    fun `ECU file communication section has correct K-line params`() {
        val entries = listOf(
            data.parser.a2l.EcuEntry("test", "", 0x1000, 1, 0, "", 0, 0, 1.0, 0.0, ""),
        )

        val tmpFile = File.createTempFile("ecu_comm_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile)

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        assertTrue(content.contains("SLOW-0x01"), "Should use 5-baud init at 0x01")
        assertTrue(content.contains("HM0"), "Should use headerless mode")
        assertTrue(content.contains("56000"), "Should use 56000 baud")
    }

    @Test
    fun `ECU file identification contains part and SW numbers`() {
        val entries = listOf(
            data.parser.a2l.EcuEntry("test", "", 0x1000, 1, 0, "", 0, 0, 1.0, 0.0, ""),
        )

        val tmpFile = File.createTempFile("ecu_ident_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile, partNumber = "1K0907115S", swNumber = "0261S02469")

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        assertTrue(content.contains("{1K0907115S}"), "Missing part number")
        assertTrue(content.contains("{0261S02469}"), "Missing SW number")
    }

    @Test
    fun `ECU measurement entries have correct format`() {
        val entries = listOf(
            data.parser.a2l.EcuEntry("nmot_w", "EngineSpeed", 0xD001B244, 2, 0, "1/min", 0, 0, 0.25, 0.0, "Engine speed"),
        )

        val tmpFile = File.createTempFile("ecu_meas_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile)

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        val measSection = content.substringAfter("[Measurements]")

        assertTrue(measSection.contains("nmot_w"), "Missing signal name")
        assertTrue(measSection.contains("{EngineSpeed}"), "Missing alias")
        assertTrue(measSection.contains("0xD001B244"), "Missing address")
        assertTrue(measSection.contains("{1/min}"), "Missing unit")
        assertTrue(measSection.contains("0.25"), "Missing factor")
        assertTrue(measSection.contains("{Engine speed}"), "Missing comment")
    }

    @Test
    fun `ECU entries with inverse conversion flag correctly`() {
        val entries = listOf(
            data.parser.a2l.EcuEntry("inv_sig", "InverseSignal", 0x5000, 2, 0, "ms", 0, 1, 50.0, 0.0, "Inverse conversion"),
        )

        val tmpFile = File.createTempFile("ecu_inverse_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile)

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        val measLine = content.lines().first { it.startsWith("inv_sig") }
        // Format: name, alias, addr, size, bitmask, unit, signed, inverse, factor, offset, comment
        // The inverse flag should be 1
        assertTrue(measLine.contains(", 0, 1,"), "Inverse flag should be 1")
    }

    @Test
    fun `ECU entries with signed byte flag correctly`() {
        val entries = listOf(
            data.parser.a2l.EcuEntry("tmot", "CoolantTemp", 0x8100, 1, 0, "C", 1, 0, 0.75, -48.0, "Signed byte"),
        )

        val tmpFile = File.createTempFile("ecu_signed_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile)

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        val measLine = content.lines().first { it.startsWith("tmot") }
        // signed=1, inverse=0
        assertTrue(measLine.contains(", 1, 0,"), "Signed flag should be 1, inverse 0")
    }

    // ── CFG file tests ─────────────────────────────────────────────────

    @Test
    fun `CFG file references ECU filename`() {
        val tmpFile = File.createTempFile("cfg_ref_", ".cfg")
        tmpFile.deleteOnExit()
        EcuWriter.writeCfgFile(tmpFile, "MED9_0261S02469.ecu", EcuWriter.BASIC_VARIABLES)

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        assertTrue(content.contains("ECUCharacteristics = MED9_0261S02469.ecu"))
    }

    @Test
    fun `CFG basic preset has all expected tuning signals`() {
        val names = EcuWriter.BASIC_VARIABLES.map { it.first }
        val expected = listOf("nmot_w", "rl_w", "pvdks_w", "fr_w", "fra_w", "ti_w",
            "zwist", "lamsbg_w", "lamsoni_w", "tmot", "tans", "wub", "vfil_w")
        for (sig in expected) {
            assertTrue(sig in names, "Basic preset missing: $sig")
        }
    }

    @Test
    fun `CFG LDRPID preset has boost control signals`() {
        val names = EcuWriter.LDRPID_VARIABLES.map { it.first }
        val expected = listOf("nmot_w", "pvdks_w", "plsol_w", "ldtvm", "wped_w", "zwist")
        for (sig in expected) {
            assertTrue(sig in names, "LDRPID preset missing: $sig")
        }
    }

    // ── Cross-reference: A2L parser → ECU writer ───────────────────────

    companion object {
        private val DAMOS_A2L = File("/tmp/med9_damos/Golf V 2.0 GTI TFSI 0261S02469 387445 Damos.A2L")

        @JvmStatic
        fun damosAvailable(): Boolean = DAMOS_A2L.exists()
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `A2L parse then ECU write produces valid file`() {
        val result = A2lParser.parse(DAMOS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)

        val tmpFile = File.createTempFile("full_ecu_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile, partNumber = "1K0907115S", swNumber = "0261S02469")

        val content = tmpFile.readText(Charsets.ISO_8859_1)

        // All sections present
        assertTrue(content.contains("[Version]"))
        assertTrue(content.contains("[Communication]"))
        assertTrue(content.contains("[Identification]"))
        assertTrue(content.contains("[Measurements]"))

        // Key signals present in output
        val keySignals = listOf("nmot_w", "rl_w", "pvdks_w", "fr_w", "fra_w", "ti_w",
            "zwist", "prist_w", "ldtvm", "tmot", "tans", "wub")
        for (sig in keySignals) {
            assertTrue(content.contains(sig), "Missing key signal in .ecu: $sig")
        }
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `A2L entries and ECU output have matching addresses`() {
        val result = A2lParser.parse(DAMOS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)

        val tmpFile = File.createTempFile("addr_check_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile)

        val content = tmpFile.readText(Charsets.ISO_8859_1)

        // Verify key signals have their addresses in the output
        val nmot = entries.first { it.name == "nmot_w" }
        val addrHex = String.format("0x%06X", nmot.address)
        assertTrue(content.contains(addrHex),
            "nmot_w address $addrHex not found in .ecu output")

        val rl = entries.first { it.name == "rl_w" }
        val rlAddrHex = String.format("0x%06X", rl.address)
        assertTrue(content.contains(rlAddrHex),
            "rl_w address $rlAddrHex not found in .ecu output")
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `A2L-derived ECU has correct conversion for nmot_w`() {
        val result = A2lParser.parse(DAMOS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)

        val nmot = entries.first { it.name == "nmot_w" }
        assertEquals("EngineSpeed", nmot.alias)
        assertEquals(2, nmot.size, "nmot_w should be UWORD (2 bytes)")
        assertEquals(0, nmot.signed, "nmot_w should be unsigned")
        assertEquals(0, nmot.inverse, "nmot_w should not be inverse")
        assertTrue(nmot.factor > 0, "nmot_w factor should be positive: ${nmot.factor}")
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `A2L-derived ECU CFG basic preset signals exist in ECU entries`() {
        val result = A2lParser.parse(DAMOS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        val entryNames = entries.map { it.name }.toSet()

        for ((name, _, _) in EcuWriter.BASIC_VARIABLES) {
            assertTrue(name in entryNames,
                "Basic CFG signal '$name' not found in A2L-derived ECU entries")
        }
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `A2L-derived ECU CFG LDRPID preset signals exist in ECU entries`() {
        val result = A2lParser.parse(DAMOS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        val entryNames = entries.map { it.name }.toSet()

        for ((name, _, _) in EcuWriter.LDRPID_VARIABLES) {
            assertTrue(name in entryNames,
                "LDRPID CFG signal '$name' not found in A2L-derived ECU entries")
        }
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `A2L-derived ECU has no duplicate addresses for same-name entries`() {
        val result = A2lParser.parse(DAMOS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)

        // Group by name — each name should appear at most once
        val duplicateNames = entries.groupBy { it.name }.filter { it.value.size > 1 }
        assertTrue(duplicateNames.isEmpty(),
            "Found duplicate entry names: ${duplicateNames.keys.take(5)}")
    }

    @Test
    @EnabledIf("damosAvailable")
    fun `generated ECU entry count matches A2L parser output`() {
        val result = A2lParser.parse(DAMOS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)

        // Should be 14000+ entries (filtered to 1-2 byte vars)
        assertTrue(entries.size > 14000,
            "Expected 14000+ entries, got ${entries.size}")

        // Write and verify the count matches lines in [Measurements]
        val tmpFile = File.createTempFile("count_check_", ".ecu")
        tmpFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries, tmpFile)

        val content = tmpFile.readText(Charsets.ISO_8859_1)
        val measLines = content.substringAfter("[Measurements]")
            .lines()
            .filter { it.isNotBlank() && !it.startsWith(";") }

        assertEquals(entries.size, measLines.size,
            "Written line count should match entry count")
    }
}
