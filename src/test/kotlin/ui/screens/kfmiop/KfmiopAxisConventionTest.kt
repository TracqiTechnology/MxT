package ui.screens.kfmiop

import data.model.EcuPlatform
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import domain.math.map.Map3d
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [KfmiopAxisConvention] — the identity-based KFMIOP axis-orientation
 * detection and the symmetric normalize/denormalize transforms that replaced the
 * PR #95 `xAxis.last() > 500` magic-number heuristic.
 */
class KfmiopAxisConventionTest {

    private fun axis(unit: String = "", id: String = "ax", varId: String = "X"): AxisDefinition =
        AxisDefinition(
            id = id, type = 0, address = 0x10, indexCount = 4, sizeBits = 16,
            rowCount = 1, columnCount = 4, unit = unit, equation = "X", varId = varId,
            axisValues = emptyList()
        )

    private fun table(x: AxisDefinition?, y: AxisDefinition?): TableDefinition =
        TableDefinition("KFMIOP", "", x, y, axis())

    // ── isRpmAxis / isLoadAxis: unit and id variants ────────────────────────

    @Test
    fun `rpm units are recognised in many spellings`() {
        for (u in listOf("1/min", "1 / min", "rpm", "RPM", "U/min", "min-1", "/min")) {
            assertTrue(KfmiopAxisConvention.isRpmAxis(axis(unit = u)), "unit '$u' should be RPM")
        }
    }

    @Test
    fun `rpm recognised from axis id or varId when unit blank`() {
        assertTrue(KfmiopAxisConvention.isRpmAxis(axis(id = "KFMIOP_nmot")))
        assertTrue(KfmiopAxisConvention.isRpmAxis(axis(varId = "nmot_w")))
        assertTrue(KfmiopAxisConvention.isRpmAxis(axis(id = "drehzahl")))
    }

    @Test
    fun `load units and ids are recognised`() {
        for (u in listOf("%", "mg", "mg/stroke", "load")) {
            assertTrue(KfmiopAxisConvention.isLoadAxis(axis(unit = u)), "unit '$u' should be load")
        }
        assertTrue(KfmiopAxisConvention.isLoadAxis(axis(id = "rl")))
        assertTrue(KfmiopAxisConvention.isLoadAxis(axis(varId = "rl_w")))
    }

    @Test
    fun `blank and null axes are neither rpm nor load`() {
        assertFalse(KfmiopAxisConvention.isRpmAxis(axis()))
        assertFalse(KfmiopAxisConvention.isLoadAxis(axis()))
        assertFalse(KfmiopAxisConvention.isRpmAxis(null))
        assertFalse(KfmiopAxisConvention.isLoadAxis(null))
    }

    @Test
    fun `rpm and load classifications do not overlap for typical units`() {
        assertFalse(KfmiopAxisConvention.isLoadAxis(axis(unit = "1/min")))
        assertFalse(KfmiopAxisConvention.isRpmAxis(axis(unit = "%")))
    }

    // ── storedRpmOnX precedence ─────────────────────────────────────────────

    @Test
    fun `x rpm y load - swap`() =
        assertTrue(KfmiopAxisConvention.storedRpmOnX(table(axis(unit = "1/min"), axis(unit = "%")), EcuPlatform.ME7))

    @Test
    fun `x load y rpm - no swap`() =
        assertFalse(KfmiopAxisConvention.storedRpmOnX(table(axis(unit = "%"), axis(unit = "1/min")), EcuPlatform.MED9))

    @Test
    fun `x ambiguous y rpm - x is load, no swap`() =
        assertFalse(KfmiopAxisConvention.storedRpmOnX(table(axis(), axis(unit = "1/min")), EcuPlatform.MED9))

    @Test
    fun `x ambiguous y load - x is rpm, swap`() =
        assertTrue(KfmiopAxisConvention.storedRpmOnX(table(axis(), axis(unit = "%")), EcuPlatform.ME7))

    @Test
    fun `x rpm wins even if y also rpm`() =
        assertTrue(KfmiopAxisConvention.storedRpmOnX(table(axis(unit = "rpm"), axis(unit = "1/min")), EcuPlatform.ME7))

    @Test
    fun `unlabeled axes fall back to platform`() {
        assertTrue(KfmiopAxisConvention.storedRpmOnX(table(axis(), axis()), EcuPlatform.MED9))
        assertFalse(KfmiopAxisConvention.storedRpmOnX(table(axis(), axis()), EcuPlatform.ME7))
        assertFalse(KfmiopAxisConvention.storedRpmOnX(table(axis(), axis()), EcuPlatform.MED17))
    }

    @Test
    fun `null table or null axes fall back to platform`() {
        assertTrue(KfmiopAxisConvention.storedRpmOnX(null, EcuPlatform.MED9))
        assertFalse(KfmiopAxisConvention.storedRpmOnX(null, EcuPlatform.ME7))
        assertTrue(KfmiopAxisConvention.storedRpmOnX(table(null, null), EcuPlatform.MED9))
    }

    // ── normalize / denormalize ─────────────────────────────────────────────

    /** MED9 raw layout: xAxis=RPM(16), yAxis=load(11), zAxis[11 load rows][16 rpm cols]. */
    private fun med9RawMap(): Map3d {
        val rpm = Array(16) { (500 + it * 400).toDouble() }
        val load = Array(11) { (it * 10).toDouble() }
        val z = Array(11) { i -> Array(16) { j -> (i * 100 + j).toDouble() } }
        return Map3d(rpm, load, z)
    }

    @Test
    fun `normalize with swap transposes to load-on-x rpm-on-y`() {
        val raw = med9RawMap()
        val norm = KfmiopAxisConvention.normalize(raw, swap = true)
        assertEquals(11, norm.xAxis.size, "x becomes load (11)")
        assertEquals(16, norm.yAxis.size, "y becomes RPM (16)")
        assertEquals(16, norm.zAxis.size, "z becomes 16 rpm rows")
        assertEquals(11, norm.zAxis[0].size, "z becomes 11 load cols")
        for (i in 0 until 11) for (j in 0 until 16)
            assertEquals(raw.zAxis[i][j], norm.zAxis[j][i], "z[$i][$j] -> z'[$j][$i]")
        assertEquals(raw.xAxis.toList(), norm.yAxis.toList(), "old x (rpm) becomes new y")
        assertEquals(raw.yAxis.toList(), norm.xAxis.toList(), "old y (load) becomes new x")
    }

    @Test
    fun `normalize then denormalize is identity (swap)`() = assertRoundTrip(med9RawMap())

    @Test
    fun `denormalize then normalize is identity (swap)`() {
        val raw = med9RawMap()
        val there = KfmiopAxisConvention.denormalize(raw, swap = true)
        val back = KfmiopAxisConvention.normalize(there, swap = true)
        assertMapEquals(raw, back)
    }

    @Test
    fun `identity holds for square and single-row maps`() {
        assertRoundTrip(Map3d(Array(4) { it.toDouble() }, Array(4) { it.toDouble() },
            Array(4) { i -> Array(4) { j -> (i * 4 + j).toDouble() } }))
        assertRoundTrip(Map3d(Array(6) { it.toDouble() }, Array(1) { it.toDouble() },
            Array(1) { i -> Array(6) { j -> (i + j).toDouble() } }))
    }

    @Test
    fun `no-swap passes the map through unchanged`() {
        val raw = med9RawMap()
        assertMapEquals(raw, KfmiopAxisConvention.normalize(raw, swap = false))
        assertMapEquals(raw, KfmiopAxisConvention.denormalize(raw, swap = false))
    }

    @Test
    fun `swap actually changes orientation (not a silent no-op)`() {
        val raw = med9RawMap()
        val norm = KfmiopAxisConvention.normalize(raw, swap = true)
        assertTrue(norm.xAxis.size != raw.xAxis.size, "orientation must change for a non-square map")
    }

    @Test
    fun `scalar map passes through even when swap requested`() {
        val scalar = Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(42.0)))
        val norm = KfmiopAxisConvention.normalize(scalar, swap = true)
        assertEquals(1, norm.zAxis.size)
        assertEquals(42.0, norm.zAxis[0][0])
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun assertRoundTrip(raw: Map3d) {
        val restored = KfmiopAxisConvention.denormalize(
            KfmiopAxisConvention.normalize(raw, swap = true), swap = true
        )
        assertMapEquals(raw, restored)
    }

    private fun assertMapEquals(a: Map3d, b: Map3d) {
        assertEquals(a.xAxis.toList(), b.xAxis.toList(), "xAxis")
        assertEquals(a.yAxis.toList(), b.yAxis.toList(), "yAxis")
        assertEquals(a.zAxis.map { it.toList() }, b.zAxis.map { it.toList() }, "zAxis")
    }
}
