package data.parser.ols

import java.io.File
import kotlin.test.*

/**
 * Tests for [OlsParser] — WinOLS OLS project file parser.
 *
 * Uses the real ME3.8.3 06A906018CJ OLS file from the technical/m_3_5/ fixtures.
 */
class OlsParserTest {

    companion object {
        private val OLS_FILE = File("technical/m_3_5/ME3.8.3 06A906018CJ.ols")
    }

    @Test
    fun `parse OLS file header`() {
        if (!OLS_FILE.exists()) return // skip if fixture not present

        val result = OlsParser.parseFile(OLS_FILE)

        assertEquals("Bosch", result.header.ecuMaker)
        assertEquals("M3.8.3", result.header.ecuVersion)
        assertEquals("06A906018CJ", result.header.partNumber)
        assertEquals("0261206516", result.header.boschNumber)
        assertEquals("VW", result.header.manufacturer)
        assertEquals("Golf", result.header.model)
        assertEquals("1.8T", result.header.engine)
        assertEquals("Bosch M3.8.3 06A906018CJ", result.header.shortId)
    }

    @Test
    fun `parse OLS extracts map names`() {
        if (!OLS_FILE.exists()) return

        val result = OlsParser.parseFile(OLS_FILE)
        val mapNames = result.maps.map { it.name }.toSet()

        // Critical M3.8.3 maps must be present
        assertTrue("MLHFM" in mapNames, "MLHFM must be extracted")
        assertTrue("KFZW.0" in mapNames, "KFZW.0 must be extracted")
        assertTrue("KFZW.1" in mapNames, "KFZW.1 must be extracted")
        assertTrue("KFZW.2" in mapNames, "KFZW.2 must be extracted")
        assertTrue("KFZWOPT.0" in mapNames, "KFZWOPT.0 must be extracted")
        assertTrue("KFZWMN" in mapNames, "KFZWMN must be extracted")
        assertTrue("KFMDOPT.0" in mapNames, "KFMDOPT.0 must be extracted")
        assertTrue("KFLDS.0" in mapNames, "KFLDS.0 must be extracted")
        assertTrue("KFLF" in mapNames, "KFLF must be extracted")
    }

    @Test
    fun `MLHFM has correct description`() {
        if (!OLS_FILE.exists()) return

        val result = OlsParser.parseFile(OLS_FILE)
        val mlhfm = result.maps.first { it.name == "MLHFM" }

        assertTrue(
            mlhfm.description.contains("Linearisierung") ||
                    mlhfm.description.contains("Heißfilmspannung") ||
                    mlhfm.description.contains("Heissfilmspannung"),
            "MLHFM description should mention MAF linearization: ${mlhfm.description}"
        )
    }

    @Test
    fun `KFZW has correct description`() {
        if (!OLS_FILE.exists()) return

        val result = OlsParser.parseFile(OLS_FILE)
        val kfzw = result.maps.first { it.name == "KFZW.0" }

        assertTrue(
            kfzw.description.contains("ndwinkel") || kfzw.description.contains("Zündwinkel"),
            "KFZW description should mention ignition angle: ${kfzw.description}"
        )
    }

    @Test
    fun `embedded BIN offset is reasonable`() {
        if (!OLS_FILE.exists()) return

        val result = OlsParser.parseFile(OLS_FILE)

        // BIN is 256KB at the end of the file
        val fileSize = OLS_FILE.length().toInt()
        val expectedBinOffset = fileSize - 262144

        assertEquals(expectedBinOffset, result.embeddedBinOffset,
            "Embedded BIN should be at file size - 256K")
    }

    @Test
    fun `large number of maps extracted`() {
        if (!OLS_FILE.exists()) return

        val result = OlsParser.parseFile(OLS_FILE)

        // OLS has ~1200 map records
        assertTrue(result.maps.size > 500,
            "Should extract many maps, got ${result.maps.size}")
    }

    @Test
    fun `rejects non-OLS file`() {
        val nonOlsFile = File("example/motronic/06A906018CG.bin")
        if (!nonOlsFile.exists()) return

        assertFailsWith<IllegalArgumentException> {
            OlsParser.parseFile(nonOlsFile)
        }
    }

    // ---- VAG archive OLS tests ----
    // Note: The OLS parser was reverse-engineered from M3.8.3 / ME7 era files.
    // MED9/MED17 OLS files use a different internal record format — header field
    // offsets and map record markers differ. These tests document what works and
    // what doesn't, providing regression fixtures for future OLS parser improvements.

    @Test
    fun `all VAG OLS files parse without exception`() {
        val olsFiles = listOf(
            "technical/vag/ols/PL46.ols" to "MED9.1 Audi A4 2.0TFSI",
            "technical/vag/ols/Audi_TT_RS_2.5_TFSI.ols" to "MED17 Audi TT RS 2.5T",
            "technical/vag/ols/DKP_Audi_Q5_2.0_TFSI_397793_Original.ols" to "MED17.1 Audi Q5 2.0TFSI"
        )

        for ((path, desc) in olsFiles) {
            val file = File(path)
            if (!file.exists()) continue

            // Parser must not throw for valid OLS files, even if extraction is incomplete
            val result = OlsParser.parseFile(file)
            // Results are returned (maps/header may be empty for unsupported OLS versions)
            assertNotNull(result, "Parse result should not be null for $desc")
        }
    }

    @Test
    fun `VAG OLS files have WinOLS magic header`() {
        val olsFiles = listOf(
            File("technical/vag/ols/PL46.ols"),
            File("technical/vag/ols/Audi_TT_RS_2.5_TFSI.ols"),
            File("technical/vag/ols/DKP_Audi_Q5_2.0_TFSI_397793_Original.ols")
        )

        for (file in olsFiles) {
            if (!file.exists()) continue

            val bytes = file.readBytes()
            val magic = String(bytes, 4, 12, Charsets.US_ASCII)
            assertTrue(magic.startsWith("WinOLS File"),
                "${file.name} should have WinOLS magic header, got: $magic")
        }
    }

    @Test
    fun `VAG OLS files contain ECU metadata in binary`() {
        // Verify that the raw bytes contain ECU metadata even if the parser
        // can't extract them (regression fixture for future parser improvements)
        val olsFile = File("technical/vag/ols/PL46.ols")
        if (!olsFile.exists()) return

        val content = olsFile.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(content.contains("Audi"),
            "PL46.ols raw bytes should contain 'Audi'")
        assertTrue(content.contains("2.0T"),
            "PL46.ols raw bytes should contain '2.0T'")
    }

    @Test
    fun `MED17 TT RS OLS contains ECU metadata in binary`() {
        val olsFile = File("technical/vag/ols/Audi_TT_RS_2.5_TFSI.ols")
        if (!olsFile.exists()) return

        val content = olsFile.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(content.contains("Audi"),
            "TT RS OLS raw bytes should contain 'Audi'")
        assertTrue(content.contains("TT RS"),
            "TT RS OLS raw bytes should contain 'TT RS'")
    }
}
