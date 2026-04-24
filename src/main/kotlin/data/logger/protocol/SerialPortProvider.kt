package data.logger.protocol

/**
 * Abstraction over serial port operations for testability.
 * Real implementation wraps jSerialComm; fake implementation records I/O.
 */
interface SerialPortWrapper {
    val portName: String
    fun open(): Boolean
    fun close()
    fun read(buffer: ByteArray, timeout: Long = 1000): Int
    fun write(data: ByteArray): Int
    fun setBreak()
    fun clearBreak()
    var baudRate: Int
    var dataBits: Int
    var stopBits: Int
    var parity: Int
    val isOpen: Boolean
}

/**
 * Provider interface for creating serial port instances.
 */
interface SerialPortProvider {
    fun getPort(name: String): SerialPortWrapper
    fun listPorts(): List<SerialPortInfo>
}

/**
 * Info about an available serial port.
 */
data class SerialPortInfo(
    val systemPortName: String,
    val descriptivePortName: String,
    val manufacturer: String = ""
) {
    val displayName: String
        get() = if (descriptivePortName.isNotEmpty() && descriptivePortName != systemPortName)
            "$systemPortName - $descriptivePortName"
        else systemPortName
}

/**
 * Fake serial port for unit testing.
 * Queues responses and records transmitted data.
 */
class FakeSerialPortWrapper(
    override val portName: String = "FAKE0"
) : SerialPortWrapper {

    private var _isOpen = false
    override val isOpen: Boolean get() = _isOpen

    override var baudRate: Int = 9600
    override var dataBits: Int = 8
    override var stopBits: Int = 1
    override var parity: Int = 0

    /** Bytes written by the tester (for verification). */
    val transmitted = mutableListOf<Byte>()

    /** Queue of bytes the ECU will "send back". */
    private val rxQueue = ArrayDeque<Byte>()

    /** Whether setBreak was called (for 5-baud init verification). */
    var breakSet = false
        private set
    var breakSetCount = 0
        private set
    var breakClearCount = 0
        private set

    fun queueResponse(vararg bytes: Byte) {
        bytes.forEach { rxQueue.addLast(it) }
    }

    fun queueResponseBytes(bytes: ByteArray) {
        bytes.forEach { rxQueue.addLast(it) }
    }

    override fun open(): Boolean {
        _isOpen = true
        return true
    }

    override fun close() {
        _isOpen = false
    }

    override fun read(buffer: ByteArray, timeout: Long): Int {
        var count = 0
        while (count < buffer.size && rxQueue.isNotEmpty()) {
            buffer[count++] = rxQueue.removeFirst()
        }
        return count
    }

    override fun write(data: ByteArray): Int {
        data.forEach { transmitted.add(it) }
        return data.size
    }

    override fun setBreak() {
        breakSet = true
        breakSetCount++
    }

    override fun clearBreak() {
        breakSet = false
        breakClearCount++
    }

    fun reset() {
        transmitted.clear()
        rxQueue.clear()
        breakSet = false
        breakSetCount = 0
        breakClearCount = 0
        _isOpen = false
    }
}

/**
 * Fake serial port provider for testing.
 */
class FakeSerialPortProvider(
    private val ports: Map<String, FakeSerialPortWrapper> = emptyMap()
) : SerialPortProvider {

    override fun getPort(name: String): SerialPortWrapper =
        ports[name] ?: FakeSerialPortWrapper(name)

    override fun listPorts(): List<SerialPortInfo> =
        ports.keys.map { SerialPortInfo(it, "Fake Port $it") }
}
