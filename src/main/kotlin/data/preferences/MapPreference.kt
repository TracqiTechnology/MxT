package data.preferences

import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import domain.math.map.Map3d
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.prefs.Preferences

open class MapPreference(
    private val tableTitlePreference: String,
    private val tableDescriptionPreference: String,
    private val tableUnitPreference: String
) {
    internal data class State(
        val title: String?,
        val description: String?,
        val unit: String?
    )

    private val prefs = Preferences.userNodeForPackage(MapPreference::class.java)

    private val _mapChanged = MutableSharedFlow<Pair<TableDefinition, Map3d>?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val mapChanged: SharedFlow<Pair<TableDefinition, Map3d>?> = _mapChanged.asSharedFlow()

    init { MapPreferenceManager.add(this) }

    fun clear() {
        runCatching {
            prefs.remove(tableTitlePreference)
            prefs.remove(tableDescriptionPreference)
            prefs.remove(tableUnitPreference)
        }
        _mapChanged.tryEmit(null)
    }

    fun getSelectedMap(): Pair<TableDefinition, Map3d>? {
        val mapList = BinParser.mapList.value
        val mapTitle = prefs.get(tableTitlePreference, "")
        val mapDescription = prefs.get(tableDescriptionPreference, "")
        val mapUnit = prefs.get(tableUnitPreference, "")

        if (mapTitle.isEmpty() && mapDescription.isEmpty()) return null

        return mapList.firstOrNull { (def, _) ->
            mapTitle == def.tableName && mapDescription == def.tableDescription && mapUnit == def.zAxis.unit
        }
    }

    fun setSelectedMap(tableDefinition: TableDefinition?) {
        if (tableDefinition != null) {
            prefs.put(tableTitlePreference, tableDefinition.tableName)
            prefs.put(tableDescriptionPreference, tableDefinition.tableDescription)
            prefs.put(tableUnitPreference, tableDefinition.zAxis.unit)
        } else {
            prefs.put(tableTitlePreference, "")
            prefs.put(tableDescriptionPreference, "")
            prefs.put(tableUnitPreference, "")
        }
        _mapChanged.tryEmit(getSelectedMap())
    }

    internal fun snapshotState(): State = State(
        prefs.get(tableTitlePreference, null),
        prefs.get(tableDescriptionPreference, null),
        prefs.get(tableUnitPreference, null)
    )

    internal fun restoreState(state: State) {
        fun restore(key: String, value: String?) {
            if (value == null) prefs.remove(key) else prefs.put(key, value)
        }
        restore(tableTitlePreference, state.title)
        restore(tableDescriptionPreference, state.description)
        restore(tableUnitPreference, state.unit)
        _mapChanged.tryEmit(getSelectedMap())
    }
}
