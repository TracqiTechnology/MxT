@file:OptIn(ExperimentalComposeUiApi::class)

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.parser.afrlog.AfrLogParser
import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.me7log.*
import data.parser.xdf.XdfParser
import data.preferences.MapPreference
import data.preferences.bin.BinFilePreferences
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import data.preferences.kfpbrk.KfpbrkPreferences
import data.preferences.kfpbrknw.KfpbrknwPreferences
import data.preferences.kfvpdksd.KfvpdksdPreferences
import data.preferences.kfzw.KfzwPreferences
import data.preferences.kfzwop.KfzwopPreferences
import data.preferences.krkte.KrktePreferences
import data.preferences.rkw.RkwPreferences
import data.preferences.logheaderdefinition.LogHeaderPreference
import data.preferences.mlhfm.MlhfmPreferences
import data.preferences.platform.EcuPlatformPreference
import data.preferences.wdkugdn.WdkugdnPreferences
import data.preferences.xdf.XdfFilePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import ui.navigation.CalibrationTab
import ui.navigation.ME7TunerApp
import ui.navigation.NavigationState
import ui.navigation.RailDestination
import ui.navigation.ToolsTab
import ui.theme.ME7TunerTheme
import java.io.File
import java.util.Locale

fun main() {
    Locale.setDefault(Locale.ENGLISH)

    // Initialize data layer
    XdfParser.init()
    BinParser.init()
    LogHeaderPreference.loadHeaders()

    // Load example files
    XdfFilePreferences.setFile(File("example/me7/xdf/8D0907551M-20170411-16bit-kfzw.xdf"))
    BinFilePreferences.setFile(File("example/me7/bin/8D0907551M-0002 (16Bit KFZW).bin"))

    // Wait for BIN parsing to complete
    println("Waiting for BIN parsing...")
    runBlocking {
        BinParser.mapList.first { it.isNotEmpty() }
    }
    println("BIN parsed: ${BinParser.mapList.value.size} maps loaded")

    // Auto-select maps
    autoSelectMap(KrktePreferences, "KRKTE")
    autoSelectMap(MlhfmPreferences, "MLHFM")
    autoSelectMap(KfmiopPreferences, "KFMIOP")
    autoSelectMap(KfmirlPreferences, "KFMIRL")
    autoSelectMap(KfzwopPreferences, "KFZWOP")
    autoSelectMap(KfzwPreferences, "KFZW")
    autoSelectMap(KfvpdksdPreferences, "KFVPDKSD")
    autoSelectMap(WdkugdnPreferences, "WDKUGDN")
    autoSelectMap(KfldrlPreferences, "KFLDRL")
    autoSelectMap(KfldimxPreferences, "KFLDIMX")
    autoSelectMap(KfpbrkPreferences, "KFPBRK")
    autoSelectMap(KfpbrknwPreferences, "KFPBRKNW")

    val outputDir = File("documentation/images")
    outputDir.mkdirs()

    // --- Brand banner ---
    @Suppress("DEPRECATION")
    captureScreen("banner.png", width = 420, height = 100) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource("pistons_0.png"),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primaryContainer)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "TracQi",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    )
                    Text(
                        text = "ME7Tuner",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // --- Static screens (no log data needed) ---

    // Hero shot / KRKTE
    captureScreen("me7Tuner.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.FUELING)
        ME7TunerApp(navState)
    }

    captureScreen("krkte.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.FUELING)
        ME7TunerApp(navState)
    }

    // Configuration
    captureScreen("configuration.png") {
        val navState = NavigationState()
        navState.navigateTo(RailDestination.CONFIGURATION)
        ME7TunerApp(navState)
    }

    // PLSOL
    captureScreen("plsol.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.PLSOL)
        ME7TunerApp(navState)
    }

    // KFMIOP
    captureScreen("kfmiop.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.KFMIOP)
        ME7TunerApp(navState)
    }

    // KFMIRL
    captureScreen("kfmirl.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.KFMIRL)
        ME7TunerApp(navState)
    }

    // KFZWOP
    captureScreen("kfzwop.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.KFZWOP)
        ME7TunerApp(navState)
    }

    // KFZW
    captureScreen("kfzw.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.KFZW)
        ME7TunerApp(navState)
    }

    // WDKUGDN
    captureScreen("wdkugdn.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.WDKUGDN)
        ME7TunerApp(navState)
    }

    // LDRPID (no log data — renders in ready state)
    captureScreen("ldrpid.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.LDRPID)
        ME7TunerApp(navState)
    }

    // --- Log-dependent screens ---

    // Closed Loop: empty state (no log data)
    captureScreen("closed_loop_mlhfm.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.CLOSED_LOOP)
        ME7TunerApp(navState)
    }

    // Open Loop: empty state (no log data)
    captureScreen("open_loop_mlhfm.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.OPEN_LOOP)
        ME7TunerApp(navState)
    }

    // KFVPDKSD: load logs
    captureScreenWithLogData(
        filename = "kfvpdksd.png",
        loadData = {
            KfvpdksdLogParser.loadDirectory(File("example/me7/logs/me7/")) { _, _ -> }
        }
    ) {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.KFVPDKSD)
        ME7TunerApp(navState)
    }

    // --- Closed Loop sub-tab screenshots (all need log data) ---

    captureScreenWithLogData(
        filename = "closed_loop_mlhfm_filter.png",
        loadData = { ClosedLoopLogParser.loadDirectory(File("example/me7/logs/me7/")) }
    ) {
        val navState = NavigationState()
        navState.closedLoopTab = 0
        navState.navigateToCalibration(CalibrationTab.CLOSED_LOOP)
        ME7TunerApp(navState)
    }

    captureScreenWithLogData(
        filename = "closed_loop_mlhfm_corrected_percentage.png",
        loadData = { ClosedLoopLogParser.loadDirectory(File("example/me7/logs/me7/")) }
    ) {
        val navState = NavigationState()
        navState.closedLoopTab = 2
        navState.closedLoopCorrectionSubTab = 0
        navState.navigateToCalibration(CalibrationTab.CLOSED_LOOP)
        ME7TunerApp(navState)
    }

    captureScreenWithLogData(
        filename = "closed_loop_mlhfm_derivative.png",
        loadData = { ClosedLoopLogParser.loadDirectory(File("example/me7/logs/me7/")) }
    ) {
        val navState = NavigationState()
        navState.closedLoopTab = 2
        navState.closedLoopCorrectionSubTab = 1
        navState.navigateToCalibration(CalibrationTab.CLOSED_LOOP)
        ME7TunerApp(navState)
    }

    captureScreenWithLogData(
        filename = "closed_loop_mlhfm_corrected.png",
        loadData = { ClosedLoopLogParser.loadDirectory(File("example/me7/logs/me7/")) }
    ) {
        val navState = NavigationState()
        navState.closedLoopTab = 2
        navState.closedLoopCorrectionSubTab = 2
        navState.navigateToCalibration(CalibrationTab.CLOSED_LOOP)
        ME7TunerApp(navState)
    }

    captureScreenWithLogData(
        filename = "closed_loop_mlhfm_corrected_best_fit.png",
        loadData = { ClosedLoopLogParser.loadDirectory(File("example/me7/logs/me7/")) }
    ) {
        val navState = NavigationState()
        navState.closedLoopTab = 2
        navState.closedLoopCorrectionSubTab = 2
        navState.autoFitDegree = 6
        navState.navigateToCalibration(CalibrationTab.CLOSED_LOOP)
        ME7TunerApp(navState)
    }

    // --- Open Loop sub-tab screenshots (all need log data) ---

    captureScreenWithLogData(
        filename = "open_loop_mlhfm_logs.png",
        loadData = {
            OpenLoopLogParser.loadFile(File("example/me7/logs/me7/me7logger.csv"))
            AfrLogParser.load(File("example/me7/logs/ziet/zeitronix.csv"))
        }
    ) {
        val navState = NavigationState()
        navState.openLoopTab = 0
        navState.openLoopLogSubTab = 0
        navState.navigateToCalibration(CalibrationTab.OPEN_LOOP)
        ME7TunerApp(navState)
    }

    captureScreenWithLogData(
        filename = "open_loop_mlhfm_airflow.png",
        loadData = {
            OpenLoopLogParser.loadFile(File("example/me7/logs/me7/me7logger.csv"))
            AfrLogParser.load(File("example/me7/logs/ziet/zeitronix.csv"))
        }
    ) {
        val navState = NavigationState()
        navState.openLoopTab = 0
        navState.openLoopLogSubTab = 1
        navState.navigateToCalibration(CalibrationTab.OPEN_LOOP)
        ME7TunerApp(navState)
    }

    captureScreenWithTwoPhaseLogData(
        filename = "open_loop_mlhfm_correction.png",
        loadPhase1 = { OpenLoopLogParser.loadFile(File("example/me7/logs/me7/me7logger.csv")) },
        loadPhase2 = { AfrLogParser.load(File("example/me7/logs/ziet/zeitronix.csv")) }
    ) {
        val navState = NavigationState()
        navState.openLoopTab = 2
        navState.openLoopCorrectionSubTab = 0
        navState.navigateToCalibration(CalibrationTab.OPEN_LOOP)
        ME7TunerApp(navState)
    }

    captureScreenWithTwoPhaseLogData(
        filename = "open_loop_mlhfm_correction_percentage.png",
        loadPhase1 = { OpenLoopLogParser.loadFile(File("example/me7/logs/me7/me7logger.csv")) },
        loadPhase2 = { AfrLogParser.load(File("example/me7/logs/ziet/zeitronix.csv")) }
    ) {
        val navState = NavigationState()
        navState.openLoopTab = 2
        navState.openLoopCorrectionSubTab = 1
        navState.navigateToCalibration(CalibrationTab.OPEN_LOOP)
        ME7TunerApp(navState)
    }

    captureScreenWithTwoPhaseLogData(
        filename = "open_loop_mlhfm_correction_best_fit.png",
        loadPhase1 = { OpenLoopLogParser.loadFile(File("example/me7/logs/me7/me7logger.csv")) },
        loadPhase2 = { AfrLogParser.load(File("example/me7/logs/ziet/zeitronix.csv")) }
    ) {
        val navState = NavigationState()
        navState.openLoopTab = 2
        navState.openLoopCorrectionSubTab = 0
        navState.autoFitDegree = 6
        navState.navigateToCalibration(CalibrationTab.OPEN_LOOP)
        ME7TunerApp(navState)
    }

    // --- PLSOL sub-tab screenshots (no log data needed) ---

    captureScreen("plsol_airflow.png") {
        val navState = NavigationState()
        navState.plsolTab = 1
        navState.navigateToCalibration(CalibrationTab.PLSOL)
        ME7TunerApp(navState)
    }

    captureScreen("plsol_power.png") {
        val navState = NavigationState()
        navState.plsolTab = 2
        navState.navigateToCalibration(CalibrationTab.PLSOL)
        ME7TunerApp(navState)
    }

    println("All ME7 screenshots generated.\n")

    // --- Tools Screenshots (platform-independent) ---
    println("\n--- Tools Screenshots ---")

    // A2L → ECU Generator (empty state — shows UI layout)
    captureScreen("tools/a2l_generator.png") {
        val navState = NavigationState()
        navState.navigateToTools(ToolsTab.A2L_GENERATOR)
        ME7TunerApp(navState)
    }

    // Data Logger — Connection tab (empty state)
    captureScreen("tools/logger_connection.png") {
        val navState = NavigationState()
        navState.navigateToTools(ToolsTab.LOGGER)
        ME7TunerApp(navState)
    }

    println("All Tools screenshots generated.\n")

    // --- MED17 Screenshots ---
    println("\n--- MED17 Screenshots ---")
    EcuPlatformPreference.platform = EcuPlatform.MED17

    // Load MED17 XDF+BIN
    XdfFilePreferences.setFile(File("technical/med17/Audi_RS3vlmspec_Gv004.xdf"))
    BinFilePreferences.setFile(File("technical/med17/OTS tunes/404J/MED17_1_62_STOCK.bin"))

    // Wait for re-parse
    println("Waiting for MED17 BIN parsing...")
    runBlocking { BinParser.mapList.first { it.isNotEmpty() } }
    println("MED17 BIN parsed: ${BinParser.mapList.value.size} maps loaded")

    // Auto-select MED17 maps
    autoSelectMap(KrktePreferences, "KRKTE")
    autoSelectMap(KfmiopPreferences, "KFMIOP")
    autoSelectMap(KfmirlPreferences, "KFMIRL")
    autoSelectMap(KfzwopPreferences, "KFZWOP")
    autoSelectMap(KfzwPreferences, "KFZW")
    autoSelectMap(KfldrlPreferences, "KFLDRL")
    autoSelectMap(KfldimxPreferences, "KFLDIMX")
    autoSelectMap(RkwPreferences, "rk_w")

    val med17OutputDir = File("documentation/images/med17")
    med17OutputDir.mkdirs()

    // MED17 hero shot / overview
    captureScreen("med17/me7Tuner_med17.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.FUELING)
        ME7TunerApp(navState)
    }

    // MED17 Configuration
    captureScreen("med17/configuration_med17.png") {
        val navState = NavigationState()
        navState.navigateTo(RailDestination.CONFIGURATION)
        ME7TunerApp(navState)
    }

    // Dual Injection (MED17-only tab)
    captureScreen("med17/dual_injection.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.DUAL_INJECTION)
        ME7TunerApp(navState)
    }

    // KFMIOP in MED17 mode
    captureScreen("med17/kfmiop_med17.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.KFMIOP)
        ME7TunerApp(navState)
    }

    // KFMIRL in MED17 mode
    captureScreen("med17/kfmirl_med17.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.KFMIRL)
        ME7TunerApp(navState)
    }

    // LDRPID in MED17 mode
    captureScreen("med17/ldrpid_med17.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.LDRPID)
        ME7TunerApp(navState)
    }

    // PLSOL in MED17 mode
    captureScreen("med17/plsol_med17.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.PLSOL)
        ME7TunerApp(navState)
    }

    // Optimizer in MED17 mode
    captureScreen("med17/optimizer_med17.png") {
        val navState = NavigationState()
        navState.navigateToOptimizer()
        ME7TunerApp(navState)
    }

    // Fuel Trim (MED17-only)
    captureScreen("med17/fuel_trim_med17.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.FUEL_TRIM)
        ME7TunerApp(navState)
    }

    // KFZWOP in MED17 mode
    captureScreen("med17/kfzwop_med17.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.KFZWOP)
        ME7TunerApp(navState)
    }

    // KFZW in MED17 mode
    captureScreen("med17/kfzw_med17.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.KFZW)
        ME7TunerApp(navState)
    }

    // Fueling in MED17 mode (KRKTE with dual presets)
    captureScreen("med17/fueling_med17.png") {
        val navState = NavigationState()
        navState.navigateToCalibration(CalibrationTab.FUELING)
        ME7TunerApp(navState)
    }

    // Restore ME7 platform
    EcuPlatformPreference.platform = EcuPlatform.ME7

    println("All screenshots generated in documentation/images/")
}

private fun autoSelectMap(preference: MapPreference, keyword: String) {
    val match = BinParser.mapList.value.firstOrNull {
        it.first.tableName.contains(keyword, ignoreCase = true)
    }
    if (match != null) {
        preference.setSelectedMap(match.first)
        println("  Selected map: ${match.first.tableName}")
    } else {
        println("  WARNING: No map found for keyword '$keyword'")
    }
}

private fun captureScreen(
    filename: String,
    width: Int = 1480,
    height: Int = 1080,
    content: @Composable () -> Unit
) {
    print("Capturing $filename...")
    val scene = ImageComposeScene(width, height, density = Density(1f)) {
        ME7TunerTheme { content() }
    }
    // Render multiple frames to settle LaunchedEffects and collectAsState
    repeat(20) { scene.render(it * 16_000_000L) }
    val image = scene.render(320_000_000L)
    val data = image.encodeToData(EncodedImageFormat.PNG)
        ?: error("Failed to encode $filename to PNG")
    File("documentation/images/$filename").writeBytes(data.bytes)
    scene.close()
    println(" done")
}

private fun captureScreenWithLogData(
    filename: String,
    width: Int = 1480,
    height: Int = 1080,
    loadData: () -> Unit,
    content: @Composable () -> Unit
) {
    print("Capturing $filename (with log data)...")
    val scene = ImageComposeScene(width, height, density = Density(1f)) {
        ME7TunerTheme { content() }
    }
    // Initial render to start LaunchedEffect collectors
    repeat(5) { scene.render(it * 16_000_000L) }

    // Trigger log parsing (emits to SharedFlow)
    loadData()

    // Allow time for async parsing and state propagation
    Thread.sleep(2000)

    // Render many frames to let state propagate through compose tree
    repeat(30) { scene.render((it + 5) * 16_000_000L) }
    val image = scene.render(560_000_000L)
    val data = image.encodeToData(EncodedImageFormat.PNG)
        ?: error("Failed to encode $filename to PNG")
    File("documentation/images/$filename").writeBytes(data.bytes)
    scene.close()
    println(" done")
}

/**
 * Two-phase variant for screens where correction depends on ME7 logs being
 * collected before AFR logs emit (e.g. OpenLoopScreen).
 */
private fun captureScreenWithTwoPhaseLogData(
    filename: String,
    width: Int = 1480,
    height: Int = 1080,
    loadPhase1: () -> Unit,
    loadPhase2: () -> Unit,
    content: @Composable () -> Unit
) {
    print("Capturing $filename (with log data)...")
    val scene = ImageComposeScene(width, height, density = Density(1f)) {
        ME7TunerTheme { content() }
    }
    // Initial render to start LaunchedEffect collectors
    repeat(5) { scene.render(it * 16_000_000L) }

    // Phase 1: load ME7 logs and let state propagate
    loadPhase1()
    Thread.sleep(2000)
    repeat(20) { scene.render((it + 5) * 16_000_000L) }

    // Phase 2: load AFR logs — me7LogMap is now set so correction will be computed
    loadPhase2()
    Thread.sleep(2000)
    repeat(20) { scene.render((it + 25) * 16_000_000L) }

    val image = scene.render(720_000_000L)
    val data = image.encodeToData(EncodedImageFormat.PNG)
        ?: error("Failed to encode $filename to PNG")
    File("documentation/images/$filename").writeBytes(data.bytes)
    scene.close()
    println(" done")
}
