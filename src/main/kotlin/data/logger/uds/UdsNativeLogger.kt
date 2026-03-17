package data.logger.uds

import data.logger.*
import data.logger.kwp2000.Kwp2000NativeLogger
import data.logger.protocol.ProtocolConstants
import data.logger.protocol.SerialPortProvider
import data.parser.a2l.EcuEntry
import data.parser.ecu.CfgFileParser
import data.parser.ecu.EcuFile
import data.parser.ecu.EcuFileParser
import data.parser.ecu.CfgFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * Native UDS logger — implements [LoggerManager] for CAN communication
 * with MED17 ECUs.
 *
 * Uses UDS services:
 * - 0x2C (DynamicallyDefineDataIdentifier) to define what to log
 * - 0x22 (ReadDataByIdentifier) to poll data
 */
class UdsNativeLogger(
    private val canTransport: CanTransport
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

    private var isoTp: IsoTpTransport? = null
    private var udsSession: UdsSession? = null
    private var pollJob: Job? = null
    private var config: LoggerConfig? = null
    private var ecuFile: EcuFile? = null
    private var cfgFile: CfgFile? = null
    private var logEntries: List<EcuEntry> = emptyList()
    private var dynamicDids: List<Int> = emptyList()

    override suspend fun connect(config: LoggerConfig) {
        this.config = config
        _status.value = LoggerStatus.CONNECTING
        _statusMessage.value = "Parsing ECU definition..."

        try {
            // Parse .ecu file
            val ecuFilePath = File(config.ecuFile)
            if (!ecuFilePath.exists()) {
                throw UdsException(".ecu file not found: ${config.ecuFile}")
            }
            ecuFile = EcuFileParser.parse(ecuFilePath)

            // Parse .cfg file if provided
            if (config.cfgFile.isNotEmpty()) {
                val cfgFilePath = File(config.cfgFile)
                if (cfgFilePath.exists()) {
                    cfgFile = CfgFileParser.parse(cfgFilePath)
                }
            }

            // Resolve log variables
            logEntries = resolveLogEntries()
            if (logEntries.isEmpty()) {
                throw UdsException("No valid log variables found")
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
        _statusMessage.value = "Opening CAN adapter..."

        try {
            // Open CAN transport
            val canConfig = CanConfig(
                portName = cfg.comPort,
                bitrate = cfg.baudRate.takeIf { it > 0 } ?: 500_000,
                txId = 0x7E0,
                rxId = 0x7E8
            )
            canTransport.open(canConfig)

            // Create ISO-TP layer
            val isotp = IsoTpTransport(canTransport, canConfig.txId, canConfig.rxId)
            isoTp = isotp

            // Start UDS session
            _statusMessage.value = "Starting UDS session..."
            val session = UdsSession(isotp)
            session.startSession()
            udsSession = session

            // Define dynamic DIDs
            _statusMessage.value = "Defining log variables..."
            dynamicDids = defineVariables(session)

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

        // Clear dynamic DIDs
        try {
            udsSession?.let { session ->
                for (did in dynamicDids) {
                    val clearReq = byteArrayOf(
                        ProtocolConstants.UDS_DYNAMICALLY_DEFINE_DATA_ID,
                        ProtocolConstants.UDS_CLEAR_DYNAMICALLY_DEFINED,
                        (did shr 8).toByte(),
                        (did and 0xFF).toByte()
                    )
                    session.sendService(clearReq)
                }
            }
            udsSession?.stopSession()
        } catch (_: Exception) {
            // Best effort
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
            cfg.variables.mapNotNull { cfgVar -> ecu.entries[cfgVar.name] }
        } else {
            ecu.entries.values.take(20).toList()
        }
    }

    /**
     * Define dynamic DIDs on the ECU using UDS 0x2C (defineByMemoryAddress).
     * Returns list of DID values that were defined.
     */
    private suspend fun defineVariables(session: UdsSession): List<Int> {
        val dids = mutableListOf<Int>()
        var currentDid = ProtocolConstants.UDS_DYNAMIC_DID_BASE
        var currentPayload = mutableListOf<Byte>()
        var currentByteCount = 0

        // addressAndLengthFormatIdentifier: 0x14 = 1 byte memorySize + 4 bytes memoryAddress
        val addrLenFormat: Byte = 0x14

        // Each variable: 4 bytes address + 1 byte size = 5 bytes
        val bytesPerVar = 5
        val maxPayloadPerDid = 200

        for (entry in logEntries) {
            if (currentByteCount + bytesPerVar > maxPayloadPerDid && currentPayload.isNotEmpty()) {
                sendDefineDid(session, currentDid, addrLenFormat, currentPayload.toByteArray())
                dids.add(currentDid)
                currentDid++
                currentPayload.clear()
                currentByteCount = 0
            }

            // Memory address (4 bytes big-endian)
            val addr = entry.address.toInt()
            currentPayload.add(entry.size.toByte())  // memorySize
            currentPayload.add(((addr shr 24) and 0xFF).toByte())
            currentPayload.add(((addr shr 16) and 0xFF).toByte())
            currentPayload.add(((addr shr 8) and 0xFF).toByte())
            currentPayload.add((addr and 0xFF).toByte())
            currentByteCount += bytesPerVar
        }

        if (currentPayload.isNotEmpty()) {
            sendDefineDid(session, currentDid, addrLenFormat, currentPayload.toByteArray())
            dids.add(currentDid)
        }

        return dids
    }

    private suspend fun sendDefineDid(session: UdsSession, did: Int, addrLenFormat: Byte, payload: ByteArray) {
        val request = ByteArray(4 + payload.size)
        request[0] = ProtocolConstants.UDS_DYNAMICALLY_DEFINE_DATA_ID
        request[1] = ProtocolConstants.UDS_DEFINE_BY_MEMORY_ADDRESS
        request[2] = (did shr 8).toByte()
        request[3] = (did and 0xFF).toByte()
        // addressAndLengthFormatIdentifier is part of payload structure
        // Simplified: just append raw payload
        payload.copyInto(request, 4)
        session.sendService(request)
    }

    /**
     * Main poll loop: reads all defined DIDs and emits samples.
     */
    private suspend fun pollLoop(session: UdsSession) {
        val startTime = System.currentTimeMillis()
        val samplesPerSecond = cfgFile?.samplesPerSecond ?: config?.samplesPerSecond ?: 20
        val intervalMs = 1000L / samplesPerSecond

        try {
            while (currentCoroutineContext().isActive) {
                val sampleStart = System.currentTimeMillis()
                val rawValues = mutableListOf<Int>()

                for (did in dynamicDids) {
                    val request = byteArrayOf(
                        ProtocolConstants.UDS_READ_DATA_BY_ID,
                        (did shr 8).toByte(),
                        (did and 0xFF).toByte()
                    )
                    val response = session.sendService(request)

                    // Response: [0x62] [DID_hi] [DID_lo] [data...]
                    if (response.size >= 3) {
                        val data = response.copyOfRange(3, response.size)
                        rawValues.addAll(parseRawValues(data))
                    }
                }

                // Convert raw → physical
                val timestamp = (sampleStart - startTime) / 1000.0
                val physicalValues = DoubleArray(logEntries.size + 1)
                physicalValues[0] = timestamp
                for (i in logEntries.indices) {
                    if (i < rawValues.size) {
                        physicalValues[i + 1] = Kwp2000NativeLogger.convert(rawValues[i], logEntries[i])
                    }
                }

                val sample = LogSample(timestamp = timestamp, values = physicalValues)
                _session.value?.samples?.add(sample)
                _samples.tryEmit(sample)

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

            val masked = if (entry.bitmask != 0) raw and entry.bitmask else raw
            values.add(masked)
        }

        return values
    }

    private fun cleanup() {
        canTransport.close()
        isoTp = null
        udsSession = null
    }
}
