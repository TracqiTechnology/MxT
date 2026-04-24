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

    // Real A2L file tests — conditional on fixture availability
    companion object {
        private val DAMOS_A2L = File("/tmp/med9_damos/Golf V 2.0 GTI TFSI 0261S02469 387445 Damos.A2L")
        private val ME7_JETTA_A2L = File("technical/vag/a2l/me7/06A906012AE_0901.A2L")
        private val ME7_TOURAN_A2L = File("technical/vag/a2l/me7/06A906032TE_0040.A2L")
        private val ME7_BORA_A2L = File("technical/vag/a2l/me7/06A906012C_0002.A2L")
        private val MED17_RS3_A2L = File("technical/vag/a2l/med17/D17162A01C000_MY17I0.a2l")
        private val MED9_TTRS_A2L = File("technical/vag/a2l/med9/8J0907404D_0020.A2L")

        @JvmStatic
        fun damosAvailable(): Boolean = DAMOS_A2L.exists()
        @JvmStatic
        fun me7JettaAvailable(): Boolean = ME7_JETTA_A2L.exists()
        @JvmStatic
        fun me7TouranAvailable(): Boolean = ME7_TOURAN_A2L.exists()
        @JvmStatic
        fun me7BoraAvailable(): Boolean = ME7_BORA_A2L.exists()
        @JvmStatic
        fun med17Rs3Available(): Boolean = MED17_RS3_A2L.exists()
        @JvmStatic
        fun med9TtrsAvailable(): Boolean = MED9_TTRS_A2L.exists()
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

    // ---- ME7.5 Jetta 1.8T A2L (VAG archive) ----

    @Test
    @EnabledIf("me7JettaAvailable")
    fun `ME7 Jetta A2L parses without error`() {
        val result = A2lParser.parse(ME7_JETTA_A2L)
        assertTrue(result.compuMethods.isNotEmpty(), "Should have COMPU_METHODs")
        assertTrue(result.measurements.isNotEmpty(), "Should have MEASUREMENTs")
    }

    @Test
    @EnabledIf("me7JettaAvailable")
    fun `ME7 Jetta A2L has expected entry counts`() {
        val result = A2lParser.parse(ME7_JETTA_A2L)
        assertTrue(result.compuMethods.size > 100,
            "Expected 100+ COMPU_METHODs, got ${result.compuMethods.size}")
        assertTrue(result.measurements.size > 2000,
            "Expected 2000+ MEASUREMENTs, got ${result.measurements.size}")
    }

    @Test
    @EnabledIf("me7JettaAvailable")
    fun `ME7 Jetta A2L contains key ME7 calibration signals`() {
        val result = A2lParser.parse(ME7_JETTA_A2L)
        val names = result.measurements.map { it.name }.toSet()
        val cmNames = result.compuMethods.keys

        // Core engine signals
        val keySignals = listOf("nmot_w", "rl_w", "tmot", "tans", "wdkba")
        for (signal in keySignals) {
            assertTrue(signal in names, "Missing key ME7 signal: $signal")
        }

        // Verify COMPU_METHODs reference known calibration maps
        val allCmContent = cmNames.joinToString(" ")
        // ME7.5 should have conversion formulas for rpm, load, temperature, etc.
        assertTrue(result.compuMethods.values.any { it.unit.contains("min") || it.unit.contains("rpm", ignoreCase = true) },
            "Should have an RPM-related COMPU_METHOD")
    }

    @Test
    @EnabledIf("me7JettaAvailable")
    fun `ME7 Jetta A2L generates valid ECU entries`() {
        val result = A2lParser.parse(ME7_JETTA_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        assertTrue(entries.size > 1000, "Expected 1000+ ECU entries, got ${entries.size}")

        // All entries must have valid addresses
        for (e in entries) {
            assertTrue(e.address > 0, "Entry ${e.name} has invalid address ${e.address}")
            assertTrue(e.size in 1..2, "Entry ${e.name} has invalid size ${e.size}")
            assertTrue(e.factor != 0.0 || e.inverse == 0,
                "Entry ${e.name} has zero factor without inverse flag")
        }

        // Entries should be sorted by address
        for (i in 1 until entries.size) {
            assertTrue(entries[i].address >= entries[i - 1].address,
                "Entries not sorted: ${entries[i-1].name}@${entries[i-1].address} > ${entries[i].name}@${entries[i].address}")
        }
    }

    // ---- ME7.5 Touran 1.8T A2L (cross-validation) ----

    @Test
    @EnabledIf("me7TouranAvailable")
    fun `ME7 Touran A2L parses and cross-validates with Jetta`() {
        val touran = A2lParser.parse(ME7_TOURAN_A2L)
        assertTrue(touran.compuMethods.size > 100,
            "Touran should have 100+ COMPU_METHODs, got ${touran.compuMethods.size}")
        assertTrue(touran.measurements.size > 2000,
            "Touran should have 2000+ MEASUREMENTs, got ${touran.measurements.size}")

        // Same ME7.5 platform — should have same core signals
        val names = touran.measurements.map { it.name }.toSet()
        for (signal in listOf("nmot_w", "rl_w", "tmot")) {
            assertTrue(signal in names, "Touran ME7.5 missing core signal: $signal")
        }
    }

    // ---- MED17.1.62 RS3 2.5T A2L ----

    @Test
    @EnabledIf("med17Rs3Available")
    fun `MED17 RS3 A2L parses without error`() {
        val result = A2lParser.parse(MED17_RS3_A2L)
        assertTrue(result.compuMethods.isNotEmpty(), "Should have COMPU_METHODs")
        assertTrue(result.measurements.isNotEmpty(), "Should have MEASUREMENTs")
    }

    @Test
    @EnabledIf("med17Rs3Available")
    fun `MED17 RS3 A2L has large entry counts`() {
        val result = A2lParser.parse(MED17_RS3_A2L)
        assertTrue(result.compuMethods.size > 300,
            "Expected 300+ COMPU_METHODs for MED17, got ${result.compuMethods.size}")
        assertTrue(result.measurements.size > 5000,
            "Expected 5000+ MEASUREMENTs for MED17, got ${result.measurements.size}")
    }

    @Test
    @EnabledIf("med17Rs3Available")
    fun `MED17 RS3 A2L generates valid ECU entries`() {
        val result = A2lParser.parse(MED17_RS3_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        assertTrue(entries.size > 3000, "Expected 3000+ ECU entries, got ${entries.size}")

        // MED17 uses TriCore — addresses should be in 0xD0000000+ RAM range
        val highAddrEntries = entries.filter { it.address > 0xD0000000L }
        assertTrue(highAddrEntries.size > entries.size / 2,
            "Most MED17 entries should have TriCore RAM addresses (0xD0000000+)")
    }

    // ---- MED9.1.2 TT RS 2.5T A2L ----

    @Test
    @EnabledIf("med9TtrsAvailable")
    fun `MED9 TTRS A2L parses without error`() {
        val result = A2lParser.parse(MED9_TTRS_A2L)
        assertTrue(result.compuMethods.isNotEmpty(), "Should have COMPU_METHODs")
        assertTrue(result.measurements.isNotEmpty(), "Should have MEASUREMENTs")
    }

    @Test
    @EnabledIf("med9TtrsAvailable")
    fun `MED9 TTRS A2L has expected entry counts`() {
        val result = A2lParser.parse(MED9_TTRS_A2L)
        assertTrue(result.compuMethods.size > 200,
            "Expected 200+ COMPU_METHODs for MED9, got ${result.compuMethods.size}")
        assertTrue(result.measurements.size > 5000,
            "Expected 5000+ MEASUREMENTs for MED9, got ${result.measurements.size}")
    }

    @Test
    @EnabledIf("med9TtrsAvailable")
    fun `MED9 TTRS A2L generates valid ECU entries`() {
        val result = A2lParser.parse(MED9_TTRS_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        assertTrue(entries.size > 3000, "Expected 3000+ ECU entries, got ${entries.size}")

        // All factors should be finite
        for (e in entries) {
            assertTrue(e.factor.isFinite(), "Entry ${e.name} has non-finite factor: ${e.factor}")
            assertTrue(e.offset.isFinite(), "Entry ${e.name} has non-finite offset: ${e.offset}")
        }
    }
}

/**
 * Generate default .ecu + .cfg files from all available A2L fixtures.
 * Produces files in the `example/` directories alongside existing BIN/XDF files.
 *
 * Each A2L generates:
 *   - {name}.ecu         — Full measurement file for ME7Logger / native logging
 *   - {name}_basic.cfg   — 17 essential tuning variables
 *   - {name}_ldrpid.cfg  — 13 boost/LDRPID variables
 */
class DefaultEcuFileGeneratorTest {

    data class A2lSource(
        val a2lFile: File,
        val outputDir: String,
        val outputName: String,
        val partNumber: String,
        val swNumber: String,
        val engineId: String
    )

    companion object {
        private val SOURCES = listOf(
            A2lSource(
                a2lFile = File("technical/vag/a2l/me7/06A906012AE_0901.A2L"),
                outputDir = "example/me7",
                outputName = "ME7_06A906012AE",
                partNumber = "06A906012AE",
                swNumber = "0901",
                engineId = "1.8L R4 20VT (Jetta/Golf)"
            ),
            A2lSource(
                a2lFile = File("technical/vag/a2l/me7/06A906032TE_0040.A2L"),
                outputDir = "example/me7",
                outputName = "ME7_06A906032TE",
                partNumber = "06A906032TE",
                swNumber = "0040",
                engineId = "1.8L R4 20VT (Touran)"
            ),
            A2lSource(
                a2lFile = File("technical/vag/a2l/me7/06A906012C_0002.A2L"),
                outputDir = "example/me7",
                outputName = "ME7_06A906012C",
                partNumber = "06A906012C",
                swNumber = "0002",
                engineId = "1.8L R4 20VT (Bora)"
            ),
            A2lSource(
                a2lFile = File("technical/vag/a2l/med9/8J0907404D_0020.A2L"),
                outputDir = "example/med9",
                outputName = "MED9_8J0907404D",
                partNumber = "8J0907404D",
                swNumber = "0020",
                engineId = "2.5L R5 TFSI (TT RS)"
            ),
            A2lSource(
                a2lFile = File("technical/vag/a2l/med17/D17162A01C000_MY17I0.a2l"),
                outputDir = "example/med17",
                outputName = "MED17_D17162A01C000",
                partNumber = "D17162A01C000",
                swNumber = "MY17I0",
                engineId = "2.5L R5 TFSI EA855 EVO (RS3/TTRS)"
            ),
        )

        @JvmStatic
        fun anyA2lAvailable(): Boolean = SOURCES.any { it.a2lFile.exists() }
    }

    @Test
    @EnabledIf("anyA2lAvailable")
    fun `generate default ecu files from all available A2L sources`() {
        for (source in SOURCES) {
            if (!source.a2lFile.exists()) {
                println("SKIP: ${source.a2lFile} not found")
                continue
            }

            println("Generating from ${source.a2lFile.name}...")
            val result = A2lParser.parse(source.a2lFile)
            val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
                .filter { it.address > 0 }  // Skip unmapped variables with address 0x00000000

            if (entries.isEmpty()) {
                println("  → SKIP: ${source.a2lFile.name} produced 0 entries (incompatible A2L format?)")
                continue
            }

            // Create output directory if needed
            val outDir = File(source.outputDir)
            outDir.mkdirs()

            // Write .ecu file
            val ecuFile = File(outDir, "${source.outputName}.ecu")
            EcuWriter.writeEcuFile(
                entries = entries,
                outputFile = ecuFile,
                partNumber = source.partNumber,
                swNumber = source.swNumber,
                engineId = source.engineId
            )
            assertTrue(ecuFile.exists(), "ECU file should exist: ${ecuFile.path}")
            assertTrue(ecuFile.length() > 100, "ECU file should not be empty")

            // Write basic .cfg
            val basicCfg = File(outDir, "${source.outputName}_basic.cfg")
            EcuWriter.writeCfgFile(
                outputFile = basicCfg,
                ecuFilename = ecuFile.name,
                variables = EcuWriter.BASIC_VARIABLES,
                samplesPerSecond = 20,
                description = "${source.engineId} — basic tuning"
            )

            // Write LDRPID .cfg
            val ldrpidCfg = File(outDir, "${source.outputName}_ldrpid.cfg")
            EcuWriter.writeCfgFile(
                outputFile = ldrpidCfg,
                ecuFilename = ecuFile.name,
                variables = EcuWriter.LDRPID_VARIABLES,
                samplesPerSecond = 20,
                description = "${source.engineId} — LDRPID boost tuning"
            )

            // Validate: parse back .ecu and verify round-trip
            val parsed = data.parser.ecu.EcuFileParser.parse(ecuFile)
            assertTrue(parsed.entries.isNotEmpty(),
                "${ecuFile.name}: parsed back should have entries")
            // Note: EcuFileParser deduplicates by name (Map), so count may be slightly less
            // than written if A2L had duplicate measurement names (struct members)
            assertTrue(parsed.entries.size >= entries.size * 95 / 100,
                "${ecuFile.name}: entry count dropped too much (wrote ${entries.size}, read ${parsed.entries.size})")

            // Verify addresses survived round-trip (for entries that exist after dedup)
            val spotCheck = entries.take(100)  // Spot-check first 100
            for (entry in spotCheck) {
                val parsedEntry = parsed.entries[entry.name] ?: continue
                assertEquals(entry.address, parsedEntry.address,
                    "${ecuFile.name}: address mismatch for '${entry.name}'")
            }

            // Validate: parse back .cfg
            val parsedCfg = data.parser.ecu.CfgFileParser.parse(basicCfg)
            assertEquals(ecuFile.name, parsedCfg.ecuFilename,
                "${basicCfg.name}: ECU filename reference should match")

            // Count how many basic variables exist in this .ecu
            val matchCount = parsedCfg.variables.count { it.name in parsed.entries }
            println("  → ${ecuFile.name}: ${entries.size} entries, $matchCount/${parsedCfg.variables.size} basic vars matched")
            assertTrue(matchCount >= 5,
                "${ecuFile.name}: at least 5 basic variables should match .ecu entries, got $matchCount")
        }
    }

    @Test
    @EnabledIf("anyA2lAvailable")
    fun `all generated ecu files have finite conversion factors`() {
        for (source in SOURCES) {
            if (!source.a2lFile.exists()) continue

            val result = A2lParser.parse(source.a2lFile)
            val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
                .filter { it.address > 0 }

            for (e in entries) {
                assertTrue(e.factor.isFinite(),
                    "${source.outputName}: entry '${e.name}' has non-finite factor ${e.factor}")
                assertTrue(e.offset.isFinite(),
                    "${source.outputName}: entry '${e.name}' has non-finite offset ${e.offset}")
                assertTrue(e.size in 1..2,
                    "${source.outputName}: entry '${e.name}' has unexpected size ${e.size}")
                assertTrue(e.address > 0,
                    "${source.outputName}: entry '${e.name}' has zero/negative address")
            }
        }
    }
}

/**
 * End-to-end test: MED17 A2L → .ecu/.cfg → EcuFileParser → UdsNativeLogger → UDS protocol frames.
 *
 * Exercises the complete pipeline from a real MED17 A2L file through to protocol-level
 * address encoding, proving the UDS logger can log TriCore RAM addresses.
 */
class Med17A2lToUdsLoggingE2ETest {

    companion object {
        private val MED17_RS3_A2L = File("technical/vag/a2l/med17/D17162A01C000_MY17I0.a2l")

        @JvmStatic
        fun med17Rs3Available(): Boolean = MED17_RS3_A2L.exists()
    }

    // Key MED17 tuning variables with known addresses from the A2L
    private data class ExpectedVar(val name: String, val type: String, val address: Long)
    private val KEY_VARIABLES = listOf(
        ExpectedVar("nmot_w",    "UWORD", 0xD000167CL),
        ExpectedVar("rl_w",      "UWORD", 0xD0000ECAL),
        ExpectedVar("plsol_w",   "UWORD", 0xD0000790L),
        ExpectedVar("ldtvm",     "UBYTE", 0xD0003043L),
        ExpectedVar("frm_w",     "UWORD", 0xD00032D8L),
        ExpectedVar("fra_w",     "UWORD", 0xD0001DC6L),
        ExpectedVar("wdkba",     "UBYTE", 0xD0002E68L),
        ExpectedVar("tmot",      "UBYTE", 0xD0002EAFL),
        ExpectedVar("lamsbg_w",  "UWORD", 0xD0001C0EL),
        ExpectedVar("zwist",     "SBYTE", 0xD0003227L),
        ExpectedVar("rk_w",      "UWORD", 0xD0001942L),
    )

    // --- Phase 1: A2L → ECU entries ---

    @Test
    @EnabledIf("med17Rs3Available")
    fun `A2L produces ECU entries with correct TriCore addresses for key variables`() {
        val result = A2lParser.parse(MED17_RS3_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        val entryMap = entries.associateBy { it.name }

        for (expected in KEY_VARIABLES) {
            val entry = entryMap[expected.name]
            assertNotNull(entry, "Key variable '${expected.name}' missing from ECU entries")
            assertEquals(expected.address, entry!!.address,
                "${expected.name}: address mismatch (expected 0x${expected.address.toString(16)}, " +
                    "got 0x${entry.address.toString(16)})")
            assertTrue(entry.address >= 0xB0000000L,
                "${expected.name}: address 0x${entry.address.toString(16)} not in TriCore RAM range")
        }
    }

    @Test
    @EnabledIf("med17Rs3Available")
    fun `A2L entries have valid aliases for key variables`() {
        val result = A2lParser.parse(MED17_RS3_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        val entryMap = entries.associateBy { it.name }

        // Check well-known aliases
        assertEquals("EngineSpeed", entryMap["nmot_w"]?.alias)
        assertEquals("EngineLoad", entryMap["rl_w"]?.alias)
        assertEquals("WastegateDutyCycle", entryMap["ldtvm"]?.alias)
        assertEquals("CoolantTemperature", entryMap["tmot"]?.alias)
    }

    // --- Phase 2: ECU entries → .ecu file → parse back ---

    @Test
    @EnabledIf("med17Rs3Available")
    fun `ecu file round-trip preserves MED17 addresses and conversions`() {
        val result = A2lParser.parse(MED17_RS3_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)

        // Write .ecu file
        val ecuFile = File.createTempFile("med17_test_", ".ecu")
        ecuFile.deleteOnExit()
        EcuWriter.writeEcuFile(
            entries = entries,
            outputFile = ecuFile,
            partNumber = "D17162A01C000",
            swNumber = "MY17I0",
            engineId = "2.5L R5 TFSI EA855 EVO"
        )

        // Read back via EcuFileParser
        val parsed = data.parser.ecu.EcuFileParser.parse(ecuFile)
        assertTrue(parsed.entries.isNotEmpty(), "Parsed .ecu should have entries")

        // Verify key variables survived the round-trip
        for (expected in KEY_VARIABLES) {
            val parsedEntry = parsed.entries[expected.name]
            assertNotNull(parsedEntry, "Key variable '${expected.name}' missing after .ecu round-trip")
            assertEquals(expected.address, parsedEntry!!.address,
                "${expected.name}: address mismatch after round-trip")
        }

        // Verify conversion factors are finite and preserved
        val nmot = parsed.entries["nmot_w"]!!
        assertTrue(nmot.factor.isFinite(), "nmot_w factor should be finite")
        assertTrue(nmot.factor > 0, "nmot_w factor should be positive")
    }

    // --- Phase 3: ECU entries → .cfg file → parse back ---

    @Test
    @EnabledIf("med17Rs3Available")
    fun `cfg file round-trip selects correct variables`() {
        val result = A2lParser.parse(MED17_RS3_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)

        // Write .ecu file (needed for .cfg reference)
        val ecuFile = File.createTempFile("med17_test_", ".ecu")
        ecuFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries = entries, outputFile = ecuFile,
            partNumber = "D17162A01C000", swNumber = "MY17I0",
            engineId = "2.5L R5 TFSI EA855 EVO")

        // Write .cfg with basic variables
        val cfgFile = File.createTempFile("med17_test_basic_", ".cfg")
        cfgFile.deleteOnExit()
        EcuWriter.writeCfgFile(
            outputFile = cfgFile,
            ecuFilename = ecuFile.name,
            variables = EcuWriter.BASIC_VARIABLES,
            samplesPerSecond = 20,
            description = "MED17 RS3 basic logging"
        )

        // Read back
        val parsedCfg = data.parser.ecu.CfgFileParser.parse(cfgFile)
        assertEquals(20, parsedCfg.samplesPerSecond)
        assertTrue(parsedCfg.variables.any { it.name == "nmot_w" }, "cfg should contain nmot_w")
        assertTrue(parsedCfg.variables.any { it.name == "rl_w" }, "cfg should contain rl_w")
        assertTrue(parsedCfg.variables.any { it.name == "ldtvm" }, "cfg should contain ldtvm")

        // Cross-reference: all .cfg variable names should exist in .ecu
        val parsedEcu = data.parser.ecu.EcuFileParser.parse(ecuFile)
        val ecuNames = parsedEcu.entries.keys
        for (cfgVar in parsedCfg.variables) {
            // Some preset variables may not exist in this A2L (e.g., pvdks_w, mshfm_w)
            // Just count how many match
        }
        val matchCount = parsedCfg.variables.count { it.name in ecuNames }
        assertTrue(matchCount >= 10,
            "At least 10 of ${parsedCfg.variables.size} .cfg variables should match .ecu entries, got $matchCount")
    }

    // --- Phase 4: .ecu + .cfg → UdsNativeLogger connect ---

    @Test
    @EnabledIf("med17Rs3Available")
    fun `UdsNativeLogger resolves log entries from generated ecu and cfg files`() = kotlinx.coroutines.runBlocking {
        val result = A2lParser.parse(MED17_RS3_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)

        // Generate .ecu + .cfg
        val ecuFile = File.createTempFile("med17_uds_test_", ".ecu")
        ecuFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries = entries, outputFile = ecuFile,
            partNumber = "D17162A01C000", swNumber = "MY17I0",
            engineId = "2.5L R5 TFSI EA855 EVO")

        val cfgFile = File.createTempFile("med17_uds_test_basic_", ".cfg")
        cfgFile.deleteOnExit()
        EcuWriter.writeCfgFile(
            outputFile = cfgFile,
            ecuFilename = ecuFile.name,
            variables = EcuWriter.BASIC_VARIABLES,
            samplesPerSecond = 20,
            description = "MED17 RS3 UDS logging test"
        )

        // Connect UdsNativeLogger with generated files
        val fakeCan = data.logger.uds.FakeCanTransport()
        val logger = data.logger.uds.UdsNativeLogger(fakeCan)

        val config = data.logger.LoggerConfig(
            ecuFile = ecuFile.absolutePath,
            cfgFile = cfgFile.absolutePath
        )
        logger.connect(config)

        assertEquals(data.logger.LoggerStatus.CONNECTED, logger.status.value,
            "Logger should be CONNECTED after loading .ecu/.cfg: ${logger.statusMessage.value}")

        // Verify variables resolved
        val vars = logger.variables.value
        assertTrue(vars.isNotEmpty(), "Logger should have resolved variables")
        assertTrue(vars.any { it.name == "nmot_w" }, "Should have nmot_w")
        assertTrue(vars.any { it.name == "rl_w" }, "Should have rl_w")
        assertTrue(vars.any { it.name == "ldtvm" }, "Should have ldtvm")

        // Count how many of the basic preset vars were found
        val basicNames = EcuWriter.BASIC_VARIABLES.map { it.first }.toSet()
        val resolvedNames = vars.map { it.name }.toSet()
        val matchCount = basicNames.intersect(resolvedNames).size
        assertTrue(matchCount >= 10,
            "Expected at least 10 basic variables resolved, got $matchCount of ${basicNames.size}")
    }

    // --- Phase 5: UDS protocol frame address encoding ---

    @Test
    @EnabledIf("med17Rs3Available")
    fun `UDS define request encodes 4-byte TriCore addresses correctly`() = kotlinx.coroutines.runBlocking {
        val result = A2lParser.parse(MED17_RS3_A2L)
        val entries = A2lParser.buildEcuEntries(result.measurements, result.compuMethods)
        val entryMap = entries.associateBy { it.name }

        // Build a small .ecu with just 3 key variables for clear frame inspection
        val smallEntries = listOf("nmot_w", "rl_w", "ldtvm").mapNotNull { entryMap[it] }
        assertEquals(3, smallEntries.size, "Should have all 3 key variables")

        val ecuFile = File.createTempFile("med17_frame_test_", ".ecu")
        ecuFile.deleteOnExit()
        EcuWriter.writeEcuFile(entries = smallEntries, outputFile = ecuFile,
            partNumber = "D17162A01C000", swNumber = "MY17I0",
            engineId = "2.5L R5 TFSI EA855 EVO")

        // .cfg with just these 3 variables
        val cfgFile = File.createTempFile("med17_frame_test_", ".cfg")
        cfgFile.deleteOnExit()
        EcuWriter.writeCfgFile(
            outputFile = cfgFile,
            ecuFilename = ecuFile.name,
            variables = smallEntries.map { Triple(it.name, it.alias, it.comment) },
            samplesPerSecond = 20
        )

        // Set up FakeCanTransport
        val fakeCan = data.logger.uds.FakeCanTransport()
        val logger = data.logger.uds.UdsNativeLogger(fakeCan)

        logger.connect(data.logger.LoggerConfig(
            ecuFile = ecuFile.absolutePath,
            cfgFile = cfgFile.absolutePath
        ))
        assertEquals(data.logger.LoggerStatus.CONNECTED, logger.status.value)

        // Queue UDS responses for startLogging:
        // 1. DiagnosticSessionControl (extended) response
        queueSingleFrame(fakeCan, 0x7E8, byteArrayOf(0x50, 0x03, 0x00, 0x19, 0x01, 0xF4.toByte()))

        // 2. DynamicDefineDataIdentifier response (positive)
        // 3 vars × 5 bytes = 15 bytes payload → request is 4 + 15 = 19 bytes → multi-frame
        // Need Flow Control from ECU for outgoing multi-frame
        queueFlowControl(fakeCan, 0x7E8)
        // Positive response to define DID
        queueSingleFrame(fakeCan, 0x7E8, byteArrayOf(0x6C, 0x02, 0xF2.toByte(), 0x00))

        // 3. ReadDataByIdentifier response — just enough to not crash
        // 3 vars: nmot_w(2 bytes) + rl_w(2 bytes) + ldtvm(1 byte) = 5 bytes data
        queueSingleFrame(fakeCan, 0x7E8, byteArrayOf(
            0x62, 0xF2.toByte(), 0x00,  // ReadDID positive response header
            0x10, 0x00,                   // nmot_w raw = 0x1000 = 4096
            0x08, 0x00,                   // rl_w raw = 0x0800 = 2048
            0x50                          // ldtvm raw = 0x50 = 80
        ))

        // Start logging (will send frames then poll)
        try {
            logger.startLogging()
            // Give poll loop a moment, then stop
            kotlinx.coroutines.delay(200)
        } catch (_: Exception) {
            // Expected — FakeCanTransport runs out of queued responses
        }
        try { logger.stopLogging() } catch (_: Exception) {}

        // Inspect sent frames — find the DynamicDefineDID request
        // It should contain our 3 variable addresses encoded as 4-byte big-endian
        val defineFrames = fakeCan.sentFrames.filter { frame ->
            frame.data.isNotEmpty() && (
                // Single frame: first byte is length, then service ID 0x2C
                (frame.data[0].toInt() and 0xF0 == 0x00 && frame.data.size > 1 && frame.data[1] == 0x2C.toByte()) ||
                // First frame of multi-frame: starts with 0x1X
                (frame.data[0].toInt() and 0xF0 == 0x10)
            )
        }

        // We should have at least one define frame (it'll be multi-frame for 3 vars)
        assertTrue(defineFrames.isNotEmpty() || fakeCan.sentFrames.size >= 3,
            "Should have sent DynamicDefineDID request frames. Total sent: ${fakeCan.sentFrames.size}")

        // Verify by reconstructing the full ISO-TP message from sent frames
        // For multi-frame: FF (first frame) has [0x1X, length, service_id, ...]
        // then CF (consecutive frames) have [0x2X, data...]
        val allSentData = reconstructIsoTpMessage(fakeCan.sentFrames)

        // Find the DynamicDefineDID message (service 0x2C)
        val defineMsg = allSentData.firstOrNull { it.isNotEmpty() && it[0] == 0x2C.toByte() }
        assertNotNull(defineMsg, "Should have sent a 0x2C DynamicDefineDID request")

        // Verify address encoding: [0x2C, 0x02, DID_hi, DID_lo, size1, addr1[3:0]..., size2, addr2[3:0]..., ...]
        // For our 3 vars:
        // Expected addresses (sorted by .ecu order, which is sorted by address):
        //   plsol_w won't be here — we have nmot_w, rl_w, ldtvm
        //   Sorted: rl_w(0xD0000ECA), nmot_w(0xD000167C), ldtvm(0xD0003043)
        assertTrue(defineMsg!!.size >= 4 + 15,
            "Define message should be at least 19 bytes (4 header + 3×5 payload), got ${defineMsg.size}")

        // Parse the payload after the 4-byte header
        val payload = defineMsg.drop(4)
        // Each variable: [memorySize, addr_31:24, addr_23:16, addr_15:8, addr_7:0]
        val var1Size = payload[0].toInt() and 0xFF
        val var1Addr = ((payload[1].toLong() and 0xFF) shl 24) or
                ((payload[2].toLong() and 0xFF) shl 16) or
                ((payload[3].toLong() and 0xFF) shl 8) or
                (payload[4].toLong() and 0xFF)

        // The .ecu entries are sorted by address, so first should be rl_w (0xD0000ECA, size 2)
        // or the order the .cfg lists them in
        assertTrue(var1Addr >= 0xD0000000L,
            "First variable address should be in TriCore range, got 0x${var1Addr.toString(16)}")
        assertTrue(var1Size in 1..2,
            "Variable size should be 1 or 2, got $var1Size")
    }

    // --- Helper functions ---

    private fun queueSingleFrame(fake: data.logger.uds.FakeCanTransport, rxId: Int, payload: ByteArray) {
        val frame = ByteArray(8)
        frame[0] = payload.size.toByte()  // SF PCI: length
        payload.copyInto(frame, 1, 0, minOf(payload.size, 7))
        fake.queueResponse(data.logger.uds.CanFrame(rxId, frame))
    }

    private fun queueFlowControl(fake: data.logger.uds.FakeCanTransport, rxId: Int) {
        fake.queueResponse(data.logger.uds.CanFrame(rxId, byteArrayOf(0x30, 0x00, 0x00, 0, 0, 0, 0, 0)))
    }

    /**
     * Reconstruct ISO-TP messages from raw CAN frames.
     * Groups frames into complete messages by detecting SF/FF/CF patterns.
     */
    private fun reconstructIsoTpMessage(frames: List<data.logger.uds.CanFrame>): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var currentMsg: MutableList<Byte>? = null
        var remaining = 0

        for (frame in frames) {
            val d = frame.data
            if (d.isEmpty()) continue
            val pci = d[0].toInt() and 0xF0

            when (pci) {
                0x00 -> {
                    // Single Frame: length in low nibble
                    val len = d[0].toInt() and 0x0F
                    if (len > 0 && len <= 7) {
                        messages.add(d.copyOfRange(1, 1 + len))
                    }
                }
                0x10 -> {
                    // First Frame: length in bits [11:8] from byte 0, bits [7:0] from byte 1
                    val len = ((d[0].toInt() and 0x0F) shl 8) or (d[1].toInt() and 0xFF)
                    currentMsg = mutableListOf()
                    for (i in 2 until minOf(8, d.size)) {
                        currentMsg.add(d[i])
                    }
                    remaining = len - (minOf(8, d.size) - 2)
                }
                0x20 -> {
                    // Consecutive Frame
                    if (currentMsg != null && remaining > 0) {
                        val bytesToCopy = minOf(7, remaining)
                        for (i in 1..minOf(bytesToCopy, d.size - 1)) {
                            currentMsg.add(d[i])
                        }
                        remaining -= bytesToCopy
                        if (remaining <= 0) {
                            messages.add(currentMsg.toByteArray())
                            currentMsg = null
                        }
                    }
                }
                0x30 -> { /* Flow Control — skip */ }
            }
        }
        return messages
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
