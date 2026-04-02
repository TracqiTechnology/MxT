package data.logger.kwp2000

import data.logger.protocol.ProtocolConstants
import data.logger.protocol.SerialPortProvider
import data.logger.protocol.SerialPortWrapper
import kotlinx.coroutines.delay

/**
 * K-line transport layer for KWP2000 communication.
 *
 * Handles:
 * - 5-baud slow init (ISO 14230 fast/slow init with target address)
 * - Baud rate switching after init
 * - HM0 (headerless mode) frame I/O: [length] [data...] [checksum]
 * - K-line echo consumption (half-duplex)
 */
class KLineTransport(
    private val portProvider: SerialPortProvider
) {
    private var port: SerialPortWrapper? = null
    private var _isOpen = false
    val isOpen: Boolean get() = _isOpen

    /**
     * Open the serial port at the initial slow baud rate (typically 9600 for init).
     */
    fun open(portName: String, initialBaudRate: Int = 9600) {
        val p = portProvider.getPort(portName)
        p.baudRate = initialBaudRate
        p.dataBits = 8
        p.stopBits = 1
        p.parity = 0
        if (!p.open()) {
            throw KLineException("Failed to open serial port: $portName")
        }
        port = p
        _isOpen = true
    }

    /**
     * Perform 5-baud slow init per ISO 14230.
     *
     * Sends the target address (typically 0x01) at 5 baud by toggling break,
     * then reads the sync byte (0x55) and two keyword bytes.
     *
     * @param targetAddress ECU address (parsed from .ecu "SLOW-0x01")
     * @return keyword bytes received from ECU
     */
    suspend fun slowInit(targetAddress: Int): SlowInitResult {
        val p = port ?: throw KLineException("Port not open")

        // Send target address at 5 baud (200ms per bit)
        // Format: start bit (0) + 8 data bits (LSB first) + stop bit (1)
        val bits = mutableListOf<Boolean>()
        bits.add(false) // start bit
        for (i in 0 until 8) {
            bits.add((targetAddress shr i) and 1 == 1)
        }
        bits.add(true) // stop bit

        for (bit in bits) {
            if (bit) {
                p.clearBreak() // high = idle
            } else {
                p.setBreak()   // low = space
            }
            delay(ProtocolConstants.SLOW_INIT_BIT_DURATION_MS)
        }
        p.clearBreak() // return to idle

        // Switch to 10400 baud for handshake
        p.baudRate = 10400

        // Read sync byte (0x55)
        val syncBuf = ByteArray(1)
        val syncRead = p.read(syncBuf, ProtocolConstants.SLOW_INIT_SYNC_TIMEOUT_MS)
        if (syncRead < 1 || syncBuf[0] != 0x55.toByte()) {
            throw KLineException("No sync byte received (got ${if (syncRead > 0) "0x${String.format("%02X", syncBuf[0])}" else "nothing"})")
        }

        // Read keyword bytes (KW1, KW2)
        val kwBuf = ByteArray(2)
        val kwRead = p.read(kwBuf, ProtocolConstants.SLOW_INIT_KEYWORD_TIMEOUT_MS)
        if (kwRead < 2) {
            throw KLineException("Keyword bytes not received (got $kwRead bytes)")
        }

        // Send inverted KW2 as acknowledgement
        val ack = byteArrayOf((kwBuf[1].toInt().inv() and 0xFF).toByte())
        p.write(ack)
        consumeEcho(1)

        // Wait for ECU's inverted target address response
        val ecuAck = ByteArray(1)
        val ackRead = p.read(ecuAck, ProtocolConstants.SLOW_INIT_KEYWORD_TIMEOUT_MS)
        if (ackRead < 1) {
            throw KLineException("ECU acknowledgement not received")
        }

        return SlowInitResult(
            syncByte = syncBuf[0],
            keyword1 = kwBuf[0],
            keyword2 = kwBuf[1],
            ecuAck = ecuAck[0]
        )
    }

    /**
     * Switch baud rate after successful init.
     */
    fun switchBaudRate(baudRate: Int) {
        val p = port ?: throw KLineException("Port not open")
        p.baudRate = baudRate
    }

    /**
     * Send an HM0 frame: [length] [data...] [checksum]
     * Checksum = sum of all bytes (length + data) mod 256.
     */
    fun sendFrame(data: ByteArray) {
        val p = port ?: throw KLineException("Port not open")

        val frame = ByteArray(data.size + 2)
        frame[0] = data.size.toByte()
        data.copyInto(frame, 1)

        var checksum = 0
        for (i in 0 until frame.size - 1) {
            checksum = (checksum + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        frame[frame.size - 1] = checksum.toByte()

        p.write(frame)

        // Consume echo (K-line is half-duplex, we see our own bytes)
        consumeEcho(frame.size)
    }

    /**
     * Receive an HM0 frame. Returns the data portion (without length byte and checksum).
     *
     * @param timeout milliseconds to wait for the length byte
     */
    fun receiveFrame(timeout: Long = ProtocolConstants.KWP_P2_MAX_MS): ByteArray {
        val p = port ?: throw KLineException("Port not open")

        // Read length byte
        val lenBuf = ByteArray(1)
        val lenRead = p.read(lenBuf, timeout)
        if (lenRead < 1) {
            throw KLineException("No response (timeout)")
        }
        val length = lenBuf[0].toInt() and 0xFF

        if (length == 0 || length > ProtocolConstants.KWP_MAX_FRAME_DATA) {
            throw KLineException("Invalid frame length: $length")
        }

        // Read data + checksum
        val remaining = ByteArray(length + 1) // data bytes + checksum
        val restRead = p.read(remaining, ProtocolConstants.KWP_P1_MAX_MS * (length + 1))
        if (restRead < length + 1) {
            throw KLineException("Incomplete frame: expected ${length + 1} bytes, got $restRead")
        }

        // Verify checksum
        var checksum = lenBuf[0].toInt() and 0xFF
        for (i in 0 until length) {
            checksum = (checksum + (remaining[i].toInt() and 0xFF)) and 0xFF
        }
        val expectedChecksum = remaining[length].toInt() and 0xFF
        if (checksum != expectedChecksum) {
            throw KLineException("Checksum mismatch: calculated 0x${String.format("%02X", checksum)}, received 0x${String.format("%02X", expectedChecksum)}")
        }

        return remaining.copyOf(length)
    }

    /**
     * Consume echo bytes from K-line (half-duplex — we see our own transmitted bytes).
     */
    private fun consumeEcho(count: Int) {
        val p = port ?: return
        val echoBuf = ByteArray(count)
        p.read(echoBuf, 50)
    }

    fun close() {
        port?.close()
        port = null
        _isOpen = false
    }
}

data class SlowInitResult(
    val syncByte: Byte,
    val keyword1: Byte,
    val keyword2: Byte,
    val ecuAck: Byte
)

class KLineException(message: String, cause: Throwable? = null) : Exception(message, cause)
