package data.parser.ecu

import data.parser.a2l.EcuEntry

/**
 * Parsed representation of an ME7Logger .ecu file.
 *
 * Sections: [Version], [Communication], [Identification], [Measurements]
 */
data class EcuFile(
    val version: String,
    val connectMethod: String,     // e.g. "SLOW-0x01"
    val communicateMode: String,   // e.g. "HM0"
    val logSpeed: Int,             // baud rate after init (56000, 125000, etc.)
    val hwNumber: String,
    val swNumber: String,
    val partNumber: String,
    val swVersion: String,
    val engineId: String,
    val entries: Map<String, EcuEntry>
)

/**
 * Parses ME7Logger-format .ecu files into [EcuFile].
 *
 * Format:
 * ```
 * [Section]
 * Key = Value           ; comment
 *
 * [Measurements]
 * Name, {Alias}, Address, Size, Bitmask, {Unit}, S, I, Factor, Offset, Comment
 * ```
 */
object EcuFileParser {

    fun parse(text: String): EcuFile {
        val sections = parseSections(text)

        val version = sections["Version"]?.get("Version") ?: ""
        val comm = sections["Communication"] ?: emptyMap()
        val ident = sections["Identification"] ?: emptyMap()

        val entries = parseMeasurements(text)

        return EcuFile(
            version = version,
            connectMethod = comm["Connect"] ?: "",
            communicateMode = comm["Communicate"] ?: "",
            logSpeed = comm["LogSpeed"]?.toIntOrNull() ?: 56000,
            hwNumber = stripBraces(ident["HWNumber"] ?: ""),
            swNumber = stripBraces(ident["SWNumber"] ?: ""),
            partNumber = stripBraces(ident["PartNumber"] ?: ""),
            swVersion = stripBraces(ident["SWVersion"] ?: ""),
            engineId = stripBraces(ident["EngineId"] ?: ""),
            entries = entries
        )
    }

    fun parse(file: java.io.File): EcuFile = parse(file.readText())

    /**
     * Parse key=value sections (everything except [Measurements]).
     */
    private fun parseSections(text: String): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        var currentSection: String? = null

        for (line in text.lines()) {
            val trimmed = line.trim()

            // Skip comments and blanks
            if (trimmed.isEmpty() || trimmed.startsWith(";")) continue

            // Section header
            val sectionMatch = SECTION_REGEX.matchEntire(trimmed)
            if (sectionMatch != null) {
                currentSection = sectionMatch.groupValues[1]
                if (currentSection == "Measurements") break // handled separately
                result.getOrPut(currentSection) { mutableMapOf() }
                continue
            }

            // Key = Value (strip inline comments)
            if (currentSection != null && currentSection != "Measurements") {
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val key = trimmed.substring(0, eqIdx).trim()
                    val rawValue = trimmed.substring(eqIdx + 1)
                    val value = rawValue.split(';').first().trim()
                    result[currentSection]?.put(key, value)
                }
            }
        }
        return result
    }

    /**
     * Parse [Measurements] section into a map of name → EcuEntry.
     */
    private fun parseMeasurements(text: String): Map<String, EcuEntry> {
        val entries = mutableMapOf<String, EcuEntry>()
        var inMeasurements = false

        for (line in text.lines()) {
            val trimmed = line.trim()

            if (trimmed == "[Measurements]") {
                inMeasurements = true
                continue
            }

            if (!inMeasurements) continue
            if (trimmed.isEmpty() || trimmed.startsWith(";")) continue

            // New section ends measurements
            if (trimmed.startsWith("[")) break

            val entry = parseMeasurementLine(trimmed) ?: continue
            entries[entry.name] = entry
        }

        return entries
    }

    /**
     * Parse a single measurement line:
     * Name, {Alias}, Address, Size, Bitmask, {Unit}, S, I, Factor, Offset, Comment
     */
    internal fun parseMeasurementLine(line: String): EcuEntry? {
        // Split by commas but respect braces
        val fields = splitMeasurementFields(line)
        if (fields.size < 11) return null

        val name = fields[0].trim()
        val alias = stripBraces(fields[1].trim())
        val address = parseAddress(fields[2].trim())
        val size = fields[3].trim().toIntOrNull() ?: return null
        val bitmask = parseAddress(fields[4].trim()).toInt()
        val unit = stripBraces(fields[5].trim())
        val signed = fields[6].trim().toIntOrNull() ?: 0
        val inverse = fields[7].trim().toIntOrNull() ?: 0
        val factor = fields[8].trim().toDoubleOrNull() ?: return null
        val offset = fields[9].trim().toDoubleOrNull() ?: return null
        val comment = stripBraces(fields.drop(10).joinToString(",").trim())

        return EcuEntry(
            name = name,
            alias = alias,
            address = address,
            size = size,
            bitmask = bitmask,
            unit = unit,
            signed = signed,
            inverse = inverse,
            factor = factor,
            offset = offset,
            comment = comment
        )
    }

    /**
     * Split measurement line by commas, treating `{...}` as atomic tokens.
     */
    private fun splitMeasurementFields(line: String): List<String> {
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

    private fun parseAddress(s: String): Long {
        val trimmed = s.trim()
        return if (trimmed.startsWith("0x", ignoreCase = true)) {
            java.lang.Long.parseLong(trimmed.substring(2), 16)
        } else {
            trimmed.toLongOrNull() ?: 0L
        }
    }

    private fun stripBraces(s: String): String =
        s.removeSurrounding("{", "}").trim()

    private val SECTION_REGEX = Regex("""\[(\w+)]""")
}
