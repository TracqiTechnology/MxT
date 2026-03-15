package data.logger

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
 * Connection configuration for ME7Logger.
 */
data class LoggerConfig(
    val comPort: String = "",
    val baudRate: Int = 56000,
    val ecuFile: String = "",
    val cfgFile: String = "",
    val me7loggerPath: String = "",
    val samplesPerSecond: Int = 20
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
