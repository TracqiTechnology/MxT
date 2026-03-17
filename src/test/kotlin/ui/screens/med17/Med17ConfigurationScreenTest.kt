package ui.screens.med17

import androidx.compose.ui.test.*
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compose UI test for the Configuration screen in MED17 mode.
 */
@OptIn(ExperimentalTestApi::class)
class Med17ConfigurationScreenTest : Med17ScreenTestBase() {

    companion object {
        // MED17-only entries — these use colon suffix in UI
        val MED17_ONLY_DEFS = listOf("KRKTE (Port):", "KRKTE (Direct):", "TVUB (Port):", "rk_w (Fuel Trim):")

        // Shared entries — also with colon suffix in UI
        // Use exact "KFZW:" (with colon) to not match "KFZWOP:"
        val SHARED_DEFS = listOf(
            "KFMIOP:", "KFMIRL:", "KFZWOP:", "KFZW:",
            "KFLDRL:", "KFLDIMX:", "KFLDIOPU:",
            "KFLDRQ0:", "KFLDRQ1:", "KFLDRQ2:"
        )

        // ME7-only entries (should NOT appear for MED17)
        val ME7_ONLY_DEFS = listOf("MLHFM", "KFVPDKSD", "WDKUGDN")
    }

    @Test
    fun configurationScreenShowsMed17MapDefinitions() = runComposeUiTest {
        setContent {
            ui.screens.configuration.ConfigurationScreen(navState = ui.navigation.NavigationState())
        }

        // All MED17-only definitions should be present
        for (def in MED17_ONLY_DEFS) {
            assertTrue(
                onAllNodesWithText(def, substring = false).fetchSemanticsNodes().isNotEmpty(),
                "MED17-only definition '$def' should be visible"
            )
        }

        // All shared definitions should be present
        for (def in SHARED_DEFS) {
            assertTrue(
                onAllNodesWithText(def, substring = false).fetchSemanticsNodes().isNotEmpty(),
                "Shared definition '$def' should be visible"
            )
        }
    }

    @Test
    fun configurationScreenHidesMe7OnlyDefinitions() = runComposeUiTest {
        setContent {
            ui.screens.configuration.ConfigurationScreen(navState = ui.navigation.NavigationState())
        }

        // ME7-only definitions should NOT be visible
        for (def in ME7_ONLY_DEFS) {
            onNodeWithText(def, substring = false, useUnmergedTree = true)
                .assertDoesNotExist()
        }
    }

    @Test
    fun configurationScreenShowsMapDefinitionsSection() = runComposeUiTest {
        setContent {
            ui.screens.configuration.ConfigurationScreen(navState = ui.navigation.NavigationState())
        }

        // Map definitions section title
        onNodeWithText("Map Definitions", substring = true).assertExists()
    }

    @Test
    fun configurationScreenShowsSelectDefinitionButtons() = runComposeUiTest {
        setContent {
            ui.screens.configuration.ConfigurationScreen(navState = ui.navigation.NavigationState())
        }

        // "Select Definition" buttons should exist for each visible map definition
        val selectButtons = onAllNodesWithText("Select Definition")
        selectButtons.assertCountEquals(MED17_ONLY_DEFS.size + SHARED_DEFS.size)
    }
}
