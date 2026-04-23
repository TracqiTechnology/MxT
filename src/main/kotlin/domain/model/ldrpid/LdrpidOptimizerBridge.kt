package domain.model.ldrpid

import domain.math.map.Map3d
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory bridge for transferring KFLDRL/KFLDIMX data between the
 * LDRPID and Optimizer screens. When the user exports from LDRPID,
 * the Optimizer can import the result as a comparison baseline.
 *
 * Pattern mirrors [data.preferences.SharedAxisPreferences].
 */
object LdrpidOptimizerBridge {

    private val _kfldrlFlow = MutableSharedFlow<Map3d>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val kfldrlFlow: SharedFlow<Map3d> = _kfldrlFlow.asSharedFlow()

    private val _kfldimxFlow = MutableSharedFlow<Map3d>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val kfldimxFlow: SharedFlow<Map3d> = _kfldimxFlow.asSharedFlow()

    fun exportKfldrl(map: Map3d) {
        _kfldrlFlow.tryEmit(Map3d(map))
    }

    fun exportKfldimx(map: Map3d) {
        _kfldimxFlow.tryEmit(Map3d(map))
    }

    fun importKfldrl(): Map3d? = _kfldrlFlow.replayCache.firstOrNull()

    fun importKfldimx(): Map3d? = _kfldimxFlow.replayCache.firstOrNull()

    /** Clear all cached data (useful for testing). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun clear() {
        _kfldrlFlow.resetReplayCache()
        _kfldimxFlow.resetReplayCache()
    }
}
