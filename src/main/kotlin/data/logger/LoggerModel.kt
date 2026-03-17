package data.logger

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Logger mode: which backend is used for ECU communication.
 */
enum class LoggerMode {
    ME7LOGGER_EXE,   // Existing: spawn ME7Logger.exe (Windows)
    NATIVE_KWP2000,  // K-line via jSerialComm (Motronic, ME7, MED9)
    NATIVE_UDS       // CAN via SLCAN/PCAN + jSerialComm (MED17)
}

/**
 * A single loggable variable definition.
 */
data class LogVariable(
    val name: String,
    val alias: String,
    val unit: String,
    val index: Int
)

/**
 * A single sample of logged data (one row of values at a timestamp).
 */
data class LogSample(
    val timestamp: Double,
    val values: DoubleArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogSample) return false
        return timestamp == other.timestamp && values.contentEquals(other.values)
    }

    override fun hashCode(): Int = 31 * timestamp.hashCode() + values.contentHashCode()
}

/**
 * Status of the logger connection.
 */
enum class LoggerStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LOGGING,
    ERROR
}

/**
 * Connection mode for ME7Logger: COM port or FTDI adapter.
 */
enum class ConnectionType { COM_PORT, FTDI }

/**
 * FTDI device identification method.
 */
sealed class FtdiIdentifier {
    data class Serial(val value: String) : FtdiIdentifier()
    data class Description(val value: String) : FtdiIdentifier()
    data class Location(val value: String) : FtdiIdentifier()
    data object None : FtdiIdentifier()
}

/**
 * Connection configuration for all logger modes.
 */
data class LoggerConfig(
    // Logger mode
    val loggerMode: LoggerMode = LoggerMode.ME7LOGGER_EXE,
    // Existing (shared)
    val comPort: String = "",
    val baudRate: Int = 56000,
    val ecuFile: String = "",
    val cfgFile: String = "",
    val me7loggerPath: String = "",
    val samplesPerSecond: Int = 20,
    // Connection type (ME7Logger.exe mode)
    val connectionType: ConnectionType = ConnectionType.COM_PORT,
    val ftdiIdentifier: FtdiIdentifier = FtdiIdentifier.None,
    // Override gates (when false, values are stored but not passed to ME7Logger)
    val overrideSamplesPerSecond: Boolean = false,
    val overrideBaudRate: Boolean = false,
    // Timestamps (ME7Logger.exe mode)
    val syncTimestamp: Boolean = false,
    val absoluteTimestamps: Boolean = false,
    val millisecondTimestamps: Boolean = false,
    // Output
    val outputLogFile: String = "",
    val realTimeWrite: Boolean = false,
    // Native UDS mode (MED17)
    val canAdapterType: data.logger.uds.CanAdapterType = data.logger.uds.CanAdapterType.SLCAN,
    val canBitrate: Int = 500_000,
    val canTxId: Int = 0x7E0,
    val canRxId: Int = 0x7E8
)

/**
 * A complete logging session (recorded data).
 */
data class LogSession(
    val variables: List<LogVariable>,
    val samples: MutableList<LogSample> = mutableListOf(),
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long = 0L
) {
    val duration: Double get() = if (samples.isEmpty()) 0.0
        else samples.last().timestamp - samples.first().timestamp

    val sampleCount: Int get() = samples.size
}

/**
 * Interface for logger implementations.
 * Track 1: Me7LoggerProcess (spawns ME7Logger.exe)
 * Track 2 (future): KWP2000NativeLogger (jSerialComm)
 */
interface LoggerManager {
    val status: StateFlow<LoggerStatus>
    val statusMessage: StateFlow<String>
    val samples: SharedFlow<LogSample>
    val variables: StateFlow<List<LogVariable>>
    val session: StateFlow<LogSession?>

    suspend fun connect(config: LoggerConfig)
    suspend fun startLogging()
    suspend fun stopLogging()
    suspend fun disconnect()
}
