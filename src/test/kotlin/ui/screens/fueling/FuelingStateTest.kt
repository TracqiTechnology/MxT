package ui.screens.fueling

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import data.parser.bin.BinParser
import data.preferences.mlhfm.MlhfmPreferences
import domain.math.map.Map3d
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ui.screens.me7.Me7TestBase
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class FuelingStateTest : Me7TestBase() {
    @Test
    fun `invalid horizontal voltage labels explain why correction is unavailable`() = runComposeUiTest {
        val original = MlhfmPreferences.getSelectedMap()!!
        val invalid = Map3d(arrayOf(0.0, 0.0), emptyArray(), arrayOf(arrayOf(10.0, 20.0)))
        BinParser.setMapListForTesting(listOf(original.first to invalid))
        setContent { ui.screens.closedloop.ClosedLoopScreen() }
        onNodeWithText("MLHFM voltage breakpoints must be finite and strictly increasing. Check for missing XDF labels.").assertExists()
        onNodeWithText("Load Logs").assertIsNotEnabled()
        onNodeWithText("Correction").assertIsNotEnabled()
    }

    @Test
    fun `closed loop opens with a configured map before logs are loaded`() = runComposeUiTest {
        setContent { ui.screens.closedloop.ClosedLoopScreen() }
        onNodeWithText("Load Logs").assertIsEnabled()
        onNodeWithText("Correction").assertIsNotEnabled()
    }

    @Test
    fun `open loop explains missing map and keeps logs accessible`() = runComposeUiTest {
        MlhfmPreferences.clear()
        setContent { ui.screens.openloop.OpenLoopScreen() }
        onNodeWithText("ME7 Logs").assertIsEnabled()
        onNodeWithText("Load a BIN and select its MLHFM map in Configuration → Map Definitions.").assertExists()
    }

    @ParameterizedTest
    @ValueSource(strings = ["map,me7,afr", "afr,map,me7", "me7,afr,map", "me7,map,afr", "map,afr,me7", "afr,me7,map"])
    fun `all input orders compute and clearing input invalidates edits`(order: String) = runComposeUiTest {
        val inputs = listOf(mutableStateOf(false), mutableStateOf(false), mutableStateOf(false))
        lateinit var result: MutableState<Int?>
        setContent {
            val ready = inputs.map { it.value }
            result = rememberFuelingCalculation(*ready.toTypedArray()) {
                if (ready.all { it }) 42 else null
            }
        }
        for (name in order.split(',')) {
            runOnIdle { inputs[listOf("map", "me7", "afr").indexOf(name)].value = true }
        }
        waitUntil { result.value == 42 }
        runOnIdle { result.value = 99; inputs[0].value = false }
        waitForIdle()
        runOnIdle { assertNull(result.value) }
        runOnIdle { inputs[0].value = true }
        waitUntil { result.value == 42 }
    }

    @Test
    fun `late calculation cannot replace result for newer inputs`() = runComposeUiTest {
        val oldWork = CompletableDeferred<Int>()
        var revision by mutableStateOf(1)
        lateinit var result: MutableState<Int?>
        setContent {
            val current = revision
            result = rememberFuelingCalculation(current) {
                if (current == 1) oldWork.await() else 20
            }
        }
        waitForIdle()
        runOnIdle { revision = 2 }
        waitUntil { result.value == 20 }
        oldWork.complete(10)
        waitForIdle()
        runOnIdle { assertEquals(20, result.value) }
    }

    @Test
    fun `map selection observes exact definition and refreshed BIN values`() = runComposeUiTest {
        val original = MlhfmPreferences.getSelectedMap()!!
        // Same title, different description: resolving by title alone selects the wrong map.
        val other = original.first.copy(tableDescription = "Other sensor") to Map3d(original.second)
        BinParser.setMapListForTesting(listOf(original, other))
        var selected: Pair<data.parser.xdf.TableDefinition, Map3d>? = null
        setContent { selected = rememberMlhfmMap().value }
        waitUntil { selected == original }
        runOnIdle { MlhfmPreferences.setSelectedMap(other.first) }
        waitUntil { selected == other }
        val refreshed = other.first to Map3d(other.second).apply { zAxis[0][0] += 1.0 }
        runOnIdle { BinParser.setMapListForTesting(listOf(original, refreshed)) }
        waitUntil { selected == refreshed }
        runOnIdle { MlhfmPreferences.clear() }
        waitUntil { selected == null }
    }
}
