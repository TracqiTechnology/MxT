package data.parser.csv

import data.parser.bin.BinParser
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import kotlin.test.*

/**
 * Tests for [WinOlsCsvDefinitionAdapter] — the CSV → TableDefinition bridge.
 */
class WinOlsCsvDefinitionAdapterTest {

    // ── Helper to build a minimal WinOlsCsvMapDefinition ─────────────────

    private fun csvDef(
        id: String = "TESTMAP",
        address: Int = 0x10000,
        rows: Int = 1,
        columns: Int = 1,
        sizeBits: Int = 16,
        lsbFirst: Boolean = true,
        scale: Double = 1.0,
        units: String = "",
        xAddress: Int = -1,
        yAddress: Int = -1,
        xScale: Double = 1.0,
        yScale: Double = 1.0,
        xUnits: String = "",
        yUnits: String = "",
        valueMin: Double = 0.0,
        valueMax: Double = 255.0,
        description: String = "Test map"
    ) = WinOlsCsvMapDefinition(
        id = id,
        rawId = id,
        address = address,
        name = id,
        columns = columns,
        rows = rows,
        sizeBits = sizeBits,
        lsbFirst = lsbFirst,
        description = description,
        units = units,
        xAddress = xAddress,
        yAddress = yAddress,
        xUnits = xUnits,
        yUnits = yUnits,
        scale = scale,
        xScale = xScale,
        yScale = yScale,
        valueMin = valueMin,
        valueMax = valueMax
    )

    // ── Scalar (1×1) ─────────────────────────────────────────────────────

    @Test
    fun `scalar definition has no x or y axis`() {
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(csvDef(rows = 1, columns = 1))

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
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(
            csvDef(rows = 1, columns = 8, xAddress = 0x20000)
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
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(
            csvDef(
                rows = 10, columns = 12,
                xAddress = 0x20000, yAddress = 0x30000,
                xUnits = "rpm", yUnits = "%"
            )
        )

        assertNotNull(td.xAxis)
        assertNotNull(td.yAxis)
        assertEquals(12, td.xAxis!!.indexCount, "x-axis indexCount should equal columns")
        assertEquals(10, td.yAxis!!.indexCount, "y-axis indexCount should equal rows")
        assertEquals(0x20000, td.xAxis!!.address)
        assertEquals(0x30000, td.yAxis!!.address)
        assertEquals("rpm", td.xAxis!!.unit)
        assertEquals("%", td.yAxis!!.unit)
        assertEquals(10, td.zAxis.rowCount)
        assertEquals(12, td.zAxis.columnCount)
    }

    // ── Signed heuristic ────────────────────────────────────────────────

    @Test
    fun `negative valueMin produces signed z-axis`() {
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(
            csvDef(valueMin = -40.0, valueMax = 200.0)
        )

        assertTrue(td.zAxis.isSigned, "Negative valueMin should produce signed axis")
    }

    @Test
    fun `non-negative valueMin produces unsigned z-axis`() {
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(
            csvDef(valueMin = 0.0, valueMax = 255.0)
        )

        assertFalse(td.zAxis.isSigned, "Non-negative valueMin should produce unsigned axis")
    }

    // ── Scale equation ──────────────────────────────────────────────────

    @Test
    fun `scale 1_0 produces identity equation`() {
        assertEquals("X", WinOlsCsvDefinitionAdapter.scaleToEquation(1.0))
    }

    @Test
    fun `non-unity scale produces multiplication equation`() {
        assertEquals("0.75 * X", WinOlsCsvDefinitionAdapter.scaleToEquation(0.75))
    }

    @Test
    fun `scale is applied to z-axis equation`() {
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(csvDef(scale = 0.023438))
        assertEquals("0.023438 * X", td.zAxis.equation)
    }

    @Test
    fun `axis scale is applied to x and y equations`() {
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(
            csvDef(
                rows = 5, columns = 5,
                xAddress = 0x20000, yAddress = 0x30000,
                xScale = 40.0, yScale = 0.75
            )
        )

        assertEquals("40.0 * X", td.xAxis!!.equation)
        assertEquals("0.75 * X", td.yAxis!!.equation)
    }

    // ── No-address filter ───────────────────────────────────────────────

    @Test
    fun `entries with address 0 or negative are excluded`() {
        val defs = listOf(
            csvDef(id = "GOOD", address = 0x10000),
            csvDef(id = "ZERO", address = 0),
            csvDef(id = "NEG", address = -1)
        )

        val result = WinOlsCsvDefinitionAdapter.toTableDefinitions(defs)

        assertEquals(1, result.size)
        assertEquals("GOOD", result[0].tableName)
    }

    // ── Byte order and bit width propagation ────────────────────────────

    @Test
    fun `sizeBits and lsbFirst propagate to all axes`() {
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(
            csvDef(
                rows = 3, columns = 4,
                sizeBits = 8, lsbFirst = false,
                xAddress = 0x20000, yAddress = 0x30000
            )
        )

        assertEquals(8, td.zAxis.sizeBits)
        assertFalse(td.zAxis.lsbFirst, "z-axis should be big-endian")
        assertEquals(8, td.xAxis!!.sizeBits)
        assertFalse(td.xAxis!!.lsbFirst)
        assertEquals(8, td.yAxis!!.sizeBits)
        assertFalse(td.yAxis!!.lsbFirst)
    }

    // ── Units and description ───────────────────────────────────────────

    @Test
    fun `units and description are preserved`() {
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(
            csvDef(units = "kg/h", description = "Mass air flow sensor linearization")
        )

        assertEquals("kg/h", td.zAxis.unit)
        assertEquals("Mass air flow sensor linearization", td.tableDescription)
    }

    // ── Min/max propagation ────────────────────────────────────────────

    @Test
    fun `valueMin and valueMax propagate to z-axis`() {
        val td = WinOlsCsvDefinitionAdapter.toTableDefinition(
            csvDef(valueMin = -10.5, valueMax = 250.0)
        )

        assertEquals(-10.5, td.zAxis.min)
        assertEquals(250.0, td.zAxis.max)
    }

    // ── Merge priority ──────────────────────────────────────────────────

    @Test
    fun `XDF definitions take precedence over CSV definitions with same name`() {
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

        val csvDefs = listOf(csvDef(id = "KFZW", address = 0xBBBB))

        val merged = BinParser.mergeDefinitions(listOf(xdfDef), csvDefs)

        assertEquals(1, merged.size, "Duplicate should be filtered")
        assertEquals(0xAAAA, merged[0].zAxis.address, "XDF address should win")
    }

    @Test
    fun `CSV definitions fill gaps not in XDF`() {
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

        val csvDefs = listOf(
            csvDef(id = "KFZW", address = 0xBBBB),
            csvDef(id = "MLHFM", address = 0xCCCC)
        )

        val merged = BinParser.mergeDefinitions(listOf(xdfDef), csvDefs)

        assertEquals(2, merged.size)
        assertEquals("KFZW", merged[0].tableName)
        assertEquals(0xAAAA, merged[0].zAxis.address, "XDF KFZW should win")
        assertEquals("MLHFM", merged[1].tableName)
        assertEquals(0xCCCC, merged[1].zAxis.address, "CSV MLHFM should fill gap")
    }

    @Test
    fun `merge is case-insensitive on table names`() {
        val xdfDef = TableDefinition(
            tableName = "Kfzw",
            tableDescription = "",
            xAxis = null,
            yAxis = null,
            zAxis = AxisDefinition(
                id = "z", type = 0, address = 0xAAAA, indexCount = 1,
                sizeBits = 16, rowCount = 1, columnCount = 1,
                unit = "", equation = "X", varId = "X", axisValues = emptyList()
            )
        )

        val csvDefs = listOf(csvDef(id = "KFZW", address = 0xBBBB))

        val merged = BinParser.mergeDefinitions(listOf(xdfDef), csvDefs)

        assertEquals(1, merged.size, "Case-insensitive match should filter duplicate")
    }

    @Test
    fun `empty CSV produces XDF-only result`() {
        val xdfDef = TableDefinition(
            tableName = "TEST",
            tableDescription = "",
            xAxis = null,
            yAxis = null,
            zAxis = AxisDefinition(
                id = "z", type = 0, address = 0x1000, indexCount = 1,
                sizeBits = 16, rowCount = 1, columnCount = 1,
                unit = "", equation = "X", varId = "X", axisValues = emptyList()
            )
        )

        val merged = BinParser.mergeDefinitions(listOf(xdfDef), emptyList())
        assertEquals(1, merged.size)
        assertEquals("TEST", merged[0].tableName)
    }

    @Test
    fun `empty XDF produces CSV-only result`() {
        val csvDefs = listOf(csvDef(id = "MLHFM", address = 0x5000))

        val merged = BinParser.mergeDefinitions(emptyList(), csvDefs)

        assertEquals(1, merged.size)
        assertEquals("MLHFM", merged[0].tableName)
    }

    // ── Batch conversion ────────────────────────────────────────────────

    @Test
    fun `toTableDefinitions converts all valid entries`() {
        val defs = listOf(
            csvDef(id = "MAP_A", address = 0x1000, rows = 5, columns = 5, xAddress = 0x2000, yAddress = 0x3000),
            csvDef(id = "MAP_B", address = 0x4000, rows = 1, columns = 10, xAddress = 0x5000),
            csvDef(id = "SCALAR_C", address = 0x6000)
        )

        val result = WinOlsCsvDefinitionAdapter.toTableDefinitions(defs)

        assertEquals(3, result.size)
        assertEquals("MAP_A", result[0].tableName)
        assertNotNull(result[0].xAxis)
        assertNotNull(result[0].yAxis)

        assertEquals("MAP_B", result[1].tableName)
        assertNotNull(result[1].xAxis)
        assertNull(result[1].yAxis)

        assertEquals("SCALAR_C", result[2].tableName)
        assertNull(result[2].xAxis)
        assertNull(result[2].yAxis)
    }

    // ── Round-trip with BinParser ───────────────────────────────────────

    @Test
    fun `CSV-derived definition can parse synthetic BIN data correctly`() {
        // Create a synthetic BIN with known values at known addresses
        // 2-byte padding at start (address 0 is treated as virtual by BinParser),
        // then x-axis at 0x02 (4 × 16-bit LE), z-data at 0x0A (4 × 16-bit LE)
        val bin = ByteArray(18)
        val xValues = shortArrayOf(1000, 2000, 3000, 4000)
        val zValues = shortArrayOf(100, 200, 300, 400)

        var offset = 2 // skip padding
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

        // Build a CSV definition pointing at these addresses
        val csv = csvDef(
            id = "TEST_1D",
            address = 0x0A,  // z-data starts at byte 10
            rows = 1,
            columns = 4,
            sizeBits = 16,
            lsbFirst = true,
            scale = 0.1,     // physical = raw * 0.1
            xAddress = 0x02, // x-axis at byte 2
            xScale = 1.0
        )

        val tableDef = WinOlsCsvDefinitionAdapter.toTableDefinition(csv)
        val result = BinParser.parseToList(bin.inputStream(), listOf(tableDef))

        assertEquals(1, result.size)
        val (_, map3d) = result[0]

        // X-axis: raw values 1000..4000, equation "X" (scale=1.0)
        assertEquals(4, map3d.xAxis.size)
        assertEquals(1000.0, map3d.xAxis[0])
        assertEquals(4000.0, map3d.xAxis[3])

        // Z-data: raw values 100..400, equation "0.1 * X"
        assertEquals(1, map3d.zAxis.size)
        assertEquals(4, map3d.zAxis[0].size)
        assertEquals(10.0, map3d.zAxis[0][0], 0.001)  // 100 * 0.1
        assertEquals(20.0, map3d.zAxis[0][1], 0.001)  // 200 * 0.1
        assertEquals(30.0, map3d.zAxis[0][2], 0.001)  // 300 * 0.1
        assertEquals(40.0, map3d.zAxis[0][3], 0.001)  // 400 * 0.1
    }

    @Test
    fun `2D CSV definition parses synthetic BIN with both axes`() {
        // 1-byte padding at start (address 0 = virtual in BinParser)
        // y-axis at 0x01 (2 × 8-bit), x-axis at 0x03 (3 × 8-bit), z-data at 0x06 (2×3 × 8-bit)
        val bin = ByteArray(12)
        // Padding
        bin[0] = 0
        // Y-axis: 10, 20
        bin[1] = 10; bin[2] = 20
        // X-axis: 1, 2, 3
        bin[3] = 1; bin[4] = 2; bin[5] = 3
        // Z-data (row-major): [11,12,13, 21,22,23]
        bin[6] = 11; bin[7] = 12; bin[8] = 13
        bin[9] = 21; bin[10] = 22; bin[11] = 23

        val csv = csvDef(
            id = "TEST_2D",
            address = 0x06,
            rows = 2,
            columns = 3,
            sizeBits = 8,
            lsbFirst = true,
            scale = 1.0,
            xAddress = 0x03,
            yAddress = 0x01,
            xScale = 1.0,
            yScale = 1.0
        )

        val tableDef = WinOlsCsvDefinitionAdapter.toTableDefinition(csv)
        val result = BinParser.parseToList(bin.inputStream(), listOf(tableDef))

        assertEquals(1, result.size)
        val map3d = result[0].second

        // Y-axis
        assertEquals(2, map3d.yAxis.size)
        assertEquals(10.0, map3d.yAxis[0])
        assertEquals(20.0, map3d.yAxis[1])

        // X-axis
        assertEquals(3, map3d.xAxis.size)
        assertEquals(1.0, map3d.xAxis[0])
        assertEquals(3.0, map3d.xAxis[2])

        // Z-data
        assertEquals(2, map3d.zAxis.size)
        assertEquals(3, map3d.zAxis[0].size)
        assertEquals(11.0, map3d.zAxis[0][0])
        assertEquals(13.0, map3d.zAxis[0][2])
        assertEquals(23.0, map3d.zAxis[1][2])
    }
}
