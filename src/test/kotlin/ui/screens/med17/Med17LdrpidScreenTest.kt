package ui.screens.med17

import androidx.compose.ui.test.*
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfldrl.KfldrlPreferences
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI test for the LDRPID screen on MED17.
 *
 * Verifies:
 *  - Profile application resolves both KfldrlPreferences and KfldimxPreferences
 *  - Screen renders without "Not configured" when both maps are set
 *  - Write buttons (KFLDRL + KFLDIMX) are present
 *  - Unconfigured state correctly shows "Not configured"
 *
 * Note: LDRPID's canWrite requires nonLinearMap/linearMap to be computed from log data.
 * Without loading log data, the maps default from the BIN file, so write buttons may
 * not be fully enabled until a log is loaded. The configured-state tests still validate
 * the profile resolves and the UI renders correctly.
 */
@OptIn(ExperimentalTestApi::class)
class Med17LdrpidScreenTest : Med17ScreenTestBase() {

    @Test
    fun profileResolvesBothPreferences() {
        val kfldrl = KfldrlPreferences.getSelectedMap()
        val kfldimx = KfldimxPreferences.getSelectedMap()
        assertNotNull(kfldrl, "Profile should resolve KFLDRL map preference")
        assertNotNull(kfldimx, "Profile should resolve KFLDIMX map preference")
        assertTrue(
            kfldrl.first.tableName.contains("linearize boost", ignoreCase = true),
            "KFLDRL table name should match, got '${kfldrl.first.tableName}'"
        )
    }

    @Test
    fun ldrpidScreenConfiguredShowsNoNotConfigured() = runComposeUiTest {
        setContent {
            ui.screens.ldrpid.LdrpidScreen()
        }

        // When both KFLDRL and KFLDIMX are configured via profile,
        // "Not configured" should NOT appear
        onAllNodesWithText("Not configured").assertCountEquals(0)
    }

    @Test
    fun ldrpidScreenShowsWriteButtons() = runComposeUiTest {
        setContent {
            ui.screens.ldrpid.LdrpidScreen()
        }

        onNodeWithText("Write KFLDRL").assertExists()
        onNodeWithText("Write KFLDIMX").assertExists()
    }

    @Test
    fun ldrpidWriteKfldrlRequiresMeasuredLogData() = runComposeUiTest {
        setContent {
            ui.screens.ldrpid.LdrpidScreen()
        }

        onNodeWithText("Write KFLDRL").assertIsNotEnabled()
        onAllNodesWithText("Are you sure you want to write KFLDRL to the binary?")
            .assertCountEquals(0)
    }

    @Test
    fun ldrpidWriteKfldimxRequiresMeasuredLogData() = runComposeUiTest {
        setContent {
            ui.screens.ldrpid.LdrpidScreen()
        }

        onNodeWithText("Write KFLDIMX").assertIsNotEnabled()
        onAllNodesWithText("Are you sure you want to write KFLDIMX to the binary?")
            .assertCountEquals(0)
    }

    @Test
    fun ldrpidScreenUnconfiguredShowsNotConfigured() = runComposeUiTest {
        KfldrlPreferences.setSelectedMap(null)
        KfldimxPreferences.setSelectedMap(null)

        setContent {
            ui.screens.ldrpid.LdrpidScreen()
        }

        val notConfiguredNodes = onAllNodesWithText("Not configured").fetchSemanticsNodes()
        assertTrue(
            notConfiguredNodes.size >= 2,
            "Should show 'Not configured' for both KFLDRL and KFLDIMX"
        )
    }

    @Test
    fun ldrpidScreenShowsDs1LogButton() = runComposeUiTest {
        setContent {
            ui.screens.ldrpid.LdrpidScreen()
        }

        onNodeWithText("Load DS1 Logs").assertExists()
    }

    @Test
    fun ldrpidScreenShowsBoostTabs() = runComposeUiTest {
        setContent {
            ui.screens.ldrpid.LdrpidScreen()
        }

        onNodeWithText("Boost Tables").assertExists()
        assertTrue(
            onAllNodesWithText("KFLDRL", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            onAllNodesWithText("KFLDIMX", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun mockWotLogRendersMeasuredBoostAndIndependentDiagnostics() {
        val kfldrl = KfldrlPreferences.getSelectedMap()!!.second
        val row = kfldrl.yAxis.indices.first { kfldrl.yAxis[it] >= 2000.0 }
        val col = kfldrl.xAxis.indices.first { kfldrl.xAxis[it] > 0.0 }
        val logDir = File.createTempFile("mxt-ldrpid-ui-", "")
        logDir.delete()
        logDir.mkdirs()
        val logFile = File(logDir, "synthetic-wot.csv")
        logFile.writeText(
            buildString {
                appendLine("DS1 firmware:test,MED17")
                appendLine(
                    "Time(s),Engine speed(nmot_w) (1/min),Throttle(wdkba) (%)," +
                        "Baro(pu_w) (hPa),WGDC(tvldste_w) (%)," +
                        "Manifold abs press(psrg_w) (hPa),Requested pressure(pvds_w) (hPa)"
                )
                repeat(10) { sample ->
                    appendLine(
                        "${sample / 10.0},${kfldrl.yAxis[row]},95," +
                            "1000,${kfldrl.xAxis[col]},2000,1900"
                    )
                }
            }
        )

        try {
            runComposeUiTest {
                setContent {
                    ui.screens.ldrpid.LdrpidScreen(preloadedLogDir = logDir)
                }

                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("ldrpid-nonlinear-cell-$row-$col")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("ldrpid-nonlinear-cell-$row-$col")
                    .assertTextEquals("14.5")
                onNodeWithTag("ldrpid-write-kfldrl").assertIsEnabled()
                onNodeWithTag("ldrpid-write-kfldimx").assertIsEnabled()

                onNodeWithText("PID Analysis").performClick()
                waitForIdle()
                val rpm = kfldrl.yAxis[row].toInt()
                onNodeWithTag("ldrpid-diagnostic-$rpm-samples")
                    .assertTextEquals("10")
                onNodeWithTag("ldrpid-diagnostic-$rpm-duty-cells")
                    .assertTextEquals("1")
                onNodeWithTag("ldrpid-diagnostic-$rpm-avg-error")
                    .assertTextEquals("100.0 mbar")
                onNodeWithTag("ldrpid-diagnostic-$rpm-overshoot")
                    .assertTextEquals("100.0 mbar")
                onNodeWithTag("ldrpid-diagnostic-$rpm-within-tolerance")
                    .assertTextEquals("0%")
            }
        } finally {
            logDir.deleteRecursively()
        }
    }
}
