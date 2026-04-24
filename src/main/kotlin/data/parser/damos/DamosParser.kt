package data.parser.damos

import data.parser.a2l.EcuEntry
import java.io.File
import java.nio.charset.Charset

/**
 * Parses DAMOS 2 `.dam` files (Bosch ECU parameter definition format).
 *
 * Extracts UMP (measurement variables), REG (register types), SRC (source channels),
 * and SND (memory segments) from the Latin-1 encoded text format.
 */
object DamosParser {

    private val LATIN1 = Charset.forName("ISO-8859-1")

    // Header patterns
    private val EPR_PATTERN = Regex("""/EPR,\s*\{([^}]*)},\s*\{([^}]*)},""")
    private val PNR_PATTERN = Regex("""/PNR,\s*\{([^}]*)}""")
    private val SPC_PATTERN = Regex("""/SPC,\s*(\d+)""")
    private val UMR_PATTERN = Regex("""/UMR,\s*(\d+)""")
    private val SRC_COUNT_PATTERN = Regex("""^/SRC,\s*(\d+)$""")
    private val UP_PATTERN = Regex("""/UP,\s*\{([^}]*)}""")
    private val SGB_PATTERN = Regex("""/SGB,\s*\{([^}]*)}""")

    // Segment pattern: /SND, NAME $START $END
    private val SND_PATTERN = Regex("""/SND,\s+(\w+)\s+\$([0-9A-Fa-f]+)\s+\$([0-9A-Fa-f]+)""")

    // UMP pattern (multi-field, brace-aware parsing needed)
    private val UMP_START = "/UMP,"

    // REG pattern
    private val REG_PATTERN = Regex(
        """(\d+),\s*/REG,\s*(\S+),\s*\{[^}]*},\s*\d+,\s*(-?\d+),\s*\{([^}]*)},\s*(\d+),\s*\d+,\s*[-\d.Ee+]+,\s*[-\d.Ee+]+"""
    )
    private val REP_PATTERN = Regex(
        """/REP,\s*([-\d.Ee+]+),\s*([-\d.Ee+]+),\s*[-\d.Ee+]+,\s*([-\d.Ee+]+),"""
    )

    // SRC pattern
    private val SRC_LINE_PATTERN = Regex(
        """(\d+),\s*/SRC,\s*(\S+),\s*\{([^}]*)},\s*\$([0-9A-Fa-f]+),\s*(-?\d+),\s*(\d+),\s*(\d+),\s*(\d+);"""
    )

    fun parse(file: File): DamosFile = parse(file.readText(LATIN1))

    fun parse(text: String): DamosFile {
        val lines = text.lines()

        // Parse header
        var programNumber = ""
        var version = ""
        var ecuFamily = ""
        var cpuType = ""
        var spzCount = 0
        var umpCount = 0
        var srcCount = 0

        for (line in lines.take(50)) {
            val trimmed = line.trim().trimEnd('\r')
            EPR_PATTERN.find(trimmed)?.let {
                programNumber = it.groupValues[1]
                version = it.groupValues[2]
            }
            PNR_PATTERN.find(trimmed)?.let { programNumber = it.groupValues[1] }
            SPC_PATTERN.find(trimmed)?.let { spzCount = it.groupValues[1].toInt() }
            UMR_PATTERN.find(trimmed)?.let { umpCount = it.groupValues[1].toInt() }
            SRC_COUNT_PATTERN.find(trimmed)?.let { srcCount = it.groupValues[1].toInt() }
            UP_PATTERN.find(trimmed)?.let { cpuType = it.groupValues[1] }
            SGB_PATTERN.find(trimmed)?.let { ecuFamily = it.groupValues[1] }
        }

        val segments = parseSegments(lines)
        val regRecords = parseRegRecords(lines)
        val umpRecords = parseUmpRecords(lines)
        val srcRecords = parseSrcRecords(lines)

        return DamosFile(
            programNumber = programNumber,
            version = version,
            ecuFamily = ecuFamily,
            cpuType = cpuType,
            spzCount = spzCount,
            umpCount = umpCount,
            srcCount = srcCount,
            segments = segments,
            umpRecords = umpRecords,
            regRecords = regRecords,
            srcRecords = srcRecords
        )
    }

    private fun parseSegments(lines: List<String>): List<DamosSegment> {
        return lines.mapNotNull { line ->
            val trimmed = line.trim().trimEnd('\r')
            SND_PATTERN.find(trimmed)?.let { m ->
                DamosSegment(
                    name = m.groupValues[1],
                    start = m.groupValues[2].toLong(16),
                    end = m.groupValues[3].toLong(16)
                )
            }
        }
    }

    private fun parseRegRecords(lines: List<String>): Map<Int, DamosRegRecord> {
        val records = mutableMapOf<Int, DamosRegRecord>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim().trimEnd('\r')
            val regMatch = REG_PATTERN.find(trimmed)
            if (regMatch != null) {
                val id = regMatch.groupValues[1].toInt()
                val name = regMatch.groupValues[2]
                val signed = regMatch.groupValues[3].toInt()
                val unit = regMatch.groupValues[4]
                val sizeBytes = regMatch.groupValues[5].toInt()

                // Look for /REP on next non-blank line
                var j = i + 1
                while (j < lines.size && lines[j].trim().trimEnd('\r').isEmpty()) j++
                if (j < lines.size) {
                    val repMatch = REP_PATTERN.find(lines[j].trim().trimEnd('\r'))
                    if (repMatch != null) {
                        records[id] = DamosRegRecord(
                            id = id,
                            name = name,
                            unit = unit,
                            factor = repMatch.groupValues[3].toDouble(),
                            divisor = repMatch.groupValues[1].toDouble(),
                            offset = repMatch.groupValues[2].toDouble(),
                            sizeBytes = sizeBytes,
                            signed = signed
                        )
                        i = j + 1
                        continue
                    }
                }
            }
            i++
        }
        return records
    }

    private fun parseUmpRecords(lines: List<String>): Map<String, DamosUmpRecord> {
        val records = mutableMapOf<String, DamosUmpRecord>()
        for (line in lines) {
            val trimmed = line.trim().trimEnd('\r')
            if (!trimmed.startsWith(UMP_START)) continue
            val record = parseUmpLine(trimmed) ?: continue
            records[record.name] = record
        }
        return records
    }

    /**
     * Parse a single UMP line:
     * `/UMP, {desc1}, name, {desc2}, $address, typeId, regId, regName, triggerGroup, $bitmask, K;`
     */
    internal fun parseUmpLine(line: String): DamosUmpRecord? {
        // Split by commas respecting braces
        val fields = splitFields(line)
        // Expected: /UMP, {desc1}, name, {desc2}, $address, typeId, regId, regName, triggerGroup, $bitmask, K;
        if (fields.size < 11) return null

        val name = fields[2].trim()
        val description = stripBraces(fields[3].trim())
        val addressStr = fields[4].trim()
        val address = parseHexAddress(addressStr) ?: return null
        val typeId = fields[5].trim().toIntOrNull() ?: return null
        val regId = fields[6].trim().toIntOrNull() ?: return null
        val regName = fields[7].trim()
        val triggerGroup = fields[8].trim().toIntOrNull() ?: return null
        val bitmaskStr = fields[9].trim()
        val bitmask = parseHexAddress(bitmaskStr) ?: return null

        return DamosUmpRecord(
            name = name,
            description = description,
            address = address,
            typeId = typeId,
            regId = regId,
            regName = regName,
            bitmask = bitmask,
            triggerGroup = triggerGroup
        )
    }

    private fun parseSrcRecords(lines: List<String>): Map<String, DamosSrcRecord> {
        val records = mutableMapOf<String, DamosSrcRecord>()
        for (line in lines) {
            val trimmed = line.trim().trimEnd('\r')
            val match = SRC_LINE_PATTERN.find(trimmed) ?: continue
            val record = DamosSrcRecord(
                num = match.groupValues[1].toInt(),
                name = match.groupValues[2],
                description = match.groupValues[3],
                address = match.groupValues[4].toLong(16),
                signed = match.groupValues[5].toInt(),
                typeId = match.groupValues[6].toInt(),
                byteWidth = match.groupValues[7].toInt(),
                channelGroup = match.groupValues[8].toInt()
            )
            records[record.name] = record
        }
        return records
    }

    /**
     * Convert DAMOS UMP and SRC records to ME7Logger-compatible [EcuEntry] list.
     *
     * Both record types represent loggable ECU signals. SRC records carry the
     * well-known variables (nmot_w, rl_w, etc.) while UMP records carry the
     * bulk of internal state variables. Uses REG records for physical conversion.
     */
    fun buildEcuEntries(damos: DamosFile): List<EcuEntry> {
        val entries = mutableMapOf<String, EcuEntry>()

        // UMP records
        for (ump in damos.umpRecords.values) {
            val reg = damos.regRecords[ump.regId]
            val factor: Double
            val offset: Double
            val unit: String

            if (reg != null && reg.divisor != 0.0) {
                factor = reg.factor / reg.divisor
                offset = reg.offset
                unit = reg.unit
            } else {
                factor = 1.0
                offset = 0.0
                unit = ""
            }

            entries[ump.name] = EcuEntry(
                name = ump.name,
                alias = "",
                address = ump.address,
                size = ump.sizeBytes,
                bitmask = ump.bitmask.toInt(),
                unit = unit,
                signed = if (ump.isSigned) 1 else 0,
                inverse = 0,
                factor = factor,
                offset = offset,
                comment = ump.description
            )
        }

        // SRC records (may overlap with UMP — SRC takes priority for well-known signals)
        for (src in damos.srcRecords.values) {
            val reg = damos.regRecords[src.typeId]
            val factor: Double
            val offset: Double
            val unit: String

            if (reg != null && reg.divisor != 0.0) {
                factor = reg.factor / reg.divisor
                offset = reg.offset
                unit = reg.unit
            } else {
                factor = 1.0
                offset = 0.0
                unit = ""
            }

            val signed = when {
                src.signed == 1 -> 1   // signed
                src.signed == 2 -> 0   // unsigned
                src.signed < 0 -> 1    // negative = signed
                else -> 0
            }

            entries[src.name] = EcuEntry(
                name = src.name,
                alias = "",
                address = src.address,
                size = src.byteWidth,
                bitmask = if (src.byteWidth == 1) 0xFF else if (src.byteWidth == 2) 0xFFFF else -1,
                unit = unit,
                signed = signed,
                inverse = 0,
                factor = factor,
                offset = offset,
                comment = src.description
            )
        }

        return entries.values.sortedBy { it.name }
    }

    // --- Utility functions ---

    private fun splitFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var braceDepth = 0

        for (ch in line) {
            when {
                ch == '{' -> { braceDepth++; current.append(ch) }
                ch == '}' -> { braceDepth--; current.append(ch) }
                ch == ',' && braceDepth == 0 -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        fields.add(current.toString())
        return fields
    }

    private fun parseHexAddress(s: String): Long? {
        val trimmed = s.trim().removeSuffix(";").trim()
        return when {
            trimmed.startsWith("\$") -> trimmed.substring(1).toLongOrNull(16)
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.substring(2).toLongOrNull(16)
            else -> trimmed.toLongOrNull()
        }
    }

    private fun stripBraces(s: String): String =
        s.removeSurrounding("{", "}").trim()
}
