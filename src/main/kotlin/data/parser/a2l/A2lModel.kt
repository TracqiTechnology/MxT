package data.parser.a2l

/**
 * Data models for ASAP2/A2L file parsing.
 * Used to extract MEASUREMENT entries and COMPU_METHOD conversion formulas
 * for generating ME7Logger-compatible .ecu files, and CHARACTERISTIC blocks
 * for calibration map reading/writing.
 */

/** A2L COMPU_METHOD block — defines physical↔internal conversion. */
data class A2lCompuMethod(
    val name: String,
    val description: String,
    val unit: String,
    val coeffs: DoubleArray,  // [a, b, c, d, e, f]
    val formatStr: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is A2lCompuMethod) return false
        return name == other.name && coeffs.contentEquals(other.coeffs)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + coeffs.contentHashCode()
}

/** A2L MEASUREMENT block — a loggable RAM variable. */
data class A2lMeasurement(
    val name: String,
    val description: String,
    val dataType: String,        // UBYTE, SBYTE, UWORD, SWORD, ULONG, SLONG
    val compuMethodName: String,
    val resolution: Double,
    val accuracy: Double,
    val lowerLimit: Double,
    val upperLimit: Double,
    val ecuAddress: Long,
    val arraySize: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// CALIBRATION MODEL (CHARACTERISTIC / RECORD_LAYOUT / AXIS_PTS)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * An ordered element within a RECORD_LAYOUT block.
 * [position] is ordinal (1 = first byte in memory).
 */
data class A2lLayoutEntry(
    val position: Int,
    val dataType: String   // UBYTE, SBYTE, UWORD, SWORD, ULONG, SLONG, FLOAT32_IEEE
)

/**
 * Parsed A2L RECORD_LAYOUT block.
 * Describes how axes and data values are physically laid out in the binary,
 * starting at the CHARACTERISTIC's (or AXIS_PTS') base address.
 *
 * - [noAxisPtsX]/[noAxisPtsY]: a single scalar storing the axis point count.
 * - [axisPtsX]/[axisPtsY]: the array of axis breakpoints.
 * - [fncValues]: the calibration data values (z-data).
 * - [isColumnDir]: true when the A2L says COLUMN_DIR (maps to isColumnMajor in AxisDefinition).
 */
data class A2lRecordLayout(
    val name: String,
    val fncValues: A2lLayoutEntry? = null,
    val axisPtsX: A2lLayoutEntry? = null,
    val axisPtsY: A2lLayoutEntry? = null,
    val noAxisPtsX: A2lLayoutEntry? = null,
    val noAxisPtsY: A2lLayoutEntry? = null,
    val isColumnDir: Boolean = false
)

/**
 * A2L AXIS_DESCR within a CHARACTERISTIC block.
 * - STD_AXIS: axis data is embedded in the CHARACTERISTIC record (requires RECORD_LAYOUT).
 * - COM_AXIS: axis data lives in a separate AXIS_PTS block (referenced by [axisPtsRef]).
 * - FIX_AXIS: axis values are fixed/virtual (no binary data).
 */
data class A2lAxisDescr(
    val axisType: String,
    val inputQuantity: String,
    val compuMethodName: String,
    val count: Int,
    val lowerLimit: Double,
    val upperLimit: Double,
    val axisPtsRef: String = ""
)

/**
 * A2L CHARACTERISTIC block — a calibration parameter stored in flash/ROM.
 * [address] is the binary file byte offset (same coordinate system as the XDF).
 */
data class A2lCharacteristic(
    val name: String,
    val description: String,
    val type: String,           // VALUE, CURVE, MAP
    val address: Long,
    val recordLayoutName: String,
    val compuMethodName: String,
    val lowerLimit: Double,
    val upperLimit: Double,
    val axes: List<A2lAxisDescr>
)

/**
 * A2L AXIS_PTS block — a standalone axis with its own binary address.
 * Referenced by COM_AXIS AXIS_DESCRs.
 */
data class A2lAxisPts(
    val name: String,
    val description: String,
    val address: Long,
    val inputQuantity: String,
    val recordLayoutName: String,
    val compuMethodName: String,
    val count: Int
)

// ─────────────────────────────────────────────────────────────────────────────

/** A single ME7Logger .ecu measurement entry (output format). */
data class EcuEntry(
    val name: String,
    val alias: String,
    val address: Long,
    val size: Int,        // 1=byte, 2=word
    val bitmask: Int,
    val unit: String,
    val signed: Int,      // 0=unsigned, 1=signed
    val inverse: Int,     // 0=normal, 1=inverse
    val factor: Double,
    val offset: Double,
    val comment: String
)

/** Conversion result from A2L COMPU_METHOD to ME7Logger format. */
data class EcuConversion(
    val factor: Double,
    val offset: Double,
    val inverse: Int
)
