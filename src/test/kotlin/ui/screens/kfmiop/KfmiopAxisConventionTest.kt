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

    private fun axis(unit: String = "", id: String = "", varId: String = "X"): AxisDefinition =
        AxisDefinition(
            id = id, type = 0, address = 0x10, indexCount = 4, sizeBits = 16,
            rowCount = 1, columnCount = 4, unit = unit, equation = "X", varId = varId,
            axisValues = emptyList()
        )

    private fun table(x: AxisDefinition?, y: AxisDefinition?): TableDefinition =
        TableDefinition("KFMIOP", "", x, y, axis())

    // ── storedRpmOnX: identity from axis metadata ───────────────────────────

    @Test
    fun `x-axis in rpm units means stored rpm-on-x (swap)`() {
        val def = table(x = axis(unit = "1/min"), y = axis(unit = "%"))
        assertTrue(KfmiopAxisConvention.storedRpmOnX(def, EcuPlatform.ME7))
    }

    @Test
    fun `x-axis in load units means algorithm convention (no swap)`() {
        val def = table(x = axis(unit = "%"), y = axis(unit = "1/min"))
        assertFalse(KfmiopAxisConvention.storedRpmOnX(def, EcuPlatform.MED9))
    }

    @Test
    fun `rpm detected from axis id when unit is blank`() {
        val def = table(x = axis(id = "KFMIOP_nmot"), y = axis(id = "KFMIOP_rl"))
        assertTrue(KfmiopAxisConvention.storedRpmOnX(def, EcuPlatform.ME7))
    }

    @Test
    fun `y-axis rpm implies x is load (no swap)`() {
        val def = table(x = axis(unit = ""), y = axis(unit = "1/min"))
        assertFalse(KfmiopAxisConvention.storedRpmOnX(def, EcuPlatform.MED9))
    }

    // ── storedRpmOnX: platform fallback only when unlabeled ──────────────────

    @Test
    fun `unlabeled axes fall back to platform - MED9 swaps`() {
        val def = table(x = axis(), y = axis())
        assertTrue(KfmiopAxisConvention.storedRpmOnX(def, EcuPlatform.MED9))
    }

    @Test
    fun `unlabeled axes fall back to platform - ME7 does not swap`() {
        val def = table(x = axis(), y = axis())
        assertFalse(KfmiopAxisConvention.storedRpmOnX(def, EcuPlatform.ME7))
    }

    @Test
    fun `null table def falls back to platform`() {
        assertTrue(KfmiopAxisConvention.storedRpmOnX(null, EcuPlatform.MED9))
        assertFalse(KfmiopAxisConvention.storedRpmOnX(null, EcuPlatform.ME7))
    }

    // ── normalize / denormalize symmetry ────────────────────────────────────

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
        // Transposed element check
        assertEquals(raw.zAxis[3][5], norm.zAxis[5][3], "z[i][j] -> z'[j][i]")
    }

    @Test
    fun `normalize then denormalize is identity (swap)`() {
        val raw = med9RawMap()
        val restored = KfmiopAxisConvention.denormalize(
            KfmiopAxisConvention.normalize(raw, swap = true), swap = true
        )
        assertEquals(raw.xAxis.toList(), restored.xAxis.toList())
        assertEquals(raw.yAxis.toList(), restored.yAxis.toList())
        assertEquals(raw.zAxis.map { it.toList() }, restored.zAxis.map { it.toList() })
    }

    @Test
    fun `no-swap passes the map through unchanged`() {
        val raw = med9RawMap()
        val norm = KfmiopAxisConvention.normalize(raw, swap = false)
        assertEquals(raw.xAxis.toList(), norm.xAxis.toList())
        assertEquals(raw.yAxis.toList(), norm.yAxis.toList())
        assertEquals(raw.zAxis.map { it.toList() }, norm.zAxis.map { it.toList() })
    }

    @Test
    fun `scalar map (empty axes) passes through even when swap requested`() {
        val scalar = Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(42.0)))
        val norm = KfmiopAxisConvention.normalize(scalar, swap = true)
        assertEquals(1, norm.zAxis.size)
        assertEquals(42.0, norm.zAxis[0][0])
    }
}
