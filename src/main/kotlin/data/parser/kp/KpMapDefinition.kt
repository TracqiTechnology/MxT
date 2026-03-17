package data.parser.kp

/**
 * A full map definition extracted from a WinOLS KP binary record.
 *
 * Unlike [KpMapHint] (which only carries name + address), this contains
 * dimensions, scaling, units, and axis addresses — enough to construct a
 * [data.parser.xdf.TableDefinition] for reading/writing BIN files without
 * an XDF.
 *
 * ## Record layout (anchored off name string end `ne`)
 * ```
 * ne+61:  cols (u32_le), rows (u32_le)
 * ne+89:  z_units string (null-terminated)
 *
 * After z_units end (ue):
 * ue+0:   z_scale (float64_le)
 * ue+16:  z_addr (u32_le)
 * ue+20:  z_end_addr (u32_le)  → bit_width = (z_end - z_addr) * 8 / (cols × rows)
 * ue+~64: x_units string (null-terminated)
 *
 * After x_units end (xue):
 * xue+0:  x_scale (float64_le)
 * xue+20: x_addr (u32_le)
 * xue+75: y_units string (null-terminated)
 *
 * After y_units end (yue):
 * yue+0:  y_scale (float64_le)
 * yue+20: y_addr (u32_le)
 * ```
 *
 * See `technical/me7/me7-kp-format.md` for the full reverse-engineering notes.
 */
data class KpMapDefinition(
    /** Map identifier, e.g. "KFPBRK", "MLHFM" */
    val name: String,
    /** Human-readable description from the record's description field */
    val description: String,
    /** Raw ECU binary address from the "(AR HEXADDR)" annotation, or -1 */
    val arAddress: Int,
    /** Number of columns (x-axis length) */
    val columns: Int,
    /** Number of rows (y-axis length) */
    val rows: Int,
    /** Element bit width: 8, 16, or 32 */
    val sizeBits: Int,
    /** Z-axis scaling factor: physical = raw × scale */
    val scale: Double,
    /** Z-axis physical units, e.g. "%/100hPa" */
    val units: String,
    /** Z-axis binary address (from ue+16), or -1 */
    val zAddress: Int,
    /** X-axis breakpoint binary address, or -1 */
    val xAddress: Int = -1,
    /** Y-axis breakpoint binary address, or -1 */
    val yAddress: Int = -1,
    /** X-axis scaling factor */
    val xScale: Double = 1.0,
    /** Y-axis scaling factor */
    val yScale: Double = 1.0,
    /** X-axis physical units */
    val xUnits: String = "",
    /** Y-axis physical units */
    val yUnits: String = ""
) {
    /** True when we have a usable binary address */
    val hasAddress: Boolean get() = arAddress >= 0 || zAddress >= 0

    /** The best available binary address (prefer zAddress, fall back to arAddress) */
    val effectiveAddress: Int get() = if (zAddress >= 0) zAddress else arAddress

    /** True when an X axis exists */
    val hasXAxis: Boolean get() = columns > 1 && xAddress >= 0

    /** True when a Y axis exists */
    val hasYAxis: Boolean get() = rows > 1 && yAddress >= 0

    /** True when this is a 2D table */
    val is2D: Boolean get() = columns > 1 && rows > 1

    /** Dimension string, e.g. "10×10" */
    val dimensionString: String get() = "${columns}×${rows}"

    /** Convert to the simpler [KpMapHint] for backward compatibility */
    fun toHint(): KpMapHint = KpMapHint(name, description, arAddress)
}
