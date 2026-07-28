package domain.model.fueltrim

import data.contract.Med17LogFileContract
import data.contract.Med17LogFileContract.Header as H
import data.parser.med17log.Med17LogParser
import java.io.File
import kotlin.math.abs
import kotlin.test.*

/**
 * Tests for [FuelTrimAnalyzer].
 *
 * Covers synthetic data (deterministic), threshold detection, edge cases,
 * and integration with a real MED17 log file when available.
 */
class FuelTrimAnalyzerTest {

    // ── helpers ──────────────────────────────────────────────────────

    /** Build a minimal log-data map from parallel lists. */
    private fun buildLogData(
        rpm: List<Double>,
        load: List<Double>,
        stft: List<Double>? = null,
        ltft: List<Double>? = null,
        longft: List<Double>? = null
    ): Map<H, List<Double>> {
        val map = mutableMapOf<H, List<Double>>()
        map[H.RPM_COLUMN_HEADER] = rpm
        map[H.ENGINE_LOAD_HEADER] = load
        stft?.let { map[H.STFT_MIXED_COLUMN_HEADER] = it }
        ltft?.let { map[H.LTFT_COLUMN_HEADER] = it }
        longft?.let { map[H.LONG_TERM_FT_HEADER] = it }
        return map
    }

    /** Repeat [value] for [n] samples. */
    private fun constant(value: Double, n: Int): List<Double> = List(n) { value }

    // ── 1. Synthetic: known trims at known RPM/load ─────────────────

    @Test
    fun `uniform positive STFT produces positive correction in matching bin`() {
        val n = 50
        val rpmBins = doubleArrayOf(2000.0, 3000.0)
        val loadBins = doubleArrayOf(50.0, 100.0)

        // All samples at RPM≈2000, load≈50%, STFT=1.06 (+6%)
        val data = buildLogData(
            rpm = constant(2000.0, n),
            load = constant(50.0, n),
            stft = constant(1.06, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        // Bin [0][0] = RPM 2000, load 50% should have avgTrim ≈ +6%
        assertEquals(6.0, result.avgTrims[0][0], 0.01, "avg trim")
        assertEquals(6.0, result.corrections[0][0], 0.01, "correction")
        // Other bins should be zero
        assertEquals(0.0, result.corrections[0][1], 0.001)
        assertEquals(0.0, result.corrections[1][0], 0.001)
        assertEquals(0.0, result.corrections[1][1], 0.001)
    }

    @Test
    fun `combined STFT and LTFT are summed correctly`() {
        val n = 20
        val rpmBins = doubleArrayOf(3000.0)
        val loadBins = doubleArrayOf(70.0)

        // STFT = 1.03 (+3%), LTFT = 1.04 (+4%) → combined +7%
        val data = buildLogData(
            rpm = constant(3000.0, n),
            load = constant(70.0, n),
            stft = constant(1.03, n),
            ltft = constant(1.04, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        assertEquals(7.0, result.avgTrims[0][0], 0.01)
        assertEquals(7.0, result.corrections[0][0], 0.01)
    }

    @Test
    fun `samples distribute across multiple RPM-load bins`() {
        val rpmBins = doubleArrayOf(1000.0, 3000.0, 5000.0)
        val loadBins = doubleArrayOf(30.0, 80.0)
        val n = 10

        // 10 samples near RPM=1000/Load=30 with STFT=1.05
        // 10 samples near RPM=5000/Load=80 with STFT=0.94
        val rpm = constant(1000.0, n) + constant(5000.0, n)
        val load = constant(30.0, n) + constant(80.0, n)
        val stft = constant(1.05, n) + constant(0.94, n)

        val data = buildLogData(rpm = rpm, load = load, stft = stft)
        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        // Bin [0][0] (RPM=1000, load=30): +5%
        assertEquals(5.0, result.avgTrims[0][0], 0.01)
        assertEquals(5.0, result.corrections[0][0], 0.01)

        // Bin [2][1] (RPM=5000, load=80): -6%
        assertEquals(-6.0, result.avgTrims[2][1], 0.01)
        assertEquals(-6.0, result.corrections[2][1], 0.01)

        // All other bins empty → 0
        assertEquals(0.0, result.avgTrims[1][0], 0.001)
        assertEquals(0.0, result.corrections[1][0], 0.001)
    }

    // ── 2. Threshold detection ──────────────────────────────────────

    @Test
    fun `trims below threshold produce zero corrections`() {
        val n = 20
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(60.0)

        // STFT = 1.02 (+2%) — below the 3% default threshold
        val data = buildLogData(
            rpm = constant(2000.0, n),
            load = constant(60.0, n),
            stft = constant(1.02, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        // Average should still be recorded
        assertEquals(2.0, result.avgTrims[0][0], 0.01)
        // But no correction generated
        assertEquals(0.0, result.corrections[0][0], 0.001)
        assertTrue(result.isEmpty, "Result should be empty (no corrections)")
    }

    @Test
    fun `trims exceeding positive threshold produce correction and warning`() {
        val n = 20
        val rpmBins = doubleArrayOf(4000.0)
        val loadBins = doubleArrayOf(90.0)

        // STFT = 1.05 → +5% exceeds +3% threshold
        val data = buildLogData(
            rpm = constant(4000.0, n),
            load = constant(90.0, n),
            stft = constant(1.05, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        assertEquals(5.0, result.corrections[0][0], 0.01)
        assertFalse(result.isEmpty)
        assertTrue(result.warnings.any { "RPM=4000" in it && "threshold" in it })
    }

    @Test
    fun `negative trims exceeding threshold produce negative correction`() {
        val n = 20
        val rpmBins = doubleArrayOf(2500.0)
        val loadBins = doubleArrayOf(40.0)

        // STFT = 0.95 → -5% exceeds -3% threshold
        val data = buildLogData(
            rpm = constant(2500.0, n),
            load = constant(40.0, n),
            stft = constant(0.95, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        assertEquals(-5.0, result.avgTrims[0][0], 0.01)
        assertEquals(-5.0, result.corrections[0][0], 0.01)
        assertTrue(result.warnings.any { "RPM=2500" in it })
    }

    @Test
    fun `custom threshold overrides default`() {
        val n = 20
        val rpmBins = doubleArrayOf(3000.0)
        val loadBins = doubleArrayOf(60.0)

        // STFT = 1.02 (+2%) — below 3% but above 1%
        val data = buildLogData(
            rpm = constant(3000.0, n),
            load = constant(60.0, n),
            stft = constant(1.02, n)
        )

        // With threshold=1%, correction should be generated
        val result = FuelTrimAnalyzer.analyzeMed17Trims(
            data, rpmBins, loadBins, trimThreshold = 1.0
        )
        assertEquals(2.0, result.corrections[0][0], 0.01)
        assertFalse(result.isEmpty)
    }

    @Test
    fun `interpolated samples retain their true trim magnitude`() {
        val n = 12
        val rpmBins = doubleArrayOf(2000.0, 3000.0)
        val loadBins = doubleArrayOf(50.0, 100.0)
        val data = buildLogData(
            rpm = constant(2500.0, n),
            load = constant(75.0, n),
            stft = constant(1.20, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        for (row in result.avgTrims) {
            for (trim in row) {
                assertEquals(20.0, trim, 0.01, "Interpolation must not dilute a 20% trim")
            }
        }
    }

    // ── 3. Empty / missing data handling ────────────────────────────

    @Test
    fun `empty log data produces empty result with warning`() {
        val data = emptyMap<H, List<Double>>()
        val result = FuelTrimAnalyzer.analyzeMed17Trims(data)

        assertTrue(result.isEmpty)
        assertTrue(result.warnings.any { "Missing RPM" in it || "Missing" in it })
    }

    @Test
    fun `missing fuel trim signals produces empty result with warning`() {
        val n = 10
        // RPM and load present but no trim signals at all
        val data = buildLogData(
            rpm = constant(2000.0, n),
            load = constant(50.0, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data)

        assertTrue(result.isEmpty)
        assertTrue(result.warnings.any { "No fuel trim data" in it })
    }

    @Test
    fun `fewer samples than minSamples threshold produces no correction`() {
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)

        // Only 2 samples — below default minSamples=3
        val data = buildLogData(
            rpm = listOf(2000.0, 2000.0),
            load = listOf(50.0, 50.0),
            stft = listOf(1.10, 1.10)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        // avg should be 0 (not enough samples)
        assertEquals(0.0, result.avgTrims[0][0], 0.001)
        assertEquals(0.0, result.corrections[0][0], 0.001)
    }

    @Test
    fun `LTFT-only data without STFT still produces corrections`() {
        val n = 20
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(60.0)

        // No STFT, only LTFT at +4%
        val data = buildLogData(
            rpm = constant(2000.0, n),
            load = constant(60.0, n),
            ltft = constant(1.04, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        assertEquals(4.0, result.avgTrims[0][0], 0.01)
        assertEquals(4.0, result.corrections[0][0], 0.01)
    }

    @Test
    fun `longft1_w is used as LTFT fallback`() {
        val n = 20
        val rpmBins = doubleArrayOf(3000.0)
        val loadBins = doubleArrayOf(50.0)

        // Provide longft1_w via LONG_TERM_FT_HEADER but no fra_w
        val data = buildLogData(
            rpm = constant(3000.0, n),
            load = constant(50.0, n),
            longft = constant(1.05, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        assertEquals(5.0, result.avgTrims[0][0], 0.01)
        assertEquals(5.0, result.corrections[0][0], 0.01)
    }

    // ── 4. nearestBinIndex helper ───────────────────────────────────

    @Test
    fun `nearestBinIndex finds correct bin`() {
        val bins = doubleArrayOf(1000.0, 2000.0, 3000.0, 4000.0)

        assertEquals(0, FuelTrimAnalyzer.nearestBinIndex(800.0, bins))
        assertEquals(0, FuelTrimAnalyzer.nearestBinIndex(1000.0, bins))
        assertEquals(0, FuelTrimAnalyzer.nearestBinIndex(1400.0, bins))
        assertEquals(1, FuelTrimAnalyzer.nearestBinIndex(1600.0, bins))
        assertEquals(1, FuelTrimAnalyzer.nearestBinIndex(2000.0, bins))
        assertEquals(3, FuelTrimAnalyzer.nearestBinIndex(5000.0, bins))
    }

    // ── 5. Integration with real MED17 log ──────────────────────────

    private fun logFile(name: String): File? {
        val url = javaClass.classLoader.getResource("logs/$name") ?: return null
        return File(url.toURI())
    }

    @Test
    fun `real log produces non-empty fuel trim analysis`() {
        val file = logFile("2025-01-21_16.24.32_log(1).csv")
            ?: run { println("Skipping: test log not found"); return }

        val parser = Med17LogParser()
        val logData = parser.parseLogFile(Med17LogParser.LogType.FUEL_TRIM, file)

        // Should have parsed some data
        val rpm = logData[H.RPM_COLUMN_HEADER]!!
        assertTrue(rpm.isNotEmpty(), "Parser should extract RPM data")

        val result = FuelTrimAnalyzer.analyzeMed17Trims(logData)

        // Real log has frm_w ≈ 1.03 and fra_w ≈ 1.02 at cruise, so combined ≈ +5%
        // That exceeds the 3% threshold, so we expect at least one correction
        assertFalse(result.isEmpty, "Real log trims should produce at least one correction")
        assertTrue(result.warnings.isNotEmpty(), "Expected threshold warnings from real data")
    }

    @Test
    fun `real log average trims are in sane range`() {
        val file = logFile("2025-01-21_16.24.32_log(1).csv")
            ?: run { println("Skipping: test log not found"); return }

        val parser = Med17LogParser()
        val logData = parser.parseLogFile(Med17LogParser.LogType.FUEL_TRIM, file)
        val result = FuelTrimAnalyzer.analyzeMed17Trims(logData)

        // All average trims should be within -25% to +25% (sane ECU range)
        for (r in result.rpmBins.indices) {
            for (l in result.loadBins.indices) {
                val avg = result.avgTrims[r][l]
                if (avg != 0.0) {
                    assertTrue(
                        abs(avg) < 25.0,
                        "Avg trim at RPM=${result.rpmBins[r]} Load=${result.loadBins[l]} " +
                                "is $avg — out of sane range"
                    )
                }
            }
        }
    }

    // ── Map3d conversion ────────────────────────────────────────────

    @Test
    fun `toAvgTrimsMap3d produces correct Map3d`() {
        val result = FuelTrimResult(
            rpmBins = doubleArrayOf(1000.0, 2000.0),
            loadBins = doubleArrayOf(10.0, 20.0, 30.0),
            avgTrims = arrayOf(
                doubleArrayOf(1.0, 2.0, 3.0),
                doubleArrayOf(4.0, 5.0, 6.0)
            ),
            corrections = arrayOf(
                doubleArrayOf(0.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0)
            ),
            warnings = emptyList()
        )

        val map = result.toAvgTrimsMap3d()
        assertContentEquals(arrayOf(10.0, 20.0, 30.0), map.xAxis)
        assertContentEquals(arrayOf(1000.0, 2000.0), map.yAxis)
        assertEquals(2, map.zAxis.size)
        assertEquals(3, map.zAxis[0].size)
        assertEquals(1.0, map.zAxis[0][0])
        assertEquals(6.0, map.zAxis[1][2])
    }

    @Test
    fun `toCorrectionsMap3d produces correct Map3d`() {
        val result = FuelTrimResult(
            rpmBins = doubleArrayOf(750.0),
            loadBins = doubleArrayOf(10.0, 20.0),
            avgTrims = arrayOf(doubleArrayOf(0.0, 0.0)),
            corrections = arrayOf(doubleArrayOf(5.5, -2.3)),
            warnings = emptyList()
        )

        val map = result.toCorrectionsMap3d()
        assertContentEquals(arrayOf(10.0, 20.0), map.xAxis)
        assertContentEquals(arrayOf(750.0), map.yAxis)
        assertEquals(5.5, map.zAxis[0][0])
        assertEquals(-2.3, map.zAxis[0][1])
    }
}
