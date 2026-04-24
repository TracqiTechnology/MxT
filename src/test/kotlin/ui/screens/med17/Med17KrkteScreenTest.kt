package ui.screens.med17

import androidx.compose.ui.test.*
import data.preferences.krkte.KrktePfiPreferences
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.krkte.KrkteCalculator
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI test for the KRKTE screen on MED17.
 *
 * Verifies:
 *  - Profile application resolves KrktePfiPreferences
 *  - Screen renders without "Not configured" when profile is applied
 *  - Write button is enabled when prerequisites are met
 *  - Clicking Write + Yes writes to BIN and changes bytes
 *  - Unconfigured state correctly shows "Not configured"
 */
@OptIn(ExperimentalTestApi::class)
class Med17KrkteScreenTest : Med17ScreenTestBase() {

    @Test
    fun profileResolvesKrktePfiPreference() {
        val selected = KrktePfiPreferences.getSelectedMap()
        assertNotNull(selected, "Profile should resolve KRKTE PFI map preference")
        assertTrue(
            selected.first.tableName.contains("inj time", ignoreCase = true),
            "KRKTE PFI table name should relate to injection time"
        )
    }

    @Test
    fun krkteScreenConfiguredShowsNoNotConfigured() = runComposeUiTest {
        setContent {
            ui.screens.krkte.KrkteScreen()
        }

        // "Not configured" should NOT appear when profile is applied
        onAllNodesWithText("Not configured").assertCountEquals(0)

        // Map name should appear in the prerequisites
        onAllNodesWithText(KRKTE_PFI_TITLE, substring = true)
            .fetchSemanticsNodes().let { nodes ->
                assertTrue(nodes.isNotEmpty(), "KRKTE PFI map name should appear in UI")
            }
    }

    @Test
    fun krkteWriteButtonIsEnabledWhenConfigured() = runComposeUiTest {
        setContent {
            ui.screens.krkte.KrkteScreen()
        }

        onNodeWithText("Write KRKATE").assertIsEnabled()
    }

    @Test
    fun krkteWriteProducesValidBinaryOutput() = runComposeUiTest {
        setContent {
            ui.screens.krkte.KrkteScreen()
        }

        val krktePair = KrktePfiPreferences.getSelectedMap()!!

        // Click Write KRKATE button
        onNodeWithText("Write KRKATE").performScrollTo().performClick()
        onNodeWithText("Are you sure you want to write KRKATE to the binary?").assertExists()
        onNodeWithText("Yes").performClick()
        waitForIdle()

        // Binary diff: only KRKTE address range should be modified
        BinaryDiffHelper.assertOnlyExpectedBytesChanged(
            stockBinCopy, tempBinFile, krktePair.first
        )
    }

    @Test
    fun krkteScreenUnconfiguredShowsNotConfigured() = runComposeUiTest {
        // Clear the KRKTE preference to simulate unconfigured state
        KrktePfiPreferences.setSelectedMap(null)

        setContent {
            ui.screens.krkte.KrkteScreen()
        }

        // "Not configured" should appear
        onNodeWithText("Not configured").assertExists()

        // Write button should be disabled
        onNodeWithText("Write KRKATE").assertIsNotEnabled()
    }

    @Test
    fun krkteScreenShowsCalculatedValue() = runComposeUiTest {
        setContent {
            ui.screens.krkte.KrkteScreen()
        }

        // Result card should show the calculated KRKATE on MED17
        onNodeWithText("Calculated KRKATE").assertExists()
        onNodeWithText("ms/%").assertExists()

        // Engine parameters should show profile values
        onNodeWithText("Engine Parameters").assertExists()
        onNodeWithText("Fuel Properties").assertExists()
        onNodeWithText("Fuel Injector").assertExists()
    }

    @Test
    fun fuelingScreenShowsKrkateTabOnMed17() = runComposeUiTest {
        // Platform is MED17 (set by Med17ScreenTestBase)
        setContent {
            ui.screens.fueling.FuelingScreen()
        }

        // On MED17, the tab should say "KRKATE" not "KRKTE"
        onNodeWithText("KRKATE").assertExists()
        onNodeWithText("Injector Scaling").assertExists()
    }
}
