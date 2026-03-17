package data.logger.protocol

/**
 * KWP2000 (ISO 14230) and UDS (ISO 14229) protocol constants.
 */
object ProtocolConstants {

    // ── KWP2000 Service IDs ──────────────────────────────────────────────
    const val KWP_START_DIAGNOSTIC_SESSION: Byte = 0x10.toByte()
    const val KWP_ECU_RESET: Byte = 0x11.toByte()
    const val KWP_STOP_DIAGNOSTIC_SESSION: Byte = 0x20.toByte()
    const val KWP_READ_DATA_BY_LOCAL_ID: Byte = 0x21.toByte()
    const val KWP_DYNAMICALLY_DEFINE_LOCAL_ID: Byte = 0x2C.toByte()
    const val KWP_TESTER_PRESENT: Byte = 0x3E.toByte()
    const val KWP_NEGATIVE_RESPONSE: Byte = 0x7F.toByte()

    // Bosch-specific extensions (ME7/MED9)
    const val KWP_BOSCH_DEFINE_VARIABLES: Byte = 0xB7.toByte()
    const val KWP_BOSCH_READ_VARIABLES: Byte = 0xB8.toByte()

    // KWP2000 session types
    const val KWP_SESSION_DIAGNOSTIC: Byte = 0x89.toByte()

    // KWP2000 DynamicallyDefineLocalIdentifier modes
    const val KWP_DEFINE_BY_MEMORY_ADDRESS: Byte = 0x03.toByte()
    const val KWP_CLEAR_DEFINITION: Byte = 0x04.toByte()

    // ── UDS Service IDs ──────────────────────────────────────────────────
    const val UDS_DIAGNOSTIC_SESSION_CONTROL: Byte = 0x10.toByte()
    const val UDS_ECU_RESET: Byte = 0x11.toByte()
    const val UDS_SECURITY_ACCESS: Byte = 0x27.toByte()
    const val UDS_READ_DATA_BY_ID: Byte = 0x22.toByte()
    const val UDS_DYNAMICALLY_DEFINE_DATA_ID: Byte = 0x2C.toByte()
    const val UDS_READ_DATA_BY_PERIODIC_ID: Byte = 0x2A.toByte()
    const val UDS_TESTER_PRESENT: Byte = 0x3E.toByte()
    const val UDS_NEGATIVE_RESPONSE: Byte = 0x7F.toByte()

    // UDS session types
    const val UDS_SESSION_DEFAULT: Byte = 0x01.toByte()
    const val UDS_SESSION_PROGRAMMING: Byte = 0x02.toByte()
    const val UDS_SESSION_EXTENDED: Byte = 0x03.toByte()

    // UDS DynamicallyDefineDataIdentifier sub-functions
    const val UDS_DEFINE_BY_MEMORY_ADDRESS: Byte = 0x02.toByte()
    const val UDS_CLEAR_DYNAMICALLY_DEFINED: Byte = 0x03.toByte()

    // ── Positive response offset ────────────────────────────────────────
    /** Positive response SID = request SID + 0x40 */
    const val POSITIVE_RESPONSE_OFFSET: Int = 0x40

    // ── Timing constants (milliseconds) ──────────────────────────────────

    // KWP2000 (ISO 14230-2) timing
    /** P1: inter-byte time in ECU response (max) */
    const val KWP_P1_MAX_MS: Long = 20
    /** P2: time between end of request and start of response (max) */
    const val KWP_P2_MAX_MS: Long = 50
    /** P3: time between end of response and start of next request (min) */
    const val KWP_P3_MIN_MS: Long = 55
    /** P4: inter-byte time in tester request (min) */
    const val KWP_P4_MIN_MS: Long = 5

    // 5-baud init timing
    /** Duration of each bit during 5-baud init (200ms = 5 baud) */
    const val SLOW_INIT_BIT_DURATION_MS: Long = 200
    /** Timeout waiting for sync byte (0x55) after 5-baud init */
    const val SLOW_INIT_SYNC_TIMEOUT_MS: Long = 300
    /** Timeout waiting for keyword bytes after sync */
    const val SLOW_INIT_KEYWORD_TIMEOUT_MS: Long = 50

    // UDS timing
    /** S3 server timeout — TesterPresent must be sent within this interval */
    const val UDS_S3_TIMEOUT_MS: Long = 5000
    /** P2 server response timeout (default) */
    const val UDS_P2_DEFAULT_MS: Long = 50
    /** P2* extended response timeout (after responsePending 0x78) */
    const val UDS_P2_STAR_MS: Long = 5000

    // ── Dynamic ID range ─────────────────────────────────────────────────
    /** First record local identifier for KWP2000 dynamic definition */
    const val KWP_FIRST_RECORD_ID: Byte = 0xF0.toByte()
    /** UDS dynamic data identifier base (0xF200-0xF3FF) */
    const val UDS_DYNAMIC_DID_BASE: Int = 0xF200

    // ── Frame size limits ────────────────────────────────────────────────
    /** Max KWP2000 data payload per frame (HM0 mode, length byte = 1 byte) */
    const val KWP_MAX_FRAME_DATA: Int = 254
    /** Max CAN data field (classic CAN) */
    const val CAN_MAX_DATA: Int = 8
    /** Max single-frame ISO-TP payload (7 bytes for standard addressing) */
    const val ISOTP_SINGLE_FRAME_MAX: Int = 7
    /** Max ISO-TP message payload (4095 bytes) */
    const val ISOTP_MAX_MESSAGE: Int = 4095
}

/**
 * KWP2000 / UDS negative response codes.
 */
enum class NegativeResponseCode(val code: Int, val description: String) {
    GENERAL_REJECT(0x10, "General reject"),
    SERVICE_NOT_SUPPORTED(0x11, "Service not supported"),
    SUB_FUNCTION_NOT_SUPPORTED(0x12, "Sub-function not supported"),
    INCORRECT_MESSAGE_LENGTH(0x13, "Incorrect message length or invalid format"),
    BUSY_REPEAT_REQUEST(0x21, "Busy — repeat request"),
    CONDITIONS_NOT_CORRECT(0x22, "Conditions not correct"),
    REQUEST_SEQUENCE_ERROR(0x24, "Request sequence error"),
    REQUEST_OUT_OF_RANGE(0x31, "Request out of range"),
    SECURITY_ACCESS_DENIED(0x33, "Security access denied"),
    INVALID_KEY(0x35, "Invalid key"),
    EXCEEDED_ATTEMPTS(0x36, "Exceeded number of attempts"),
    UPLOAD_DOWNLOAD_NOT_ACCEPTED(0x70, "Upload/download not accepted"),
    RESPONSE_PENDING(0x78, "Response pending"),
    SERVICE_NOT_SUPPORTED_IN_SESSION(0x7F, "Service not supported in active session");

    companion object {
        fun fromCode(code: Int): NegativeResponseCode? =
            entries.firstOrNull { it.code == code }
    }
}
