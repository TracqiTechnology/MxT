package data.parser.a2l

import data.preferences.a2l.A2lFilePreferences
import data.parser.xdf.TableDefinition
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Singleton that watches [A2lFilePreferences] and publishes a
 * [StateFlow<List<TableDefinition>>] derived from the A2L's CHARACTERISTIC blocks.
 *
 * This makes A2L calibration maps available as a BinParser source alongside
 * XDF, CSV, and KP definitions.  Priority in BinParser: XDF > CSV > KP > A2L.
 *
 * Only VALUE, CURVE, and MAP types are converted; CUBOID and higher are skipped.
 * MED9 data is Big-Endian (PowerPC/MPC562); lsbFirst = false is applied to all axes.
 */
object A2lCalibrationParser {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _tableDefinitions = MutableStateFlow<List<TableDefinition>>(emptyList())
    val tableDefinitions: StateFlow<List<TableDefinition>> = _tableDefinitions.asStateFlow()

    /** Non-null when the last A2L parse attempt failed. */
    private val _parseError = MutableStateFlow<String?>(null)
    val parseError: StateFlow<String?> = _parseError.asStateFlow()

    /** Number of CHARACTERISTICs parsed from the last A2L file. */
    private val _characteristicCount = MutableStateFlow(0)
    val characteristicCount: StateFlow<Int> = _characteristicCount.asStateFlow()

    fun init() {
        scope.launch {
            A2lFilePreferences.file.collect { file ->
                if (!file.exists() || !file.isFile) {
                    _tableDefinitions.value = emptyList()
                    _characteristicCount.value = 0
                    _parseError.value = null
                    return@collect
                }
                try {
                    _parseError.value = null
                    val result = A2lParser.parse(file)
                    val defs = result.toTableDefinitions()
                    _tableDefinitions.value = defs
                    _characteristicCount.value = result.characteristics.size
                } catch (e: Exception) {
                    e.printStackTrace()
                    _parseError.value = e.message ?: "Unknown error parsing A2L"
                    _tableDefinitions.value = emptyList()
                    _characteristicCount.value = 0
                }
            }
        }
    }
}
