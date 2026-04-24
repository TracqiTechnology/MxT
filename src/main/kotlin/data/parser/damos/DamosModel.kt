package data.parser.damos

/**
 * Parsed representation of a DAMOS 2 `.dam` file.
 *
 * Contains UMP (measurement variables), REG (register type definitions),
 * SRC (source channels), and SND (memory segments).
 */
data class DamosFile(
    val programNumber: String,
    val version: String,
    val ecuFamily: String,
    val cpuType: String,
    val spzCount: Int,
    val umpCount: Int,
    val srcCount: Int,
    val segments: List<DamosSegment>,
    val umpRecords: Map<String, DamosUmpRecord>,
    val regRecords: Map<Int, DamosRegRecord>,
    val srcRecords: Map<String, DamosSrcRecord>
)

/**
 * UMP record — a runtime measurement variable (RAM signal) for logging.
 *
 * Format: `/UMP, {desc1}, name, {desc2}, $address, typeId, regId, regName, triggerGroup, $bitmask, K;`
 *
 * TypeId encoding:
 * - 513 = unsigned byte (1 byte)
 * - 514 = unsigned word (2 bytes)
 * - 516 = unsigned long (4 bytes)
 * - 529 = signed byte (1 byte)
 * - 530 = signed word (2 bytes)
 * - 532 = signed long (4 bytes)
 */
data class DamosUmpRecord(
    val name: String,
    val description: String,
    val address: Long,
    val typeId: Int,
    val regId: Int,
    val regName: String,
    val bitmask: Long,
    val triggerGroup: Int
) {
    /** Size in bytes derived from typeId. */
    val sizeBytes: Int
        get() = when (typeId) {
            513, 529 -> 1   // byte
            514, 530 -> 2   // word
            516, 532 -> 4   // long
            else -> 2       // default to word
        }

    /** Whether the value is signed, derived from typeId. */
    val isSigned: Boolean
        get() = typeId in 529..532
}

/**
 * REG record — register type definition for physical conversion.
 *
 * Physical conversion: `phys = (raw * factor / divisor) + offset`
 *
 * Format:
 * ```
 * num, /REG, name, {desc}, class, signed, {unit}, sizeBytes, dataType, physMin, physMax
 * /REP, divisor, offset, unused, factor, unused, unused;
 * ```
 */
data class DamosRegRecord(
    val id: Int,
    val name: String,
    val unit: String,
    val factor: Double,
    val divisor: Double,
    val offset: Double,
    val sizeBytes: Int,
    val signed: Int
)

/**
 * SRC record — source channel (measurement variable with address and channel info).
 *
 * Format: `num, /SRC, name, {desc}, $address, signed, typeId, byteWidth, channelGroup;`
 */
data class DamosSrcRecord(
    val num: Int,
    val name: String,
    val description: String,
    val address: Long,
    val signed: Int,
    val typeId: Int,
    val byteWidth: Int,
    val channelGroup: Int
)

/**
 * SND record — memory segment definition.
 *
 * Format: `/SND, name $start $end`
 */
data class DamosSegment(
    val name: String,
    val start: Long,
    val end: Long
)
