package data.parser.kp

import data.preferences.kp.KpFilePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Parses WinOLS KP files and exposes both [KpMapHint] objects (name + address)
 * and full [KpMapDefinition] objects (dimensions, scaling, units, axis addresses).
 *
 * ## Format overview
 * A KP file is a proprietary EVC/WinOLS binary container:
 *   - Bytes 0..N : WinOLS binary header (magic: `WinOLS File\0`)
 *   - Embedded ZIP archive containing a single entry named `intern`
 *   - `intern` : flat binary record database; each record is variable-length
 *     with the total length stored as uint32-LE in the first 4 bytes
 *
 * ## What we extract
 * From each record we extract:
 *   1. Description string  — null-terminated at offset 16
 *   2. Name string         — pattern `[A-Z][A-Z0-9_]+ (AR HEXADDR)`
 *   3. AR address          — hex address from the name annotation
 *   4. Dimensions          — cols/rows at ne+61 (u32 pair)
 *   5. Z-axis units        — null-terminated string at ne+89
 *   6. Z-axis scale        — float64_le at ue+0
 *   7. Z-axis address      — u32_le at ue+16
 *   8. Bit width           — derived from (z_end - z_addr) * 8 / (cols × rows)
 *   9. X/Y axis units, scale, address — from subsequent string/float/u32 fields
 *
 * See `technical/me7/me7-kp-format.md` for the full reverse-engineering notes.
 */
object KpHintParser {

    // Regex: captures "MAPNAME (AR 1E3B0)" or "MAPNAME (AR 1E3B0, RS4 ...)"
    private val NAME_WITH_ADDR_RE = Regex("""([A-Z][A-Z0-9_]{1,24}) \(AR ([0-9A-F]{4,6})""")
    // Regex: plain name without address — at least 2 uppercase chars, ends at null
    private val PLAIN_NAME_RE = Regex("""^([A-Z][A-Z0-9_]{1,24})$""")

    // ZIP magic embedded somewhere inside the KP file
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _hints = MutableStateFlow<List<KpMapHint>>(emptyList())
    val hints: StateFlow<List<KpMapHint>> = _hints.asStateFlow()

    private val _definitions = MutableStateFlow<List<KpMapDefinition>>(emptyList())
    val definitions: StateFlow<List<KpMapDefinition>> = _definitions.asStateFlow()

    /** Initialise: re-parse whenever the stored KP file preference changes. */
    fun init() {
        scope.launch {
            KpFilePreferences.file.collect { file ->
                if (file.exists() && file.extension.equals("kp", ignoreCase = true)) {
                    val result = runCatching { parseFileFull(file) }.getOrElse { Pair(emptyList(), emptyList()) }
                    _hints.value = result.first
                    _definitions.value = result.second
                } else {
                    _hints.value = emptyList()
                    _definitions.value = emptyList()
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Parse [file] synchronously and return the extracted hints. */
    fun parseFile(file: File): List<KpMapHint> {
        val raw = file.readBytes()
        val internBytes = extractInternBlob(raw) ?: return emptyList()
        return parseInternBlob(internBytes).first
    }

    /** Parse [file] synchronously and return full definitions. */
    fun parseFileDefinitions(file: File): List<KpMapDefinition> {
        val raw = file.readBytes()
        val internBytes = extractInternBlob(raw) ?: return emptyList()
        return parseInternBlob(internBytes).second
    }

    /** Parse [file] and return both hints and definitions. */
    internal fun parseFileFull(file: File): Pair<List<KpMapHint>, List<KpMapDefinition>> {
        val raw = file.readBytes()
        val internBytes = extractInternBlob(raw) ?: return Pair(emptyList(), emptyList())
        return parseInternBlob(internBytes)
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** Locate the embedded ZIP archive inside the raw KP bytes, open it, and
     * return the contents of the `intern` entry.
     */
    internal fun extractInternBlob(raw: ByteArray): ByteArray? {
        val zipOffset = findZipOffset(raw) ?: return null
        return try {
            val zipStream = ZipInputStream(java.io.ByteArrayInputStream(raw, zipOffset, raw.size - zipOffset))
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "intern") {
                    return zipStream.readBytes()
                }
                entry = zipStream.nextEntry
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Scan [raw] for the first occurrence of the PK local-file-header magic. */
    private fun findZipOffset(raw: ByteArray): Int? {
        val magicB = ZIP_MAGIC[1]
        val magicK = ZIP_MAGIC[2]
        val magic03 = ZIP_MAGIC[3]
        for (i in 0 until raw.size - 4) {
            if (raw[i] == 0x50.toByte() && raw[i + 1] == magicB &&
                raw[i + 2] == magicK && raw[i + 3] == magic03
            ) {
                return i
            }
        }
        return null
    }

    /**
     * Walk the flat binary record database and extract both [KpMapHint] and
     * [KpMapDefinition] for each record.
     *
     * Returns (hints, definitions) where definitions is a subset of records
     * for which full binary layout parsing succeeded.
     */
    private fun parseInternBlob(blob: ByteArray): Pair<List<KpMapHint>, List<KpMapDefinition>> {
        val hints = mutableListOf<KpMapHint>()
        val definitions = mutableListOf<KpMapDefinition>()
        val seen = mutableSetOf<String>()
        var pos = 0

        while (pos <= blob.size - 8) {
            val recSize = readU32(blob, pos)
            if (recSize < 50 || recSize > 200_000 || pos + recSize > blob.size) {
                pos++
                continue
            }

            val recEnd = pos + recSize
            val recBytes = blob.sliceArray(pos until recEnd)

            // --- description string at offset 16 ---
            val desc = readCString(blob, pos + 16)

            // --- scan record region for name + optional address ---
            val regionStr = recBytes.drop(16)
                .joinToString("") { b ->
                    val i = b.toInt() and 0xFF
                    if (i in 32..126) i.toChar().toString() else if (i == 0) "\u0000" else "\u0001"
                }

            val nameMatches = NAME_WITH_ADDR_RE.findAll(regionStr).toList()
            if (nameMatches.isNotEmpty()) {
                for (nameMatch in nameMatches) {
                    val mapName = nameMatch.groupValues[1]
                    val addr = nameMatch.groupValues[2].toInt(16)
                    if (mapName !in seen) {
                        seen.add(mapName)
                        hints.add(KpMapHint(mapName, desc, addr))

                        // Attempt full record parsing
                        val nameEnd = findNameEnd(recBytes, nameMatch, regionStr)
                        if (nameEnd != null) {
                            val def = parseFullRecord(recBytes, mapName, desc, addr, nameEnd)
                            if (def != null) definitions.add(def)
                        }
                    }
                }
                pos = recEnd
                continue
            }

            // Look for plain uppercase name in the second string slot
            val nameStart = 16 + desc.length + 1 + 28
            if (nameStart + 2 < recEnd - pos) {
                val plainName = readCStringRaw(blob, pos + nameStart)
                if (plainName.length in 2..24 && PLAIN_NAME_RE.matches(plainName)) {
                    if (plainName !in seen) {
                        seen.add(plainName)
                        hints.add(KpMapHint(plainName, desc, -1))
                    }
                }
            }

            pos = recEnd
        }

        return Pair(hints.sortedBy { it.name }, definitions.sortedBy { it.name })
    }

    /**
     * Find the byte offset (within the record) where the name string ends (the
     * null terminator after the name + address annotation).
     */
    private fun findNameEnd(recBytes: ByteArray, nameMatch: MatchResult, regionStr: String): Int? {
        // The regionStr is offset by 16 from the record start.
        // The name match end in regionStr corresponds to some position in the raw bytes.
        // We need to find the null terminator after the ')' that closes the address annotation.
        val matchEndInRegion = nameMatch.range.last + 1
        // regionStr index = recBytes index - 16, so recBytes index = regionStr index + 16
        // But we need to find the actual null terminator after the full "(AR HEXADDR...)" string
        val searchStart = matchEndInRegion + 16

        // Scan forward past the rest of the address annotation to find the null terminator
        var i = searchStart
        while (i < recBytes.size) {
            val b = recBytes[i].toInt() and 0xFF
            if (b == 0) return i + 1 // return position after the null
            i++
        }
        return null
    }

    /**
     * Attempt full binary record parsing to extract dimensions, scaling, units,
     * and axis addresses.
     *
     * @param recBytes The full record bytes
     * @param name Map name
     * @param desc Description string
     * @param arAddr AR address from the name annotation
     * @param nameEnd Byte offset within recBytes where the name string's null terminator was found + 1
     * @return A [KpMapDefinition] if parsing succeeds, null otherwise
     */
    internal fun parseFullRecord(
        recBytes: ByteArray,
        name: String,
        desc: String,
        arAddr: Int,
        nameEnd: Int
    ): KpMapDefinition? {
        val ne = nameEnd
        if (ne + 69 > recBytes.size) return null

        // ── Dimensions at ne+61 ────────────────────────────────────────
        val cols = readU32(recBytes, ne + 61)
        val rows = readU32(recBytes, ne + 65)
        if (cols <= 0 || cols > 1000 || rows <= 0 || rows > 1000) return null

        // ── Z-axis units string at ne+89 ───────────────────────────────
        if (ne + 89 >= recBytes.size) return null
        val zUnits = readCString(recBytes, ne + 89)
        val zUnitsEnd = ne + 89 + zUnits.length + 1 // +1 for null terminator
        val ue = zUnitsEnd

        if (ue + 24 > recBytes.size) return null

        // ── Z-axis scale at ue+0 (float64_le) ─────────────────────────
        val zScale = readF64(recBytes, ue)
        if (zScale.isNaN() || zScale.isInfinite() || zScale == 0.0) return null

        // ── Z-axis address at ue+16 ───────────────────────────────────
        val zAddr = readU32(recBytes, ue + 16)
        if (zAddr <= 0) return null

        // ── Z-axis end address at ue+20 → derive bit width ────────────
        val zEndAddr = readU32(recBytes, ue + 20)
        val totalBits = if (zEndAddr > zAddr) (zEndAddr - zAddr) * 8 else 0
        val cellCount = cols * rows
        val sizeBits = if (cellCount > 0 && totalBits > 0) {
            val raw = totalBits / cellCount
            // Snap to nearest valid bit width
            when {
                raw <= 8 -> 8
                raw <= 16 -> 16
                else -> 32
            }
        } else 8 // default to 8-bit

        // ── X-axis (optional) ──────────────────────────────────────────
        var xUnits = ""
        var xScale = 1.0
        var xAddr = -1
        var yUnits = ""
        var yScale = 1.0
        var yAddr = -1

        if (cols > 1) {
            // X-axis units string at approximately ue+64
            val xUnitsOffset = findNextUnitString(recBytes, ue + 28, ue + 80)
            if (xUnitsOffset != null) {
                xUnits = readCString(recBytes, xUnitsOffset)
                val xue = xUnitsOffset + xUnits.length + 1

                if (xue + 24 <= recBytes.size) {
                    xScale = readF64(recBytes, xue)
                    if (xScale.isNaN() || xScale.isInfinite() || xScale == 0.0) xScale = 1.0
                    xAddr = readU32(recBytes, xue + 20)
                    if (xAddr <= 0) xAddr = -1

                    // ── Y-axis (optional) ──────────────────────────────
                    if (rows > 1) {
                        val yUnitsOffset = findNextUnitString(recBytes, xue + 28, xue + 100)
                        if (yUnitsOffset != null) {
                            yUnits = readCString(recBytes, yUnitsOffset)
                            val yue = yUnitsOffset + yUnits.length + 1

                            if (yue + 24 <= recBytes.size) {
                                yScale = readF64(recBytes, yue)
                                if (yScale.isNaN() || yScale.isInfinite() || yScale == 0.0) yScale = 1.0
                                yAddr = readU32(recBytes, yue + 20)
                                if (yAddr <= 0) yAddr = -1
                            }
                        }
                    }
                }
            }
        } else if (rows > 1) {
            // 1D column vector — look for Y-axis directly after z-axis fields
            val yUnitsOffset = findNextUnitString(recBytes, ue + 28, ue + 80)
            if (yUnitsOffset != null) {
                yUnits = readCString(recBytes, yUnitsOffset)
                val yue = yUnitsOffset + yUnits.length + 1
                if (yue + 24 <= recBytes.size) {
                    yScale = readF64(recBytes, yue)
                    if (yScale.isNaN() || yScale.isInfinite() || yScale == 0.0) yScale = 1.0
                    yAddr = readU32(recBytes, yue + 20)
                    if (yAddr <= 0) yAddr = -1
                }
            }
        }

        return KpMapDefinition(
            name = name,
            description = desc,
            arAddress = arAddr,
            columns = cols,
            rows = rows,
            sizeBits = sizeBits,
            scale = zScale,
            units = zUnits,
            zAddress = zAddr,
            xAddress = xAddr,
            yAddress = yAddr,
            xScale = xScale,
            yScale = yScale,
            xUnits = xUnits,
            yUnits = yUnits
        )
    }

    /**
     * Scan a region of the record for what looks like a unit string.
     * Unit strings are short (1-20 chars), null-terminated, containing printable ASCII
     * that includes at least one letter, %, or /.
     */
    private fun findNextUnitString(recBytes: ByteArray, from: Int, to: Int): Int? {
        val limit = minOf(to, recBytes.size - 1)
        var i = from
        while (i < limit) {
            val str = readCString(recBytes, i)
            if (str.length in 1..20 && str.any { it.isLetter() || it == '%' || it == '/' || it == '°' }) {
                return i
            }
            i++
        }
        return null
    }

    // ── Binary helpers ────────────────────────────────────────────────────

    internal fun readU32(blob: ByteArray, offset: Int): Int {
        if (offset + 3 >= blob.size) return 0
        return ((blob[offset + 3].toInt() and 0xFF) shl 24) or
                ((blob[offset + 2].toInt() and 0xFF) shl 16) or
                ((blob[offset + 1].toInt() and 0xFF) shl 8) or
                (blob[offset].toInt() and 0xFF)
    }

    internal fun readF64(blob: ByteArray, offset: Int): Double {
        if (offset + 7 >= blob.size) return Double.NaN
        val buf = ByteBuffer.wrap(blob, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
        return buf.double
    }

    /** Read a null-terminated ASCII/Latin-1 string; returns "" on error. */
    internal fun readCString(blob: ByteArray, offset: Int): String {
        if (offset >= blob.size) return ""
        val end = blob.indexOf(0, offset).takeIf { it >= offset } ?: return ""
        return try {
            blob.decodeToString(offset, end, throwOnInvalidSequence = false)
                .filter { it.code in 32..126 }
        } catch (_: Exception) { "" }
    }

    /** Same as [readCString] but validates it looks like a map name. */
    private fun readCStringRaw(blob: ByteArray, offset: Int): String {
        if (offset >= blob.size) return ""
        val sb = StringBuilder()
        var i = offset
        while (i < blob.size && i < offset + 32) {
            val b = blob[i].toInt() and 0xFF
            if (b == 0) break
            if (b in 32..126) sb.append(b.toChar()) else break
            i++
        }
        return sb.toString()
    }

    private fun ByteArray.indexOf(value: Byte, startIndex: Int): Int {
        for (i in startIndex until size) if (this[i] == value) return i
        return -1
    }
}
