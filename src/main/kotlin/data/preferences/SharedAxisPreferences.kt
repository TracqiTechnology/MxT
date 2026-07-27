package data.preferences

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import domain.math.map.Map3d
import data.preferences.bin.BinFilePreferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.platform.EcuPlatformPreference

/**
 * In-memory shared state for axis synchronization between calibration screens.
 * - Y-axis (RPM): KFMIOP ↔ KFMIRL, and KFMIOP → KFZW/KFZWOP
 * - X-axis (Load): KFMIOP → KFZW/KFZWOP
 */
object SharedAxisPreferences {
    private data class WorkspaceContext(
        val binPath: String,
        val binLength: Long,
        val binModified: Long,
        val platform: String,
        val kfmiopMapName: String?
    )

    private data class ScopedValue<T>(
        val context: WorkspaceContext,
        val value: T
    )

    private fun currentContext(): WorkspaceContext {
        val bin = BinFilePreferences.file.value.absoluteFile
        return WorkspaceContext(
            binPath = bin.path,
            binLength = if (bin.isFile) bin.length() else 0L,
            binModified = if (bin.isFile) bin.lastModified() else 0L,
            platform = EcuPlatformPreference.platform.name,
            kfmiopMapName = KfmiopPreferences.getSelectedMap()?.first?.tableName
        )
    }

    private val _kfmiopEditedYAxis = MutableSharedFlow<ScopedValue<Array<Double>>?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val kfmiopEditedYAxis: Flow<Array<Double>?> =
        _kfmiopEditedYAxis.map { scoped ->
            scoped?.takeIf { it.context == currentContext() }?.value?.copyOf()
        }

    private val _kfmirlEditedYAxis = MutableSharedFlow<ScopedValue<Array<Double>>?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val kfmirlEditedYAxis: Flow<Array<Double>?> =
        _kfmirlEditedYAxis.map { scoped ->
            scoped?.takeIf { it.context == currentContext() }?.value?.copyOf()
        }

    private val _kfmiopEditedXAxis = MutableSharedFlow<ScopedValue<Array<Double>>?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Load (X) axis edited in KFMIOP — consumed by KFZW, KFZWOP */
    val kfmiopEditedXAxis: Flow<Array<Double>?> =
        _kfmiopEditedXAxis.map { scoped ->
            scoped?.takeIf { it.context == currentContext() }?.value?.copyOf()
        }

    private val _kfmiopCalculatedMap = MutableSharedFlow<ScopedValue<Map3d>?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Complete calculated KFMIOP result that KFMIRL can consume without a BIN write/reload. */
    val kfmiopCalculatedMap: Flow<Map3d?> =
        _kfmiopCalculatedMap.map { scoped ->
            scoped?.takeIf { it.context == currentContext() }?.value?.let(::Map3d)
        }

    fun setKfmiopEditedYAxis(axis: Array<Double>?) {
        _kfmiopEditedYAxis.tryEmit(axis?.let { ScopedValue(currentContext(), it.copyOf()) })
    }

    fun setKfmirlEditedYAxis(axis: Array<Double>?) {
        _kfmirlEditedYAxis.tryEmit(axis?.let { ScopedValue(currentContext(), it.copyOf()) })
    }

    fun setKfmiopEditedXAxis(axis: Array<Double>?) {
        _kfmiopEditedXAxis.tryEmit(axis?.let { ScopedValue(currentContext(), it.copyOf()) })
    }

    fun setKfmiopCalculatedMap(map: Map3d?) {
        _kfmiopCalculatedMap.tryEmit(map?.let { ScopedValue(currentContext(), Map3d(it)) })
    }

    /** Invalidate replayed workspace data when the BIN or ECU platform changes. */
    fun clear() {
        _kfmiopEditedYAxis.tryEmit(null)
        _kfmirlEditedYAxis.tryEmit(null)
        _kfmiopEditedXAxis.tryEmit(null)
        _kfmiopCalculatedMap.tryEmit(null)
    }
}
