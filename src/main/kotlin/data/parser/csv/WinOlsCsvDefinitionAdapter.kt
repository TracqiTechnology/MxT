package data.parser.csv

import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition

/**
 * Converts [WinOlsCsvMapDefinition] objects (from WinOLS CSV exports) into
 * [TableDefinition] objects that BinParser can use directly.
 *
 * This bridge allows calibration workflows to function with a WinOLS CSV
 * export alone — no XDF file required.
 */
object WinOlsCsvDefinitionAdapter {

    /**
     * Convert a list of CSV map definitions to XDF-compatible [TableDefinition]s.
     * Entries with no valid address (`address <= 0`) are excluded.
     */
    fun toTableDefinitions(csvDefs: List<WinOlsCsvMapDefinition>): List<TableDefinition> =
        csvDefs.filter { it.hasAddress }.map { toTableDefinition(it) }

    /**
     * Convert a single [WinOlsCsvMapDefinition] to a [TableDefinition].
     */
    fun toTableDefinition(csv: WinOlsCsvMapDefinition): TableDefinition {
        val zAxis = buildZAxis(csv)
        val xAxis = if (csv.hasXAxis) buildXAxis(csv) else null
        val yAxis = if (csv.hasYAxis) buildYAxis(csv) else null

        return TableDefinition(
            tableName = csv.id,
            tableDescription = csv.description,
            xAxis = xAxis,
            yAxis = yAxis,
            zAxis = zAxis
        )
    }

    // ── Axis builders ────────────────────────────────────────────────────

    private fun buildZAxis(csv: WinOlsCsvMapDefinition): AxisDefinition =
        AxisDefinition(
            id = "z",
            type = if (csv.valueMin < 0) 0x01 else 0x00, // bit 0 = signed
            address = csv.address,
            indexCount = csv.rows * csv.columns,
            sizeBits = csv.sizeBits,
            rowCount = csv.rows,
            columnCount = csv.columns,
            unit = csv.units,
            equation = scaleToEquation(csv.scale),
            varId = "X",
            axisValues = emptyList(),
            lsbFirst = csv.lsbFirst,
            min = csv.valueMin,
            max = csv.valueMax
        )

    private fun buildXAxis(csv: WinOlsCsvMapDefinition): AxisDefinition =
        AxisDefinition(
            id = "x",
            type = 0x00,
            address = csv.xAddress,
            indexCount = csv.columns,
            sizeBits = csv.sizeBits, // default to same as z-axis
            rowCount = 1,
            columnCount = csv.columns,
            unit = csv.xUnits,
            equation = scaleToEquation(csv.xScale),
            varId = "X",
            axisValues = emptyList(),
            lsbFirst = csv.lsbFirst
        )

    private fun buildYAxis(csv: WinOlsCsvMapDefinition): AxisDefinition =
        AxisDefinition(
            id = "y",
            type = 0x00,
            address = csv.yAddress,
            indexCount = csv.rows,
            sizeBits = csv.sizeBits, // default to same as z-axis
            rowCount = csv.rows,
            columnCount = 1,
            unit = csv.yUnits,
            equation = scaleToEquation(csv.yScale),
            varId = "X",
            axisValues = emptyList(),
            lsbFirst = csv.lsbFirst
        )

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Convert a numeric scale factor to a GraalJS equation string.
     * `1.0` → `"X"`, `0.75` → `"0.75 * X"`
     */
    internal fun scaleToEquation(scale: Double): String =
        if (scale == 1.0) "X" else "$scale * X"
}
