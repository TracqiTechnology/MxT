package data.logger.uds

import data.logger.protocol.SerialPortProvider
import data.logger.protocol.SerialPortWrapper
import kotlinx.coroutines.delay

/**
 * SLCAN (Serial Line CAN / Lawicel) transport.
 *
 * Works with CANable, USBtin, and other SLCAN-compatible adapters.
 * ASCII protocol over serial port:
 * - `O` = open CAN channel
 * - `C` = close CAN channel
 * - `S6` = set 500kbps CAN bitrate
 * - `tIIILDD..` = send standard frame (11-bit ID)
 * - `TIIIIIIIILDD..` = send extended frame (29-bit ID)
 */
class SlcanTransport(
    private val portProvider: SerialPortProvider
) : CanTransport {

    private var port: SerialPortWrapper? = null
    private var config: CanConfig? = null
    private val readBuffer = StringBuilder()

    override suspend fun open(config: CanConfig) {
        this.config = config
        val p = portProvider.getPort(config.portName)
        p.baudRate = 115200  // SLCAN adapters typically run at 115200
        p.dataBits = 8
        p.stopBits = 1
        p.parity = 0

        if (!p.open()) {
            throw SlcanException("Failed to open serial port: ${config.portName}")
        }
        port = p

        // Reset adapter
        sendCommand("C")  // close first in case already open
        delay(50)

        // Set CAN bitrate
        val bitrateCmd = bitrateToSlcan(config.bitrate)
        sendCommand(bitrateCmd)
        delay(10)

        // Open CAN channel
        sendCommand("O")
        delay(10)
    }

    override suspend fun sendFrame(id: Int, data: ByteArray) {
        val p = port ?: throw SlcanException("Port not open")

        val cmd = buildString {
            if (id > 0x7FF) {
                // Extended frame (29-bit ID)
                append('T')
                append(String.format("%08X", id))
            } else {
                // Standard frame (11-bit ID)
                append('t')
                append(String.format("%03X", id))
            }
            append(data.size)
            for (b in data) {
                append(String.format("%02X", b.toInt() and 0xFF))
            }
            append('\r')
        }

        p.write(cmd.toByteArray(Charsets.US_ASCII))
    }

    override suspend fun receiveFrame(timeout: Long): CanFrame? {
        val p = port ?: throw SlcanException("Port not open")
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val buf = ByteArray(256)
            val n = p.read(buf, minOf(timeout, 50))
            if (n > 0) {
                readBuffer.append(String(buf, 0, n, Charsets.US_ASCII))
            }

            // Look for complete frames (terminated by \r)
            val crIdx = readBuffer.indexOf('\r')
            if (crIdx >= 0) {
                val line = readBuffer.substring(0, crIdx)
                readBuffer.delete(0, crIdx + 1)

                val frame = parseSlcanFrame(line)
                if (frame != null) {
                    // Filter by expected RX ID
                    val rxId = config?.rxId ?: return frame
                    if (frame.id == rxId) return frame
                    // Otherwise, keep looking
                }
            }

            if (n <= 0) delay(1)
        }

        return null
    }

    override fun close() {
        try {
            sendCommand("C")  // close CAN channel
        } catch (_: Exception) { }
        port?.close()
        port = null
        readBuffer.clear()
    }

    /**
     * Parse an SLCAN frame string into a CanFrame.
     * `tIIILDD..` for standard, `TIIIIIIIILDD..` for extended.
     */
    internal fun parseSlcanFrame(line: String): CanFrame? {
        if (line.isEmpty()) return null

        return when (line[0]) {
            't' -> {
                // Standard frame: tIIILDD..
                if (line.length < 5) return null
                val id = line.substring(1, 4).toIntOrNull(16) ?: return null
                val dlc = line[4].digitToIntOrNull() ?: return null
                val data = parseHexData(line.substring(5), dlc) ?: return null
                CanFrame(id, data)
            }
            'T' -> {
                // Extended frame: TIIIIIIIILDD..
                if (line.length < 10) return null
                val id = line.substring(1, 9).toIntOrNull(16) ?: return null
                val dlc = line[9].digitToIntOrNull() ?: return null
                val data = parseHexData(line.substring(10), dlc) ?: return null
                CanFrame(id, data)
            }
            else -> null
        }
    }

    private fun parseHexData(hex: String, dlc: Int): ByteArray? {
        if (hex.length < dlc * 2) return null
        return ByteArray(dlc) { i ->
            hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
    }

    private fun sendCommand(cmd: String) {
        port?.write("$cmd\r".toByteArray(Charsets.US_ASCII))
    }

    companion object {
        fun bitrateToSlcan(bitrate: Int): String = when (bitrate) {
            10_000 -> "S0"
            20_000 -> "S1"
            50_000 -> "S2"
            100_000 -> "S3"
            125_000 -> "S4"
            250_000 -> "S5"
            500_000 -> "S6"
            800_000 -> "S7"
            1_000_000 -> "S8"
            else -> "S6" // default 500k
        }
    }
}

class SlcanException(message: String, cause: Throwable? = null) : Exception(message, cause)
