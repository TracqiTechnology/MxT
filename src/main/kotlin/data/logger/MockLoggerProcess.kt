package data.logger

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Mock logger that replays CSV log files as if they were live ME7Logger data.
 *
 * Reads real ME7Logger CSV fixtures (from example/med9/logs/ or any CSV) and
 * emits LogSample events at configurable speed. Useful for:
 * - UI development without hardware
 * - Integration testing of the logger data pipeline
 * - Demo mode
 *
 * @param replayDelayMs delay between samples (0 = instant, 50 = ~20 samples/sec)
 */
class MockLoggerProcess(
    private val replayDelayMs: Long = 0L
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

    private var logFile: File? = null
    private var replayJob: Job? = null

    /**
     * Connect by specifying the CSV log file to replay.
     * Uses cfgFile from config as the log file path.
     */
    override suspend fun connect(config: LoggerConfig) {
        _status.value = LoggerStatus.CONNECTING
        _statusMessage.value = "Loading log file..."

        val file = File(config.cfgFile)
        if (!file.exists()) {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = "Log file not found: ${config.cfgFile}"
            return
        }

        logFile = file
        _status.value = LoggerStatus.CONNECTED
        _statusMessage.value = "Ready to replay: ${file.name}"
    }

    /**
     * Connect directly with a File (convenience for tests).
     */
    suspend fun connectWithFile(file: File) {
        connect(LoggerConfig(cfgFile = file.absolutePath))
    }

    override suspend fun startLogging() {
        val file = logFile ?: run {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = "No log file loaded"
            return
        }

        _status.value = LoggerStatus.CONNECTING
        _statusMessage.value = "Parsing log header..."

        if (replayDelayMs == 0L) {
            // Instant replay — run synchronously for testability
            try {
                replayFile(file)
            } catch (e: Exception) {
                _status.value = LoggerStatus.ERROR
                _statusMessage.value = "Replay error: ${e.message}"
            }
        } else {
            // Timed replay — run in background
            replayJob = CoroutineScope(Dispatchers.Default).launch {
                try {
                    replayFile(file)
                } catch (e: CancellationException) {
                    // Normal stop
                } catch (e: Exception) {
                    _status.value = LoggerStatus.ERROR
                    _statusMessage.value = "Replay error: ${e.message}"
                }
            }
        }
    }

    override suspend fun stopLogging() {
        replayJob?.cancel()
        replayJob = null
        _session.value?.let { it.endTime = System.currentTimeMillis() }
        _status.value = LoggerStatus.CONNECTED
        _statusMessage.value = "Stopped. ${_session.value?.sampleCount ?: 0} samples replayed."
    }

    override suspend fun disconnect() {
        stopLogging()
        _status.value = LoggerStatus.DISCONNECTED
        _statusMessage.value = ""
        _variables.value = emptyList()
        _session.value = null
        logFile = null
    }

    /**
     * Replay a CSV log file, emitting samples through SharedFlow.
     * Handles ME7Logger format: metadata lines precede a 3-row CSV header.
     * The names row is detected by starting with "TIME" (case-insensitive).
     */
    private suspend fun replayFile(file: File) {
        val reader = file.bufferedReader()
        val lines = reader.readLines()

        var headerRowCount = 0
        var namesRow = emptyList<String>()
        var unitsRow = emptyList<String>()
        var variables = emptyList<LogVariable>()
        var headersParsed = false

        for (line in lines) {
            if (!currentCoroutineContext().isActive) return

            if (line.isBlank()) continue

            if (!headersParsed) {
                // Detect the CSV names row: starts with TIME/TimeStamp and has commas
                val trimmed = line.trim()
                if (headerRowCount == 0 && !trimmed.startsWith("TIME", ignoreCase = true)) {
                    // Metadata line — skip
                    continue
                }

                headerRowCount++
                when (headerRowCount) {
                    1 -> namesRow = line.split(",").map { it.trim() }
                    2 -> unitsRow = line.split(",").map { it.trim() }
                    3 -> {
                        val aliases = line.split(",").map { it.trim().removeSurrounding("\"") }
                        variables = namesRow.mapIndexed { i, name ->
                            LogVariable(
                                name = name,
                                alias = aliases.getOrElse(i) { "" },
                                unit = unitsRow.getOrElse(i) { "" },
                                index = i
                            )
                        }
                        _variables.value = variables
                        _session.value = LogSession(variables = variables)
                        _status.value = LoggerStatus.LOGGING
                        _statusMessage.value = "Replaying ${variables.size} variables..."
                        headersParsed = true
                    }
                }
                continue
            }

            // Data row
            val parts = line.split(",")
            if (parts.size >= 2) {
                val values = parts.map { it.trim().toDoubleOrNull() ?: 0.0 }.toDoubleArray()
                val sample = LogSample(
                    timestamp = values.firstOrNull() ?: 0.0,
                    values = values
                )
                _session.value?.samples?.add(sample)
                _samples.emit(sample)

                if (replayDelayMs > 0) {
                    delay(replayDelayMs)
                }
            }
        }

        // Replay complete
        _session.value?.let { it.endTime = System.currentTimeMillis() }
        _status.value = LoggerStatus.CONNECTED
        _statusMessage.value = "Replay complete. ${_session.value?.sampleCount ?: 0} samples."
    }
}
