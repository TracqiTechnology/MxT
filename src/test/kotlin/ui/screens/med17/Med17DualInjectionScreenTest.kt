package ui.screens.med17

import androidx.compose.ui.test.*
import kotlin.test.Test

/**
 * Compose UI test for the DualInjection screen with real MED17 data.
 */
@OptIn(ExperimentalTestApi::class)
class Med17DualInjectionScreenTest : Med17ScreenTestBase() {

    @Test
    fun dualInjectionScreenRendersWithTabs() = runComposeUiTest {
        setContent {
            ui.screens.dualinjection.DualInjectionScreen()
        }

        // Three tabs
        onNodeWithText("Port Injector").assertExists()
        onNodeWithText("Direct Injector").assertExists()
        onNodeWithText("Split Calculator").assertExists()
    }

    @Test
    fun portInjectorTabShowsFields() = runComposeUiTest {
        setContent {
            ui.screens.dualinjection.DualInjectionScreen()
        }

        // Port Injector tab is default — shows PFI scaling content
        onNodeWithText("Port Injector (PFI) Scaling").assertExists()

        // Flow rate and pressure fields (from semantics tree)
        onAllNodesWithText("Flow Rate (cc/min)").assertCountEquals(2) // Stock + New
        onAllNodesWithText("Fuel Pressure (bar, gauge)").assertCountEquals(2)

        // Stock/New sections
        onNodeWithText("Stock Port Injector").assertExists()
        onNodeWithText("New Port Injector").assertExists()

        // Calculate button
        onNodeWithText("Calculate Port KRKTE Scale Factor").assertExists()
    }

    @Test
    fun directInjectorTabRenders() = runComposeUiTest {
        setContent {
            ui.screens.dualinjection.DualInjectionScreen()
        }

        // Switch to Direct Injector tab
        onNodeWithText("Direct Injector").performClick()
        waitForIdle()

        // Should show DI scaling content — "Direct Injector (GDI) Scaling" or similar
        onNodeWithText("Direct Injector (GDI) Scaling", substring = true).assertExists()
    }

    @Test
    fun directInjectorTabHasNoDeadTimeFields() = runComposeUiTest {
        setContent {
            ui.screens.dualinjection.DualInjectionScreen()
        }

        // Switch to Direct Injector tab
        onNodeWithText("Direct Injector").performClick()
        waitForIdle()

        // DI tab should NOT have dead time fields — TVUB_GDI is firmware-handled on MED17
        onAllNodesWithText("Dead Time", substring = true).assertCountEquals(0)

        // But it should still have flow rate and pressure fields
        onAllNodesWithText("Flow Rate (cc/min)").fetchSemanticsNodes().let { nodes ->
            assert(nodes.isNotEmpty()) { "DI tab must have flow rate fields" }
        }
        onAllNodesWithText("Fuel Pressure (bar, absolute)").fetchSemanticsNodes().let { nodes ->
            assert(nodes.isNotEmpty()) { "DI tab must have fuel pressure fields" }
        }
    }

    @Test
    fun portInjectorTabStillHasDeadTimeFields() = runComposeUiTest {
        setContent {
            ui.screens.dualinjection.DualInjectionScreen()
        }

        // Port Injector tab is default — TVUB_PFI IS tunable, so dead time must be present
        onAllNodesWithText("Dead Time", substring = true).fetchSemanticsNodes().let { nodes ->
            assert(nodes.isNotEmpty()) { "PFI tab must still have dead time fields (TVUB_PFI is tunable)" }
        }
    }

    @Test
    fun directInjectorTabShowsAbsolutePressureLabels() = runComposeUiTest {
        setContent {
            ui.screens.dualinjection.DualInjectionScreen()
        }

        // Switch to Direct Injector tab
        onNodeWithText("Direct Injector").performClick()
        waitForIdle()

        // DI pressure fields must say "absolute" (not gauge)
        onAllNodesWithText("Fuel Pressure (bar, absolute)").fetchSemanticsNodes().let { nodes ->
            assert(nodes.size >= 2) { "DI tab must have 2 'absolute' pressure fields (stock + new), found ${nodes.size}" }
        }
    }

    @Test
    fun splitCalculatorTabRenders() = runComposeUiTest {
        setContent {
            ui.screens.dualinjection.DualInjectionScreen()
        }

        // Switch to Split Calculator tab
        onNodeWithText("Split Calculator").performClick()
        waitForIdle()

        // Should show split calculator title
        onNodeWithText("RPM-Dependent PFI Split Calculator").assertExists()
    }

    @Test
    fun presetsSection() = runComposeUiTest {
        setContent {
            ui.screens.dualinjection.DualInjectionScreen()
        }

        // Presets section visible on Port Injector tab
        onNodeWithText("Presets").assertExists()
    }
}
