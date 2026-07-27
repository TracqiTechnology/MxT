package ui.screens.med17

import androidx.compose.ui.test.*
import data.preferences.fueltrim.FuelTrimPreferences
import data.preferences.rkw.RkwPreferences
import domain.model.fueltrim.FuelTrimSettings
import java.io.File
import java.text.DecimalFormat
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI test for the FuelTrim screen on MED17.
 *
 * Verifies the full workflow: rk_w map loaded from BIN (input) →
 * log analysis → corrected rk_w (output) → write to BIN.
 */
@OptIn(ExperimentalTestApi::class)
class Med17FuelTrimScreenTest : Med17ScreenTestBase() {

    @Test
    fun profileResolvesRkwPreference() {
        val rkw = RkwPreferences.getSelectedMap()
        assertNotNull(rkw, "Profile should resolve rk_w map preference")
        assertTrue(
            rkw.first.tableName.contains("Rel fuel mass", ignoreCase = true),
            "rk_w table name should match, got '${rkw.first.tableName}'"
        )
    }

    @Test
    fun rkwMapIs2d() {
        val selected = RkwPreferences.getSelectedMap()!!
        val map = selected.second
        assertTrue(map.xAxis.size > 1 && map.yAxis.size > 1,
            "rk_w should be a 2D map, got x=${map.xAxis.size} y=${map.yAxis.size}")
    }

    @Test
    fun fuelTrimScreenRendersEmptyState() = runComposeUiTest {
        setContent {
            ui.screens.fueltrim.FuelTrimScreen()
        }

        // Main title
        onNodeWithText("Fuel Trim Analysis", substring = true).assertExists()

        // Load button
        onNodeWithText("Load Fuel Trim Logs").assertExists()

        // Input section shows
        onNodeWithText("Input: Current rk_w", substring = true).assertExists()

        // Write button present but disabled (no logs loaded yet)
        onNodeWithText("Write rk_w").assertIsNotEnabled()
    }

    @Test
    fun fuelTrimScreenShowsInputTable() = runComposeUiTest {
        setContent {
            ui.screens.fueltrim.FuelTrimScreen()
        }

        // rk_w is configured via profile, so "Not configured" should NOT appear
        onAllNodesWithText("Not configured").assertCountEquals(0)
    }

    @Test
    fun fuelTrimScreenUnconfiguredShowsNotConfigured() = runComposeUiTest {
        RkwPreferences.setSelectedMap(null)

        setContent {
            ui.screens.fueltrim.FuelTrimScreen()
        }

        onNodeWithText("Not configured", substring = true).assertExists()
        onNodeWithText("Write rk_w").assertIsNotEnabled()
    }

    @Test
    fun mockLogRendersExactTwentyPercentCorrectionInOutputCell() {
        FuelTrimPreferences.save(
            FuelTrimSettings(
                trimThresholdPercent = 3.0,
                minimumSamples = 3,
                standardDeviationLimitPercent = 5.0,
                maximumRpmChangePerSecond = 1000.0,
                requireClosedLoopWhenAvailable = true,
                maximumLambdaDeviation = 0.05
            )
        )
        val input = RkwPreferences.getSelectedMap()!!.second
        val row = input.yAxis.indices.first { input.yAxis[it] > 0.0 }
        val col = input.xAxis.indices.first { input.xAxis[it] > 0.0 }
        val logFile = File.createTempFile("mxt-fuel-trim-ui-", ".csv")
        logFile.writeText(
            buildString {
                appendLine("DS1 firmware:test,MED17")
                appendLine(
                    "Time(s),Engine speed(nmot_w) (1/min),Load(rl_w) (%)," +
                        "STFT(frm_w) (-),LTFT(fra_w) (-),Closed loop(B_lr) (-)," +
                        "Lambda request(lamsbg_w) (-)"
                )
                repeat(10) { sample ->
                    appendLine(
                        "${sample / 10.0},${input.yAxis[row]},${input.xAxis[col]}," +
                            "1.20,1.00,1,1.0"
                    )
                }
            }
        )

        try {
            runComposeUiTest {
                setContent {
                    ui.screens.fueltrim.FuelTrimScreen(
                        preloadedLogFiles = listOf(logFile)
                    )
                }

                onNodeWithText("Load Fuel Trim Logs").performScrollTo()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("fuel-trim-status")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("fuel-trim-status")
                    .assertTextEquals("✓ Loaded 1 file(s) — 1 bins with corrections")

                onNodeWithText("Output: Corrected rk_w").performScrollTo()
                val expected = DecimalFormat("#.##")
                    .format(input.zAxis[row][col] * 1.20)
                onNodeWithTag("fuel-trim-output-cell-$row-$col")
                    .assertTextEquals(expected)
                onNodeWithTag("fuel-trim-write").assertIsEnabled()
            }
        } finally {
            logFile.delete()
        }
    }

    @Test
    fun reactionThresholdCanBeRaisedThroughUiToSuppressTheSameMockTrim() {
        FuelTrimPreferences.save(FuelTrimSettings())
        val input = RkwPreferences.getSelectedMap()!!.second
        val row = input.yAxis.indices.first { input.yAxis[it] > 0.0 }
        val col = input.xAxis.indices.first { input.xAxis[it] > 0.0 }
        val logFile = File.createTempFile("mxt-fuel-trim-threshold-ui-", ".csv")
        logFile.writeText(
            buildString {
                appendLine("DS1 firmware:test,MED17")
                appendLine(
                    "Time(s),Engine speed(nmot_w) (1/min),Load(rl_w) (%)," +
                        "STFT(frm_w) (-),LTFT(fra_w) (-),Closed loop(B_lr) (-)," +
                        "Lambda request(lamsbg_w) (-)"
                )
                repeat(10) { sample ->
                    appendLine(
                        "${sample / 10.0},${input.yAxis[row]},${input.xAxis[col]}," +
                            "1.20,1.00,1,1.0"
                    )
                }
            }
        )

        try {
            runComposeUiTest {
                setContent {
                    ui.screens.fueltrim.FuelTrimScreen(listOf(logFile))
                }
                onNodeWithText("Load Fuel Trim Logs").performScrollTo()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("fuel-trim-status")
                        .fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText("Analysis Settings").performClick()
                onNodeWithTag("fuel-trim-threshold").performTextReplacement("25")
                onNodeWithTag("fuel-trim-apply-settings")
                    .performScrollTo()
                    .performClick()
                waitUntil(timeoutMillis = 5_000) {
                    runCatching {
                        onNodeWithTag("fuel-trim-status").assertTextEquals(
                            "Reanalyzed — no accepted bins exceeded ±25.0%"
                        )
                    }.isSuccess
                }

                onNodeWithTag("fuel-trim-status").assertTextEquals(
                    "Reanalyzed — no accepted bins exceeded ±25.0%"
                )
                onNodeWithText("Output: Corrected rk_w").performScrollTo()
                val expected = DecimalFormat("#.##").format(input.zAxis[row][col])
                onNodeWithTag("fuel-trim-output-cell-$row-$col")
                    .assertTextEquals(expected)
            }
        } finally {
            logFile.delete()
        }
    }
}
