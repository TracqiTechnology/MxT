package data.parser.ols

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses WinOLS OLS project files to extract map definitions.
 *
 * ## Format overview
 * An OLS file is a WinOLS binary project container:
 *   - Starts with "WinOLS File\0" magic
 *   - Header region with ECU metadata (manufacturer, part number, EPROM ID)
 *   - Map definition records (variable-size, ~640 bytes each)
 *   - Embedded ECU binary at a discoverable offset
 *
 * ## Record structure
 * Each map definition record contains (in approximate order):
 *   - Axis descriptors: description text, unit, and scaling info for each axis
 *   - Map name: preceded by marker `0A 00 00 00  01 00 00 00  [len u32LE]  [name\0]`
 *   - Post-name data: display parameters and internal WinOLS configuration
 *
 * ## What we extract
 * - Map name (reliable)
 * - Map description text (reliable)
 * - Axis units and labels (reliable)
 * - ECU header metadata: manufacturer, part number, EPROM variant
 *
 * ## What we do NOT extract
 * - Binary addresses within the ECU EPROM (embedded in undocumented binary fields)
 * - Exact axis dimensions and breakpoint values
 * - Scaling equations
 *
 * For full binary parsing with addresses, use the companion [XdfGenerator] with
 * a BIN-aware address finder, or load a TunerPro XDF file.
 */
object OlsParser {

    private val OLS_MAGIC = "WinOLS File\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Record name marker: appears immediately before `[uint32 nameLen] [name\0]`
     * in every map record. The `0x0A` value appears to be a constant record-type
     * indicator, and `0x01` a constant sub-type.
     */
    private val NAME_MARKER = byteArrayOf(0x0A, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00)

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Parse an OLS file and return all map definitions found.
     *
     * @param file the OLS file to parse
     * @return list of map definitions, sorted by name
     * @throws IllegalArgumentException if the file is not a valid OLS file
     */
    fun parseFile(file: File): OlsParseResult {
        val raw = file.readBytes()
        require(raw.size > OLS_MAGIC.size && matchesAt(raw, 4, OLS_MAGIC)) {
            "Not a WinOLS OLS file: missing 'WinOLS File' magic"
        }

        val header = parseHeader(raw)
        val binOffset = findEmbeddedBin(raw)
        val maps = parseMapRecords(raw, binOffset)

        return OlsParseResult(header, maps, binOffset)
    }

    // ── Header parsing ──────────────────────────────────────────────────

    private fun parseHeader(raw: ByteArray): OlsHeader {
        // OLS header structure:
        //   [uint32 len] "WinOLS File\0"
        //   [uint32 innerBlockLen] [4-byte inner header] [subfield0_len] [subfield0] [\0] ...
        //
        // The inner block starts at offset 0x14 (after magic length-prefix + magic + null).
        // First 4 bytes of the inner block are a version/type marker, then the ECU metadata
        // fields follow as length-prefixed null-terminated strings.
        val fields = mutableListOf<String>()

        // Skip: [4 bytes magic len] [magic bytes including null] = 4 + magicLen
        val innerBlockLenPos = 4 + OLS_MAGIC.size
        if (innerBlockLenPos + 4 >= raw.size) return OlsHeader()

        val innerBlockLen = readU32LE(raw, innerBlockLenPos)
        if (innerBlockLen < 20 || innerBlockLen > 10000) return OlsHeader()

        // Inner subfields start at innerBlockLenPos + 4 (skip inner block length) + 4 (skip inner header)
        var pos = innerBlockLenPos + 4 + 4
        val innerEnd = innerBlockLenPos + 4 + innerBlockLen

        repeat(15) {
            if (pos + 4 >= innerEnd) return@repeat
            val fieldLen = readU32LE(raw, pos)
            if (fieldLen in 1..200 && pos + 4 + fieldLen < innerEnd) {
                val str = readLatin1String(raw, pos + 4, fieldLen)
                fields.add(str)
                pos += 4 + fieldLen + 1 // +1 for null terminator
            } else {
                return@repeat
            }
        }

        // Expected field order (from observed OLS files):
        // 0=manufacturer (VW), 1=model (Golf), 2=engine (1.8T), 3=year (2000),
        // 4=fuel (Turbo-Petrol), 5=displacement (1.8), 6=power (150PS/110.3KW),
        // 7=transmission, 8=EPROM type, 9=ECU maker (Bosch), 10=ECU version (M3.8.3),
        // 11=part number (06A906018CJ), 12=Bosch number (0261206516)
        return OlsHeader(
            manufacturer = fields.getOrElse(0) { "" },
            model = fields.getOrElse(1) { "" },
            engine = fields.getOrElse(2) { "" },
            year = fields.getOrElse(3) { "" },
            fuelType = fields.getOrElse(4) { "" },
            displacement = fields.getOrElse(5) { "" },
            power = fields.getOrElse(6) { "" },
            transmission = fields.getOrElse(7) { "" },
            epromType = fields.getOrElse(8) { "" },
            ecuMaker = fields.getOrElse(9) { "" },
            ecuVersion = fields.getOrElse(10) { "" },
            partNumber = fields.getOrElse(11) { "" },
            boschNumber = fields.getOrElse(12) { "" }
        )
    }

    // ── Embedded BIN detection ──────────────────────────────────────────

    /**
     * Find the offset where the embedded ECU binary starts.
     * Returns the size of the file if no embedded BIN is found.
     *
     * Heuristic: the BIN typically starts in the second half of the OLS file
     * and is preceded by map definition records. We look for a transition
     * from map metadata to raw binary code patterns.
     */
    private fun findEmbeddedBin(raw: ByteArray): Int {
        // The BIN is typically at a fixed offset that can be detected by
        // looking for the last NAME_MARKER occurrence + some gap
        // A simpler heuristic: BIN is 256KB (262144 bytes) at end of file
        val expectedBinSizes = listOf(262144, 524288, 1048576) // 256K, 512K, 1MB
        for (binSize in expectedBinSizes) {
            if (raw.size > binSize + 10000) {
                // The BIN ends at or near the file end
                val candidateOffset = raw.size - binSize
                // Verify: there should be no NAME_MARKERs in the BIN region
                if (!containsNameMarker(raw, candidateOffset, raw.size)) {
                    return candidateOffset
                }
            }
        }
        return raw.size
    }

    private fun containsNameMarker(raw: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to - NAME_MARKER.size) {
            if (matchesAt(raw, i, NAME_MARKER)) {
                // Verify it's really a name marker (followed by valid name length and ASCII)
                if (i + 12 < to) {
                    val nameLen = readU32LE(raw, i + 8)
                    if (nameLen in 1..50) {
                        val firstByte = raw[i + 12].toInt() and 0xFF
                        if (firstByte in 'A'.code..'Z'.code) return true
                    }
                }
            }
        }
        return false
    }

    // ── Map record parsing ──────────────────────────────────────────────

    private fun parseMapRecords(raw: ByteArray, binOffset: Int): List<OlsMapDefinition> {
        val results = mutableListOf<OlsMapDefinition>()
        var pos = 0

        while (pos < binOffset - NAME_MARKER.size) {
            pos = findNextNameMarker(raw, pos, binOffset) ?: break

            val nameLen = readU32LE(raw, pos + 8)
            if (nameLen !in 1..50 || pos + 12 + nameLen >= binOffset) {
                pos++
                continue
            }

            val name = readAsciiString(raw, pos + 12, nameLen)
            if (name.isEmpty() || !name[0].isUpperCase()) {
                pos++
                continue
            }

            // Extract strings from the lookback region (axis descriptors + map description)
            val lookbackStart = maxOf(0, pos - 700)
            val strings = extractLengthPrefixedStrings(raw, lookbackStart, pos)

            // The last string before the name marker is typically the map description.
            // Strings before that are axis descriptors (unit, label pairs).
            val mapDef = buildMapDefinition(name, strings)
            results.add(mapDef)

            pos += 12 + nameLen + 1 // advance past name + null
        }

        return results.sortedBy { it.name }
    }

    private fun findNextNameMarker(raw: ByteArray, from: Int, limit: Int): Int? {
        for (i in from until limit - NAME_MARKER.size) {
            if (matchesAt(raw, i, NAME_MARKER)) return i
        }
        return null
    }

    /**
     * Extract all length-prefixed strings from [start] to [end].
     * Each string is encoded as: [uint32LE length] [bytes] [null terminator]
     */
    private fun extractLengthPrefixedStrings(raw: ByteArray, start: Int, end: Int): List<ExtractedString> {
        val strings = mutableListOf<ExtractedString>()
        var pos = start

        while (pos < end - 5) {
            val strLen = readU32LE(raw, pos)
            if (strLen in 2..200 && pos + 4 + strLen < end) {
                // Check null terminator
                if (pos + 4 + strLen < raw.size && raw[pos + 4 + strLen] == 0.toByte()) {
                    val text = readLatin1String(raw, pos + 4, strLen)
                    if (text.isNotBlank() && isPrintableLatin1(text)) {
                        strings.add(ExtractedString(pos, text))
                        pos += 4 + strLen + 1
                        continue
                    }
                }
            }
            pos++
        }

        return strings
    }

    /**
     * Build an [OlsMapDefinition] from the map name and the extracted strings
     * found in the lookback region before the name marker.
     *
     * OLS records typically contain axis descriptors in this order (bottom-up):
     *   - description string (the map's own description)
     *   - [fields]
     *   - x-axis unit string (e.g. "Upm")
     *   - x-axis label string (e.g. "Drehzahlquantisierung 40.00[Upm]")
     *   - y-axis unit string (e.g. "ms/Umdr.")
     *   - y-axis label string (e.g. "Quantisierung der Einspritzzeit")
     *   - z-axis unit string (e.g. "grad KW")
     *   - z-axis label string (e.g. "Zündwinkel mit Offset, 8 Bit")
     */
    private fun buildMapDefinition(name: String, strings: List<ExtractedString>): OlsMapDefinition {
        // Work backward from the end: the last meaningful string is the map description
        // Before that are alternating label/unit pairs for the axes
        val meaningful = strings.filter { it.text.length >= 2 }

        if (meaningful.isEmpty()) {
            return OlsMapDefinition(name = name, description = "")
        }

        // The description is the last string (closest to the name marker)
        val description = meaningful.last().text.trim()

        // Common unit strings
        val knownUnits = setOf(
            "Upm", "ms", "ms/Umdr.", "grad KW", "grad DK", "Nm",
            "kg/h", "sec", "grad C", "%", "1/min", "mbar",
            "%TV/sec", "dez", "V"
        )

        // Try to identify unit/label pairs by scanning backward
        var xUnit = ""
        var xLabel = ""
        var yUnit = ""
        var yLabel = ""
        var zUnit = ""
        var zLabel = ""

        // Simple heuristic: collect the last 6 strings before the description
        // Pairs go: [label, unit, label, unit, label, unit] = z-desc, z-unit, y-desc, y-unit, x-desc, x-unit
        // But the order varies. Instead, identify units by matching known unit strings.

        val beforeDesc = meaningful.dropLast(1).takeLast(6)
        val unitIndices = beforeDesc.indices.filter { isLikelyUnit(beforeDesc[it].text, knownUnits) }
        val labelIndices = beforeDesc.indices.filter { it !in unitIndices }

        // Assign units to axes. Typically x-axis is RPM ("Upm"), y-axis is load/temp, z-axis is the value
        // The last unit before description is usually the z-axis unit (closest to description)
        if (unitIndices.isNotEmpty()) {
            // Assign from the end (closest to description = x-axis in OLS layout)
            val sortedUnits = unitIndices.sortedDescending()
            val sortedLabels = labelIndices.sortedDescending()

            if (sortedUnits.isNotEmpty()) {
                xUnit = beforeDesc[sortedUnits[0]].text
                if (sortedLabels.isNotEmpty()) {
                    // Find the label that's closest to (and before) this unit
                    val nearestLabel = sortedLabels.firstOrNull { it < sortedUnits[0] || it == sortedUnits[0] - 1 }
                        ?: sortedLabels.firstOrNull()
                    if (nearestLabel != null) xLabel = beforeDesc[nearestLabel].text.trim()
                }
            }
            if (sortedUnits.size >= 2) {
                yUnit = beforeDesc[sortedUnits[1]].text
                if (sortedLabels.size >= 2) {
                    val nearestLabel = sortedLabels.firstOrNull { it < sortedUnits[1] }
                        ?: sortedLabels.getOrNull(1)
                    if (nearestLabel != null) yLabel = beforeDesc[nearestLabel].text.trim()
                }
            }
            if (sortedUnits.size >= 3) {
                zUnit = beforeDesc[sortedUnits[2]].text
                if (sortedLabels.size >= 3) {
                    zLabel = sortedLabels.getOrNull(2)?.let { beforeDesc[it].text.trim() } ?: ""
                }
            }
        }

        return OlsMapDefinition(
            name = name,
            description = description,
            zUnit = zUnit,
            xAxisLabel = xLabel,
            xUnit = xUnit,
            yAxisLabel = yLabel,
            yUnit = yUnit,
            zAxisLabel = zLabel
        )
    }

    private fun isLikelyUnit(text: String, knownUnits: Set<String>): Boolean {
        val trimmed = text.trim()
        if (trimmed in knownUnits) return true
        // Short strings (1-8 chars) that don't start with a space are likely units
        return trimmed.length in 1..8 && !trimmed.startsWith(" ") && !trimmed.any { it.isWhitespace() && it != ' ' }
    }

    // ── Binary helpers ──────────────────────────────────────────────────

    private fun readU32LE(raw: ByteArray, offset: Int): Int {
        if (offset + 3 >= raw.size) return 0
        return ((raw[offset + 3].toInt() and 0xFF) shl 24) or
                ((raw[offset + 2].toInt() and 0xFF) shl 16) or
                ((raw[offset + 1].toInt() and 0xFF) shl 8) or
                (raw[offset].toInt() and 0xFF)
    }

    private fun readAsciiString(raw: ByteArray, offset: Int, maxLen: Int): String {
        val sb = StringBuilder()
        for (i in 0 until maxLen) {
            val b = raw[offset + i].toInt() and 0xFF
            if (b == 0) break
            if (b in 0x20..0x7E) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readLatin1String(raw: ByteArray, offset: Int, maxLen: Int): String {
        val end = minOf(offset + maxLen, raw.size)
        val bytes = raw.copyOfRange(offset, end)
        val nullIdx = bytes.indexOf(0)
        val trimmed = if (nullIdx >= 0) bytes.copyOf(nullIdx) else bytes
        return String(trimmed, Charsets.ISO_8859_1)
    }

    private fun matchesAt(raw: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > raw.size) return false
        for (i in pattern.indices) {
            if (raw[offset + i] != pattern[i]) return false
        }
        return true
    }

    private fun isPrintableLatin1(text: String): Boolean {
        // Allow printable ASCII + common Latin-1 extended chars (accented, German umlauts, etc.)
        return text.all { c ->
            c.code in 0x20..0x7E || c.code in 0xA0..0xFF
        }
    }

    // ── Internal data classes ───────────────────────────────────────────

    private data class ExtractedString(val offset: Int, val text: String)
}

/**
 * Result of parsing an OLS file.
 */
data class OlsParseResult(
    val header: OlsHeader,
    val maps: List<OlsMapDefinition>,
    /** Byte offset where the embedded BIN starts in the OLS file, or file size if not found */
    val embeddedBinOffset: Int
)

/**
 * ECU metadata extracted from the OLS file header.
 */
data class OlsHeader(
    val manufacturer: String = "",
    val model: String = "",
    val engine: String = "",
    val year: String = "",
    val fuelType: String = "",
    val displacement: String = "",
    val power: String = "",
    val transmission: String = "",
    val epromType: String = "",
    val ecuMaker: String = "",
    val ecuVersion: String = "",
    val partNumber: String = "",
    val boschNumber: String = ""
) {
    /** Short ECU identifier, e.g. "Bosch M3.8.3 06A906018CJ" */
    val shortId: String
        get() = listOfNotNull(
            ecuMaker.takeIf { it.isNotBlank() },
            ecuVersion.takeIf { it.isNotBlank() },
            partNumber.takeIf { it.isNotBlank() }
        ).joinToString(" ")
}
