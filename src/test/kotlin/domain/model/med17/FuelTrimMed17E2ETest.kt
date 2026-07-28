package domain.model.med17

import data.contract.Med17LogFileContract
import data.parser.med17log.Med17LogParser
import data.parser.med17log.Med17LogParser.LogType
import domain.model.fueltrim.FuelTrimAnalyzer
import java.io.File
import kotlin.math.abs
import kotlin.test.*

/**
 * End-to-end tests for MED17 fuel trim analysis with real log data.
 *
 * Validates:
 *  - Cruise log produces fuel trim corrections in sane range
 *  - Mixed log handles both cruise and WOT data
 *  - WOT-only log handles gracefully (less fuel trim data at WOT)
 *  - Corrections are within ±25% (reasonable for real-world trims)
 */
class FuelTrimMed17E2ETest {

    private val parser = Med17LogParser()

    private fun logFile(name: String): File {
        val url = javaClass.classLoader.getResource("logs/$name")
            ?: error("Test resource not found: logs/$name")
        return File(url.toURI())
    }

    private fun parseFuelTrim(filename: String): Map<Med17LogFileContract.Header, List<Double>> =
        parser.parseLogFile(LogType.FUEL_TRIM, logFile(filename))

    // ── 1. Cruise Log (Best Source of Fuel Trim Data) ───────────────

    @Test
    fun `cruise log parses successfully for fuel trim`() {
        val data = parseFuelTrim("2023-05-19_21.08.33_log.csv")

        val rpm = data[Med17LogFileContract.Header.RPM_COLUMN_HEADER]
        assertNotNull(rpm, "Should parse RPM from cruise log")
        assertTrue(rpm.isNotEmpty(), "Cruise log should have RPM data")

        val load = data[Med17LogFileContract.Header.ENGINE_LOAD_HEADER]
        assertNotNull(load, "Should parse engine load from cruise log")
        assertTrue(load.isNotEmpty(), "Cruise log should have load data")

        println("Cruise log: ${rpm.size} rows with RPM data")
    }

    @Test
    fun `cruise log has fuel trim signals`() {
        val data = parseFuelTrim("2023-05-19_21.08.33_log.csv")

        // Should have at least one fuel trim signal
        val stftMixed = data[Med17LogFileContract.Header.STFT_MIXED_COLUMN_HEADER]
        val stft = data[Med17LogFileContract.Header.STFT_COLUMN_HEADER]
        val ltft = data[Med17LogFileContract.Header.LTFT_COLUMN_HEADER]
        val ltftAlt = data[Med17LogFileContract.Header.LONG_TERM_FT_HEADER]

        val hasStft = !stftMixed.isNullOrEmpty() || !stft.isNullOrEmpty()
        val hasLtft = !ltft.isNullOrEmpty() || !ltftAlt.isNullOrEmpty()

        assertTrue(hasStft || hasLtft, "Cruise log should have at least one fuel trim signal")

        if (hasStft) println("  STFT rows: ${(stftMixed?.size ?: 0) + (stft?.size ?: 0)}")
        if (hasLtft) println("  LTFT rows: ${(ltft?.size ?: 0) + (ltftAlt?.size ?: 0)}")
    }

    @Test
    fun `analyzeMed17Trims with cruise log produces corrections`() {
        val data = parseFuelTrim("2023-05-19_21.08.33_log.csv")
        val result = FuelTrimAnalyzer.analyzeMed17Trims(data)

        assertNotNull(result, "Should produce fuel trim result")
        assertTrue(result.rpmBins.isNotEmpty(), "Should have RPM bins")
        assertTrue(result.loadBins.isNotEmpty(), "Should have load bins")
        assertTrue(result.corrections.isNotEmpty(), "Should have correction grid")

        println("\nFuel trim corrections (RPM × Load grid):")
        println("  RPM bins: ${result.rpmBins.toList()}")
        println("  Load bins: ${result.loadBins.toList()}")

        var nonZeroCount = 0
        for (r in result.corrections.indices) {
            for (c in result.corrections[r].indices) {
                if (result.corrections[r][c] != 0.0) nonZeroCount++
            }
        }
        assertEquals(
            1,
            nonZeroCount,
            "The real cruise log must retain its independently verified corrective bin"
        )
        println("  Non-zero corrections: $nonZeroCount / ${result.corrections.size * result.corrections[0].size}")
    }

    @Test
    fun `cruise log corrections are within plus-minus 25 percent`() {
        val data = parseFuelTrim("2023-05-19_21.08.33_log.csv")
        val result = FuelTrimAnalyzer.analyzeMed17Trims(data)

        for (r in result.corrections.indices) {
            for (c in result.corrections[r].indices) {
                val correction = result.corrections[r][c]
                assertTrue(abs(correction) <= 25.0,
                    "Correction at [${result.rpmBins[r]}, ${result.loadBins[c]}] = $correction " +
                            "exceeds ±25% sane range")
            }
        }
    }

    // ── 2. Mixed Log ────────────────────────────────────────────────

    @Test
    fun `mixed log produces fuel trim result without crash`() {
        val data = parseFuelTrim("2023-05-19_21.31.12_log.csv")
        val result = FuelTrimAnalyzer.analyzeMed17Trims(data)
        assertNotNull(result)
        assertTrue(result.rpmBins.isNotEmpty())
    }

    @Test
    fun `mixed log corrections are within sane range`() {
        val data = parseFuelTrim("2023-05-19_21.31.12_log.csv")
        val result = FuelTrimAnalyzer.analyzeMed17Trims(data)

        for (r in result.corrections.indices) {
            for (c in result.corrections[r].indices) {
                val correction = result.corrections[r][c]
                assertTrue(abs(correction) <= 25.0,
                    "Mixed log correction at [${result.rpmBins[r]}, ${result.loadBins[c]}] = $correction")
            }
        }
    }

    // ── 3. WOT-Only Log ────────────────────────────────────────────

    @Test
    fun `WOT log fuel trim analysis does not crash`() {
        val data = parseFuelTrim("2025-01-21_16.24.32_log(1).csv")
        val result = FuelTrimAnalyzer.analyzeMed17Trims(data)

        // WOT log may have fewer fuel trim samples (trims less active at WOT)
        assertNotNull(result)
        println("WOT log fuel trim: ${result.corrections.size} rows × " +
                "${result.corrections.firstOrNull()?.size ?: 0} cols")
    }

    // ── 4. Warnings ─────────────────────────────────────────────────

    @Test
    fun `empty data produces warnings`() {
        val emptyData = mapOf<Med17LogFileContract.Header, List<Double>>()
        val result = FuelTrimAnalyzer.analyzeMed17Trims(emptyData)

        assertTrue(result.warnings.isNotEmpty(), "Empty data should produce warnings")
    }
}
