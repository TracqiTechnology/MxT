package ui.screens.optimizer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import data.parser.bin.BinParser
import data.parser.me7log.Me7LogParser
import data.parser.med17log.Med17LogAdapter
import data.parser.med17log.Med17LogParser
import data.parser.xdf.TableDefinition
import data.preferences.bin.BinFilePreferences
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.kfldrq0.Kfldrq0Preferences
import data.preferences.kfldrq1.Kfldrq1Preferences
import data.preferences.kfldrq2.Kfldrq2Preferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import data.preferences.kfpbrk.KfpbrkPreferences
import data.preferences.kfpbrknw.KfpbrknwPreferences
import data.preferences.optimizer.OptimizerPreferences
import data.preferences.platform.EcuPlatformPreference
import data.model.EcuPlatform
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.optimizer.*
import domain.model.simulator.Me7Simulator
import data.writer.XdfPatchWriter
import data.writer.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.components.ChartSeries
import ui.components.LineChart
import ui.components.MapTable
import ui.components.ParameterField
import ui.theme.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private fun findMap(
    mapList: List<Pair<TableDefinition, Map3d>>,
    pref: data.preferences.MapPreference
): Pair<TableDefinition, Map3d>? {
    val selected = pref.getSelectedMap()
    return if (selected != null) {
        mapList.find { it.first.tableName == selected.first.tableName }
    } else null
}

// ── Reusable icon-aware composables ──────────────────────────────────

/**
 * Section header combining a Material icon with a title.
 * Replaces the emoji-prefix pattern (e.g. "🎯 Title") throughout the UI.
 */
@Composable
private fun IconSectionTitle(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, style = style)
    }
}

/** Sub-section header with icon — replaces "📊 Sub-title" patterns. */
@Composable
private fun IconSubTitle(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.secondary,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, style = style)
    }
}

/**
 * Status dot (check/cancel icon) — replaces 🟢 / 🔴 emoji circles.
 * Uses CheckCircle for OK, Cancel for error.
 */
@Composable
private fun StatusDot(ok: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Icon(
        imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
        contentDescription = if (ok) "OK" else "Error",
        tint = if (ok) Color(0xFF4CAF50) else Color(0xFFF44336),
        modifier = modifier.size(size)
    )
}

/**
 * 4-level quality indicator — replaces 🟢 / 🟡 / 🟠 / 🔴 in pull quality contexts.
 * level: 0=good, 1=noisy, 2=short, 3=incomplete
 */
@Composable
private fun QualityDot(level: Int, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val (icon, color) = when (level) {
        0 -> Icons.Filled.CheckCircle to Color(0xFF4CAF50)
        1 -> Icons.Filled.Warning to Color(0xFFFFEB3B)
        2 -> Icons.Filled.RemoveCircle to Color(0xFFFF9800)
        else -> Icons.Filled.Cancel to Color(0xFFF44336)
    }
    Icon(imageVector = icon, contentDescription = null, tint = color, modifier = modifier.size(size))
}

/** Confidence indicator — replaces 🟢/🟡/🟠/⚪ in per-RPM table confidence columns. */
@Composable
private fun ConfidenceDot(confidence: MapDelta.Confidence, modifier: Modifier = Modifier) {
    val (icon, color) = when (confidence) {
        MapDelta.Confidence.HIGH -> Icons.Filled.CheckCircle to Color(0xFF4CAF50)
        MapDelta.Confidence.MEDIUM -> Icons.Filled.Warning to Color(0xFFFFEB3B)
        MapDelta.Confidence.LOW -> Icons.Filled.RemoveCircle to Color(0xFFFF9800)
        MapDelta.Confidence.NONE -> Icons.Filled.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
    }
    Icon(imageVector = icon, contentDescription = null, tint = color, modifier = modifier.size(14.dp))
}

/** Inline icon + text row for recommendation / info lines. */
@Composable
private fun IconTextRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.secondary,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall
) {
    Row(verticalAlignment = Alignment.Top, modifier = modifier.padding(vertical = 1.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = tint,
            modifier = Modifier.size(14.dp).padding(top = 1.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = style)
    }
}


/**
 * Renders a domain-layer message string with the appropriate leading Material icon
 * based on a semantic tag prefix (WARNING:, ERROR:, INFO:, OK:, BOOST:, VE:).
 * Strips the prefix from the displayed text.
 */
@Composable
private fun TaggedMessageRow(
    message: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val (icon, tint, displayText) = when {
        message.startsWith("ERROR:") ->
            Triple(Icons.Filled.Cancel, Color(0xFFF44336), message.removePrefix("ERROR:").trim())
        message.startsWith("WARNING:") ->
            Triple(Icons.Filled.Warning, Color(0xFFFF9800), message.removePrefix("WARNING:").trim())
        message.startsWith("INFO:") ->
            Triple(Icons.Filled.Info, MaterialTheme.colorScheme.primary, message.removePrefix("INFO:").trim())
        message.startsWith("OK:") ->
            Triple(Icons.Filled.CheckCircle, Color(0xFF4CAF50), message.removePrefix("OK:").trim())
        message.startsWith("BOOST:") ->
            Triple(Icons.Filled.Bolt, MaterialTheme.colorScheme.secondary, message.removePrefix("BOOST:").trim())
        message.startsWith("VE:") ->
            Triple(Icons.Filled.BarChart, MaterialTheme.colorScheme.secondary, message.removePrefix("VE:").trim())
        else ->
            Triple(Icons.Filled.Info, MaterialTheme.colorScheme.secondary, message)
    }
    Row(verticalAlignment = Alignment.Top, modifier = modifier) {
        Icon(imageVector = icon, contentDescription = null, tint = tint,
            modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(displayText, style = style, color = textColor)
    }
}

@Composable
fun OptimizerScreen() {
    val mapList by BinParser.mapList.collectAsState()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Resolve current maps from preferences
    val kfldrlPair = remember(mapList) { findMap(mapList, KfldrlPreferences) }
    val kfldimxPair = remember(mapList) { findMap(mapList, KfldimxPreferences) }
    val kfpbrkPair = remember(mapList) { findMap(mapList, KfpbrkPreferences) }
    val kfpbrknwPair = remember(mapList) { findMap(mapList, KfpbrknwPreferences) }
    val kfmiopPair = remember(mapList) { findMap(mapList, KfmiopPreferences) }
    val kfmirlPair = remember(mapList) { findMap(mapList, KfmirlPreferences) }
    // v4: PID gain maps
    val kfldrq0Pair = remember(mapList) { findMap(mapList, Kfldrq0Preferences) }
    val kfldrq1Pair = remember(mapList) { findMap(mapList, Kfldrq1Preferences) }
    val kfldrq2Pair = remember(mapList) { findMap(mapList, Kfldrq2Preferences) }

    // Phase 22: Auto-read KFURL from BIN if available
    val autoKfurl = remember(mapList) { KfurlSolver.findKfurlFromBin(mapList) }
    // Finding 2: Also load the full KFURL Kennlinie (RPM-dependent) if available
    val autoKfurlMap = remember(mapList) { KfurlSolver.findKfurlMapFromBin(mapList) }

    // Preference-backed input state
    var toleranceMbar by remember { mutableStateOf(OptimizerPreferences.mapToleranceMbar.toString()) }
    var ldrxnTarget by remember { mutableStateOf(OptimizerPreferences.ldrxnTarget.toString()) }
    var kfldimxOverhead by remember { mutableStateOf(OptimizerPreferences.kfldimxOverheadPercent.toString()) }
    var minThrottleAngle by remember { mutableStateOf(OptimizerPreferences.minThrottleAngle.toString()) }
    var kfurl by remember { mutableStateOf(OptimizerPreferences.kfurl.toString()) }

    // Auto-populate KFURL from BIN on first load
    LaunchedEffect(autoKfurl) {
        if (autoKfurl != null && kfurl == "0.106") {
            kfurl = String.format("%.4f", autoKfurl)
            OptimizerPreferences.kfurl = autoKfurl
        }
    }

    // Result state
    var result by remember { mutableStateOf<OptimizerCalculator.OptimizerResult?>(null) }
    var logFileName by remember { mutableStateOf("No Log Selected") }
    var showProgress by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }

    val tabTitles = listOf("Overview", "Per-Link", "Boost Control", "VE Model", "Calibration", "Prediction", "Pulls", "Export")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(8.dp)
    ) {
        // ── Parameters row ────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Optimizer Parameters", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ParameterField(
                        value = toleranceMbar,
                        onValueChange = {
                            toleranceMbar = it
                            it.toDoubleOrNull()?.let { v -> OptimizerPreferences.mapToleranceMbar = v }
                        },
                        label = "MAP Tolerance (mbar)",
                        tooltip = "Maximum allowed deviation between requested boost pressure (pssol) and actual boost pressure (pvdks_w). " +
                            "Log entries within this tolerance are considered \"on-target\" and used for KFLDRL/KFPBRK suggestions. " +
                            "Typical values: 20\u201350 mbar. Lower = stricter matching, fewer data points.",
                        modifier = Modifier.weight(1f)
                    )
                    ParameterField(
                        value = ldrxnTarget,
                        onValueChange = {
                            ldrxnTarget = it
                            it.toDoubleOrNull()?.let { v -> OptimizerPreferences.ldrxnTarget = v }
                        },
                        label = "LDRXN Target (%)",
                        tooltip = "The maximum specified engine load (LDRXN) your tune is targeting. " +
                            "Used by the Intervention Watchdog to detect if rlsol is being capped below this value by torque limiters (KFMIOP/KFMIZUFIL). " +
                            "Set this to match your KFMIRL maximum load axis value.",
                        modifier = Modifier.weight(1f)
                    )
                    ParameterField(
                        value = kfldimxOverhead,
                        onValueChange = {
                            kfldimxOverhead = it
                            it.toDoubleOrNull()?.let { v -> OptimizerPreferences.kfldimxOverheadPercent = v }
                        },
                        label = "KFLDIMX Overhead (%)",
                        tooltip = "Percentage headroom added on top of the suggested KFLDRL values to derive KFLDIMX (the PID I-regulator limit). " +
                            "This gives the PID controller room to make corrections without winding up. " +
                            "Typical values: 5\u201310%. Higher = more PID authority, but risk of overshoot.",
                        modifier = Modifier.weight(1f)
                    )
                    ParameterField(
                        value = minThrottleAngle,
                        onValueChange = {
                            minThrottleAngle = it
                            it.toDoubleOrNull()?.let { v -> OptimizerPreferences.minThrottleAngle = v }
                        },
                        label = "Min Throttle Angle",
                        tooltip = "Minimum throttle plate angle (wdkba) to qualify a log entry as Wide-Open Throttle (WOT). " +
                            "Only log rows above this threshold are used for analysis. " +
                            "Typical value: 80\u00B0. Lower values include partial-throttle data which may skew results.",
                        modifier = Modifier.weight(1f)
                    )
                    ParameterField(
                        value = kfurl,
                        onValueChange = {
                            kfurl = it
                            it.toDoubleOrNull()?.let { v -> OptimizerPreferences.kfurl = v }
                        },
                        label = if (autoKfurl != null) "KFURL (Auto)" else "KFURL",
                        tooltip = "Slope of the rl(ps) characteristic — the VE constant used in the PLSOL/RLSOL " +
                            "pressure\u2194load conversion. This is the single most important constant for simulation accuracy. " +
                            if (autoKfurl != null) "Auto-read from BIN: ${String.format("%.4f", autoKfurl)}. " else "" +
                            "Default: 0.106. The KFURL Solver can find the optimal value from your log data.",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Load buttons ──────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Button(onClick = {
                val dialog = FileDialog(Frame(), "Select ME7 Log File", FileDialog.LOAD)
                val lastDir = OptimizerPreferences.lastDirectory
                if (lastDir.isNotEmpty()) dialog.directory = lastDir
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val selectedFile = File(dir, file)
                    OptimizerPreferences.lastDirectory = dir
                    logFileName = selectedFile.name
                    showProgress = true

                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val parser = Me7LogParser()
                            val values = parser.parseLogFile(Me7LogParser.LogType.OPTIMIZER, selectedFile)

                            val analysisResult = OptimizerCalculator.analyze(
                                values = values,
                                kfldrlMap = kfldrlPair?.second,
                                kfldimxMap = kfldimxPair?.second,
                                kfpbrkMap = kfpbrkPair?.second,
                                kfmiopMap = kfmiopPair?.second,
                                kfmirlMap = kfmirlPair?.second,
                                ldrxnTarget = ldrxnTarget.toDoubleOrNull() ?: 191.0,
                                toleranceMbar = toleranceMbar.toDoubleOrNull() ?: 30.0,
                                minThrottleAngle = minThrottleAngle.toDoubleOrNull() ?: 80.0,
                                kfldimxOverheadPercent = kfldimxOverhead.toDoubleOrNull() ?: 8.0,
                                kfurl = kfurl.toDoubleOrNull() ?: 0.106,
                                kfurlMap = autoKfurlMap,  // Finding 2: RPM-dependent KFURL
                                kfldrq0Map = kfldrq0Pair?.second,
                                kfldrq1Map = kfldrq1Pair?.second,
                                kfldrq2Map = kfldrq2Pair?.second
                            )

                            withContext(Dispatchers.Main) {
                                result = analysisResult
                                showProgress = false
                            }
                        }
                    }
                }
            }) {
                Text("Load ME7 Log File")
            }

            Button(onClick = {
                val dialog = FileDialog(Frame(), "Select Log Directory", FileDialog.LOAD)
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                val lastDir = OptimizerPreferences.lastDirectory
                if (lastDir.isNotEmpty()) dialog.directory = lastDir
                dialog.isVisible = true
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val selectedDir = File(dir, file)
                    OptimizerPreferences.lastDirectory = selectedDir.parent ?: dir
                    logFileName = selectedDir.path
                    showProgress = true

                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val minAngle = minThrottleAngle.toDoubleOrNull() ?: 80.0
                            val isMed17 = EcuPlatformPreference.platform == EcuPlatform.MED17

                            // Parse each file individually for per-log summaries
                            val logFiles = selectedDir.listFiles()?.filter { it.isFile && it.name.endsWith(".csv", ignoreCase = true) } ?: emptyList()
                            val summaries = mutableListOf<domain.model.optimizer.LogSummary>()

                            // Parse merged data for main analysis — platform-aware
                            var rawMed17Values: Map<data.contract.Med17LogFileContract.Header, List<Double>>? = null
                            val mergedValues = if (isMed17) {
                                val med17Parser = Med17LogParser()
                                val med17Values = med17Parser.parseLogDirectory(
                                    Med17LogParser.LogType.OPTIMIZER, selectedDir
                                ) { _, _ -> }
                                rawMed17Values = med17Values
                                Med17LogAdapter.toMe7OptimizerFormat(med17Values)
                            } else {
                                val parser = Me7LogParser()
                                parser.parseLogDirectory(
                                    Me7LogParser.LogType.OPTIMIZER, selectedDir
                                ) { _, _ -> }
                            }

                            // Build per-log summaries — platform-aware
                            for (logFile in logFiles) {
                                try {
                                    val fileValues = if (isMed17) {
                                        val fileParser = Med17LogParser()
                                        val med17Values = fileParser.parseLogFile(Med17LogParser.LogType.OPTIMIZER, logFile)
                                        Med17LogAdapter.toMe7OptimizerFormat(med17Values)
                                    } else {
                                        val fileParser = Me7LogParser()
                                        fileParser.parseLogFile(Me7LogParser.LogType.OPTIMIZER, logFile)
                                    }
                                    val wotEntries = OptimizerCalculator.filterWotEntries(fileValues, minAngle)
                                    if (wotEntries.isNotEmpty()) {
                                        val rpmRange = "${wotEntries.minOf { it.rpm }.toInt()} – ${wotEntries.maxOf { it.rpm }.toInt()}"
                                        val avgPressureError = wotEntries.map { it.requestedMap - it.actualMap }.average()
                                        summaries.add(domain.model.optimizer.LogSummary(
                                            fileName = logFile.name,
                                            wotSampleCount = wotEntries.size,
                                            rpmRange = rpmRange,
                                            avgPressureError = avgPressureError
                                        ))
                                    }
                                } catch (_: Exception) { /* skip unparseable files */ }
                            }

                            val analysisResult = if (isMed17) {
                                OptimizerCalculator.analyzeMed17(
                                    values = mergedValues,
                                    kfldrlMap = kfldrlPair?.second,
                                    kfldimxMap = kfldimxPair?.second,
                                    kfmiopMap = kfmiopPair?.second,
                                    kfmirlMap = kfmirlPair?.second,
                                    ldrxnTarget = ldrxnTarget.toDoubleOrNull() ?: 191.0,
                                    toleranceMbar = toleranceMbar.toDoubleOrNull() ?: 30.0,
                                    minThrottleAngle = minAngle,
                                    kfldimxOverheadPercent = kfldimxOverhead.toDoubleOrNull() ?: 8.0,
                                    logSummaries = summaries,
                                    kfldrq0Map = kfldrq0Pair?.second,
                                    kfldrq1Map = kfldrq1Pair?.second,
                                    kfldrq2Map = kfldrq2Pair?.second,
                                    fupsrlsValues = rawMed17Values?.get(data.contract.Med17LogFileContract.Header.FUPSRLS_HEADER)
                                )
                            } else {
                                OptimizerCalculator.analyze(
                                    values = mergedValues,
                                    kfldrlMap = kfldrlPair?.second,
                                    kfldimxMap = kfldimxPair?.second,
                                    kfpbrkMap = kfpbrkPair?.second,
                                    kfmiopMap = kfmiopPair?.second,
                                    kfmirlMap = kfmirlPair?.second,
                                    ldrxnTarget = ldrxnTarget.toDoubleOrNull() ?: 191.0,
                                    toleranceMbar = toleranceMbar.toDoubleOrNull() ?: 30.0,
                                    minThrottleAngle = minAngle,
                                    kfldimxOverheadPercent = kfldimxOverhead.toDoubleOrNull() ?: 8.0,
                                    kfurl = kfurl.toDoubleOrNull() ?: 0.106,
                                    kfurlMap = autoKfurlMap,
                                    logSummaries = summaries,
                                    kfldrq0Map = kfldrq0Pair?.second,
                                    kfldrq1Map = kfldrq1Pair?.second,
                                    kfldrq2Map = kfldrq2Pair?.second
                                )
                            }

                            withContext(Dispatchers.Main) {
                                result = analysisResult
                                showProgress = false
                            }
                        }
                    }
                }
            }) {
                val buttonLabel = if (EcuPlatformPreference.platform == EcuPlatform.MED17) "Load DS1 Log Directory" else "Load ME7 Log Directory"
                Text(buttonLabel)
            }

            Text(logFileName, style = MaterialTheme.typography.bodySmall)

            if (showProgress) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        // ── Status summary ────────────────────────────────────────────
        result?.let { r ->
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Analysis: ${r.wotEntries.size} WOT data points",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (r.wotEntries.isEmpty()) {
                        Text(
                            "No WOT data found. Ensure your log contains the required headers " +
                                "(pssol_w, rlsol_w, rl_w, pvdks_w, ldtvm, wdkba, nmot, pus_w) " +
                                "and that throttle angle exceeds the minimum threshold.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // ── Tabbed results ────────────────────────────────────────────
        if (result != null && result!!.wotEntries.isNotEmpty()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when (selectedTab) {
                0 -> OverviewTab(result!!)
                1 -> PerLinkTab(result!!)
                2 -> BoostControlTab(result!!, kfldrlPair, kfldimxPair)
                3 -> VeModelTab(result!!, kfpbrkPair, kfpbrknwPair)
                4 -> CalibrationTab(result!!)
                5 -> PredictionTab(result!!)
                6 -> PullsTab(result!!)
                7 -> ExportTab(result!!, kfldrlPair, kfldimxPair, kfpbrkPair, kfmiopPair, kfmirlPair)
            }
        }
    }
}

// ── Tab: Boost Control ────────────────────────────────────────────────

@Composable
private fun BoostControlTab(
    result: OptimizerCalculator.OptimizerResult,
    kfldrlPair: Pair<TableDefinition, Map3d>?,
    kfldimxPair: Pair<TableDefinition, Map3d>?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Current KFLDRL
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Current KFLDRL", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                if (kfldrlPair != null) {
                    Box(modifier = Modifier.heightIn(max = 400.dp)) {
                        MapTable(map = kfldrlPair.second, editable = false)
                    }
                } else {
                    Text("KFLDRL not configured", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Suggested KFLDRL
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Suggested KFLDRL", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                if (result.suggestedKfldrl != null) {
                    Box(modifier = Modifier.heightIn(max = 400.dp)) {
                        MapTable(map = result.suggestedKfldrl, editable = false)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val kfldrlDef = KfldrlPreferences.getSelectedMap()
                        if (kfldrlDef != null) {
                            val binFile = BinFilePreferences.getStoredFile()
                            if (binFile.exists()) {
                                BinWriter.write(binFile, kfldrlDef.first, result.suggestedKfldrl)
                            }
                        }
                    }) {
                        Text("Write KFLDRL")
                    }
                } else {
                    Text("No suggestion (KFLDRL not configured)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Current KFLDIMX
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Current KFLDIMX", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                if (kfldimxPair != null) {
                    Box(modifier = Modifier.heightIn(max = 400.dp)) {
                        MapTable(map = kfldimxPair.second, editable = false)
                    }
                } else {
                    Text("KFLDIMX not configured", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Suggested KFLDIMX
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Suggested KFLDIMX", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                if (result.suggestedKfldimx != null) {
                    Box(modifier = Modifier.heightIn(max = 400.dp)) {
                        MapTable(map = result.suggestedKfldimx, editable = false)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val kfldimxDef = KfldimxPreferences.getSelectedMap()
                        if (kfldimxDef != null) {
                            val binFile = BinFilePreferences.getStoredFile()
                            if (binFile.exists()) {
                                BinWriter.write(binFile, kfldimxDef.first, result.suggestedKfldimx)
                            }
                        }
                    }) {
                        Text("Write KFLDIMX")
                    }
                } else {
                    Text("No suggestion (KFLDIMX not configured)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ── Tab: VE Model ─────────────────────────────────────────────────────

@Composable
private fun VeModelTab(
    result: OptimizerCalculator.OptimizerResult,
    kfpbrkPair: Pair<TableDefinition, Map3d>?,
    kfpbrknwPair: Pair<TableDefinition, Map3d>?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "VE Model Corrections (KFPBRK)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            "These corrections assume boost is on-target (Phase 1 complete). " +
                "The suggested KFPBRK values are the original map cells multiplied by " +
                "the average (requestedLoad / actualLoad) ratio at each RPM breakpoint.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Current KFPBRK
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Current KFPBRK", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                if (kfpbrkPair != null) {
                    Box(modifier = Modifier.heightIn(max = 400.dp)) {
                        MapTable(map = kfpbrkPair.second, editable = false)
                    }
                } else {
                    Text("KFPBRK not configured in Configuration tab", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Suggested KFPBRK
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Suggested KFPBRK", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                if (result.kfpbrkMultipliers != null) {
                    Box(modifier = Modifier.heightIn(max = 400.dp)) {
                        MapTable(map = result.kfpbrkMultipliers, editable = false)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val kfpbrkDef = KfpbrkPreferences.getSelectedMap()
                        if (kfpbrkDef != null) {
                            val binFile = BinFilePreferences.getStoredFile()
                            if (binFile.exists()) {
                                BinWriter.write(binFile, kfpbrkDef.first, result.kfpbrkMultipliers)
                            }
                        }
                    }) {
                        Text("Write KFPBRK")
                    }
                } else {
                    Text(
                        "No VE corrections available. Either KFPBRK is not configured " +
                            "or there is insufficient data where boost was on-target.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // KFPBRKNW section (informational)
        if (kfpbrknwPair != null) {
            Text(
                "KFPBRKNW (Variable Cam Timing Active)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "KFPBRKNW corrections require cam state (nw_w) in the log. " +
                    "Currently the optimizer applies corrections to KFPBRK only. " +
                    "If your vehicle has active variable cam timing, the same ratio " +
                    "approach can be applied manually to KFPBRKNW.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                MapTable(map = kfpbrknwPair.second, editable = false)
            }
        }
    }
}

// ── Tab: Overview (Dashboard) ──────────────────────────────────────────

@Composable
private fun OverviewTab(result: OptimizerCalculator.OptimizerResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val diag = result.chainDiagnosis
        val entries = result.wotEntries

        // ── Calibration Status ──────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                val ldrxn = if (result.simulationResults.isNotEmpty()) result.simulationResults[0].ldrxnTarget else 191.0
                val avgRl = if (entries.isNotEmpty()) entries.map { it.actualLoad }.average() else 0.0
                val deficit = ldrxn - avgRl
                val statusOk = deficit < 5.0

                IconSectionTitle(Icons.Filled.TrackChanges, "Calibration Status")
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Target: LDRXN = ${String.format("%.1f", ldrxn)}%")
                    Text("Achieved: avg rl_w = ${String.format("%.1f", avgRl)}%")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    StatusDot(statusOk, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (statusOk) "ON TARGET" else "NEEDS CALIBRATION — deficit ${String.format("%.1f", deficit)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Chain Health ─────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Signal Chain Health", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                // Link 1: Only samples above 3500 RPM are evaluated for torque capping.
                // Below that RPM, KFMIOP intentionally limits rlsol (normal torque ramp).
                val highRpmSamples = result.simulationResults.count { it.rpm >= 3500.0 }
                val link1Label = if (highRpmSamples > 0) {
                    "Link 1: LDRXN → rlsol (Torque, ≥3500 RPM)"
                } else {
                    "Link 1: LDRXN → rlsol (Torque — no high-RPM data)"
                }
                ChainLinkBar(link1Label, 100.0 - diag.torqueCappedPercent)
                ChainLinkBar("Link 2: rlsol → pssol (VE Model)", 100.0 - diag.pssolErrorPercent)
                ChainLinkBar("Link 3: pssol → pvdks (Boost Control)", 100.0 - diag.boostShortfallPercent)
                ChainLinkBar("Link 4: pvdks → rl_w (VE Readback)", 100.0 - diag.veMismatchPercent)

                Spacer(Modifier.height(8.dp))
                val (dominantIcon, dominantTint, dominantLabel) = when (diag.dominantError) {
                    Me7Simulator.ErrorSource.TORQUE_CAPPED ->
                        Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.error, "TORQUE_CAPPED — increase KFMIOP/KFMIRL")
                    Me7Simulator.ErrorSource.PSSOL_WRONG ->
                        Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.error, "PSSOL_WRONG — check KFURL value")
                    Me7Simulator.ErrorSource.BOOST_SHORTFALL ->
                        Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.error, "BOOST_SHORTFALL — apply KFLDRL corrections")
                    Me7Simulator.ErrorSource.VE_MISMATCH ->
                        Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.error, "VE_MISMATCH — apply KFPBRK corrections")
                    Me7Simulator.ErrorSource.ON_TARGET ->
                        Triple(Icons.Filled.CheckCircle, Color(0xFF4CAF50), "ON TARGET")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = dominantIcon, contentDescription = null, tint = dominantTint,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Dominant Issue: $dominantLabel",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Mechanical Limits ────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Mechanical Limits", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val limits = result.mechanicalLimits
                MechLimitRow("MAF Sensor", !limits.mafMaxed, if (limits.mafMaxValue > 0) "${String.format("%.0f", limits.mafMaxValue)} g/s peak" else "N/A")
                MechLimitRow("MAF Voltage", !limits.mafVoltageMaxed,
                    if (limits.mafMaxVoltage > 0) "${String.format("%.2f", limits.mafMaxVoltage)} V peak (5V = clipped)" else "N/A (log uhfm_w)")
                MechLimitRow("Injectors", !limits.injectorMaxed, if (limits.injectorMaxDutyCycle > 0) "${String.format("%.0f", limits.injectorMaxDutyCycle * 100)}% max DC" else "N/A")
                MechLimitRow("Turbo", !limits.turboMaxed, if (limits.turboMaxWgdc > 0) "${String.format("%.0f", limits.turboMaxWgdc)}% max WGDC" else "N/A")
                MechLimitRow("MAP Sensor", !limits.mapSensorMaxed,
                    if (limits.mapSensorMaxValue > 0) "${String.format("%.0f", limits.mapSensorMaxValue)} mbar peak (${limits.mapSensorType})" else "N/A")
                // v4: Throttle body check
                result.throttleCheck?.let { tc ->
                    MechLimitRow("Throttle Body", !tc.restricted,
                        if (tc.restricted) "${String.format("%.0f", tc.avgPressureDeficit)} mbar deficit" else "OK")
                }

                // Sensor saturation details
                if (limits.sensorSaturationWarnings.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    IconSubTitle(
                        Icons.Filled.Bolt,
                        "Sensor Saturation",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    for (sat in limits.sensorSaturationWarnings) {
                        val rpmDetail = if (sat.affectedRpmRanges.isNotEmpty()) {
                            " (${sat.affectedRpmRanges.joinToString(", ") {
                                "${it.first.toInt()}–${it.second.toInt()} RPM"
                            }})"
                        } else ""
                        Text(
                            "• ${sat.sensorName}: ${String.format("%.1f", sat.maxValue)} ${sat.unit} — " +
                                "${String.format("%.0f", sat.plateauPercent)}% at ceiling$rpmDetail",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "  → ${sat.recommendation}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Data reliability warning
                if (limits.dataReliabilityCompromised) {
                    Spacer(Modifier.height(12.dp))
                    Text(limits.dataReliabilityDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // ── v4: Environmental Conditions ─────────────────────────
        result.environmental?.let { env ->
            if (env.altitudeDeviation || env.tempDeviation) {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        IconSectionTitle(Icons.Filled.Public, "Environmental Conditions")
                        Spacer(Modifier.height(8.dp))
                        Text("Avg Barometric Pressure: ${String.format("%.0f", env.avgBaroPressure)} mbar")
                        Text("Estimated Altitude: ${String.format("%.0f", env.estimatedAltitudeM)}m / ${String.format("%.0f", env.estimatedAltitudeM * 3.281)}ft")
                    }
                }
            }
        }

        // ── v4: Transient Events ─────────────────────────────────
        result.transients?.let { trans ->
            if (trans.events.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        IconSectionTitle(Icons.Filled.Bolt, "Transient Events")
                        Spacer(Modifier.height(8.dp))
                        if (trans.overboostEvents > 0) {
                            Text("Overboost: ${trans.overboostEvents} event(s), ${trans.overboostSampleCount} samples")
                        }
                        if (trans.knockEvents > 0) {
                            Text("Knock Reduction: ${trans.knockEvents} event(s), ${trans.knockSampleCount} samples",
                                color = MaterialTheme.colorScheme.error)
                        }
                        for (rec in trans.recommendations) {
                            Spacer(Modifier.height(4.dp))
                            IconTextRow(Icons.Filled.Lightbulb, rec,
                                tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }

        // ── v4: Safety Mode Summary ──────────────────────────────
        result.safetyModes?.let { safety ->
            if (safety.excludedSamples.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        IconSectionTitle(Icons.Filled.Security, "Safety Mode Events")
                        Spacer(Modifier.height(8.dp))
                        Text("${safety.excludedSamples.size} samples excluded from calibration:")
                        if (safety.overloadCount > 0) Text("  • Overload (DPUPS): ${safety.overloadCount} samples")
                        if (safety.fallbackCount > 0) Text("  • Fallback (B_lds): ${safety.fallbackCount} samples")
                        if (safety.regulationErrorCount > 0) Text("  • Regulation Error: ${safety.regulationErrorCount} samples")
                    }
                }
            }
        }

        // ── v4: KFURL Solver ─────────────────────────────────────
        result.kfurlSolverResult?.let { solver ->
            if (solver.errorReductionPercent > 5) {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        IconSectionTitle(Icons.Filled.Build, "KFURL Solver")
                        Spacer(Modifier.height(8.dp))
                        Text("Optimal KFURL (scalar): ${String.format("%.4f", solver.optimalKfurl)}")
                        Text("pssol RMSE: ${String.format("%.1f", solver.rmse)} mbar")
                        Text("Error reduction vs current: ${String.format("%.0f", solver.errorReductionPercent)}%")

                        // Finding 2: Show per-RPM KFURL values if available
                        solver.perRpmKfurl?.let { perRpm ->
                            if (perRpm.improvementPercent > 2) {
                                Spacer(Modifier.height(12.dp))
                                IconSubTitle(Icons.Filled.BarChart, "RPM-Dependent KFURL (Kennlinie)",
                                    modifier = Modifier.padding(top = 12.dp))
                                Text(
                                    "Per-RPM solving improves RMSE by additional ${String.format("%.1f", perRpm.improvementPercent)}% " +
                                        "(${String.format("%.1f", perRpm.scalarRmse)} → ${String.format("%.1f", perRpm.perRpmRmse)} mbar)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(4.dp))
                                for ((rpm, kfurl) in perRpm.rpmValues) {
                                    Text(
                                        "  ${rpm.toInt()} RPM → ${String.format("%.4f", kfurl)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── v4: Convergence Summary ──────────────────────────────
        result.convergenceHistory?.let { conv ->
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    IconSectionTitle(Icons.Filled.Refresh, "Iterative Convergence")
                    Spacer(Modifier.height(8.dp))
                    val (convOk, convLabel) = when {
                        conv.converged -> true to "Converged in ${conv.iterationsToConverge} iterations"
                        conv.diverged -> false to "Diverged after ${conv.iterationsToConverge} iterations"
                        else -> false to "Did not converge (${conv.steps.size} iterations)"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(convOk, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(convLabel, fontWeight = FontWeight.Bold)
                    }
                    Text("Final error: ${String.format("%.2f", conv.finalError)}")
                    if (conv.steps.size >= 2) {
                        val improvement = conv.steps.first().totalError - conv.steps.last().totalError
                        Text("Total improvement: ${String.format("%.2f", improvement)} (${String.format("%.0f", improvement / conv.steps.first().totalError * 100)}%)")
                    }
                }
            }
        }

        // ── Recommendations ──────────────────────────────────────
        if (diag.recommendations.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    IconSectionTitle(Icons.Filled.Lightbulb, "Recommended Actions")
                    Spacer(Modifier.height(8.dp))
                    diag.recommendations.forEachIndexed { _, rec ->
                        if (rec.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                        } else {
                            TaggedMessageRow(rec, modifier = Modifier.padding(bottom = 6.dp))
                        }
                    }
                }
            }
        }

        // ── Log Summary ──────────────────────────────────────────
        if (entries.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Log Summary", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val rpmRange = "${entries.minOf { it.rpm }.toInt()} – ${entries.maxOf { it.rpm }.toInt()}"
                    val avgPressureError = entries.map { it.requestedMap - it.actualMap }.average()
                    Text("WOT Samples: ${entries.size}")
                    Text("RPM Range: $rpmRange")
                    Text("Avg Pressure Error: ${String.format("%+.0f", avgPressureError)} mbar")
                    Text("Avg WGDC: ${String.format("%.1f", entries.map { it.wgdc }.average())}%")

                    // Show suggested map summary
                    val sm = result.suggestedMaps
                    Spacer(Modifier.height(8.dp))
                    Text("Suggested Corrections:", style = MaterialTheme.typography.titleSmall)
                    sm.kfmiop?.let { Text("  KFMIOP: ${it.cellsModified} cells modified (torque cap fix)") }
                    sm.kfmirl?.let { Text("  KFMIRL: ${it.cellsModified} cells modified (inverse of KFMIOP)") }
                    sm.kfpbrk?.let { Text("  KFPBRK: ${it.cellsModified} cells modified (avg Δ ${String.format("%+.1f", it.avgAbsoluteDelta)})") }
                    sm.kfldrl?.let { Text("  KFLDRL: ${it.cellsModified} cells modified (avg Δ ${String.format("%+.1f", it.avgAbsoluteDelta)}% WGDC)") }
                    sm.kfldimx?.let { Text("  KFLDIMX: ${it.cellsModified} cells modified") }

                    // Per-log summary table (when loading a directory)
                    if (result.logSummaries.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Per-Log Breakdown (${result.logSummaries.size} files):", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("File", fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                            Text("WOT", fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp), style = MaterialTheme.typography.bodySmall)
                            Text("RPM Range", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("Avg ΔP", fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                        for (ls in result.logSummaries) {
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(ls.fileName, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                                Text(ls.wotSampleCount.toString(), modifier = Modifier.width(50.dp), style = MaterialTheme.typography.bodySmall)
                                Text(ls.rpmRange, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text("${String.format("%+.0f", ls.avgPressureError)} mbar", modifier = Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // ── Warnings ─────────────────────────────────────────────
        if (result.warnings.isNotEmpty()) {
            result.warnings.forEachIndexed { index, warning ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Warning ${index + 1}", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        TaggedMessageRow(warning,
                            style = MaterialTheme.typography.bodyMedium,
                            textColor = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun MechLimitRow(name: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(ok, size = 14.dp)
        Spacer(Modifier.width(8.dp))
        Text("$name:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(116.dp))
        Text(
            if (ok) "OK" else "MAXED",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.width(60.dp)
        )
        Text(detail, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PidIndicator(label: String, triggered: Boolean, detail: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StatusDot(!triggered, size = 20.dp)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(detail, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Tab: Per-Link Analysis ────────────────────────────────────────────

@Composable
private fun PerLinkTab(result: OptimizerCalculator.OptimizerResult) {
    var selectedLink by remember { mutableStateOf(0) }
    val linkNames = listOf("Link 3: Boost", "Link 4: VE", "Link 1: Torque", "Link 2: PLSOL")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            linkNames.forEachIndexed { index, name ->
                FilterChip(
                    selected = selectedLink == index,
                    onClick = { selectedLink = index },
                    label = { Text(name) }
                )
            }
        }

        when (selectedLink) {
            0 -> PerLinkBoostContent(result)
            1 -> PerLinkVeContent(result)
            2 -> PerLinkTorqueContent(result)
            3 -> PerLinkPlsolContent(result)
        }
    }
}

@Composable
private fun PerLinkBoostContent(result: OptimizerCalculator.OptimizerResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val boostOk = 100.0 - result.chainDiagnosis.boostShortfallPercent
        Text("Link 3: Boost Control (pssol → pvdks) — ${String.format("%.0f", boostOk)}% OK",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

        // Pressure chart
        val requestedP = result.wotEntries.map { Pair(it.rpm, it.requestedMap) }
        val actualP = result.wotEntries.map { Pair(it.rpm, it.actualMap) }
        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
            LineChart(
                series = listOf(
                    ChartSeries("pssol (Requested)", requestedP, Primary, showPoints = true, showLine = false),
                    ChartSeries("pvdks_w (Actual)", actualP, ChartRed, showPoints = true, showLine = false)
                ),
                title = "Requested vs Actual Pressure",
                xAxisLabel = "RPM", yAxisLabel = "Pressure (mbar)"
            )
        }
        Spacer(Modifier.height(12.dp))

        // Boost error chart
        Box(modifier = Modifier.fillMaxWidth().height(250.dp)) {
            LineChart(
                series = listOf(ChartSeries("Boost Error (mbar)", result.pressureErrors, ChartOrange, showPoints = true, showLine = false)),
                title = "Boost Error vs RPM", xAxisLabel = "RPM", yAxisLabel = "Error (mbar)"
            )
        }
        Spacer(Modifier.height(12.dp))

        // RPM breakpoint table
        PerRpmTable(result.perRpmAnalysis["Link 3"], "RPM", "Avg Deficit (mbar)", "Max Deficit", "WGDC Correction", "%")

        // ── v4: PID Dynamics ──────────────────────────────────
        result.pidSimulation?.let { pidSim ->
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                        IconSectionTitle(Icons.Filled.Tune, "PID Dynamics Analysis")
                    Spacer(Modifier.height(8.dp))

                    val diag = pidSim.diagnosis
                    // Status indicators
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PidIndicator("Oscillation", diag.oscillationDetected, "${diag.oscillationCount} reversals")
                        PidIndicator("I-Term Windup", diag.windupDetected, "${String.format("%.0f", diag.windupDurationMs)}ms")
                        PidIndicator("Slow Convergence", diag.slowConvergence, "${String.format("%.0f", diag.convergenceTimeMs)}ms")
                        PidIndicator("Overshoot", diag.overshootDetected, "${String.format("%.0f", diag.overshootMagnitude)} mbar")
                    }

                    Text("Avg |lde|: ${String.format("%.0f", diag.avgAbsLde)} mbar",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))

                    // PID recommendations
                    if (diag.recommendations.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Recommendations:", style = MaterialTheme.typography.titleSmall)
                        for (rec in diag.recommendations) {
                            Text("• $rec", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                        }
                    }

                    // P/I/D contribution chart
                    if (pidSim.states.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        val pSeries = pidSim.states.map { Pair(it.timeMs, it.ldptv) }
                        val iSeries = pidSim.states.map { Pair(it.timeMs, it.lditv) }
                        val dSeries = pidSim.states.map { Pair(it.timeMs, it.ldrdtv) }
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                            LineChart(
                                series = listOf(
                                    ChartSeries("P-Term", pSeries, ChartRed),
                                    ChartSeries("I-Term", iSeries, ChartGreen),
                                    ChartSeries("D-Term", dSeries, ChartBlue)
                                ),
                                title = "PID Contributions (% duty)",
                                xAxisLabel = "Time (ms)", yAxisLabel = "Duty %"
                            )
                        }

                        // Control deviation chart
                        Spacer(Modifier.height(12.dp))
                        val ldeSeries = pidSim.states.map { Pair(it.timeMs, it.lde) }
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                            LineChart(
                                series = listOf(ChartSeries("Control Deviation (lde)", ldeSeries, ChartOrange)),
                                title = "Control Deviation (plsol − pvdks)",
                                xAxisLabel = "Time (ms)", yAxisLabel = "lde (mbar)"
                            )
                        }

                        // Actual vs PID WGDC chart
                        Spacer(Modifier.height(12.dp))
                        val pidWgdc = pidSim.states.map { Pair(it.timeMs, it.ldtv) }
                        val actualWgdc = pidSim.states.map { Pair(it.timeMs, it.actualWgdc) }
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                            LineChart(
                                series = listOf(
                                    ChartSeries("PID Simulated", pidWgdc, ChartBlue),
                                    ChartSeries("Actual WGDC", actualWgdc, ChartRed, showPoints = true, showLine = false)
                                ),
                                title = "Simulated vs Actual WGDC",
                                xAxisLabel = "Time (ms)", yAxisLabel = "WGDC %"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PerLinkVeContent(result: OptimizerCalculator.OptimizerResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val veOk = 100.0 - result.chainDiagnosis.veMismatchPercent
        Text("Link 4: VE Model (pvdks → rl_w) — ${String.format("%.0f", veOk)}% OK",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

        // KFPBRK correction factor chart
        val corrPoints = result.simulationResults
            .filter { it.kfpbrkCorrectionFactor.isFinite() && it.kfpbrkCorrectionFactor > 0 }
            .map { Pair(it.rpm, it.kfpbrkCorrectionFactor) }
        if (corrPoints.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                LineChart(
                    series = listOf(ChartSeries("KFPBRK Correction", corrPoints, ChartMagenta, showPoints = true, showLine = false)),
                    title = "VE Correction Factor by RPM (1.0 = perfect)", xAxisLabel = "RPM", yAxisLabel = "Correction Factor"
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Load chart
        val requestedL = result.wotEntries.map { Pair(it.rpm, it.requestedLoad) }
        val actualL = result.wotEntries.map { Pair(it.rpm, it.actualLoad) }
        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
            LineChart(
                series = listOf(
                    ChartSeries("rlsol (Requested)", requestedL, ChartBlue, showPoints = true, showLine = false),
                    ChartSeries("rl_w (Actual)", actualL, ChartGreen, showPoints = true, showLine = false)
                ),
                title = "Load: Requested vs Actual", xAxisLabel = "RPM", yAxisLabel = "Load (%)"
            )
        }
        Spacer(Modifier.height(12.dp))

        PerRpmTable(result.perRpmAnalysis["Link 4"], "RPM", "Avg VE Error (%)", "Max Error", "Correction Factor", "×")
    }
}

@Composable
private fun PerLinkTorqueContent(result: OptimizerCalculator.OptimizerResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val tOk = 100.0 - result.chainDiagnosis.torqueCappedPercent
        Text("Link 1: Torque Structure (LDRXN → rlsol) — ${String.format("%.0f", tOk)}% OK",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

        val ldrxn = if (result.simulationResults.isNotEmpty()) result.simulationResults[0].ldrxnTarget else 191.0
        val rlsolPoints = result.wotEntries.map { Pair(it.rpm, it.requestedLoad) }
        val targetLine = result.wotEntries.map { Pair(it.rpm, ldrxn) }

        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
            LineChart(
                series = listOf(
                    ChartSeries("LDRXN Target", targetLine, ChartOrange, showPoints = false, showLine = true, strokeWidth = 2f),
                    ChartSeries("rlsol (Actual Request)", rlsolPoints, ChartBlue, showPoints = true, showLine = false)
                ),
                title = "Load Request vs LDRXN Target", xAxisLabel = "RPM", yAxisLabel = "Load (%)"
            )
        }
        Spacer(Modifier.height(12.dp))

        PerRpmTable(result.perRpmAnalysis["Link 1"], "RPM", "Avg Headroom (%)", "Max Headroom", "% Capped", "%")
    }
}

@Composable
private fun PerLinkPlsolContent(result: OptimizerCalculator.OptimizerResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val pOk = 100.0 - result.chainDiagnosis.pssolErrorPercent
        Text("Link 2: PLSOL Model (rlsol → pssol) — ${String.format("%.0f", pOk)}% OK",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

        if (result.simulatedPressureSeries.isNotEmpty()) {
            val requestedP = result.wotEntries.map { Pair(it.rpm, it.requestedMap) }
            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                LineChart(
                    series = listOf(
                        ChartSeries("pssol (Logged)", requestedP, Primary, showPoints = true, showLine = false),
                        ChartSeries("Simulated pssol (PLSOL)", result.simulatedPressureSeries, ChartCyan, showPoints = true, showLine = false)
                    ),
                    title = "Logged pssol vs Simulated pssol (KFURL model)", xAxisLabel = "RPM", yAxisLabel = "Pressure (mbar)"
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        PerRpmTable(result.perRpmAnalysis["Link 2"], "RPM", "Avg pssol Error (mbar)", "Max Error", "Correction (mbar)", "mbar")
    }
}

@Composable
private fun PerRpmTable(
    analysis: List<RpmBreakpointAnalysis>?,
    col1: String, col2: String, col3: String, col4: String, unit: String
) {
    if (analysis.isNullOrEmpty()) {
        Text("No per-RPM data available.", style = MaterialTheme.typography.bodySmall)
        return
    }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Per-RPM Breakpoint Analysis", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            // Header row
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(col1, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
                Text("Samples", fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
                Text(col2, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(col3, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(col4, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("Conf.", fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()

            for (row in analysis) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(row.rpm.toInt().toString(), modifier = Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
                    Text(row.sampleCount.toString(), modifier = Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
                    Text(String.format("%.1f", row.avgError), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(String.format("%.1f", row.maxError), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(String.format("%.2f", row.correction), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Box(modifier = Modifier.width(50.dp), contentAlignment = Alignment.CenterStart) {
                        ConfidenceDot(row.confidence)
                    }
                }
            }
        }
    }
}

// ── Tab: Calibration (Before / After / Delta) ─────────────────────────

@Composable
private fun CalibrationTab(result: OptimizerCalculator.OptimizerResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val sm = result.suggestedMaps

        if (sm.kfldrl == null && sm.kfpbrk == null && sm.kfldimx == null && sm.kfmiop == null) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No map corrections available.", style = MaterialTheme.typography.titleMedium)
                    Text("Ensure KFLDRL and KFPBRK are configured in the Configuration tab.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return
        }

        sm.kfmiop?.let { MapDeltaCard(it, "Link 1: Torque Structure") }
        sm.kfmiop?.let { Spacer(Modifier.height(16.dp)) }
        sm.kfmirl?.let { MapDeltaCard(it, "Link 1: Torque Structure (Inverse)") }
        sm.kfmirl?.let { Spacer(Modifier.height(16.dp)) }
        sm.kfpbrk?.let { MapDeltaCard(it, "Link 2+4: VE Correction") }
        Spacer(Modifier.height(16.dp))
        sm.kfldrl?.let { MapDeltaCard(it, "Link 3: Boost Feedforward") }
        Spacer(Modifier.height(16.dp))
        sm.kfldimx?.let { MapDeltaCard(it, "Link 3: PID I-Term Limiter") }
    }
}

@Composable
private fun MapDeltaCard(delta: MapDelta, chainLink: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("${delta.mapName} — $chainLink", style = MaterialTheme.typography.titleMedium)

            // Confidence summary
            Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Cells modified: ${delta.cellsModified}/${delta.totalCells}", style = MaterialTheme.typography.bodySmall)
                Text("Coverage: ${String.format("%.0f", delta.coverage * 100)}%", style = MaterialTheme.typography.bodySmall)
                Text("Avg samples/cell: ${String.format("%.1f", delta.avgSamplesPerModifiedCell)}", style = MaterialTheme.typography.bodySmall)
            }

            // Current map
            Text("Current ${delta.mapName}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Box(modifier = Modifier.heightIn(max = 350.dp)) {
                MapTable(map = delta.current, editable = false)
            }

            // Suggested map
            Text("Suggested ${delta.mapName}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Box(modifier = Modifier.heightIn(max = 350.dp)) {
                MapTable(map = delta.suggested, editable = false)
            }

            // Delta map
            Text("Delta (Suggested − Current)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Box(modifier = Modifier.heightIn(max = 350.dp)) {
                MapTable(map = delta.delta, editable = false)
            }

            // Delta percent map
            Text("Delta % Change", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Box(modifier = Modifier.heightIn(max = 350.dp)) {
                MapTable(map = delta.deltaPercent, editable = false)
            }
        }
    }
}

// ── Tab: Prediction ───────────────────────────────────────────────────

@Composable
private fun PredictionTab(result: OptimizerCalculator.OptimizerResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val pred = result.prediction

        if (pred == null) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No prediction data available.", style = MaterialTheme.typography.titleMedium)
                    Text("Ensure map corrections have been computed (KFLDRL and/or KFPBRK configured).", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return
        }

        // ── Convergence Summary ─────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                    IconSectionTitle(Icons.AutoMirrored.Filled.TrendingUp, "Predicted Convergence")
                Text(
                    "Estimated ${String.format("%.0f", pred.convergenceImprovement)}% improvement in overall error",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Comparison table
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("", modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
                    Text("Current", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("Predicted", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("Change", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                PredictionRow("Avg Load Deficit", pred.currentAvgLoadDeficit, pred.predictedAvgLoadDeficit, "%")
                PredictionRow("Avg Pressure Error", pred.currentAvgPressureError, pred.predictedAvgPressureError, " mbar")
            }
        }

        // ── Predicted Chain Health ───────────────────────────────
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Predicted Chain Health (after corrections)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val predDiag = pred.predictedChainHealth
                ChainLinkBar("Link 1: LDRXN → rlsol", 100.0 - predDiag.torqueCappedPercent)
                ChainLinkBar("Link 2: rlsol → pssol", 100.0 - predDiag.pssolErrorPercent)
                ChainLinkBar("Link 3: pssol → pvdks", 100.0 - predDiag.boostShortfallPercent)
                ChainLinkBar("Link 4: pvdks → rl_w", 100.0 - predDiag.veMismatchPercent)
            }
        }

        // ── Pressure overlay chart ──────────────────────────────
        if (pred.predictedPressureSeries.isNotEmpty()) {
            Text("Pressure: Current vs Predicted vs Target", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val requestedP = result.wotEntries.map { Pair(it.rpm, it.requestedMap) }
            val actualP = result.wotEntries.map { Pair(it.rpm, it.actualMap) }

            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                LineChart(
                    series = listOf(
                        ChartSeries("pssol Target", requestedP, Primary, showPoints = false, showLine = true, strokeWidth = 1.5f),
                        ChartSeries("Current pvdks", actualP, ChartRed, showPoints = true, showLine = false),
                        ChartSeries("Predicted pvdks", pred.predictedPressureSeries, ChartCyan, showPoints = true, showLine = false)
                    ),
                    title = "Boost Pressure: Current → Predicted",
                    xAxisLabel = "RPM", yAxisLabel = "Pressure (mbar)"
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Load overlay chart ──────────────────────────────────
        if (pred.predictedLoadSeries.isNotEmpty()) {
            Text("Load: Current vs Predicted vs LDRXN", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val ldrxn = if (result.simulationResults.isNotEmpty()) result.simulationResults[0].ldrxnTarget else 191.0
            val ldrxnLine = result.wotEntries.map { Pair(it.rpm, ldrxn) }
            val actualL = result.wotEntries.map { Pair(it.rpm, it.actualLoad) }

            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                LineChart(
                    series = listOf(
                        ChartSeries("LDRXN Target", ldrxnLine, ChartOrange, showPoints = false, showLine = true, strokeWidth = 1.5f),
                        ChartSeries("Current rl_w", actualL, ChartRed, showPoints = true, showLine = false),
                        ChartSeries("Predicted rl_w", pred.predictedLoadSeries, ChartCyan, showPoints = true, showLine = false)
                    ),
                    title = "Engine Load: Current → Predicted",
                    xAxisLabel = "RPM", yAxisLabel = "Load (%)"
                )
            }
        }
    }
}

@Composable
private fun PredictionRow(label: String, current: Double, predicted: Double, unit: String) {
    val change = predicted - current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
        Text("${String.format("%.1f", current)}$unit", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text("${String.format("%.1f", predicted)}$unit", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(
            "${String.format("%+.1f", change)}$unit",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Tab: Export ────────────────────────────────────────────────────────

// ── Tab: Pulls (v4) ───────────────────────────────────────────────────

@Composable
private fun PullsTab(result: OptimizerCalculator.OptimizerResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val pulls = result.pulls
        val consistency = result.pullConsistency

        if (pulls.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No WOT pulls detected.", style = MaterialTheme.typography.titleMedium)
                    Text("WOT pulls require ≥10 samples with RPM span >800.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return
        }

        // ── Pull summary ──────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("${pulls.size} WOT Pull(s) Detected", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                val good = pulls.count { it.quality == PullSegmenter.PullQuality.GOOD }
                val noisy = pulls.count { it.quality == PullSegmenter.PullQuality.NOISY }
                val short = pulls.count { it.quality == PullSegmenter.PullQuality.SHORT }
                val incomplete = pulls.count { it.quality == PullSegmenter.PullQuality.INCOMPLETE }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    QualityDot(0); Text("Good: $good", style = MaterialTheme.typography.bodySmall)
                    QualityDot(1); Text("Noisy: $noisy", style = MaterialTheme.typography.bodySmall)
                    QualityDot(2); Text("Short: $short", style = MaterialTheme.typography.bodySmall)
                    QualityDot(3); Text("Incomplete: $incomplete", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── Per-pull cards ────────────────────────────────────
        for (pull in pulls) {
            val qualityLevel = when (pull.quality) {
                PullSegmenter.PullQuality.GOOD -> 0
                PullSegmenter.PullQuality.NOISY -> 1
                PullSegmenter.PullQuality.SHORT -> 2
                PullSegmenter.PullQuality.INCOMPLETE -> 3
            }

            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            QualityDot(qualityLevel)
                            Spacer(Modifier.width(6.dp))
                            Text("Pull ${pull.pullIndex + 1}", style = MaterialTheme.typography.titleSmall)
                        }
                        Text("${pull.sampleCount} samples",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Column {
                            Text("RPM: ${String.format("%.0f", pull.rpmStart)} → ${String.format("%.0f", pull.rpmEnd)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Column {
                            Text("Avg ΔP: ${String.format("%+.0f", pull.avgPressureError)} mbar",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Column {
                            Text("Avg Load Deficit: ${String.format("%+.1f", pull.avgLoadDeficit)}%",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Dominant error breakdown
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pull.dominantErrors.forEach { (source, pct) ->
                            if (pct > 5.0) {
                                Text("${source.take(12)}: ${String.format("%.0f", pct)}%",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Consistency warning if any
                    consistency[pull.pullIndex]?.let { note ->
                        Spacer(Modifier.height(4.dp))
                        IconTextRow(Icons.Filled.Warning, note,
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ── Convergence chart ─────────────────────────────────
        result.convergenceHistory?.let { conv ->
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Iterative Convergence History", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    if (conv.steps.isNotEmpty()) {
                        val convergenceSeries = conv.steps.map { Pair(it.iteration.toDouble(), it.totalError) }
                        LineChart(
                            series = listOf(ChartSeries("Total Error", convergenceSeries, ChartRed)),
                            xAxisLabel = "Iteration",
                            yAxisLabel = "Error",
                            modifier = Modifier.fillMaxWidth().height(200.dp)
                        )

                        Spacer(Modifier.height(8.dp))
                        // Convergence table
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("Iter", fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall)
                            Text("Total Error", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("ΔP Error", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("Load Deficit", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("Cells", fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                        for (step in conv.steps) {
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text("${step.iteration}", modifier = Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall)
                                Text(String.format("%.2f", step.totalError), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(String.format("%.1f", step.avgPressureError), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(String.format("%.1f", step.avgLoadDeficit), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text("${step.cellsModified}", modifier = Modifier.width(50.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // ── KFURL Sensitivity ─────────────────────────────────
        result.kfurlSolverResult?.let { solver ->
            if (solver.sensitivityCurve.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("KFURL Sensitivity", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Optimal: ${String.format("%.4f", solver.optimalKfurl)} (RMSE: ${String.format("%.1f", solver.rmse)} mbar)")
                        Spacer(Modifier.height(8.dp))
                        LineChart(
                            series = listOf(ChartSeries("RMSE vs KFURL", solver.sensitivityCurve, ChartBlue)),
                            xAxisLabel = "KFURL",
                            yAxisLabel = "RMSE (mbar)",
                            modifier = Modifier.fillMaxWidth().height(200.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Tab: Export ───────────────────────────────────────────────────────

@Composable
private fun ExportTab(
    result: OptimizerCalculator.OptimizerResult,
    kfldrlPair: Pair<TableDefinition, Map3d>?,
    kfldimxPair: Pair<TableDefinition, Map3d>?,
    kfpbrkPair: Pair<TableDefinition, Map3d>?,
    kfmiopPair: Pair<TableDefinition, Map3d>? = null,
    kfmirlPair: Pair<TableDefinition, Map3d>? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val sm = result.suggestedMaps

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                IconSectionTitle(Icons.Filled.SaveAlt, "Write Corrections to BIN")
                Spacer(Modifier.height(12.dp))

                var writeKfmiop by remember { mutableStateOf(true) }
                var writeKfmirl by remember { mutableStateOf(true) }
                var writeKfpbrk by remember { mutableStateOf(true) }
                var writeKfldrl by remember { mutableStateOf(true) }
                var writeKfldimx by remember { mutableStateOf(true) }
                var writeStatus by remember { mutableStateOf("") }
                var showConfirm by remember { mutableStateOf(false) }

                // KFMIOP (Link 1 — highest priority)
                if (sm.kfmiop != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = writeKfmiop, onCheckedChange = { writeKfmiop = it })
                        Text("KFMIOP — ${sm.kfmiop.cellsModified} cells modified (torque cap fix)")
                    }
                }

                // KFMIRL (Link 1 — inverse of KFMIOP)
                if (sm.kfmirl != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = writeKfmirl, onCheckedChange = { writeKfmirl = it })
                        Text("KFMIRL — ${sm.kfmirl.cellsModified} cells modified (inverse of KFMIOP)")
                    }
                }

                // KFPBRK (Link 2+4)
                if (sm.kfpbrk != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = writeKfpbrk, onCheckedChange = { writeKfpbrk = it })
                        Text("KFPBRK — ${sm.kfpbrk.cellsModified} cells modified (avg correction ${String.format("%.3f", sm.kfpbrk.avgAbsoluteDelta)})")
                    }
                }

                // KFLDRL (Link 3)
                if (sm.kfldrl != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = writeKfldrl, onCheckedChange = { writeKfldrl = it })
                        Text("KFLDRL — ${sm.kfldrl.cellsModified} cells modified (avg Δ ${String.format("%+.1f", sm.kfldrl.avgAbsoluteDelta)}% WGDC)")
                    }
                }

                // KFLDIMX (Link 3 — derived)
                if (sm.kfldimx != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = writeKfldimx, onCheckedChange = { writeKfldimx = it })
                        Text("KFLDIMX — ${sm.kfldimx.cellsModified} cells modified (derived from KFLDRL)")
                    }
                }

                val anyAvailable = sm.kfmiop != null || sm.kfmirl != null || sm.kfpbrk != null ||
                    sm.kfldrl != null || sm.kfldimx != null
                if (!anyAvailable) {
                    Text("No corrections to write. Ensure maps are configured in Configuration tab.", style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(12.dp))

                // Count how many maps will be written
                val writeCount = listOf(
                    writeKfmiop && sm.kfmiop != null,
                    writeKfmirl && sm.kfmirl != null,
                    writeKfpbrk && sm.kfpbrk != null,
                    writeKfldrl && sm.kfldrl != null,
                    writeKfldimx && sm.kfldimx != null
                ).count { it }

                val totalCellsModified = listOfNotNull(
                    if (writeKfmiop) sm.kfmiop?.cellsModified else null,
                    if (writeKfmirl) sm.kfmirl?.cellsModified else null,
                    if (writeKfpbrk) sm.kfpbrk?.cellsModified else null,
                    if (writeKfldrl) sm.kfldrl?.cellsModified else null,
                    if (writeKfldimx) sm.kfldimx?.cellsModified else null
                ).sum()

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { showConfirm = true },
                        enabled = writeCount > 0
                    ) {
                        Text("Write Selected to BIN ($writeCount maps)")
                    }
                }

                // ── Confirmation dialog ───────────────────────────
                if (showConfirm) {
                    AlertDialog(
                        onDismissRequest = { showConfirm = false },
                        title = { Text("Confirm BIN Write") },
                        text = {
                            Column {
                                Text("About to write $writeCount map(s) to BIN.")
                                Text("$totalCellsModified total cells will be modified.")
                                Spacer(Modifier.height(8.dp))
                                if (writeKfmiop && sm.kfmiop != null) Text("• KFMIOP: ${sm.kfmiop.cellsModified} cells")
                                if (writeKfmirl && sm.kfmirl != null) Text("• KFMIRL: ${sm.kfmirl.cellsModified} cells")
                                if (writeKfpbrk && sm.kfpbrk != null) Text("• KFPBRK: ${sm.kfpbrk.cellsModified} cells")
                                if (writeKfldrl && sm.kfldrl != null) Text("• KFLDRL: ${sm.kfldrl.cellsModified} cells")
                                if (writeKfldimx && sm.kfldimx != null) Text("• KFLDIMX: ${sm.kfldimx.cellsModified} cells")
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Warning, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Ensure you have a backup of your original BIN!", fontWeight = FontWeight.Bold)
                                }                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showConfirm = false
                                val binFile = BinFilePreferences.getStoredFile()
                                if (!binFile.exists()) {
                                    writeStatus = "ERROR: BIN file not found"
                                    return@Button
                                }

                                var written = 0
                                // Write in dependency order: KFMIOP → KFMIRL → KFPBRK → KFLDRL → KFLDIMX
                                if (writeKfmiop && sm.kfmiop != null) {
                                    val def = KfmiopPreferences.getSelectedMap()
                                    if (def != null) { BinWriter.write(binFile, def.first, sm.kfmiop.suggested); written++ }
                                }
                                if (writeKfmirl && sm.kfmirl != null) {
                                    val def = KfmirlPreferences.getSelectedMap()
                                    if (def != null) { BinWriter.write(binFile, def.first, sm.kfmirl.suggested); written++ }
                                }
                                if (writeKfpbrk && sm.kfpbrk != null) {
                                    val def = KfpbrkPreferences.getSelectedMap()
                                    if (def != null) { BinWriter.write(binFile, def.first, sm.kfpbrk.suggested); written++ }
                                }
                                if (writeKfldrl && sm.kfldrl != null) {
                                    val def = KfldrlPreferences.getSelectedMap()
                                    if (def != null) { BinWriter.write(binFile, def.first, sm.kfldrl.suggested); written++ }
                                }
                                if (writeKfldimx && sm.kfldimx != null) {
                                    val def = KfldimxPreferences.getSelectedMap()
                                    if (def != null) { BinWriter.write(binFile, def.first, sm.kfldimx.suggested); written++ }
                                }
                                writeStatus = "OK: $written map(s) written to BIN successfully"
                            }) {
                                Text("Confirm Write")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showConfirm = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                if (writeStatus.isNotEmpty()) {
                    TaggedMessageRow(writeStatus, modifier = Modifier.padding(top = 8.dp))
                }

                IconTextRow(
                    Icons.Filled.Warning,
                    "Always keep a backup of your original BIN file before writing!",
                    modifier = Modifier.padding(top = 8.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        // ── v4: Export Options ────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                IconSectionTitle(Icons.Filled.Description, "Export Reports")
                Spacer(Modifier.height(12.dp))

                var exportStatus by remember { mutableStateOf("") }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // XDF Patch Export
                    Button(onClick = {
                        val dialog = FileDialog(Frame(), "Save XDF Patch", FileDialog.SAVE)
                        dialog.file = "MxT_corrections.xdf"
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val file = dialog.file
                        if (dir != null && file != null) {
                            try {
                                val corrections = mutableListOf<Pair<TableDefinition, MapDelta>>()
                                sm.kfmiop?.let { d -> kfmiopPair?.first?.let { corrections.add(it to d) } }
                                sm.kfmirl?.let { d -> kfmirlPair?.first?.let { corrections.add(it to d) } }
                                sm.kfpbrk?.let { d -> kfpbrkPair?.first?.let { corrections.add(it to d) } }
                                sm.kfldrl?.let { d -> kfldrlPair?.first?.let { corrections.add(it to d) } }
                                sm.kfldimx?.let { d -> kfldimxPair?.first?.let { corrections.add(it to d) } }
                                XdfPatchWriter.write(File(dir, file), corrections)
                                exportStatus = "OK: XDF patch exported (${corrections.size} maps)"
                            } catch (e: Exception) {
                                exportStatus = "ERROR: XDF export failed: ${e.message}"
                            }
                        }
                    }) {
                        Text("Export XDF Patch")
                    }

                    // HTML Report Export
                    Button(onClick = {
                        val dialog = FileDialog(Frame(), "Save HTML Report", FileDialog.SAVE)
                        dialog.file = "MxT_report.html"
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val file = dialog.file
                        if (dir != null && file != null) {
                            try {
                                ReportExporter.export(
                                    outputFile = File(dir, file),
                                    result = result,
                                    pulls = result.pulls,
                                    transients = result.transients,
                                    safetyModes = result.safetyModes,
                                    convergence = result.convergenceHistory,
                                    kfurlResult = result.kfurlSolverResult
                                )
                                exportStatus = "OK: HTML report exported"
                            } catch (e: Exception) {
                                exportStatus = "ERROR: Report export failed: ${e.message}"
                            }
                        }
                    }) {
                        Text("Export HTML Report")
                    }
                }

                if (exportStatus.isNotEmpty()) {
                    TaggedMessageRow(exportStatus, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

// ── Chain Health Bar ──────────────────────────────────────────────────

@Composable
private fun ChainLinkBar(label: String, okPercent: Double) {
    val clamped = okPercent.coerceIn(0.0, 100.0)
    val barColor = when {
        clamped >= 90 -> ChartGreen
        clamped >= 70 -> ChartOrange
        else -> ChartRed
    }
    val needsFix = clamped < 80

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(260.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = (clamped / 100.0).toFloat())
                    .background(barColor, shape = RoundedCornerShape(4.dp))
            )
        }
        Text(
            "${String.format("%.0f", clamped)}% OK${if (needsFix) " ← FIX" else ""}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(90.dp).padding(start = 8.dp),
            fontWeight = if (needsFix) FontWeight.Bold else FontWeight.Normal
        )
    }
}


// ParameterField lives in ui.components.InfoTooltip — imported above.
