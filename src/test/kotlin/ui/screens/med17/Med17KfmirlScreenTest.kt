package ui.screens.med17

import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import domain.model.kfmiop.Kfmiop
import domain.model.rlsol.Rlsol
import java.text.DecimalFormat
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI test for the KFMIRL screen on MED17.
 *
 * With the corrected profile (KFMIOP → "Opt eng tq"), KFMIOP is a full 14×16
 * 2D table on standard (non-DS1) XDFs.  KFMIRL uses the ME7-style inverse
 * calculator path: it reads KFMIOP's x-axis (load axis) and computes the
 * inverse relationship to generate output KFMIRL values.
 *
 * When a DS1 XDF is loaded where KFMIOP is collapsed to a 1×1 scalar,
 * the scalar detection automatically switches to the self-axis rescale path.
 */
@OptIn(ExperimentalTestApi::class)
class Med17KfmirlScreenTest : Med17ScreenTestBase() {

    @Test
    fun profileResolvesBothPreferences() {
        val kfmiop = KfmiopPreferences.getSelectedMap()
        val kfmirl = KfmirlPreferences.getSelectedMap()
        assertNotNull(kfmiop, "Profile should resolve KFMIOP map preference")
        assertNotNull(kfmirl, "Profile should resolve KFMIRL map preference")
        assertTrue(
            kfmirl.first.tableName.contains("Tgt filling", ignoreCase = true),
            "KFMIRL table name should contain 'Tgt filling', got '${kfmirl.first.tableName}'"
        )
    }

    @Test
    fun kfmirlMapIs2dOnMed17() {
        val selected = KfmirlPreferences.getSelectedMap()!!
        val map = selected.second
        assertTrue(map.xAxis.size > 1 && map.yAxis.size > 1,
            "KFMIRL should be a 2D map, got x=${map.xAxis.size} y=${map.yAxis.size}")
    }

    @Test
    fun kfmirlScreenConfiguredShowsNoNotConfigured() = runComposeUiTest {
        setContent { ui.screens.kfmirl.KfmirlScreen() }
        onAllNodesWithText("Not configured").assertCountEquals(0)
    }

    @Test
    fun kfmirlScreenShowsSelectMap() = runComposeUiTest {
        setContent { ui.screens.kfmirl.KfmirlScreen() }
        // 2D mode: Select Map buttons are visible for KFMIOP and KFMIRL
        val selectMapNodes = onAllNodesWithText("Select Map").fetchSemanticsNodes()
        assertTrue(
            selectMapNodes.size >= 1,
            "Expected at least 1 'Select Map' button, got ${selectMapNodes.size}"
        )
    }

    @Test
    fun kfmirlWriteButtonEnabled() = runComposeUiTest {
        setContent { ui.screens.kfmirl.KfmirlScreen() }
        onNodeWithText("Write KFLMIRL").assertIsEnabled()
    }

    @Test
    fun kfmirlWriteProducesValidBinaryOutput() = runComposeUiTest {
        setContent { ui.screens.kfmirl.KfmirlScreen() }

        val kfmirlPair = KfmirlPreferences.getSelectedMap()!!

        // Click Write
        onNodeWithText("Write KFLMIRL").performClick()
        onNodeWithText("Are you sure you want to write KFLMIRL to the binary?").assertExists()
        onNodeWithText("Yes").performClick()
        waitForIdle()

        // Binary diff: only KFMIRL address range should be modified
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, kfmirlPair.first
        )
    }

    @Test
    fun kfmirlScreenUnconfiguredShowsNotConfigured() = runComposeUiTest {
        KfmiopPreferences.setSelectedMap(null)
        KfmirlPreferences.setSelectedMap(null)
        setContent { ui.screens.kfmirl.KfmirlScreen() }

        val notConfiguredNodes = onAllNodesWithText("Not configured").fetchSemanticsNodes()
        assertTrue(
            notConfiguredNodes.isNotEmpty(),
            "Should show 'Not configured' when KFMIRL is not set"
        )
        onNodeWithText("Write KFLMIRL").assertIsNotEnabled()
    }

    @Test
    fun kfmirlScreenShowsDs1Banner() = runComposeUiTest {
        setContent { ui.screens.kfmirl.KfmirlScreen() }
        onNodeWithText("DS1 Note", substring = true).assertExists()
    }

    @Test
    fun kfmirlScreenShowsPlatformAwareLabel() = runComposeUiTest {
        setContent { ui.screens.kfmirl.KfmirlScreen() }
        // MED17 platform should show KFLMIRL consistently
        onAllNodesWithText("KFLMIRL", substring = true)
            .fetchSemanticsNodes().isNotEmpty().let {
                assertTrue(it, "MED17 KFMIRL screen should display KFLMIRL labels")
            }
    }

    @Test
    fun calculatedKfmiopCanBeHandedOffWithExactValuesWithoutWritingBin() = runComposeUiTest {
        val input = KfmiopPreferences.getSelectedMap()!!.second
        val maxMapLoad = Rlsol.rlsol(
            1030.0, KfmiopPreferences.maxMapPressure, 0.0, 96.0, 0.106,
            KfmiopPreferences.maxMapPressure
        )
        val maxBoostLoad = Rlsol.rlsol(
            1030.0, KfmiopPreferences.maxBoostPressure, 0.0, 96.0, 0.106,
            KfmiopPreferences.maxBoostPressure
        )
        val calculated = Kfmiop.calculateKfmiop(input, maxMapLoad, maxBoostLoad)!!
            .outputKfmiop
        val expected = DecimalFormat("#.##").format(calculated.zAxis[0][0])
        val showKfmirl = mutableStateOf(false)

        setContent {
            if (showKfmirl.value) {
                ui.screens.kfmirl.KfmirlScreen()
            } else {
                ui.screens.kfmiop.KfmiopScreen()
            }
        }

        onNodeWithTag("kfmiop-output-cell-0-0").assertTextEquals(expected)
        runOnIdle { showKfmirl.value = true }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("kfmirl-use-kfmiop").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag("kfmirl-use-kfmiop").performClick()
        onNodeWithTag("kfmirl-workspace-status")
            .assertTextEquals("Using calculated KFLMIOP output")
        onNodeWithTag("kfmirl-input-cell-0-0").assertTextEquals(expected)
    }
}
