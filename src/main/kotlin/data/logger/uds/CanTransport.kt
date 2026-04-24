package data.logger.uds

/**
 * CAN transport interface — abstracts over different CAN adapters
 * (SLCAN serial, PCAN-USB, etc.).
 */
interface CanTransport {
    suspend fun open(config: CanConfig)
    suspend fun sendFrame(id: Int, data: ByteArray)
    suspend fun receiveFrame(timeout: Long = 1000): CanFrame?
    fun close()
}

/**
 * A single CAN frame.
 */
data class CanFrame(
    val id: Int,
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return id == other.id && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * id + data.contentHashCode()

    override fun toString(): String =
        "CanFrame(id=0x${String.format("%03X", id)}, data=[${data.joinToString(" ") { String.format("%02X", it) }}])"
}

/**
 * CAN adapter configuration.
 */
data class CanConfig(
    val portName: String = "",
    val bitrate: Int = 500_000,
    val txId: Int = 0x7E0,
    val rxId: Int = 0x7E8
)

/**
 * CAN adapter type for UI selection.
 */
enum class CanAdapterType(val displayName: String) {
    SLCAN("SLCAN (CANable, USBtin)"),
    PCAN("PCAN-USB (Peak)")
}

/**
 * Fake CAN transport for unit testing.
 */
class FakeCanTransport : CanTransport {

    private var _isOpen = false
    val isOpen: Boolean get() = _isOpen

    /** Frames sent by the tester (for verification). */
    val sentFrames = mutableListOf<CanFrame>()

    /** Queue of frames the ECU will "send back". */
    private val rxQueue = ArrayDeque<CanFrame>()

    var openConfig: CanConfig? = null
        private set

    fun queueResponse(frame: CanFrame) {
        rxQueue.addLast(frame)
    }

    fun queueResponse(id: Int, vararg data: Byte) {
        rxQueue.addLast(CanFrame(id, data.toList().toByteArray()))
    }

    override suspend fun open(config: CanConfig) {
        openConfig = config
        _isOpen = true
    }

    override suspend fun sendFrame(id: Int, data: ByteArray) {
        sentFrames.add(CanFrame(id, data.clone()))
    }

    override suspend fun receiveFrame(timeout: Long): CanFrame? {
        return if (rxQueue.isNotEmpty()) rxQueue.removeFirst() else null
    }

    override fun close() {
        _isOpen = false
    }

    fun reset() {
        sentFrames.clear()
        rxQueue.clear()
        _isOpen = false
        openConfig = null
    }
}
