package data.parser.kp

import data.parser.bin.BinParser
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import kotlin.test.*

/**
 * Tests for [KpDefinitionAdapter] — the KpMapDefinition → TableDefinition bridge,
 * and merge priority with XDF/CSV definitions.
 */
class KpDefinitionAdapterTest {

    // ── Helper to build a minimal KpMapDefinition ─────────────────────────

    private fun kpDef(
        name: String = "TESTMAP",
        arAddress: Int = 0x10000,
        columns: Int = 1,
        rows: Int = 1,
        sizeBits: Int = 16,
        scale: Double = 1.0,
        units: String = "",
        zAddress: Int = 0x10000,
        xAddress: Int = -1,
        yAddress: Int = -1,
        xScale: Double = 1.0,
        yScale: Double = 1.0,
        xUnits: String = "",
        yUnits: String = "",
        description: String = "Test map"
    ) = KpMapDefinition(
        name = name,
        description = description,
        arAddress = arAddress,
        columns = columns,
        rows = rows,
        sizeBits = sizeBits,
        scale = scale,
        units = units,
        zAddress = zAddress,
        xAddress = xAddress,
        yAddress = yAddress,
        xScale = xScale,
        yScale = yScale,
        xUnits = xUnits,
        yUnits = yUnits
    )

    // ── Scalar (1×1) ─────────────────────────────────────────────────────

    @Test
    fun `scalar definition has no x or y axis`() {
        val td = KpDefinitionAdapter.toTableDefinition(kpDef(rows = 1, columns = 1))

        assertEquals("TESTMAP", td.tableName)
        assertNull(td.xAxis, "Scalar should have no x-axis")
        assertNull(td.yAxis, "Scalar should have no y-axis")
        assertEquals(1, td.zAxis.rowCount)
        assertEquals(1, td.zAxis.columnCount)
        assertEquals(0x10000, td.zAxis.address)
    }

    // ── 1D curve (N×1) ──────────────────────────────────────────────────

    @Test
    fun `1D curve has x-axis with correct indexCount and no y-axis`() {
        val td = KpDefinitionAdapter.toTableDefinition(
            kpDef(rows = 1, columns = 8, xAddress = 0x20000)
        )

        assertNotNull(td.xAxis)
        assertNull(td.yAxis, "1D curve should have no y-axis")
        assertEquals(8, td.xAxis!!.indexCount)
        assertEquals(0x20000, td.xAxis!!.address)
        assertEquals(1, td.zAxis.rowCount)
        assertEquals(8, td.zAxis.columnCount)
    }

    // ── 2D table (N×M) ─────────────────────────────────────────────────

    @Test
    fun `2D table has both x and y axes with correct dimensions`() {
        val td = KpDefinitionAdapter.toTableDefinition(
            kpDef(
                rows = 10, columns = 12,
                xAddress = 0x20000, yAddress = 0x30000,
                xUnits = "rpm", yUnits = "%"
            )
        )

        assertNotNull(td.xAxis)
        assertNotNull(td.yAxis)
        assertEquals(12, td.xAxis!!.indexCount)
        assertEquals(10, td.yAxis!!.indexCount)
        assertEquals(0x20000, td.xAxis!!.address)
        assertEquals(0x30000, td.yAxis!!.address)
        assertEquals("rpm", td.xAxis!!.unit)
        assertEquals("%", td.yAxis!!.unit)
    }

    // ── Scale equation ──────────────────────────────────────────────────

    @Test
    fun `scale 1_0 produces identity equation`() {
        assertEquals("X", KpDefinitionAdapter.scaleToEquation(1.0))
    }

    @Test
    fun `non-unity scale produces multiplication equation`() {
        assertEquals("0.75 * X", KpDefinitionAdapter.scaleToEquation(0.75))
    }

    @Test
    fun `scale is applied to z-axis equation`() {
        val td = KpDefinitionAdapter.toTableDefinition(kpDef(scale = 0.023438))
        assertEquals("0.023438 * X", td.zAxis.equation)
    }

    // ── Signed heuristic ────────────────────────────────────────────────

    @Test
    fun `negative scale produces signed z-axis`() {
        val td = KpDefinitionAdapter.toTableDefinition(kpDef(scale = -0.75))
        assertTrue(td.zAxis.isSigned, "Negative scale should produce signed axis")
    }

    @Test
    fun `positive scale produces unsigned z-axis`() {
        val td = KpDefinitionAdapter.toTableDefinition(kpDef(scale = 0.75))
        assertFalse(td.zAxis.isSigned)
    }

    // ── Always little-endian ────────────────────────────────────────────

    @Test
    fun `all axes are little-endian`() {
        val td = KpDefinitionAdapter.toTableDefinition(
            kpDef(rows = 3, columns = 4, xAddress = 0x20000, yAddress = 0x30000)
        )

        assertTrue(td.zAxis.lsbFirst, "z-axis should be LE")
        assertTrue(td.xAxis!!.lsbFirst, "x-axis should be LE")
        assertTrue(td.yAxis!!.lsbFirst, "y-axis should be LE")
    }

    // ── No-address filter ───────────────────────────────────────────────

    @Test
    fun `entries with no valid address are excluded`() {
        val defs = listOf(
            kpDef(name = "GOOD", arAddress = 0x10000, zAddress = 0x10000),
            kpDef(name = "BAD", arAddress = -1, zAddress = -1)
        )

        val result = KpDefinitionAdapter.toTableDefinitions(defs)
        assertEquals(1, result.size)
        assertEquals("GOOD", result[0].tableName)
    }

    // ── Effective address ───────────────────────────────────────────────

    @Test
    fun `effectiveAddress prefers zAddress over arAddress`() {
        val def = kpDef(arAddress = 0x1000, zAddress = 0x2000)
        assertEquals(0x2000, def.effectiveAddress)
    }

    @Test
    fun `effectiveAddress falls back to arAddress when zAddress is -1`() {
        val def = kpDef(arAddress = 0x1000, zAddress = -1)
        assertEquals(0x1000, def.effectiveAddress)
    }

    // ── toHint backward compatibility ───────────────────────────────────

    @Test
    fun `toHint preserves name, description, and arAddress`() {
        val def = kpDef(name = "KFPBRK", description = "Correction factor", arAddress = 0x1E3B0)
        val hint = def.toHint()

        assertEquals("KFPBRK", hint.name)
        assertEquals("Correction factor", hint.description)
        assertEquals(0x1E3B0, hint.arAddress)
    }

    // ── Merge priority: XDF > CSV > KP ──────────────────────────────────

    @Test
    fun `KP definitions fill gaps not in XDF or CSV`() {
        val xdfDef = TableDefinition(
            tableName = "KFZW",
            tableDescription = "XDF version",
            xAxis = null,
            yAxis = null,
            zAxis = AxisDefinition(
                id = "z", type = 0, address = 0xAAAA, indexCount = 1,
                sizeBits = 16, rowCount = 1, columnCount = 1,
                unit = "", equation = "X", varId = "X", axisValues = emptyList()
            )
        )

        val kpDefs = listOf(
            kpDef(name = "KFZW", zAddress = 0xBBBB),  // should be filtered (XDF has it)
            kpDef(name = "MLHFM", zAddress = 0xCCCC)   // should fill gap
        )

        val merged = BinParser.mergeDefinitions(listOf(xdfDef), emptyList(), kpDefs)

        assertEquals(2, merged.size)
        assertEquals("KFZW", merged[0].tableName)
        assertEquals(0xAAAA, merged[0].zAxis.address, "XDF should win")
        assertEquals("MLHFM", merged[1].tableName)
        assertEquals(0xCCCC, merged[1].zAxis.address, "KP should fill gap")
    }

    @Test
    fun `KP-only produces KP result when XDF and CSV are empty`() {
        val kpDefs = listOf(kpDef(name = "TEST", zAddress = 0x5000))

        val merged = BinParser.mergeDefinitions(emptyList(), emptyList(), kpDefs)

        assertEquals(1, merged.size)
        assertEquals("TEST", merged[0].tableName)
    }

    // ── Round-trip with BinParser ───────────────────────────────────────

    @Test
    fun `KP-derived definition can parse synthetic BIN data`() {
        // x-axis at 0x02 (4 × 16-bit LE), z-data at 0x0A (4 × 16-bit LE)
        val bin = ByteArray(18)
        val xValues = shortArrayOf(1000, 2000, 3000, 4000)
        val zValues = shortArrayOf(100, 200, 300, 400)

        var offset = 2
        for (v in xValues) {
            bin[offset] = (v.toInt() and 0xFF).toByte()
            bin[offset + 1] = (v.toInt() shr 8 and 0xFF).toByte()
            offset += 2
        }
        for (v in zValues) {
            bin[offset] = (v.toInt() and 0xFF).toByte()
            bin[offset + 1] = (v.toInt() shr 8 and 0xFF).toByte()
            offset += 2
        }

        val kp = kpDef(
            name = "TEST_1D",
            zAddress = 0x0A,
            arAddress = 0x0A,
            rows = 1,
            columns = 4,
            sizeBits = 16,
            scale = 0.1,
            xAddress = 0x02,
            xScale = 1.0
        )

        val tableDef = KpDefinitionAdapter.toTableDefinition(kp)
        val result = BinParser.parseToList(bin.inputStream(), listOf(tableDef))

        assertEquals(1, result.size)
        val map3d = result[0].second

        assertEquals(4, map3d.xAxis.size)
        assertEquals(1000.0, map3d.xAxis[0])
        assertEquals(4000.0, map3d.xAxis[3])

        assertEquals(1, map3d.zAxis.size)
        assertEquals(4, map3d.zAxis[0].size)
        assertEquals(10.0, map3d.zAxis[0][0], 0.001)
        assertEquals(40.0, map3d.zAxis[0][3], 0.001)
    }
}
