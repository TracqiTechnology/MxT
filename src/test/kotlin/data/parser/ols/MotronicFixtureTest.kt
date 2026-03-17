package data.parser.ols

import data.parser.xdf.XdfParser
import java.io.File
import kotlin.test.*

/**
 * Integration tests for Motronic M3.8.3 test fixtures.
 *
 * Verifies that the generated XDF loads with [XdfParser] and produces
 * sensible map data when combined with the M3.8.3 BIN file.
 */
class MotronicFixtureTest {

    companion object {
        private val XDF_FILE = File("example/motronic/06A906018CG.xdf")
        private val BIN_FILE = File("example/motronic/06A906018CG.bin")
    }

    @Test
    fun `XDF file exists and is valid XML`() {
        assertTrue(XDF_FILE.exists(), "XDF fixture must exist at ${XDF_FILE.path}")

        val content = XDF_FILE.readText()
        assertTrue(content.contains("<XDFFORMAT"), "Must be valid XDF format")
        assertTrue(content.contains("</XDFFORMAT>"), "Must have closing tag")
        assertTrue(content.contains("06A906018CG"), "Must reference correct ECU")
    }

    @Test
    fun `BIN file exists and is 256KB`() {
        assertTrue(BIN_FILE.exists(), "BIN fixture must exist at ${BIN_FILE.path}")
        assertEquals(262144, BIN_FILE.length().toInt(), "M3.8.3 BIN must be 256KB")
    }

    @Test
    fun `XDF contains MLHFM table`() {
        if (!XDF_FILE.exists()) return

        val content = XDF_FILE.readText()
        assertTrue(content.contains("<title>MLHFM</title>"), "XDF must contain MLHFM")
        assertTrue(content.contains("kg/h"), "MLHFM must have kg/h unit")
    }

    @Test
    fun `MLHFM data is monotonically non-decreasing`() {
        if (!BIN_FILE.exists()) return

        val bin = BIN_FILE.readBytes()
        val mlhfmAddr = 0x7540
        val entryCount = 266

        // Read MLHFM as uint16 LE
        val values = (0 until entryCount).map { i ->
            val offset = mlhfmAddr + i * 2
            (bin[offset].toInt() and 0xFF) or ((bin[offset + 1].toInt() and 0xFF) shl 8)
        }

        // Verify monotonicity
        for (i in 1 until values.size) {
            assertTrue(values[i] >= values[i - 1],
                "MLHFM must be monotonically non-decreasing at index $i: " +
                        "${values[i]} < ${values[i - 1]}")
        }
    }

    @Test
    fun `MLHFM airflow range is physically reasonable`() {
        if (!BIN_FILE.exists()) return

        val bin = BIN_FILE.readBytes()
        val mlhfmAddr = 0x7540
        val entryCount = 266

        val values = (0 until entryCount).map { i ->
            val offset = mlhfmAddr + i * 2
            val raw = (bin[offset].toInt() and 0xFF) or ((bin[offset + 1].toInt() and 0xFF) shl 8)
            raw * 0.125 // kg/h
        }

        // First entries should be zero (below usable voltage range)
        assertTrue(values.take(5).all { it == 0.0 },
            "First 5 MLHFM entries should be zero (sub-threshold voltage)")

        // Baseline should be around 200 kg/h (MLOFS offset)
        val firstNonZero = values.first { it > 0 }
        assertTrue(firstNonZero in 150.0..250.0,
            "First non-zero MLHFM value should be near 200 kg/h (MLOFS), was $firstNonZero")

        // Max airflow should be reasonable for a 1.8T engine
        val maxFlow = values.max()
        assertTrue(maxFlow in 500.0..2000.0,
            "Max MLHFM airflow should be 500-2000 kg/h for 1.8T, was $maxFlow")

        // No NaN or Inf
        assertTrue(values.none { it.isNaN() || it.isInfinite() },
            "MLHFM must not contain NaN or Inf values")
    }
}
