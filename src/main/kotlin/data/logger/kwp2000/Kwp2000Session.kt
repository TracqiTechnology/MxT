package data.logger.kwp2000

import data.logger.protocol.NegativeResponseCode
import data.logger.protocol.ProtocolConstants
import kotlinx.coroutines.*

/**
 * KWP2000 session layer.
 *
 * Manages:
 * - StartDiagnosticSession / StopDiagnosticSession
 * - TesterPresent keepalive
 * - Negative response handling (retry on busy, wait on responsePending)
 */
class Kwp2000Session(
    private val transport: KLineTransport,
    private val keepaliveIntervalMs: Long = 2000L
) {
    private var keepaliveJob: Job? = null
    private var _isActive = false
    val isActive: Boolean get() = _isActive

    /**
     * Start a diagnostic session (service 0x10, sub-function 0x89).
     */
    suspend fun startSession() {
        val request = byteArrayOf(
            ProtocolConstants.KWP_START_DIAGNOSTIC_SESSION,
            ProtocolConstants.KWP_SESSION_DIAGNOSTIC
        )
        val response = sendWithRetry(request)

        // Positive response: 0x50, 0x89
        if (response[0] != (ProtocolConstants.KWP_START_DIAGNOSTIC_SESSION.toInt() + ProtocolConstants.POSITIVE_RESPONSE_OFFSET).toByte()) {
            throw Kwp2000Exception("Unexpected session response: 0x${String.format("%02X", response[0])}")
        }

        _isActive = true
        startKeepalive()
    }

    /**
     * Stop the diagnostic session.
     */
    suspend fun stopSession() {
        stopKeepalive()

        if (_isActive) {
            try {
                val request = byteArrayOf(ProtocolConstants.KWP_STOP_DIAGNOSTIC_SESSION)
                sendWithRetry(request)
            } catch (_: Exception) {
                // Best effort
            }
            _isActive = false
        }
    }

    /**
     * Send a KWP2000 service request and receive the response.
     * Handles negative responses: retry on busy, wait on responsePending.
     */
    suspend fun sendService(request: ByteArray): ByteArray = sendWithRetry(request)

    private suspend fun sendWithRetry(request: ByteArray, maxRetries: Int = 3): ByteArray {
        var retries = 0
        while (true) {
            delay(ProtocolConstants.KWP_P3_MIN_MS)
            transport.sendFrame(request)
            val response = transport.receiveFrame(ProtocolConstants.KWP_P2_MAX_MS * 10)

            // Check for negative response
            if (response.isNotEmpty() && response[0] == ProtocolConstants.KWP_NEGATIVE_RESPONSE) {
                if (response.size >= 3) {
                    val nrc = response[2].toInt() and 0xFF
                    when (NegativeResponseCode.fromCode(nrc)) {
                        NegativeResponseCode.BUSY_REPEAT_REQUEST -> {
                            retries++
                            if (retries > maxRetries) {
                                throw Kwp2000Exception("ECU busy after $maxRetries retries")
                            }
                            delay(100)
                            continue
                        }
                        NegativeResponseCode.RESPONSE_PENDING -> {
                            // ECU needs more time — wait and re-read
                            val extended = transport.receiveFrame(ProtocolConstants.UDS_P2_STAR_MS)
                            return extended
                        }
                        else -> {
                            val code = NegativeResponseCode.fromCode(nrc)
                            throw Kwp2000Exception(
                                "Negative response: ${code?.description ?: "unknown"} (0x${String.format("%02X", nrc)})"
                            )
                        }
                    }
                }
                throw Kwp2000Exception("Malformed negative response")
            }

            return response
        }
    }

    private fun startKeepalive() {
        keepaliveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(keepaliveIntervalMs)
                try {
                    transport.sendFrame(byteArrayOf(ProtocolConstants.KWP_TESTER_PRESENT))
                    transport.receiveFrame(ProtocolConstants.KWP_P2_MAX_MS * 5)
                } catch (_: Exception) {
                    // Keepalive failure — session may have expired
                }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }
}

class Kwp2000Exception(message: String, cause: Throwable? = null) : Exception(message, cause)
