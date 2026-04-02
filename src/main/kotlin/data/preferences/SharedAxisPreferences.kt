package data.preferences

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory shared state for Y-axis (RPM) synchronization between
 * KFMIOP and KFMIRL screens. When one screen edits the RPM axis,
 * the other can offer to apply the same breakpoints.
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

    fun setKfmiopEditedYAxis(axis: Array<Double>?) {
        _kfmiopEditedYAxis.tryEmit(axis)
    }

    fun setKfmirlEditedYAxis(axis: Array<Double>?) {
        _kfmirlEditedYAxis.tryEmit(axis)
    }
}
