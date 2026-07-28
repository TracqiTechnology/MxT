package ui.screens.med17

import androidx.compose.ui.test.*
import data.preferences.dualinjection.DualInjectionPreferences
import java.io.File
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

    @Test
    fun mockPfiLogDrivesExactOnTimeAndReverseTableValues() {
        DualInjectionPreferences.krkateAlreadyPressureCompensated = true
        val logFile = File.createTempFile("mxt-pfi-ui-", ".csv")
        logFile.writeText(
            buildString {
                appendLine("DS1 firmware:test,MED17")
                appendLine(
                    "Time(s),Engine speed(nmot_w) (1/min),Load(rl_w) (%)," +
                        "Tgt dist fac prop port fuel inj(InjSys_facPrtnPfiTar) (-)"
                )
                repeat(10) { sample ->
                    appendLine("${sample / 10.0},5000,160,0.28")
                }
            }
        )

        try {
            runComposeUiTest {
                setContent {
                    ui.screens.dualinjection.DualInjectionScreen(
                        initialTab = 2,
                        initialKrktePfi = "0.0307",
                        initialKrkteGdi = "0.0320",
                        preloadedPfiLogFile = logFile
                    )
                }

                waitUntil(timeoutMillis = 10_000) {
                    runCatching {
                        onNodeWithText("Logged RPM × load").assertIsEnabled()
                    }.isSuccess
                }

                onNodeWithTag("pfi-target-load").performTextReplacement("160")
                onNodeWithTag("pfi-calculate").performScrollTo().performClick()
                waitForIdle()

                onNodeWithTag("pfi-calculation-result").assertExists()
                onNodeWithText("Port (PFI) on-time:   1.3754 ms").assertExists()
                onNodeWithText("Direct (GDI) on-time: 3.6864 ms").assertExists()
                onNodeWithText(
                    "RPM: 5000  |  PFI Share: 28.0% (LOGGED_SURFACE)  |  " +
                        "Available window: 24.00 ms"
                ).assertExists()

                onNodeWithTag("pfi-reverse-toggle").performScrollTo().performClick()
                onNodeWithTag("pfi-reverse-calculate").performScrollTo().performClick()
                waitForIdle()

                onNodeWithTag("pfi-reverse-root").assertExists()
                onNodeWithTag("pfi-reverse-cell-0-0").assertTextEquals("0")
                onNodeWithTag("pfi-reverse-cell-0-9").assertTextEquals("21.88")
            }
        } finally {
            logFile.delete()
        }
    }

    @Test
    fun invalidKrkteIsRejectedInTheRenderedWorkflow() = runComposeUiTest {
        DualInjectionPreferences.krkateAlreadyPressureCompensated = true
        setContent {
            ui.screens.dualinjection.DualInjectionScreen(
                initialTab = 2,
                initialKrktePfi = "-0.0307",
                initialKrkteGdi = "0.0320"
            )
        }

        onNodeWithTag("pfi-calculate").performScrollTo().performClick()
        onNodeWithText("KRKTE_PFI must be positive").assertExists()
        onAllNodesWithTag("pfi-calculation-result").assertCountEquals(0)
    }
}
