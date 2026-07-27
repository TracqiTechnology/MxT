package ui.screens.axisrescaler

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import domain.math.map.Map3d
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AxisRescalerComposeE2ETest {

    @Test
    fun editingOutputAxisAndRescalingRendersExactInterpolatedValues() = runComposeUiTest {
        setContent {
            AxisRescalerScreen(
                preloadedMap = Map3d(
                    xAxis = arrayOf(10.0, 20.0, 30.0),
                    yAxis = arrayOf(1000.0, 2000.0, 3000.0),
                    zAxis = arrayOf(
                        arrayOf(1.0, 2.0, 3.0),
                        arrayOf(4.0, 5.0, 6.0),
                        arrayOf(7.0, 8.0, 9.0)
                    )
                )
            )
        }

        val outputMiddleX = onNodeWithTag("axis-output-x_cell_0_1")
        outputMiddleX.performClick()
        outputMiddleX.performClick()
        val activeEditor = onNode(hasSetTextAction() and isFocused())
        activeEditor.performTextReplacement("15")
        activeEditor.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        onNodeWithTag("axis-rescale").performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag("axis-output-map-cell-0-1").assertTextEquals("1.5")
        onNodeWithTag("axis-output-map-cell-1-1").assertTextEquals("4.5")
        onNodeWithTag("axis-output-map-cell-2-1").assertTextEquals("7.5")
    }
}
