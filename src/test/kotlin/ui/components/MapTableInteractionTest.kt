package ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import domain.math.map.Map3d
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class MapTableInteractionTest {

    @Test
    fun pasteStartsAtTheFirstSelectedMapCell() = runComposeUiTest {
        lateinit var clipboard: ClipboardManager
        var changed: Map3d? = null
        setContent {
            clipboard = LocalClipboardManager.current
            MapTable(
                map = Map3d(
                    arrayOf(10.0, 20.0),
                    arrayOf(1000.0, 2000.0),
                    arrayOf(arrayOf(1.0, 2.0), arrayOf(3.0, 4.0))
                ),
                onMapChanged = { changed = it },
                testTagPrefix = "paste-map"
            )
        }

        runOnIdle { clipboard.setText(AnnotatedString("9\t8\n7\t6")) }
        onNodeWithTag("paste-map-cell-0-0").performClick()
        onNodeWithTag("paste-map-root").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }
        mainClock.advanceTimeBy(200)
        waitForIdle()

        assertNotNull(changed)
        assertEquals(9.0, changed!!.zAxis[0][0])
        assertEquals(8.0, changed!!.zAxis[0][1])
        assertEquals(7.0, changed!!.zAxis[1][0])
        assertEquals(6.0, changed!!.zAxis[1][1])
    }

    @Test
    fun pasteStartsAtTheFirstSelectedAxisCell() = runComposeUiTest {
        lateinit var clipboard: ClipboardManager
        var changed: Array<Array<Double>>? = null
        setContent {
            clipboard = LocalClipboardManager.current
            MapAxis(
                data = arrayOf(arrayOf(10.0, 20.0, 30.0)),
                onDataChanged = { changed = it },
                testTagPrefix = "paste-axis"
            )
        }

        runOnIdle { clipboard.setText(AnnotatedString("11\t22\t33")) }
        onNodeWithTag("paste-axis_cell_0_0").performClick()
        onNodeWithTag("paste-axis_root").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }
        mainClock.advanceTimeBy(200)
        waitForIdle()

        assertNotNull(changed)
        assertEquals(listOf(11.0, 22.0, 33.0), changed!![0].toList())
    }
}
