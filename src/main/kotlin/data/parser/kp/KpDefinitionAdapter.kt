package data.parser.kp

import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition

/**
 * Converts [KpMapDefinition] objects (from WinOLS KP binary parsing) into
 * [TableDefinition] objects that BinParser can use directly.
 *
 * This bridge allows calibration workflows to function with a WinOLS KP
 * file alone — no XDF or CSV export required.
 *
 * Mirrors [data.parser.csv.WinOlsCsvDefinitionAdapter].
 */
object KpDefinitionAdapter {

    /**
     * Convert a list of KP map definitions to XDF-compatible [TableDefinition]s.
     * Entries with no valid address are excluded.
     */
    fun toTableDefinitions(kpDefs: List<KpMapDefinition>): List<TableDefinition> =
        kpDefs.filter { it.hasAddress }.map { toTableDefinition(it) }

    /**
     * Convert a single [KpMapDefinition] to a [TableDefinition].
     */
    fun toTableDefinition(kp: KpMapDefinition): TableDefinition {
        val zAxis = buildZAxis(kp)
        val xAxis = if (kp.hasXAxis) buildXAxis(kp) else null
        val yAxis = if (kp.hasYAxis) buildYAxis(kp) else null

        return TableDefinition(
            tableName = kp.name,
            tableDescription = kp.description,
            xAxis = xAxis,
            yAxis = yAxis,
            zAxis = zAxis
        )
    }

    // ── Axis builders ────────────────────────────────────────────────────

    private fun buildZAxis(kp: KpMapDefinition): AxisDefinition =
        AxisDefinition(
            id = "z",
            type = if (kp.scale < 0) 0x01 else 0x00, // signed if scale is negative
            address = kp.effectiveAddress,
            indexCount = kp.rows * kp.columns,
            sizeBits = kp.sizeBits,
            rowCount = kp.rows,
            columnCount = kp.columns,
            unit = kp.units,
            equation = scaleToEquation(kp.scale),
            varId = "X",
            axisValues = emptyList(),
            lsbFirst = true // ME7 is always little-endian
        )

    private fun buildXAxis(kp: KpMapDefinition): AxisDefinition =
        AxisDefinition(
            id = "x",
            type = 0x00,
            address = kp.xAddress,
            indexCount = kp.columns,
            sizeBits = kp.sizeBits, // default to same as z-axis
            rowCount = 1,
            columnCount = kp.columns,
            unit = kp.xUnits,
            equation = scaleToEquation(kp.xScale),
            varId = "X",
            axisValues = emptyList(),
            lsbFirst = true
        )

    private fun buildYAxis(kp: KpMapDefinition): AxisDefinition =
        AxisDefinition(
            id = "y",
            type = 0x00,
            address = kp.yAddress,
            indexCount = kp.rows,
            sizeBits = kp.sizeBits, // default to same as z-axis
            rowCount = kp.rows,
            columnCount = 1,
            unit = kp.yUnits,
            equation = scaleToEquation(kp.yScale),
            varId = "X",
            axisValues = emptyList(),
            lsbFirst = true
        )

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Convert a numeric scale factor to a GraalJS equation string.
     * `1.0` → `"X"`, `0.75` → `"0.75 * X"`
     */
    internal fun scaleToEquation(scale: Double): String =
        if (scale == 1.0) "X" else "$scale * X"
}
