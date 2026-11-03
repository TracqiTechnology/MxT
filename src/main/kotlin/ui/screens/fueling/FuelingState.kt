package ui.screens.fueling

import androidx.compose.runtime.*
import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.preferences.mlhfm.MlhfmPreferences
import domain.math.map.Map3d
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/** Resolve the exact selection again when either the BIN or map preference changes. */
@Composable
internal fun rememberMlhfmMap(): State<Pair<TableDefinition, Map3d>?> =
    produceState(MlhfmPreferences.getSelectedMap()) {
        merge(
            BinParser.mapList.map { Unit },
            MlhfmPreferences.mapChanged.map { Unit }
        ).collect { value = MlhfmPreferences.getSelectedMap() }
    }

/** Invalidate edited results immediately and cancel old work when any input changes. */
@Composable
internal fun <T> rememberFuelingCalculation(
    vararg inputs: Any?,
    calculate: suspend () -> T?
): MutableState<T?> {
    val result = remember(*inputs) { mutableStateOf<T?>(null) }
    LaunchedEffect(result) {
        result.value = withContext(Dispatchers.Default) { calculate() }
    }
    return result
}
