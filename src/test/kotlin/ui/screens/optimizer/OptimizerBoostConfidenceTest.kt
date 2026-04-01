package ui.screens.optimizer

import domain.math.map.Map3d
import domain.model.optimizer.MapDelta
import domain.model.optimizer.SuggestedMaps
import domain.model.optimizer.OptimizerCalculator
import kotlin.test.*

/**
 * Tests for MapDelta confidence levels and coverage calculations
 * used by BoostControlTab's confidence overlay.
 */
class OptimizerBoostConfidenceTest {

    // ── Helpers ──────────────────────────────────────────────────────

    private fun make3x3Map(fillValue: Double = 50.0): Map3d {
        val x = arrayOf(1000.0, 2000.0, 3000.0)
        val y = arrayOf(2.0, 4.0, 6.0)
        val z = Array(3) { Array(3) { fillValue } }
        return Map3d(x, y, z)
    }

    private fun buildDelta(sampleCounts: Array<IntArray>): MapDelta {
        val current = make3x3Map(50.0)
        val suggested = make3x3Map(55.0)
        return MapDelta.build("KFLDRL", current, suggested, sampleCounts)
    }

    // ── cellConfidence threshold tests ───────────────────────────────

    @Test
    fun `cellConfidence returns NONE for 0 samples`() {
        val counts = Array(3) { IntArray(3) { 0 } }
        val delta = buildDelta(counts)
        assertEquals(MapDelta.Confidence.NONE, delta.cellConfidence(0, 0))
    }

    @Test
    fun `cellConfidence returns LOW for 1 to 4 samples`() {
        val counts = Array(3) { IntArray(3) { 0 } }
        counts[0][0] = 1
        counts[1][1] = 3
        counts[2][2] = 4
        val delta = buildDelta(counts)

        assertEquals(MapDelta.Confidence.LOW, delta.cellConfidence(0, 0))
        assertEquals(MapDelta.Confidence.LOW, delta.cellConfidence(1, 1))
        assertEquals(MapDelta.Confidence.LOW, delta.cellConfidence(2, 2))
    }

    @Test
    fun `cellConfidence returns MEDIUM for 5 to 20 samples`() {
        val counts = Array(3) { IntArray(3) { 0 } }
        counts[0][0] = 5
        counts[1][0] = 10
        counts[2][0] = 20
        val delta = buildDelta(counts)

        assertEquals(MapDelta.Confidence.MEDIUM, delta.cellConfidence(0, 0))
        assertEquals(MapDelta.Confidence.MEDIUM, delta.cellConfidence(1, 0))
        assertEquals(MapDelta.Confidence.MEDIUM, delta.cellConfidence(2, 0))
    }

    @Test
    fun `cellConfidence returns HIGH for more than 20 samples`() {
        val counts = Array(3) { IntArray(3) { 0 } }
        counts[0][0] = 21
        counts[1][1] = 50
        counts[2][2] = 100
        val delta = buildDelta(counts)

        assertEquals(MapDelta.Confidence.HIGH, delta.cellConfidence(0, 0))
        assertEquals(MapDelta.Confidence.HIGH, delta.cellConfidence(1, 1))
        assertEquals(MapDelta.Confidence.HIGH, delta.cellConfidence(2, 2))
    }

    @Test
    fun `cellConfidence returns NONE for out-of-bounds indices`() {
        val counts = Array(3) { IntArray(3) { 25 } }
        val delta = buildDelta(counts)
        assertEquals(MapDelta.Confidence.NONE, delta.cellConfidence(10, 0))
        assertEquals(MapDelta.Confidence.NONE, delta.cellConfidence(0, 10))
    }

    // ── Coverage calculation tests ───────────────────────────────────

    @Test
    fun `coverage is 0 when no cells have data`() {
        val counts = Array(3) { IntArray(3) { 0 } }
        val delta = buildDelta(counts)
        assertEquals(0, delta.cellsWithData)
        assertEquals(9, delta.totalCells)
        assertEquals(0.0, delta.coverage, 0.001)
    }

    @Test
    fun `coverage is 1 when all cells have data`() {
        val counts = Array(3) { IntArray(3) { 10 } }
        val delta = buildDelta(counts)
        assertEquals(9, delta.cellsWithData)
        assertEquals(9, delta.totalCells)
        assertEquals(1.0, delta.coverage, 0.001)
    }

    @Test
    fun `coverage calculation for partial data`() {
        val counts = Array(3) { IntArray(3) { 0 } }
        // 4 out of 9 cells have data
        counts[0][0] = 5
        counts[0][1] = 10
        counts[1][0] = 3
        counts[2][2] = 25
        val delta = buildDelta(counts)

        assertEquals(4, delta.cellsWithData)
        assertEquals(9, delta.totalCells)
        assertEquals(4.0 / 9.0, delta.coverage, 0.001)
    }

    @Test
    fun `avgSamplesPerModifiedCell is correct`() {
        val counts = Array(3) { IntArray(3) { 0 } }
        counts[0][0] = 10
        counts[1][1] = 20
        counts[2][2] = 30
        val delta = buildDelta(counts)

        // 3 cells with data, total 60 samples → avg 20
        assertEquals(3, delta.cellsWithData)
        assertEquals(20.0, delta.avgSamplesPerModifiedCell, 0.001)
    }

    // ── suggestedMaps availability in OptimizerResult ────────────────

    @Test
    fun `OptimizerResult suggestedMaps carries MapDelta for kfldrl`() {
        val counts = Array(3) { IntArray(3) { 15 } }
        val delta = buildDelta(counts)
        val suggestedMaps = SuggestedMaps(kfldrl = delta)

        val result = OptimizerCalculator.OptimizerResult(
            suggestedKfldrl = delta.suggested,
            suggestedKfldimx = null,
            kfpbrkMultipliers = null,
            pressureErrors = emptyList(),
            loadErrors = emptyList(),
            warnings = emptyList(),
            wotEntries = emptyList(),
            suggestedMaps = suggestedMaps
        )

        assertNotNull(result.suggestedMaps.kfldrl)
        assertEquals("KFLDRL", result.suggestedMaps.kfldrl!!.mapName)
        assertEquals(1.0, result.suggestedMaps.kfldrl!!.coverage, 0.001)
    }

    @Test
    fun `OptimizerResult suggestedMaps carries MapDelta for kfldimx`() {
        val counts = Array(3) { IntArray(3) { 8 } }
        val delta = MapDelta.build("KFLDIMX", make3x3Map(100.0), make3x3Map(110.0), counts)
        val suggestedMaps = SuggestedMaps(kfldimx = delta)

        val result = OptimizerCalculator.OptimizerResult(
            suggestedKfldrl = null,
            suggestedKfldimx = delta.suggested,
            kfpbrkMultipliers = null,
            pressureErrors = emptyList(),
            loadErrors = emptyList(),
            warnings = emptyList(),
            wotEntries = emptyList(),
            suggestedMaps = suggestedMaps
        )

        assertNotNull(result.suggestedMaps.kfldimx)
        assertEquals("KFLDIMX", result.suggestedMaps.kfldimx!!.mapName)
        assertEquals(9, result.suggestedMaps.kfldimx!!.cellsWithData)
    }

    @Test
    fun `MapDelta build computes correct delta and deltaPercent`() {
        val current = make3x3Map(100.0)
        val suggested = make3x3Map(110.0)
        val counts = Array(3) { IntArray(3) { 10 } }
        val delta = MapDelta.build("TEST", current, suggested, counts)

        // Absolute delta should be 10.0 for every cell
        for (row in delta.delta.zAxis) {
            for (v in row) {
                assertEquals(10.0, v, 0.001)
            }
        }
        // Percent delta should be 10.0% for every cell
        for (row in delta.deltaPercent.zAxis) {
            for (v in row) {
                assertEquals(10.0, v, 0.001)
            }
        }
    }

    @Test
    fun `cellsModified counts only non-zero deltas`() {
        val current = make3x3Map(100.0)
        // Only modify some cells
        val sugZ = Array(3) { r -> Array(3) { c ->
            if (r == 0) 110.0 else 100.0 // Only first row changes
        } }
        val suggested = Map3d(current.xAxis, current.yAxis, sugZ)
        val counts = Array(3) { IntArray(3) { 10 } }
        val delta = MapDelta.build("TEST", current, suggested, counts)

        assertEquals(3, delta.cellsModified) // Only the 3 cells in row 0
    }
}
