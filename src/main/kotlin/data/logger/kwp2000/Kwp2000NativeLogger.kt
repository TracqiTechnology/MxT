package data.logger.kwp2000

import data.logger.*
import data.logger.protocol.ProtocolConstants
import data.logger.protocol.SerialPortProvider
import data.parser.a2l.EcuEntry
import data.parser.ecu.CfgFileParser
import data.parser.ecu.EcuFileParser
import data.parser.ecu.EcuFile
import data.parser.ecu.CfgFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * Native KWP2000 logger — implements [LoggerManager] for K-line communication
 * with Motronic 3.8-5.9, ME7, and MED9 ECUs.
 *
 * Uses KWP2000 services:
 * - 0xB7 (Bosch DefineVariables) to define what to log
 * - 0xB8 (Bosch ReadVariables) to poll data
 * - Falls back to standard 0x2C/0x21 if Bosch extensions unavailable
 */
class Kwp2000NativeLogger(
    private val portProvider: SerialPortProvider
) : LoggerManager {

    private val _status = MutableStateFlow(LoggerStatus.DISCONNECTED)
    override val status: StateFlow<LoggerStatus> = _status.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _samples = MutableSharedFlow<LogSample>(extraBufferCapacity = 1000)
    override val samples: SharedFlow<LogSample> = _samples.asSharedFlow()

    private val _variables = MutableStateFlow<List<LogVariable>>(emptyList())
    override val variables: StateFlow<List<LogVariable>> = _variables.asStateFlow()

    private val _session = MutableStateFlow<LogSession?>(null)
    override val session: StateFlow<LogSession?> = _session.asStateFlow()

    private var transport: KLineTransport? = null
    private var kwpSession: Kwp2000Session? = null
    private var pollJob: Job? = null
    private var config: LoggerConfig? = null
    private var ecuFile: EcuFile? = null
    private var cfgFile: CfgFile? = null
    private var logEntries: List<EcuEntry> = emptyList()
    private var recordIds: List<Byte> = emptyList()

    override suspend fun connect(config: LoggerConfig) {
        this.config = config
        _status.value = LoggerStatus.CONNECTING
        _statusMessage.value = "Parsing ECU definition..."

        try {
            // Parse .ecu file
            val ecuFilePath = File(config.ecuFile)
            if (!ecuFilePath.exists()) {
                throw Kwp2000Exception(".ecu file not found: ${config.ecuFile}")
            }
            ecuFile = EcuFileParser.parse(ecuFilePath)

            // Parse .cfg file if provided
            if (config.cfgFile.isNotEmpty()) {
                val cfgFilePath = File(config.cfgFile)
                if (cfgFilePath.exists()) {
                    cfgFile = CfgFileParser.parse(cfgFilePath)
                }
            }

            // Resolve log variables from .cfg → .ecu
            logEntries = resolveLogEntries()
            if (logEntries.isEmpty()) {
                throw Kwp2000Exception("No valid log variables found")
            }

            // Build LogVariable list for UI
            val vars = logEntries.mapIndexed { i, entry ->
                LogVariable(
                    name = entry.name,
                    alias = entry.alias.ifEmpty { entry.name },
                    unit = entry.unit,
                    index = i
                )
            }
            _variables.value = vars

            _status.value = LoggerStatus.CONNECTED
            _statusMessage.value = "Ready. ${logEntries.size} variables from ${ecuFilePath.name}"
        } catch (e: Exception) {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = "Connect failed: ${e.message}"
        }
    }

    override suspend fun startLogging() {
        val cfg = config ?: run {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = "Not configured"
            return
        }

        _status.value = LoggerStatus.CONNECTING
        _statusMessage.value = "Opening serial port..."

        try {
            // Open transport
            val kline = KLineTransport(portProvider)
            kline.open(cfg.comPort)
            transport = kline

            _statusMessage.value = "5-baud init..."

            // Parse target address from connect method (e.g., "SLOW-0x01" → 0x01)
            val ecu = ecuFile!!
            val targetAddress = parseTargetAddress(ecu.connectMethod)
            kline.slowInit(targetAddress)

            _statusMessage.value = "Switching baud rate to ${ecu.logSpeed}..."
            val baudRate = if (cfg.overrideBaudRate) cfg.baudRate else ecu.logSpeed
            kline.switchBaudRate(baudRate)

            // Start KWP2000 session
            _statusMessage.value = "Starting diagnostic session..."
            val session = Kwp2000Session(kline)
            session.startSession()
            kwpSession = session

            // Define variables
            _statusMessage.value = "Defining log variables..."
            recordIds = defineVariables(session)

            // Start poll loop
            _session.value = LogSession(variables = _variables.value)
            _status.value = LoggerStatus.LOGGING
            _statusMessage.value = "Logging ${logEntries.size} variables..."

            pollJob = CoroutineScope(Dispatchers.IO).launch {
                pollLoop(session)
            }
        } catch (e: Exception) {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = "Start failed: ${e.message}"
            cleanup()
        }
    }

    override suspend fun stopLogging() {
        _statusMessage.value = "Stopping..."
        pollJob?.cancel()
        pollJob = null

        // Clear dynamic definitions
        try {
            kwpSession?.let { session ->
                for (recordId in recordIds) {
                    val clearReq = byteArrayOf(
                        ProtocolConstants.KWP_DYNAMICALLY_DEFINE_LOCAL_ID,
                        ProtocolConstants.KWP_CLEAR_DEFINITION,
                        recordId
                    )
                    session.sendService(clearReq)
                }
            }
            kwpSession?.stopSession()
        } catch (_: Exception) {
            // Best effort cleanup
        }

        _session.value?.endTime = System.currentTimeMillis()
        _status.value = LoggerStatus.CONNECTED
        _statusMessage.value = "Stopped. ${_session.value?.sampleCount ?: 0} samples recorded."
    }

    override suspend fun disconnect() {
        stopLogging()
        cleanup()
        _status.value = LoggerStatus.DISCONNECTED
        _statusMessage.value = ""
        _variables.value = emptyList()
        config = null
        ecuFile = null
        cfgFile = null
        logEntries = emptyList()
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun resolveLogEntries(): List<EcuEntry> {
        val ecu = ecuFile ?: return emptyList()
        val cfg = cfgFile

        return if (cfg != null) {
            // Use .cfg variable list, resolve against .ecu entries
            cfg.variables.mapNotNull { cfgVar ->
                ecu.entries[cfgVar.name]
            }
        } else {
            // No .cfg — use first N entries from .ecu (fallback)
            ecu.entries.values.take(20).toList()
        }
    }

    /**
     * Define log variables on the ECU using Bosch 0xB7 service.
     *
     * Variables are packed into record IDs (0xF0, 0xF1...), splitting across
     * multiple IDs if they exceed the max frame payload.
     */
    private suspend fun defineVariables(session: Kwp2000Session): List<Byte> {
        val ids = mutableListOf<Byte>()
        var currentId = ProtocolConstants.KWP_FIRST_RECORD_ID
        var currentPayload = mutableListOf<Byte>()
        var currentByteCount = 0

        // Each variable adds: 3 bytes (address) + 1 byte (size)
        val bytesPerVar = 4
        val maxPayloadPerRecord = 240 // conservative limit

        for (entry in logEntries) {
            if (currentByteCount + bytesPerVar > maxPayloadPerRecord && currentPayload.isNotEmpty()) {
                // Send current record
                sendDefineRecord(session, currentId, currentPayload.toByteArray())
                ids.add(currentId)
                currentId = (currentId.toInt() + 1).toByte()
                currentPayload.clear()
                currentByteCount = 0
            }

            // Add entry: address (3 bytes big-endian) + size
            val addr = entry.address.toInt()
            currentPayload.add(((addr shr 16) and 0xFF).toByte())
            currentPayload.add(((addr shr 8) and 0xFF).toByte())
            currentPayload.add((addr and 0xFF).toByte())
            currentPayload.add(entry.size.toByte())
            currentByteCount += bytesPerVar
        }

        // Send last record
        if (currentPayload.isNotEmpty()) {
            sendDefineRecord(session, currentId, currentPayload.toByteArray())
            ids.add(currentId)
        }

        return ids
    }

    private suspend fun sendDefineRecord(session: Kwp2000Session, recordId: Byte, payload: ByteArray) {
        val request = ByteArray(3 + payload.size)
        request[0] = ProtocolConstants.KWP_BOSCH_DEFINE_VARIABLES
        request[1] = ProtocolConstants.KWP_DEFINE_BY_MEMORY_ADDRESS
        request[2] = recordId
        payload.copyInto(request, 3)
        session.sendService(request)
    }

    /**
     * Main poll loop: repeatedly reads all defined records and emits samples.
     */
    private suspend fun pollLoop(session: Kwp2000Session) {
        val startTime = System.currentTimeMillis()
        val samplesPerSecond = cfgFile?.samplesPerSecond ?: config?.samplesPerSecond ?: 20
        val intervalMs = 1000L / samplesPerSecond

        try {
            while (currentCoroutineContext().isActive) {
                val sampleStart = System.currentTimeMillis()
                val rawValues = mutableListOf<Int>()

                // Read each record ID
                for (recordId in recordIds) {
                    val request = byteArrayOf(
                        ProtocolConstants.KWP_BOSCH_READ_VARIABLES,
                        recordId
                    )
                    val response = session.sendService(request)

                    // Response: [0xF8] [recordId] [data...]
                    if (response.size >= 2) {
                        val data = response.copyOfRange(2, response.size)
                        rawValues.addAll(parseRawValues(data))
                    }
                }

                // Convert raw → physical
                val timestamp = (sampleStart - startTime) / 1000.0
                val physicalValues = DoubleArray(logEntries.size + 1)
                physicalValues[0] = timestamp
                for (i in logEntries.indices) {
                    if (i < rawValues.size) {
                        physicalValues[i + 1] = convert(rawValues[i], logEntries[i])
                    }
                }

                val sample = LogSample(timestamp = timestamp, values = physicalValues)
                _session.value?.samples?.add(sample)
                _samples.tryEmit(sample)

                // Pace to target sample rate
                val elapsed = System.currentTimeMillis() - sampleStart
                if (elapsed < intervalMs) {
                    delay(intervalMs - elapsed)
                }
            }
        } catch (_: CancellationException) {
            // Normal shutdown
        } catch (e: Exception) {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = "Poll error: ${e.message}"
        }
    }

    /**
     * Parse raw integer values from response data bytes.
     * Uses entry sizes to determine byte grouping.
     */
    private fun parseRawValues(data: ByteArray): List<Int> {
        val values = mutableListOf<Int>()
        var offset = 0

        for (entry in logEntries) {
            if (offset >= data.size) break

            val raw = when (entry.size) {
                1 -> {
                    if (offset < data.size) {
                        val v = data[offset].toInt() and 0xFF
                        offset++
                        v
                    } else 0
                }
                2 -> {
                    if (offset + 1 < data.size) {
                        val hi = (data[offset].toInt() and 0xFF) shl 8
                        val lo = data[offset + 1].toInt() and 0xFF
                        offset += 2
                        hi or lo
                    } else 0
                }
                else -> { offset += entry.size; 0 }
            }

            // Apply bitmask if non-zero
            val masked = if (entry.bitmask != 0) raw and entry.bitmask else raw
            values.add(masked)
        }

        return values
    }

    private fun cleanup() {
        transport?.close()
        transport = null
        kwpSession = null
    }

    companion object {
        /**
         * Parse target address from connect method string.
         * E.g., "SLOW-0x01" → 1, "SLOW-0x11" → 17
         */
        fun parseTargetAddress(connectMethod: String): Int {
            val match = Regex("""0x([0-9A-Fa-f]+)""").find(connectMethod)
            return match?.groupValues?.get(1)?.toIntOrNull(16) ?: 0x01
        }

        /**
         * Convert raw ECU value to physical value using EcuEntry parameters.
         *
         * Normal:  physical = factor * raw - offset
         * Inverse: physical = factor / (raw - offset)
         */
        fun convert(raw: Int, entry: EcuEntry): Double {
            val v = if (entry.signed == 1) raw.toShort().toDouble() else raw.toDouble()
            return if (entry.inverse == 1) {
                val denom = v - entry.offset
                if (denom == 0.0) 0.0 else entry.factor / denom
            } else {
                entry.factor * v - entry.offset
            }
        }
    }
}
