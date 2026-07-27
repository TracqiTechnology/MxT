package data.preferences

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object MapPreferenceManager {
    internal data class Snapshot(
        val values: List<Pair<MapPreference, MapPreference.State>>
    )

    private val preferences = mutableListOf<MapPreference>()
    private val _onClear = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val onClear: SharedFlow<Boolean> = _onClear.asSharedFlow()

    fun add(preference: MapPreference) { preferences.add(preference) }

    fun clear() {
        preferences.forEach { it.clear() }
        _onClear.tryEmit(true)
    }

    internal fun snapshot(): Snapshot =
        Snapshot(preferences.map { it to it.snapshotState() })

    internal fun restore(snapshot: Snapshot) {
        snapshot.values.forEach { (preference, state) -> preference.restoreState(state) }
    }
}
