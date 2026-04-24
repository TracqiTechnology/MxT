package data.model

/**
 * Supported ECU platform families.
 *
 * Gates all platform-specific behaviour throughout the app: which calibration tabs
 * are visible, which log contract is used, which map name labels are displayed,
 * and which optimizer path runs.
 *
 * Also carries platform-specific memory layout information used by the RAM sniffer
 * for signature building and scanning.
 */
enum class EcuPlatform(
    val displayName: String,
    val shortName: String,
    val stability: StabilityLevel = StabilityLevel.STABLE,
    /** Address width in bytes: 2 for C166/C167 (16-bit DPP offset), 4 for TriCore (32-bit absolute). */
    val addressWidth: Int = 2,
    /** Start of RAM address range (inclusive). */
    val ramRangeStart: Long = 0x380000L,
    /** End of RAM address range (inclusive). */
    val ramRangeEnd: Long = 0x3FFFFFL,
    /** Code segment ranges in the flash image (addresses as stored in BIN/HEX). */
    val codeRanges: List<LongRange> = listOf(0x800000L..0x80FFFFL, 0x820000L..0x8FFFFFL),
    /** Data/calibration segment ranges (excluded from signature scanning). */
    val dataRanges: List<LongRange> = listOf(0x810000L..0x81FFFFL)
) {
    MOTRONIC(
        displayName = "Motronic 3.8x-5.9x",
        shortName = "M3/5",
        stability = StabilityLevel.ALPHA,
        addressWidth = 2,
        ramRangeStart = 0x380000L,
        ramRangeEnd = 0x3FFFFFL,
        codeRanges = listOf(0x000000L..0x03FFFFL),
        dataRanges = listOf(0x010000L..0x01FFFFL)
    ),
    ME7(
        displayName = "ME7 (Bosch ME7.x)",
        shortName = "ME7",
        stability = StabilityLevel.STABLE,
        addressWidth = 2,
        ramRangeStart = 0x380000L,
        ramRangeEnd = 0x3FFFFFL,
        codeRanges = listOf(0x800000L..0x80FFFFL, 0x820000L..0x8FFFFFL),
        dataRanges = listOf(0x810000L..0x81FFFFL)
    ),
    MED9(
        displayName = "MED9 (Bosch MED9.x)",
        shortName = "MED9",
        stability = StabilityLevel.ALPHA,
        addressWidth = 4,
        ramRangeStart = 0x7F0000L,
        ramRangeEnd = 0x7FFFFFL,
        codeRanges = listOf(0x800000L..0xBFFFFL),
        dataRanges = listOf(0xA0000L..0xAFFFFL)
    ),
    MED17(
        displayName = "MED17 (Bosch MED17.x)",
        shortName = "MED17",
        stability = StabilityLevel.BETA,
        addressWidth = 4,
        ramRangeStart = 0xD0000000L,
        ramRangeEnd = 0xD00FFFFFL,
        codeRanges = listOf(0x80000000L..0x801FFFFFL),
        dataRanges = listOf(0x80100000L..0x8017FFFFL)
    );
}
