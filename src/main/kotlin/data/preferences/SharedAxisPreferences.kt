package data.preferences

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory shared state for axis synchronization between calibration screens.
 * - Y-axis (RPM): KFMIOP ↔ KFMIRL, and KFMIOP → KFZW/KFZWOP
 * - X-axis (Load): KFMIOP → KFZW/KFZWOP
 */
object SharedAxisPreferences {
    private val _kfmiopEditedYAxis = MutableSharedFlow<Array<Double>?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val kfmiopEditedYAxis: SharedFlow<Array<Double>?> = _kfmiopEditedYAxis.asSharedFlow()

    private val _kfmirlEditedYAxis = MutableSharedFlow<Array<Double>?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val kfmirlEditedYAxis: SharedFlow<Array<Double>?> = _kfmirlEditedYAxis.asSharedFlow()

    private val _kfmiopEditedXAxis = MutableSharedFlow<Array<Double>?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Load (X) axis edited in KFMIOP — consumed by KFZW, KFZWOP */
    val kfmiopEditedXAxis: SharedFlow<Array<Double>?> = _kfmiopEditedXAxis.asSharedFlow()

    fun setKfmiopEditedYAxis(axis: Array<Double>?) {
        _kfmiopEditedYAxis.tryEmit(axis)
    }

    fun setKfmirlEditedYAxis(axis: Array<Double>?) {
        _kfmirlEditedYAxis.tryEmit(axis)
    }

    fun setKfmiopEditedXAxis(axis: Array<Double>?) {
        _kfmiopEditedXAxis.tryEmit(axis)
    }
}
