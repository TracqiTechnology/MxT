package data.logger

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

/**
 * Track 1 logger implementation: spawns ME7Logger.exe as a child process
 * and parses its stdout CSV output in real-time.
 *
 * ME7Logger.exe with -R flag writes CSV data to stdout:
 *   Lines 1-N:  Comment lines starting with ";"
 *   Row 1:      Signal names (comma-separated)
 *   Row 2:      Units (comma-separated)
 *   Row 3:      Aliases (quoted, comma-separated)
 *   Data rows:  timestamp, val1, val2, ...
 *
 * This class handles:
 * - Spawning the process with correct arguments
 * - Parsing the 3-row header block
 * - Streaming data rows as LogSample events via SharedFlow
 * - Graceful shutdown via process destroy
 */
class Me7LoggerProcess : LoggerManager {

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

    private var process: Process? = null
    private var readerJob: Job? = null
    private var config: LoggerConfig? = null

    override suspend fun connect(config: LoggerConfig) {
        this.config = config
        _status.value = LoggerStatus.CONNECTING
        _statusMessage.value = "Validating configuration..."

        // Validate paths
        val me7loggerExe = File(config.me7loggerPath)
        if (!me7loggerExe.exists()) {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = "ME7Logger.exe not found: ${config.me7loggerPath}"
            return
        }

        val ecuFile = File(config.ecuFile)
        if (!ecuFile.exists()) {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = ".ecu file not found: ${config.ecuFile}"
            return
        }

        val cfgFile = File(config.cfgFile)
        if (!cfgFile.exists()) {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = ".cfg file not found: ${config.cfgFile}"
            return
        }

        _status.value = LoggerStatus.CONNECTED
        _statusMessage.value = "Ready. ECU: ${ecuFile.name}, Config: ${cfgFile.name}"
    }

    override suspend fun startLogging() {
        val cfg = config ?: run {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = "Not configured"
            return
        }

        _status.value = LoggerStatus.CONNECTING
        _statusMessage.value = "Starting ME7Logger..."

        try {
            val cmd = buildCommand(cfg)
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.directory(File(cfg.me7loggerPath).parentFile)

            process = pb.start()
            _session.value = LogSession(variables = emptyList())

            readerJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    parseOutputStream(reader)
                } catch (e: CancellationException) {
                    // Normal shutdown
                } catch (e: Exception) {
                    _status.value = LoggerStatus.ERROR
                    _statusMessage.value = "Read error: ${e.message}"
                }
            }
        } catch (e: Exception) {
            _status.value = LoggerStatus.ERROR
            _statusMessage.value = "Failed to start: ${e.message}"
        }
    }

    override suspend fun stopLogging() {
        _statusMessage.value = "Stopping..."
        readerJob?.cancel()
        readerJob = null
        process?.destroyForcibly()
        process = null

        _session.value?.let { it.endTime = System.currentTimeMillis() }
        _status.value = LoggerStatus.CONNECTED
        _statusMessage.value = "Stopped. ${_session.value?.sampleCount ?: 0} samples recorded."
    }

    override suspend fun disconnect() {
        stopLogging()
        _status.value = LoggerStatus.DISCONNECTED
        _statusMessage.value = ""
        _variables.value = emptyList()
        config = null
    }

    /**
     * Parse ME7Logger stdout CSV stream.
     * Header detection: first non-comment line with commas = signal names.
     */
    internal suspend fun parseOutputStream(reader: BufferedReader) {
        var headersParsed = false
        var namesRow: List<String> = emptyList()
        var unitsRow: List<String> = emptyList()
        var headerRowCount = 0

        reader.lineSequence().forEach { line ->
            if (!currentCoroutineContext().isActive) return

            // Skip comment lines
            if (line.startsWith(";") || line.startsWith("#") || line.isBlank()) {
                // Check for "Log started at:" to detect connection
                if (line.contains("Log started at:")) {
                    _status.value = LoggerStatus.LOGGING
                    _statusMessage.value = "Logging..."
                }
                return@forEach
            }

            if (!headersParsed) {
                headerRowCount++
                when (headerRowCount) {
                    1 -> {
                        // Signal names row
                        namesRow = line.split(",").map { it.trim() }
                    }
                    2 -> {
                        // Units row
                        unitsRow = line.split(",").map { it.trim() }
                    }
                    3 -> {
                        // Aliases row (quoted)
                        val aliases = line.split(",").map { it.trim().removeSurrounding("\"") }
                        val vars = namesRow.mapIndexed { i, name ->
                            LogVariable(
                                name = name,
                                alias = aliases.getOrElse(i) { "" },
                                unit = unitsRow.getOrElse(i) { "" },
                                index = i
                            )
                        }
                        _variables.value = vars
                        _session.value = LogSession(variables = vars)
                        _status.value = LoggerStatus.LOGGING
                        _statusMessage.value = "Logging ${vars.size} variables..."
                        headersParsed = true
                    }
                }
                return@forEach
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
                _samples.tryEmit(sample)
            }
        }

        // Process ended
        if (_status.value == LoggerStatus.LOGGING) {
            _session.value?.let { it.endTime = System.currentTimeMillis() }
            _status.value = LoggerStatus.CONNECTED
            _statusMessage.value = "Log complete. ${_session.value?.sampleCount ?: 0} samples."
        }
    }

    /**
     * Parse a CSV log file (offline mode — for replaying saved logs).
     */
    fun parseLogFile(file: File): LogSession {
        val reader = file.bufferedReader()
        val lines = reader.readLines()

        var headerRowCount = 0
        var namesRow = emptyList<String>()
        var unitsRow = emptyList<String>()
        var variables = emptyList<LogVariable>()
        val samples = mutableListOf<LogSample>()

        for (line in lines) {
            if (line.startsWith(";") || line.startsWith("#") || line.isBlank()) continue

            headerRowCount++
            when {
                headerRowCount == 1 -> namesRow = line.split(",").map { it.trim() }
                headerRowCount == 2 -> unitsRow = line.split(",").map { it.trim() }
                headerRowCount == 3 -> {
                    val aliases = line.split(",").map { it.trim().removeSurrounding("\"") }
                    variables = namesRow.mapIndexed { i, name ->
                        LogVariable(
                            name = name,
                            alias = aliases.getOrElse(i) { "" },
                            unit = unitsRow.getOrElse(i) { "" },
                            index = i
                        )
                    }
                }
                else -> {
                    val parts = line.split(",")
                    if (parts.size >= 2) {
                        val values = parts.map { it.trim().toDoubleOrNull() ?: 0.0 }.toDoubleArray()
                        samples.add(LogSample(timestamp = values.firstOrNull() ?: 0.0, values = values))
                    }
                }
            }
        }

        return LogSession(variables = variables, samples = samples)
    }

    private fun buildCommand(cfg: LoggerConfig): List<String> {
        val cmd = mutableListOf(cfg.me7loggerPath)
        if (cfg.comPort.isNotEmpty()) {
            cmd.addAll(listOf("-p", cfg.comPort))
        }
        cmd.addAll(listOf("-R", cfg.cfgFile))
        return cmd
    }
}
