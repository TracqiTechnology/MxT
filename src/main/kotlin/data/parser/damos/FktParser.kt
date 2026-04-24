package data.parser.damos

import java.io.File
import java.nio.charset.Charset

/**
 * Parser for DAMOS FKT (Funktionsrahmen) conversion formula tables.
 *
 * FKT files define the physical↔internal conversion formulas for every
 * measurement variable in a DAMOS-based ECU. Each line specifies:
 *   name  signed  factor  offset  unit  precision
 *
 * The signed field uses a compact encoding:
 *   0 = verbal/enumeration (display as-is)
 *   1 = unsigned linear (PHYS = factor * raw + offset)
 *   5 = counter/timer (special rollover semantics, same linear math)
 *   7 = counter with initial value (factor is max, offset is initial)
 *
 * These conversion tables complement DAMOS .dam files (which provide
 * signal addresses) and A2L files (which embed conversions inline).
 */
object FktParser {

    private val LATIN1 = Charset.forName("ISO-8859-1")

    /**
     * A single conversion formula entry from an FKT file.
     *
     * @property name      Conversion formula name (e.g. "nmot_q1", "kw_q0p75")
     * @property typeCode  Signed/type code: 0=verbal, 1=unsigned, 5=counter, 7=counter+init
     * @property factor    Multiplicative factor for raw→physical conversion
     * @property offset    Additive offset for raw→physical conversion
     * @property unit      Physical unit string (e.g. "1/min", "GradKW", "kg/h")
     * @property precision Display precision (number of decimal places)
     */
    data class FktEntry(
        val name: String,
        val typeCode: Int,
        val factor: Double,
        val offset: Double,
        val unit: String,
        val precision: Int
    )

    /**
     * Parse an FKT file from disk.
     */
    fun parse(file: File): List<FktEntry> {
        return parse(file.readText(LATIN1))
    }

    /**
     * Parse FKT content from a string.
     */
    fun parse(content: String): List<FktEntry> {
        val entries = mutableListOf<FktEntry>()

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(";")) continue

            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 6) continue

            val name = parts[0]
            val typeCode = parts[1].toIntOrNull() ?: continue
            val factor = parts[2].toDoubleOrNull() ?: continue
            val offset = parts[3].toDoubleOrNull() ?: continue
            val unit = parts[4]
            val precision = parts[5].toIntOrNull() ?: continue

            entries.add(FktEntry(name, typeCode, factor, offset, unit, precision))
        }

        return entries
    }
}
