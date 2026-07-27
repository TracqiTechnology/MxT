package domain.model.fueltrim

import data.contract.Med17LogFileContract.Header as H
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

/**
 * Tests for closed-loop stability filtering and per-cell diagnostics
 * in [FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics].
 */
class FuelTrimStabilityFilterTest {

    // ── helpers ──────────────────────────────────────────────────────

    /** Build log data with optional B_lr and lamsbg_w filter columns. */
    private fun buildLogData(
        rpm: List<Double>,
        load: List<Double>,
        stft: List<Double>? = null,
        ltft: List<Double>? = null,
        bLr: List<Double>? = null,
        lamsbgW: List<Double>? = null
    ): Map<H, List<Double>> {
        val map = mutableMapOf<H, List<Double>>()
        map[H.RPM_COLUMN_HEADER] = rpm
        map[H.ENGINE_LOAD_HEADER] = load
        stft?.let { map[H.STFT_MIXED_COLUMN_HEADER] = it }
        ltft?.let { map[H.LTFT_COLUMN_HEADER] = it }
        bLr?.let { map[H.LAMBDA_CONTROL_ACTIVE_HEADER] = it }
        lamsbgW?.let { map[H.REQUESTED_LAMBDA_HEADER] = it }
        return map
    }

    private fun constant(value: Double, n: Int): List<Double> = List(n) { value }

    // ── 1. Closed-loop filter excludes B_lr != 1 samples ────────────

    @Test
    fun `closed-loop filter excludes B_lr != 1 samples`() {
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)

        // 10 samples with B_lr=1 (closed-loop) → STFT=1.06 (+6%)
        // 10 samples with B_lr=0 (open-loop)   → STFT=1.20 (+20%)
        val rpm = constant(2000.0, 20)
        val load = constant(50.0, 20)
        val stft = constant(1.06, 10) + constant(1.20, 10)
        val bLr = constant(1.0, 10) + constant(0.0, 10)

        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            buildLogData(rpm = rpm, load = load, stft = stft, bLr = bLr),
            rpmBins, loadBins
        )

        // Only the closed-loop samples should be used
        val cell = result.diagnostics[0][0]
        assertEquals(10, cell.sampleCount, "Only B_lr=1 samples should be counted")
        assertEquals(6.0, cell.meanTrimPercent, 0.01, "Mean should reflect only CL samples")
        assertEquals(10, result.samplesFilteredOut, "10 OL samples should be filtered out")
    }

    // ── 2. Lambda filter excludes enrichment samples ────────────────

    @Test
    fun `lambda filter excludes enrichment samples`() {
        val rpmBins = doubleArrayOf(3000.0)
        val loadBins = doubleArrayOf(60.0)

        // 10 samples at lamsbg_w=1.0 (stoichiometric) → STFT=1.04 (+4%)
        // 10 samples at lamsbg_w=0.85 (rich/enrichment) → STFT=1.10 (+10%)
        val rpm = constant(3000.0, 20)
        val load = constant(60.0, 20)
        val stft = constant(1.04, 10) + constant(1.10, 10)
        val lamsbgW = constant(1.0, 10) + constant(0.85, 10)

        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            buildLogData(rpm = rpm, load = load, stft = stft, lamsbgW = lamsbgW),
            rpmBins, loadBins
        )

        val cell = result.diagnostics[0][0]
        assertEquals(10, cell.sampleCount, "Only stoichiometric samples should be counted")
        assertEquals(4.0, cell.meanTrimPercent, 0.01, "Mean should reflect only λ≈1 samples")
        assertEquals(10, result.samplesFilteredOut)
    }

    // ── 3. Filters are optional — analysis works without B_lr/lamsbg_w

    @Test
    fun `filters are optional - analysis works without B_lr or lamsbg_w`() {
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)
        val n = 20

        // No B_lr, no lamsbg_w → all samples should be used
        val data = buildLogData(
            rpm = constant(2000.0, n),
            load = constant(50.0, n),
            stft = constant(1.05, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            data, rpmBins, loadBins
        )

        assertEquals(0, result.samplesFilteredOut, "No samples should be filtered")
        assertEquals(n, result.diagnostics[0][0].sampleCount)
        assertEquals(5.0, result.diagnostics[0][0].meanTrimPercent, 0.01)
    }

    @Test
    fun `short optional filter channels do not truncate valid trim rows`() {
        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            buildLogData(
                rpm = constant(2000.0, 10),
                load = constant(50.0, 10),
                stft = constant(1.10, 10),
                bLr = listOf(1.0),
                lamsbgW = listOf(1.0)
            ),
            rpmBins = doubleArrayOf(2000.0),
            loadBins = doubleArrayOf(50.0)
        )

        assertEquals(10, result.totalSamplesProcessed)
        assertEquals(10, result.diagnostics[0][0].sampleCount)
        assertEquals(10.0, result.diagnostics[0][0].meanTrimPercent, 0.01)
    }

    @Test
    fun `diagnostic weighting requires enough effective evidence in each cell`() {
        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            buildLogData(
                rpm = constant(2500.0, 10),
                load = constant(75.0, 10),
                stft = constant(1.20, 10)
            ),
            rpmBins = doubleArrayOf(2000.0, 3000.0),
            loadBins = doubleArrayOf(50.0, 100.0)
        )

        for (row in result.diagnostics) {
            for (cell in row) {
                assertEquals(20.0, cell.meanTrimPercent, 0.01)
                assertEquals(2.5, cell.effectiveSampleWeight, 0.01)
                assertTrue(cell.rejected)
                assertEquals(0.0, cell.correctionApplied, 0.01)
            }
        }
    }

    @Test
    fun `distributed samples react once each cell reaches the effective sample threshold`() {
        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            buildLogData(
                rpm = constant(2500.0, 12),
                load = constant(75.0, 12),
                stft = constant(1.20, 12)
            ),
            rpmBins = doubleArrayOf(2000.0, 3000.0),
            loadBins = doubleArrayOf(50.0, 100.0)
        )

        for (row in result.diagnostics) {
            for (cell in row) {
                assertEquals(3.0, cell.effectiveSampleWeight, 0.01)
                assertFalse(cell.rejected)
                assertEquals(20.0, cell.correctionApplied, 0.01)
            }
        }
    }

    // ── 4. High std_dev bins are rejected ───────────────────────────

    @Test
    fun `high std_dev bins are rejected`() {
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)

        // Wild variance: half at +20%, half at -20% → mean≈0, stdDev≈20%
        val stft = constant(1.20, 10) + constant(0.80, 10)

        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            buildLogData(
                rpm = constant(2000.0, 20),
                load = constant(50.0, 20),
                stft = stft
            ),
            rpmBins, loadBins,
            stdDevThreshold = 5.0
        )

        val cell = result.diagnostics[0][0]
        assertTrue(cell.rejected, "Bin with high std_dev should be rejected")
        assertTrue(cell.rejectReason!!.contains("std_dev"), "Reason should mention std_dev")
        assertEquals(0.0, cell.correctionApplied, 0.001, "Rejected bin should have 0 correction")
        assertEquals(1, result.binsRejected)
    }

    // ── 5. Diagnostics include correct mean and std_dev ─────────────

    @Test
    fun `diagnostics include correct mean and std_dev`() {
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)

        // Known values: 4%, 6%, 8% → mean=6%, stdDev=√(8/3)≈1.633
        val stft = listOf(1.04, 1.06, 1.08)

        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            buildLogData(
                rpm = constant(2000.0, 3),
                load = constant(50.0, 3),
                stft = stft
            ),
            rpmBins, loadBins,
            minSamples = 1
        )

        val cell = result.diagnostics[0][0]
        assertEquals(3, cell.sampleCount)
        assertEquals(6.0, cell.meanTrimPercent, 0.01, "Mean should be 6%")

        // Population std_dev: √(((4-6)² + (6-6)² + (8-6)²) / 3) = √(8/3) ≈ 1.633
        val expectedStdDev = sqrt(8.0 / 3.0)
        assertEquals(expectedStdDev, cell.stdDevPercent, 0.01, "Std dev should match")
    }

    // ── 6. Correction not applied to rejected bins ──────────────────

    @Test
    fun `correction not applied to rejected bins`() {
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)

        // Only 2 samples (below minSamples=3), but large trim
        val data = buildLogData(
            rpm = listOf(2000.0, 2000.0),
            load = listOf(50.0, 50.0),
            stft = listOf(1.10, 1.10)
        )

        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            data, rpmBins, loadBins,
            minSamples = 3
        )

        val cell = result.diagnostics[0][0]
        assertTrue(cell.rejected, "Bin with insufficient samples should be rejected")
        assertEquals(0.0, cell.correctionApplied, 0.001, "Correction should be 0 for rejected bin")
        assertTrue(cell.rejectReason!!.contains("insufficient"), "Reason should mention insufficient")
    }

    // ── 7. Summary counts are correct ───────────────────────────────

    @Test
    fun `summary counts are correct`() {
        val rpmBins = doubleArrayOf(2000.0, 4000.0)
        val loadBins = doubleArrayOf(50.0, 100.0)

        // 15 CL samples at bin [0][0] with consistent trim (+6%)
        // 5 OL samples (filtered)
        // 3 samples at bin [1][1] with wild variance (stdDev > 5%)
        val rpm = constant(2000.0, 15) + constant(2000.0, 5) + listOf(4000.0, 4000.0, 4000.0)
        val load = constant(50.0, 15) + constant(50.0, 5) + listOf(100.0, 100.0, 100.0)
        val stft = constant(1.06, 15) + constant(1.15, 5) + listOf(1.30, 0.70, 1.30)
        val bLr = constant(1.0, 15) + constant(0.0, 5) + constant(1.0, 3)

        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            buildLogData(rpm = rpm, load = load, stft = stft, bLr = bLr),
            rpmBins, loadBins,
            stdDevThreshold = 5.0
        )

        assertEquals(23, result.totalSamplesProcessed, "Total samples")
        assertEquals(5, result.samplesFilteredOut, "Filtered out (OL)")
        assertEquals(2, result.binsWithData, "Bins with data: [0][0] and [1][1]")
        assertEquals(1, result.binsRejected, "Bins rejected: [1][1] high variance")
    }

    // ── 8. Backward compatibility — analyzeMed17Trims still works ───

    @Test
    fun `backward compatibility - analyzeMed17Trims still works`() {
        val n = 20
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)

        val data = buildLogData(
            rpm = constant(2000.0, n),
            load = constant(50.0, n),
            stft = constant(1.06, n)
        )

        // Original method should still work identically
        val result = FuelTrimAnalyzer.analyzeMed17Trims(data, rpmBins, loadBins)

        assertEquals(6.0, result.avgTrims[0][0], 0.01)
        assertEquals(6.0, result.corrections[0][0], 0.01)
        assertFalse(result.isEmpty)
        assertTrue(result.warnings.any { "RPM=2000" in it && "threshold" in it })
    }

    // ── Additional edge cases ───────────────────────────────────────

    @Test
    fun `both B_lr and lamsbg_w filters work together`() {
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)

        // 5 samples: CL + stoich → valid
        // 5 samples: CL + rich → filtered by lambda
        // 5 samples: OL + stoich → filtered by B_lr
        val rpm = constant(2000.0, 15)
        val load = constant(50.0, 15)
        val stft = constant(1.06, 5) + constant(1.15, 5) + constant(1.20, 5)
        val bLr = constant(1.0, 5) + constant(1.0, 5) + constant(0.0, 5)
        val lamsbgW = constant(1.0, 5) + constant(0.80, 5) + constant(1.0, 5)

        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            buildLogData(rpm = rpm, load = load, stft = stft, bLr = bLr, lamsbgW = lamsbgW),
            rpmBins, loadBins
        )

        assertEquals(5, result.diagnostics[0][0].sampleCount)
        assertEquals(10, result.samplesFilteredOut)
        assertEquals(6.0, result.diagnostics[0][0].meanTrimPercent, 0.01)
    }

    @Test
    fun `within-threshold bins have reason and correction zero`() {
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)
        val n = 20

        // STFT=1.02 (+2%) — below default 3% threshold
        val data = buildLogData(
            rpm = constant(2000.0, n),
            load = constant(50.0, n),
            stft = constant(1.02, n)
        )

        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            data, rpmBins, loadBins
        )

        val cell = result.diagnostics[0][0]
        assertEquals(2.0, cell.meanTrimPercent, 0.01)
        assertEquals(0.0, cell.correctionApplied, 0.001)
        assertFalse(cell.rejected, "A stable bin within the reaction threshold is valid, not rejected")
        assertNotNull(cell.rejectReason)
        assertTrue(cell.rejectReason!!.contains("within threshold"))
    }

    @Test
    fun `empty log data produces empty diagnostic result`() {
        val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(emptyMap())

        assertTrue(result.isEmpty)
        assertEquals(0, result.totalSamplesProcessed)
        assertEquals(0, result.binsWithData)
        assertTrue(result.warnings.any { "Missing RPM" in it || "Missing" in it })
    }

    @Test
    fun `toFuelTrimResult conversion preserves corrections and warnings`() {
        val n = 20
        val rpmBins = doubleArrayOf(2000.0)
        val loadBins = doubleArrayOf(50.0)

        val data = buildLogData(
            rpm = constant(2000.0, n),
            load = constant(50.0, n),
            stft = constant(1.06, n)
        )

        val diagResult = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            data, rpmBins, loadBins
        )
        val ftResult = diagResult.toFuelTrimResult()

        assertEquals(6.0, ftResult.corrections[0][0], 0.01)
        assertEquals(6.0, ftResult.avgTrims[0][0], 0.01)
        assertFalse(ftResult.isEmpty)
    }
}
