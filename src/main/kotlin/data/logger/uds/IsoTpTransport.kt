package data.logger.uds

import data.logger.protocol.ProtocolConstants
import kotlinx.coroutines.delay

/**
 * ISO-TP (ISO 15765-2) transport layer over CAN.
 *
 * Handles segmented message transfer for UDS messages that exceed 8 bytes:
 * - Single Frame (SF): messages <= 7 bytes
 * - First Frame (FF) + Consecutive Frames (CF): larger messages
 * - Flow Control (FC): pacing for multi-frame transfer
 */
class IsoTpTransport(
    private val canTransport: CanTransport,
    private val txId: Int = 0x7E0,
    private val rxId: Int = 0x7E8
) {
    // Flow control parameters (received from ECU)
    private var blockSize: Int = 0
    private var separationTimeMs: Long = 10

    /**
     * Send an ISO-TP message. Automatically segments if > 7 bytes.
     */
    suspend fun send(data: ByteArray) {
        if (data.size <= ProtocolConstants.ISOTP_SINGLE_FRAME_MAX) {
            sendSingleFrame(data)
        } else {
            sendMultiFrame(data)
        }
    }

    /**
     * Receive an ISO-TP message. Handles reassembly of multi-frame messages.
     */
    suspend fun receive(timeout: Long = 1000): ByteArray {
        val frame = canTransport.receiveFrame(timeout)
            ?: throw IsoTpException("No response (timeout)")

        if (frame.data.isEmpty()) throw IsoTpException("Empty CAN frame")

        val pciType = (frame.data[0].toInt() and 0xF0) shr 4

        return when (pciType) {
            PCI_SINGLE_FRAME -> {
                val length = frame.data[0].toInt() and 0x0F
                if (length == 0 || length > 7) throw IsoTpException("Invalid SF length: $length")
                frame.data.copyOfRange(1, 1 + length)
            }
            PCI_FIRST_FRAME -> {
                receiveMultiFrame(frame, timeout)
            }
            else -> throw IsoTpException("Unexpected PCI type: $pciType")
        }
    }

    // ── Single Frame ─────────────────────────────────────────────────────

    private suspend fun sendSingleFrame(data: ByteArray) {
        val frame = ByteArray(ProtocolConstants.CAN_MAX_DATA)
        frame[0] = (PCI_SINGLE_FRAME shl 4 or data.size).toByte()
        data.copyInto(frame, 1)
        canTransport.sendFrame(txId, frame)
    }

    // ── Multi Frame TX ───────────────────────────────────────────────────

    private suspend fun sendMultiFrame(data: ByteArray) {
        if (data.size > ProtocolConstants.ISOTP_MAX_MESSAGE) {
            throw IsoTpException("Message too large: ${data.size} bytes (max ${ProtocolConstants.ISOTP_MAX_MESSAGE})")
        }

        // Send First Frame
        val ffData = ByteArray(ProtocolConstants.CAN_MAX_DATA)
        ffData[0] = (PCI_FIRST_FRAME shl 4 or (data.size shr 8 and 0x0F)).toByte()
        ffData[1] = (data.size and 0xFF).toByte()
        val firstChunkSize = minOf(data.size, 6) // FF carries 6 data bytes
        data.copyInto(ffData, 2, 0, firstChunkSize)
        canTransport.sendFrame(txId, ffData)

        // Wait for Flow Control
        val fc = canTransport.receiveFrame(1000)
            ?: throw IsoTpException("No Flow Control received")

        if (fc.data.isEmpty() || (fc.data[0].toInt() and 0xF0) shr 4 != PCI_FLOW_CONTROL) {
            throw IsoTpException("Expected Flow Control, got PCI type: ${if (fc.data.isNotEmpty()) (fc.data[0].toInt() and 0xF0) shr 4 else -1}")
        }

        val fcFlag = fc.data[0].toInt() and 0x0F
        if (fcFlag == FC_OVERFLOW) throw IsoTpException("ECU indicated overflow")
        if (fcFlag == FC_WAIT) {
            // Wait and retry — simplified: just delay
            delay(100)
        }

        blockSize = if (fc.data.size > 1) fc.data[1].toInt() and 0xFF else 0
        separationTimeMs = if (fc.data.size > 2) parseSeparationTime(fc.data[2]) else 10

        // Send Consecutive Frames
        var offset = firstChunkSize
        var seqNum = 1
        var blockCount = 0

        while (offset < data.size) {
            val cfData = ByteArray(ProtocolConstants.CAN_MAX_DATA)
            cfData[0] = (PCI_CONSECUTIVE_FRAME shl 4 or (seqNum and 0x0F)).toByte()
            val chunkSize = minOf(data.size - offset, 7)
            data.copyInto(cfData, 1, offset, offset + chunkSize)

            canTransport.sendFrame(txId, cfData)

            offset += chunkSize
            seqNum = (seqNum + 1) and 0x0F
            blockCount++

            // Respect separation time
            if (separationTimeMs > 0) delay(separationTimeMs)

            // Respect block size (0 = no limit)
            if (blockSize > 0 && blockCount >= blockSize && offset < data.size) {
                // Wait for next FC
                val nextFc = canTransport.receiveFrame(1000)
                    ?: throw IsoTpException("No Flow Control for next block")
                blockCount = 0
            }
        }
    }

    // ── Multi Frame RX ───────────────────────────────────────────────────

    private suspend fun receiveMultiFrame(firstFrame: CanFrame, timeout: Long): ByteArray {
        // Parse total length from FF
        val totalLength = ((firstFrame.data[0].toInt() and 0x0F) shl 8) or
                (firstFrame.data[1].toInt() and 0xFF)

        val result = ByteArray(totalLength)
        val firstChunkSize = minOf(totalLength, 6)
        firstFrame.data.copyInto(result, 0, 2, 2 + firstChunkSize)

        // Send Flow Control (continue, BS=0, STmin=10ms)
        val fc = ByteArray(ProtocolConstants.CAN_MAX_DATA)
        fc[0] = (PCI_FLOW_CONTROL shl 4 or FC_CONTINUE).toByte()
        fc[1] = 0 // block size = unlimited
        fc[2] = 10 // STmin = 10ms
        canTransport.sendFrame(txId, fc)

        // Receive Consecutive Frames
        var offset = firstChunkSize
        var expectedSeq = 1

        while (offset < totalLength) {
            val cf = canTransport.receiveFrame(timeout)
                ?: throw IsoTpException("Timeout waiting for CF (received $offset/$totalLength bytes)")

            if (cf.data.isEmpty()) continue
            val pciType = (cf.data[0].toInt() and 0xF0) shr 4
            if (pciType != PCI_CONSECUTIVE_FRAME) {
                throw IsoTpException("Expected CF, got PCI type: $pciType")
            }

            val seqNum = cf.data[0].toInt() and 0x0F
            if (seqNum != expectedSeq) {
                throw IsoTpException("Sequence error: expected $expectedSeq, got $seqNum")
            }

            val chunkSize = minOf(totalLength - offset, 7)
            cf.data.copyInto(result, offset, 1, 1 + chunkSize)
            offset += chunkSize
            expectedSeq = (expectedSeq + 1) and 0x0F
        }

        return result
    }

    private fun parseSeparationTime(stMin: Byte): Long {
        val value = stMin.toInt() and 0xFF
        return when {
            value <= 0x7F -> value.toLong()           // 0-127 ms
            value in 0xF1..0xF9 -> 1L                 // 100-900 us → round to 1ms
            else -> 10L                                // default
        }
    }

    companion object {
        private const val PCI_SINGLE_FRAME = 0
        private const val PCI_FIRST_FRAME = 1
        private const val PCI_CONSECUTIVE_FRAME = 2
        private const val PCI_FLOW_CONTROL = 3

        private const val FC_CONTINUE = 0
        private const val FC_WAIT = 1
        private const val FC_OVERFLOW = 2
    }
}

class IsoTpException(message: String, cause: Throwable? = null) : Exception(message, cause)
