package data.parser.a2l

/**
 * Data models for ASAP2/A2L file parsing.
 * Used to extract MEASUREMENT entries and COMPU_METHOD conversion formulas
 * for generating ME7Logger-compatible .ecu files.
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
