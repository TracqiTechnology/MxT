package ui.screens.med17

import androidx.compose.ui.test.*
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfzw.KfzwPreferences
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI test for the KFZW screen on MED17.
 *
 * With the corrected profile (KFMIOP → "Opt eng tq"), KFMIOP is a full 14×16
 * 2D table on standard (non-DS1) XDFs.  KFZW uses the ME7-style calculator
 * path: it reads KFMIOP's x-axis (load axis) and computes rescaled ignition
 * timing values for the new load range.
 */
@OptIn(ExperimentalTestApi::class)
class Med17KfzwScreenTest : Med17ScreenTestBase() {

    @Test
    fun profileResolvesBothPreferences() {
        val kfzw = KfzwPreferences.getSelectedMap()
        val kfmiop = KfmiopPreferences.getSelectedMap()
        assertNotNull(kfzw, "Profile should resolve KFZW map preference")
        assertNotNull(kfmiop, "Profile should resolve KFMIOP map preference")
        assertTrue(
            kfzw.first.tableName.contains("Ignition GDI", ignoreCase = true),
            "KFZW table name should match DS1 ignition switch map, got '${kfzw.first.tableName}'"
        )
    }

    @Test
    fun kfzwMapIs2dOnMed17() {
        val selected = KfzwPreferences.getSelectedMap()!!
        val map = selected.second
        assertTrue(map.xAxis.size > 1 && map.yAxis.size > 1,
            "KFZW should be a 2D map, got x=${map.xAxis.size} y=${map.yAxis.size}")
    }

    @Test
    fun kfzwScreenConfiguredShowsNoNotConfigured() = runComposeUiTest {
        setContent { ui.screens.kfzw.KfzwScreen() }
        onAllNodesWithText("Not configured").assertCountEquals(0)
    }

    @Test
    fun kfzwScreenShowsSelectMap() = runComposeUiTest {
        setContent { ui.screens.kfzw.KfzwScreen() }
        // 2D mode: Select Map buttons are visible for KFZW and KFMIOP
        val selectMapNodes = onAllNodesWithText("Select Map").fetchSemanticsNodes()
        assertTrue(
            selectMapNodes.size >= 1,
            "Expected at least 1 'Select Map' button, got ${selectMapNodes.size}"
        )
    }

    @Test
    fun kfzwWriteButtonEnabled() = runComposeUiTest {
        setContent { ui.screens.kfzw.KfzwScreen() }
        onNodeWithText("Write KFZW").assertIsEnabled()
    }

    @Test
    fun kfzwWriteProducesValidBinaryOutput() = runComposeUiTest {
        setContent { ui.screens.kfzw.KfzwScreen() }

        KfzwPreferences.getSelectedMap()!!

        // Click Write
        onNodeWithText("Write KFZW").performClick()
        onNodeWithText("Are you sure you want to write KFZW to the binary?").assertExists()
        onNodeWithText("Yes").performClick()
        waitForIdle()

        // With no edits the output equals the input, so the write must be a
        // byte-perfect identity round-trip. (Before the linked-axis buffer fix
        // this "passed" only because oversized axis writes zeroed the shared
        // linked axis tables — i.e. the corruption itself registered as change.)
        assertTrue(
            stockBinCopy.readBytes().contentEquals(tempBinFile.readBytes()),
            "unedited KFZW write must leave the BIN byte-identical (no axis-spill corruption)"
        )
    }

    @Test
    fun kfzwScreenUnconfiguredShowsNotConfigured() = runComposeUiTest {
        KfzwPreferences.setSelectedMap(null)
        KfmiopPreferences.setSelectedMap(null)
        setContent { ui.screens.kfzw.KfzwScreen() }

        val notConfiguredNodes = onAllNodesWithText("Not configured").fetchSemanticsNodes()
        assertTrue(
            notConfiguredNodes.isNotEmpty(),
            "Should show 'Not configured' when KFZW is not set"
        )
        onNodeWithText("Write KFZW").assertIsNotEnabled()
    }

    @Test
    fun kfzwScreenShowsDs1Banner() = runComposeUiTest {
        setContent { ui.screens.kfzw.KfzwScreen() }
        onNodeWithText("DS1 Note", substring = true).assertExists()
    }
}
