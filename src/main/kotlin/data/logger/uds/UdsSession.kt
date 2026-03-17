package data.logger.uds

import data.logger.protocol.NegativeResponseCode
import data.logger.protocol.ProtocolConstants
import kotlinx.coroutines.*

/**
 * UDS (ISO 14229) session layer over ISO-TP.
 *
 * Manages:
 * - DiagnosticSessionControl (extended session)
 * - TesterPresent keepalive
 * - Negative response handling
 * - SecurityAccess if needed for RAM reads
 */
class UdsSession(
    private val transport: IsoTpTransport,
    private val keepaliveIntervalMs: Long = 4000L  // Must be < S3 (5000ms)
) {
    private var keepaliveJob: Job? = null
    private var _isActive = false
    val isActive: Boolean get() = _isActive

    /**
     * Start an extended diagnostic session (0x10 0x03).
     */
    suspend fun startSession() {
        val request = byteArrayOf(
            ProtocolConstants.UDS_DIAGNOSTIC_SESSION_CONTROL,
            ProtocolConstants.UDS_SESSION_EXTENDED
        )
        val response = sendWithRetry(request)

        val expectedSid = (ProtocolConstants.UDS_DIAGNOSTIC_SESSION_CONTROL.toInt() + ProtocolConstants.POSITIVE_RESPONSE_OFFSET).toByte()
        if (response.isEmpty() || response[0] != expectedSid) {
            throw UdsException("Unexpected session response: ${if (response.isNotEmpty()) "0x${String.format("%02X", response[0])}" else "empty"}")
        }

        _isActive = true
        startKeepalive()
    }

    /**
     * Stop the diagnostic session (return to default).
     */
    suspend fun stopSession() {
        stopKeepalive()

        if (_isActive) {
            try {
                val request = byteArrayOf(
                    ProtocolConstants.UDS_DIAGNOSTIC_SESSION_CONTROL,
                    ProtocolConstants.UDS_SESSION_DEFAULT
                )
                sendWithRetry(request)
            } catch (_: Exception) {
                // Best effort
            }
            _isActive = false
        }
    }

    /**
     * Send a UDS service request and receive the response.
     */
    suspend fun sendService(request: ByteArray): ByteArray = sendWithRetry(request)

    /**
     * Perform SecurityAccess (0x27) — seed/key exchange.
     * The key algorithm is ECU-specific and must be provided.
     */
    suspend fun securityAccess(level: Int, keyCalculator: (ByteArray) -> ByteArray) {
        // Request seed (odd sub-function)
        val seedRequest = byteArrayOf(
            ProtocolConstants.UDS_SECURITY_ACCESS,
            level.toByte()
        )
        val seedResponse = sendWithRetry(seedRequest)

        if (seedResponse.size < 2) {
            throw UdsException("SecurityAccess seed response too short")
        }

        // Extract seed (bytes after SID + sub-function)
        val seed = seedResponse.copyOfRange(2, seedResponse.size)

        // All-zero seed means already unlocked
        if (seed.all { it == 0.toByte() }) return

        // Calculate key
        val key = keyCalculator(seed)

        // Send key (even sub-function = level + 1)
        val keyRequest = ByteArray(2 + key.size)
        keyRequest[0] = ProtocolConstants.UDS_SECURITY_ACCESS
        keyRequest[1] = (level + 1).toByte()
        key.copyInto(keyRequest, 2)

        val keyResponse = sendWithRetry(keyRequest)
        val expectedSid = (ProtocolConstants.UDS_SECURITY_ACCESS.toInt() + ProtocolConstants.POSITIVE_RESPONSE_OFFSET).toByte()
        if (keyResponse.isEmpty() || keyResponse[0] != expectedSid) {
            throw UdsException("SecurityAccess key rejected")
        }
    }

    private suspend fun sendWithRetry(request: ByteArray, maxRetries: Int = 3): ByteArray {
        var retries = 0
        while (true) {
            transport.send(request)
            val response = transport.receive(ProtocolConstants.UDS_P2_DEFAULT_MS * 20)

            // Check for negative response
            if (response.isNotEmpty() && response[0] == ProtocolConstants.UDS_NEGATIVE_RESPONSE) {
                if (response.size >= 3) {
                    val nrc = response[2].toInt() and 0xFF
                    when (NegativeResponseCode.fromCode(nrc)) {
                        NegativeResponseCode.BUSY_REPEAT_REQUEST -> {
                            retries++
                            if (retries > maxRetries) {
                                throw UdsException("ECU busy after $maxRetries retries")
                            }
                            delay(100)
                            continue
                        }
                        NegativeResponseCode.RESPONSE_PENDING -> {
                            // Wait for the real response
                            val extended = transport.receive(ProtocolConstants.UDS_P2_STAR_MS)
                            return extended
                        }
                        else -> {
                            val code = NegativeResponseCode.fromCode(nrc)
                            throw UdsException(
                                "Negative response: ${code?.description ?: "unknown"} (0x${String.format("%02X", nrc)})"
                            )
                        }
                    }
                }
                throw UdsException("Malformed negative response")
            }

            return response
        }
    }

    private fun startKeepalive() {
        keepaliveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(keepaliveIntervalMs)
                try {
                    val request = byteArrayOf(
                        ProtocolConstants.UDS_TESTER_PRESENT,
                        0x00 // sub-function: no response expected (suppressPosRspMsgIndicationBit)
                    )
                    transport.send(request)
                    // Don't wait for response when suppress bit is set
                } catch (_: Exception) {
                    // Keepalive failure
                }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }
}

class UdsException(message: String, cause: Throwable? = null) : Exception(message, cause)
