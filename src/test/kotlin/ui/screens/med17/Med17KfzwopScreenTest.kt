package ui.screens.med17

import androidx.compose.ui.test.*
import data.preferences.kfzwop.KfzwopPreferences
import java.text.DecimalFormat
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI test for the KFZWOP screen on MED17.
 *
 * Verifies:
 *  - Profile application resolves KfzwopPreferences
 *  - Screen renders without "Not configured" when profile is applied
 *  - Write button is enabled when prerequisites are met
 *  - Clicking Write + Yes performs a corruption-free identity write
 *  - Unconfigured state correctly shows "Not configured"
 */
@OptIn(ExperimentalTestApi::class)
class Med17KfzwopScreenTest : Med17ScreenTestBase() {

    @Test
    fun profileResolvesKfzwopPreference() {
        val selected = KfzwopPreferences.getSelectedMap()
        assertNotNull(selected, "Profile should resolve KFZWOP map preference")
        assertTrue(
            selected.first.tableName.contains("Opt model ref ignition", ignoreCase = true),
            "KFZWOP table name should match, got '${selected.first.tableName}'"
        )
    }

    @Test
    fun kfzwopScreenConfiguredShowsNoNotConfigured() = runComposeUiTest {
        setContent {
            ui.screens.kfzwop.KfzwopScreen()
        }

        onAllNodesWithText("Not configured").assertCountEquals(0)
    }

    @Test
    fun kfzwopWriteButtonIsEnabledWhenConfigured() = runComposeUiTest {
        setContent {
            ui.screens.kfzwop.KfzwopScreen()
        }

        // canWrite = binLoaded && kfzwopMapConfigured && outputKfzwop != null
        onNodeWithText("Write KFZWOP").assertIsEnabled()
    }

    @Test
    fun kfzwopWriteProducesValidBinaryOutput() = runComposeUiTest {
        setContent {
            ui.screens.kfzwop.KfzwopScreen()
        }

        KfzwopPreferences.getSelectedMap()!!

        // Click Write
        onNodeWithText("Write KFZWOP").performClick()
        onNodeWithText("Are you sure you want to write KFZWOP to the binary?").assertExists()
        onNodeWithText("Yes").performClick()
        waitForIdle()

        // With no axis edits the rescale is identity, so the write must be a
        // byte-perfect identity round-trip. (Before the linked-axis buffer fix
        // this "passed" only because oversized axis writes zeroed the shared
        // linked axis tables — i.e. the corruption itself registered as change.)
        assertTrue(
            stockBinCopy.readBytes().contentEquals(tempBinFile.readBytes()),
            "unedited KFZWOP write must leave the BIN byte-identical (no axis-spill corruption)"
        )
    }

    @Test
    fun kfzwopScreenUnconfiguredShowsNotConfigured() = runComposeUiTest {
        KfzwopPreferences.setSelectedMap(null)

        setContent {
            ui.screens.kfzwop.KfzwopScreen()
        }

        onNodeWithText("Not configured").assertExists()
        onNodeWithText("Write KFZWOP").assertIsNotEnabled()
    }

    @Test
    fun kfzwopScreenShowsDs1Banner() = runComposeUiTest {
        setContent {
            ui.screens.kfzwop.KfzwopScreen()
        }

        onNodeWithText("DS1 Note", substring = true).assertExists()
    }

    @Test
    fun nativeAxesAndIdentityRescaleRenderExactInputAndOutputValues() = runComposeUiTest {
        val input = KfzwopPreferences.getSelectedMap()!!.second
        val expectedCell = DecimalFormat("#.##").format(input.zAxis[0][0])
        val expectedAxis = DecimalFormat("#.00").format(input.xAxis[0])

        setContent { ui.screens.kfzwop.KfzwopScreen() }

        onNodeWithTag("kfzwop-output-x_cell_0_0").assertTextEquals(expectedAxis)
        onNodeWithTag("kfzwop-input-cell-0-0").assertTextEquals(expectedCell)
        onNodeWithText("KFZWOP (Comparison)").performClick()
        waitForIdle()
        onNodeWithTag("kfzwop-original-cell-0-0").assertTextEquals(expectedCell)
        onNodeWithTag("kfzwop-calculated-cell-0-0").assertTextEquals(expectedCell)
    }
}
