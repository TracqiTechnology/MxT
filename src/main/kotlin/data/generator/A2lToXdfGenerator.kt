package data.generator

import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition

/**
 * Generates a TunerPro-compatible XDF file from a list of [TableDefinition]s
 * produced by [data.parser.a2l.A2lParser.toTableDefinitions].
 *
 * MED9 specifics baked in:
 *  - DEFAULTS lsbfirst="0"  → big-endian by default
 *  - mmedtypeflags bit 1 = 0 → per-axis big-endian (explicit override)
 *  - No BASEOFFSET (A2L addresses are direct binary file offsets for MED9)
 */
object A2lToXdfGenerator {

    /**
     * Generate the full XDF XML string.
     *
     * @param tableDefinitions  Parsed calibration maps (VALUE / CURVE / MAP).
     * @param title             XDF header title.
     * @param author            XDF header author.
     * @return UTF-8 XML string ready to write to a .xdf file.
     */
    fun generate(
        tableDefinitions: List<TableDefinition>,
        title: String = "MED9 — Generated from A2L",
        author: String = "TracQi MxT A2L→XDF Generator"
    ): String {
        val sb = StringBuilder()
        var uid = 1

        fun nextUid() = "0x${(uid++).toString(16).padStart(8, '0').uppercase()}"

        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<XDFFORMAT version="1.70">""")
        sb.appendLine("""  <XDFHEADER>""")
        sb.appendLine("""    <deftitle>${escapeXml(title)}</deftitle>""")
        sb.appendLine("""    <author>${escapeXml(author)}</author>""")
        sb.appendLine("""    <BASEOFFSET offset="0" subtract="0"/>""")
        // lsbfirst="0" → all axes default to Big-Endian (MED9 PowerPC)
        sb.appendLine("""    <DEFAULTS datasizeinbits="8" signedflag="0" lsbfirst="0" float="0" sigdigits="2"/>""")
        sb.appendLine("""  </XDFHEADER>""")
        sb.appendLine()

        for (table in tableDefinitions) {
            val tableUid = nextUid()
            sb.appendLine("""  <XDFTABLE uniqueid="$tableUid" flags="0">""")
            sb.appendLine("""    <title>${escapeXml(table.tableName)}</title>""")
            if (table.tableDescription.isNotBlank()) {
                sb.appendLine("""    <description>${escapeXml(table.tableDescription)}</description>""")
            }

            // X axis (CURVE / MAP)
            table.xAxis?.let { ax ->
                sb.appendAxisElement("x", nextUid(), ax)
            }

            // Y axis (MAP only)
            table.yAxis?.let { ax ->
                sb.appendAxisElement("y", nextUid(), ax)
            }

            // Z axis (always present — data values)
            sb.appendAxisElement("z", nextUid(), table.zAxis)

            sb.appendLine("""  </XDFTABLE>""")
        }

        sb.appendLine("""</XDFFORMAT>""")
        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun StringBuilder.appendAxisElement(id: String, uniqueId: String, ax: AxisDefinition) {
        val flags    = typeFlags(ax)
        val addrHex  = "0x${ax.address.toString(16).uppercase()}"
        val rows     = maxOf(ax.rowCount, 1)
        val cols     = maxOf(ax.columnCount, 1)
        val idxCount = rows * cols
        val decPl    = if (ax.decimalPl >= 0) ax.decimalPl else 2

        appendLine("""    <XDFAXIS id="$id" uniqueid="$uniqueId">""")
        appendLine("""      <indexcount>$idxCount</indexcount>""")
        appendLine(
            """      <EMBEDDEDDATA""" +
            """ mmedtypeflags="$flags"""" +
            """ mmedaddress="$addrHex"""" +
            """ mmedelementsizebits="${ax.sizeBits}"""" +
            """ mmedrowcount="$rows"""" +
            """ mmedcolcount="$cols"""" +
            """ mmedmajorstridebits="${ax.majorStrideBits}"""" +
            """ mmedminorstridebits="${ax.minorStrideBits}"/>"""
        )
        if (ax.unit.isNotBlank() && ax.unit != "-") {
            appendLine("""      <units>${escapeXml(ax.unit)}</units>""")
        }
        appendLine("""      <decimalpl>$decPl</decimalpl>""")
        if (!ax.min.isNaN()) appendLine("""      <min>${ax.min}</min>""")
        if (!ax.max.isNaN()) appendLine("""      <max>${ax.max}</max>""")
        appendLine("""      <MATH equation="${escapeXml(ax.equation)}">""")
        appendLine("""        <VAR id="${ax.varId}"/>""")
        appendLine("""      </MATH>""")
        appendLine("""    </XDFAXIS>""")
    }

    /**
     * Compute mmedtypeflags from an [AxisDefinition].
     *
     * Bit layout (TunerPro XDF):
     *   bit 0 (0x01) — signed integer
     *   bit 1 (0x02) — LSB-first / little-endian  ← 0 for MED9 big-endian
     *   bit 2 (0x04) — column-major storage
     *   bit 3 (0x08) — IEEE-754 float
     */
    private fun typeFlags(ax: AxisDefinition): String {
        var f = 0
        if (ax.isSigned)      f = f or 0x01
        if (ax.lsbFirst)      f = f or 0x02   // MED9 axes have lsbFirst=false → bit stays 0
        if (ax.isColumnMajor) f = f or 0x04
        if (ax.isFloat)       f = f or 0x08
        return "0x${f.toString(16).uppercase().padStart(2, '0')}"
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
