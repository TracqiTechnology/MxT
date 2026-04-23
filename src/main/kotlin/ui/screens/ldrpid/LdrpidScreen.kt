package ui.screens.ldrpid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import data.parser.bin.BinParser
import data.parser.me7log.Me7LogParser
import data.parser.med17log.Med17LogAdapter
import data.parser.med17log.Med17LogParser
import data.parser.xdf.TableDefinition
import data.preferences.MapPreference
import data.preferences.bin.BinFilePreferences
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.ldrpid.LdrpidPreferences
import data.preferences.platform.EcuPlatformPreference
import data.model.EcuPlatform
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.ldrpid.LdrpidCalculator
import domain.model.ldrpid.LdrpidOptimizerBridge
import domain.model.optimizer.OptimizerCalculator
import domain.model.simulator.PidSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.components.MapAxis
import ui.components.MapTable
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private enum class WriteStatus { Idle, Success, Error }

private fun findMap(
    mapList: List<Pair<TableDefinition, Map3d>>,
    pref: MapPreference
): Pair<TableDefinition, Map3d>? {
    val selected = pref.getSelectedMap()
    return if (selected != null) {
        mapList.find { it.first.tableName == selected.first.tableName }
    } else null
}

@Composable
fun LdrpidScreen(preloadedLogDir: java.io.File? = null) {
    val mapList by BinParser.mapList.collectAsState()
    val scope = rememberCoroutineScope()

    val kfldrlPair = remember(mapList) { findMap(mapList, KfldrlPreferences) }
    val kfldimxPair = remember(mapList) { findMap(mapList, KfldimxPreferences) }

    // State for the 4 map tables
    var nonLinearMap by remember { mutableStateOf<Map3d?>(null) }
    var linearMap by remember { mutableStateOf<Map3d?>(null) }
    var kfldrlMap by remember { mutableStateOf<Map3d?>(null) }
    var kfldimxMap by remember { mutableStateOf<Map3d?>(null) }
    var kfldimxXAxis by remember { mutableStateOf<Array<Array<Double>>?>(null) }
    // Sample count state for confidence display
    var nonLinearSampleCounts by remember { mutableStateOf<Array<IntArray>?>(null) }
    var kfldrlSampleCounts by remember { mutableStateOf<Array<IntArray>?>(null) }
    var kfldimxSampleCounts by remember { mutableStateOf<Array<IntArray>?>(null) }

    // Initialize maps from preferences (only set KFLDRL/KFLDIMX definitions, not empty zeros)
    LaunchedEffect(kfldrlPair, kfldimxPair) {
        if (kfldrlPair != null) {
            kfldrlMap = kfldrlPair.second
            // Don't pre-fill nonLinearMap/linearMap with zeros — show "load log" prompt instead
        }
        if (kfldimxPair != null) {
            kfldimxMap = kfldimxPair.second
            kfldimxXAxis = arrayOf(kfldimxPair.second.xAxis)
        }
    }

    fun recomputeFromNonLinear(editedNonLinear: Map3d) {
        val kfldrlDef = kfldrlPair ?: return
        val kfldimxDef = kfldimxPair ?: return
        nonLinearMap = editedNonLinear
        val linear = LdrpidCalculator.calculateLinearTable(editedNonLinear.zAxis, kfldrlDef.second)
        linearMap = linear
        val newKfldrl = LdrpidCalculator.calculateKfldrl(editedNonLinear.zAxis, linear.zAxis, kfldrlDef.second)
        kfldrlMap = newKfldrl
        val newKfldimx = LdrpidCalculator.calculateKfldimx(editedNonLinear.zAxis, linear.zAxis, kfldrlDef.second, kfldimxDef.second)
        kfldimxMap = newKfldimx
        kfldimxXAxis = arrayOf(newKfldimx.xAxis)
    }

    // Progress & log state
    var progressValue by remember { mutableStateOf(0) }
    var progressMax by remember { mutableStateOf(1) }
    var showProgress by remember { mutableStateOf(false) }
    var logDirName by remember { mutableStateOf("No Directory Selected") }
    var consistencyWarning by remember { mutableStateOf<String?>(null) }

    // Auto-load log data when preloadedLogDir is provided (screenshot harness)
    LaunchedEffect(preloadedLogDir, kfldrlPair, kfldimxPair) {
        if (preloadedLogDir != null && kfldrlPair != null && kfldimxPair != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val med17Parser = Med17LogParser()
                val med17Values = med17Parser.parseLogDirectory(
                    Med17LogParser.LogType.LDRPID, preloadedLogDir
                ) { _, _ -> }
                val values = Med17LogAdapter.toMe7LdrpidFormat(med17Values)
                val result = LdrpidCalculator.calculateWithCounts(values, kfldrlPair!!.second, kfldimxPair!!.second)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    nonLinearMap = result.nonLinearOutput
                    linearMap = result.linearOutput
                    kfldrlMap = result.kfldrl
                    kfldimxMap = result.kfldimx
                    kfldimxXAxis = arrayOf(result.kfldimx.xAxis)
                    nonLinearSampleCounts = result.nonLinearSampleCounts
                    kfldrlSampleCounts = result.kfldrlSampleCounts
                    kfldimxSampleCounts = result.kfldimxSampleCounts
                    logDirName = preloadedLogDir.name
                }
            }
        }
    }

    // Write state
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile
    val kfldrlConfigured = kfldrlPair != null
    val kfldimxConfigured = kfldimxPair != null

    var writeKfldrlStatus by remember { mutableStateOf(WriteStatus.Idle) }
    var writeKfldimxStatus by remember { mutableStateOf(WriteStatus.Idle) }
    var showWriteKfldrlConfirm by remember { mutableStateOf(false) }
    var showWriteKfldimxConfirm by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(writeKfldrlStatus) {
        if (writeKfldrlStatus != WriteStatus.Idle) { delay(3000); writeKfldrlStatus = WriteStatus.Idle }
    }
    LaunchedEffect(writeKfldimxStatus) {
        if (writeKfldimxStatus != WriteStatus.Idle) { delay(3000); writeKfldimxStatus = WriteStatus.Idle }
    }

    // Write confirmation dialogs
    if (showWriteKfldrlConfirm) {
        AlertDialog(
            onDismissRequest = { showWriteKfldrlConfirm = false },
            title = { Text("Write KFLDRL") },
            text = { Text("Are you sure you want to write KFLDRL to the binary?") },
            confirmButton = {
                TextButton(onClick = {
                    showWriteKfldrlConfirm = false
                    val tableDef = kfldrlPair?.first
                    if (tableDef != null && kfldrlMap != null) {
                        try {
                            BinWriter.write(BinFilePreferences.file.value, tableDef, kfldrlMap!!)
                            writeKfldrlStatus = WriteStatus.Success
                        } catch (e: Exception) {
                            e.printStackTrace()
                            writeKfldrlStatus = WriteStatus.Error
                        }
                    }
                }) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { showWriteKfldrlConfirm = false }) { Text("No") } }
        )
    }

    if (showWriteKfldimxConfirm) {
        AlertDialog(
            onDismissRequest = { showWriteKfldimxConfirm = false },
            title = { Text("Write KFLDIMX") },
            text = { Text("Are you sure you want to write KFLDIMX to the binary?") },
            confirmButton = {
                TextButton(onClick = {
                    showWriteKfldimxConfirm = false
                    val tableDef = kfldimxPair?.first
                    if (tableDef != null && kfldimxMap != null) {
                        try {
                            BinWriter.write(BinFilePreferences.file.value, tableDef, kfldimxMap!!)
                            writeKfldimxStatus = WriteStatus.Success
                        } catch (e: Exception) {
                            e.printStackTrace()
                            writeKfldimxStatus = WriteStatus.Error
                        }
                    }
                }) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { showWriteKfldimxConfirm = false }) { Text("No") } }
        )
    }

    // ── Main layout ───────────────────────────────────────────────────
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Configuration Card ────────────────────────────────────────
        LdrpidConfigCard(
            kfldrlName = kfldrlPair?.first?.tableName,
            kfldimxName = kfldimxPair?.first?.tableName,
            logDirName = logDirName,
            showProgress = showProgress,
            progressValue = progressValue,
            progressMax = progressMax,
            onLoadLogs = {
                val dialog = FileDialog(Frame(), "Select Log Directory", FileDialog.LOAD)
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                val lastDir = LdrpidPreferences.lastDirectory
                if (lastDir.isNotEmpty()) dialog.directory = lastDir
                dialog.isVisible = true
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val selectedDir = File(dir, file)
                    LdrpidPreferences.lastDirectory = selectedDir.parent ?: dir
                    logDirName = selectedDir.path
                    showProgress = true
                    progressValue = 0
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val values = if (EcuPlatformPreference.platform == EcuPlatform.MED17) {
                                // Parse each file individually for consistency check
                                val csvFiles = selectedDir.listFiles()
                                    ?.filter { it.isFile && it.name.endsWith(".csv", ignoreCase = true) }
                                    ?: emptyList()
                                val perFileData = mutableListOf<Map<data.contract.Me7LogFileContract.Header, List<Double>>>()
                                csvFiles.forEachIndexed { idx, csvFile ->
                                    val parser = Med17LogParser()
                                    val fileData = parser.parseLogFile(Med17LogParser.LogType.LDRPID, csvFile)
                                    perFileData.add(Med17LogAdapter.toMe7LdrpidFormat(fileData))
                                    progressValue = idx + 1
                                    progressMax = csvFiles.size
                                    showProgress = idx < csvFiles.size - 1
                                }
                                // Check consistency across files
                                val warning = LdrpidCalculator.checkLogConsistency(perFileData)
                                withContext(Dispatchers.Main) { consistencyWarning = warning }

                                // Aggregate: parse as directory for combined output
                                val med17Parser = Med17LogParser()
                                val med17Values = med17Parser.parseLogDirectory(
                                    Med17LogParser.LogType.LDRPID, selectedDir
                                ) { _, _ -> }
                                Med17LogAdapter.toMe7LdrpidFormat(med17Values)
                            } else {
                                consistencyWarning = null
                                val parser = Me7LogParser()
                                parser.parseLogDirectory(
                                    Me7LogParser.LogType.LDRPID, selectedDir
                                ) { value, max ->
                                    progressValue = value
                                    progressMax = max
                                    showProgress = value < max - 1
                                }
                            }
                            val kfldimxDef = KfldimxPreferences.getSelectedMap()
                            val kfldrlDef = KfldrlPreferences.getSelectedMap()
                            if (kfldimxDef != null && kfldrlDef != null) {
                                val result = LdrpidCalculator.calculateWithCounts(values, kfldrlDef.second, kfldimxDef.second)
                                withContext(Dispatchers.Main) {
                                    nonLinearMap = result.nonLinearOutput
                                    linearMap = result.linearOutput
                                    kfldrlMap = result.kfldrl
                                    kfldimxMap = result.kfldimx
                                    kfldimxXAxis = arrayOf(result.kfldimx.xAxis)
                                    nonLinearSampleCounts = result.nonLinearSampleCounts
                                    kfldrlSampleCounts = result.kfldrlSampleCounts
                                    kfldimxSampleCounts = result.kfldimxSampleCounts
                                    showProgress = false
                                }
                            }
                        }
                    }
                }
            }
        )

        // ── Log Consistency Warning ───────────────────────────────────
        AnimatedVisibility(visible = consistencyWarning != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        consistencyWarning ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // ── Tabbed Comparison Area ────────────────────────────────────
        LdrpidComparisonArea(
            modifier = Modifier.weight(1f),
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            nonLinearMap = nonLinearMap,
            linearMap = linearMap,
            kfldrlMap = kfldrlMap,
            kfldimxMap = kfldimxMap,
            kfldimxXAxis = kfldimxXAxis,
            onNonLinearChanged = { recomputeFromNonLinear(it) },
            nonLinearSampleCounts = nonLinearSampleCounts,
            kfldrlSampleCounts = kfldrlSampleCounts,
            kfldimxSampleCounts = kfldimxSampleCounts,
            onExportToOptimizer = {
                if (kfldrlMap != null) LdrpidOptimizerBridge.exportKfldrl(kfldrlMap!!)
                if (kfldimxMap != null) LdrpidOptimizerBridge.exportKfldimx(kfldimxMap!!)
            }
        )

        // ── Write to Binary Section ───────────────────────────────────
        LdrpidWriteSection(
            binLoaded = binLoaded,
            binFileName = if (binLoaded) binFile.name else null,
            kfldrlConfigured = kfldrlConfigured,
            kfldrlName = kfldrlPair?.first?.tableName,
            kfldimxConfigured = kfldimxConfigured,
            kfldimxName = kfldimxPair?.first?.tableName,
            canWriteKfldrl = binLoaded && kfldrlConfigured && kfldrlMap != null,
            canWriteKfldimx = binLoaded && kfldimxConfigured && kfldimxMap != null,
            writeKfldrlStatus = writeKfldrlStatus,
            writeKfldimxStatus = writeKfldimxStatus,
            onWriteKfldrl = { showWriteKfldrlConfirm = true },
            onWriteKfldimx = { showWriteKfldimxConfirm = true }
        )
    }
}

// ── Configuration Card ────────────────────────────────────────────────

@Composable
private fun LdrpidConfigCard(
    kfldrlName: String?,
    kfldimxName: String?,
    logDirName: String,
    showProgress: Boolean,
    progressValue: Int,
    progressMax: Int,
    onLoadLogs: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "LDRPID \u2014 Feed-Forward PID Linearization",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Linearize boost pressure control for wastegate pre-control (KFLDRL) and PID I-limiter (KFLDIMX)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    PrerequisiteRow(
                        label = "KFLDRL",
                        detail = kfldrlName ?: "Not configured",
                        met = kfldrlName != null
                    )
                    PrerequisiteRow(
                        label = "KFLDIMX",
                        detail = kfldimxName ?: "Not configured",
                        met = kfldimxName != null
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Button(onClick = onLoadLogs) {
                        Text(
                            if (EcuPlatformPreference.platform == EcuPlatform.MED17)
                                "Load DS1 Logs" else "Load ME7 Logs"
                        )
                    }
                    if (showProgress) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { if (progressMax > 0) progressValue.toFloat() / progressMax.toFloat() else 0f },
                            modifier = Modifier.width(200.dp).height(6.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = logDirName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Tabbed Comparison Area ────────────────────────────────────────────

/** Build a cellColorProvider from sample counts: green=high confidence, yellow=low, null=interpolated */
private fun sampleCountColorProvider(counts: Array<IntArray>?): ((Int, Int) -> Color?)? {
    if (counts == null) return null
    return { rowIdx, colIdx ->
        val count = counts.getOrNull(rowIdx)?.getOrNull(colIdx) ?: 0
        when {
            count >= 5 -> Color(0x4000C853)   // green — high confidence
            count >= 1 -> Color(0x40FFD600)   // yellow — low confidence
            else -> null                       // interpolated — fall back to default HSB
        }
    }
}

@Composable
private fun LdrpidComparisonArea(
    modifier: Modifier = Modifier,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    nonLinearMap: Map3d?,
    linearMap: Map3d?,
    kfldrlMap: Map3d?,
    kfldimxMap: Map3d?,
    kfldimxXAxis: Array<Array<Double>>?,
    onNonLinearChanged: (Map3d) -> Unit,
    nonLinearSampleCounts: Array<IntArray>? = null,
    kfldrlSampleCounts: Array<IntArray>? = null,
    kfldimxSampleCounts: Array<IntArray>? = null,
    onExportToOptimizer: () -> Unit = {}
) {
    Column(modifier = modifier) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { onTabSelected(0) }, text = { Text("Boost Tables") })
            Tab(selected = selectedTab == 1, onClick = { onTabSelected(1) }, text = { Text("KFLDRL") })
            Tab(selected = selectedTab == 2, onClick = { onTabSelected(2) }, text = { Text("KFLDIMX") })
            Tab(selected = selectedTab == 3, onClick = { onTabSelected(3) }, text = { Text("PID Analysis") })
        }

        when (selectedTab) {
            0 -> BoostTablesTab(nonLinearMap, linearMap, onNonLinearChanged, Modifier.fillMaxWidth().weight(1f), nonLinearSampleCounts)
            1 -> KfldrlTab(kfldrlMap, Modifier.fillMaxWidth().weight(1f), kfldrlSampleCounts, onExportToOptimizer)
            2 -> KfldimxTab(kfldimxMap, kfldimxXAxis, Modifier.fillMaxWidth().weight(1f), kfldimxSampleCounts)
            3 -> PidAnalysisTab(kfldrlMap, kfldimxMap, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun BoostTablesTab(
    nonLinearMap: Map3d?,
    linearMap: Map3d?,
    onNonLinearChanged: (Map3d) -> Unit,
    modifier: Modifier = Modifier,
    nonLinearSampleCounts: Array<IntArray>? = null
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Non-Linear Boost (editable)
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = "Non-Linear Boost (editable)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (nonLinearMap != null && nonLinearMap.zAxis.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(
                        map = nonLinearMap,
                        editable = true,
                        onMapChanged = { onNonLinearChanged(it) },
                        cellColorProvider = sampleCountColorProvider(nonLinearSampleCounts)
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No boost data loaded", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Click the load button above to load WOT log data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Linear Boost (read-only)
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = "Linear Boost",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (linearMap != null && linearMap.zAxis.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(map = linearMap, editable = false)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Calculated after log load", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun KfldrlTab(kfldrlMap: Map3d?, modifier: Modifier = Modifier, sampleCounts: Array<IntArray>? = null, onExportToOptimizer: () -> Unit = {}) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "KFLDRL \u2014 Linearized Wastegate Duty Cycle",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (kfldrlMap != null && kfldrlMap.zAxis.isNotEmpty()) {
                OutlinedButton(
                    onClick = onExportToOptimizer,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Export to Optimizer", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (kfldrlMap != null && kfldrlMap.zAxis.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                MapTable(
                    map = kfldrlMap,
                    editable = false,
                    cellColorProvider = sampleCountColorProvider(sampleCounts)
                )
            }
        } else {
            Text("No map data", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun KfldimxTab(
    kfldimxMap: Map3d?,
    kfldimxXAxis: Array<Array<Double>>?,
    modifier: Modifier = Modifier,
    sampleCounts: Array<IntArray>? = null
) {
    Column(modifier = modifier) {
        if (kfldimxXAxis != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "KFLDIMX X-Axis (Boost Pressure)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    MapAxis(data = kfldimxXAxis, editable = false)
                }
            }
        }

        Text(
            text = "KFLDIMX \u2014 PID I-Regulator Limit",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        if (kfldimxMap != null && kfldimxMap.zAxis.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                MapTable(
                    map = kfldimxMap,
                    editable = false,
                    cellColorProvider = sampleCountColorProvider(sampleCounts)
                )
            }
        } else {
            Text("No map data", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── PID Analysis Tab ──────────────────────────────────────────────────

private enum class StabilityRating(val label: String, val color: Color) {
    STABLE("Stable", Color(0xFF00C853)),
    MARGINAL("Marginal", Color(0xFFFFD600)),
    UNSTABLE("Oscillation Risk", Color(0xFFFF1744))
}

/**
 * Runs [PidSimulator] against the computed KFLDRL/KFLDIMX and displays
 * per-RPM stability metrics with color-coded warnings.
 */
@Composable
private fun PidAnalysisTab(
    kfldrlMap: Map3d?,
    kfldimxMap: Map3d?,
    modifier: Modifier = Modifier
) {
    if (kfldrlMap == null || kfldrlMap.zAxis.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PID Analysis unavailable", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Load WOT log data and compute KFLDRL to enable PID analysis",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val rpmBreakpoints = kfldrlMap.yAxis
    val analysisResults: List<Pair<Double, PidSimulator.PidSimulationResult>> = remember(kfldrlMap, kfldimxMap) {
        rpmBreakpoints.map { rpm ->
            val pull = buildSyntheticPull(rpm, targetBoostMbar = 2200.0, count = 60)
            val result = PidSimulator.simulate(
                pullEntries = pull,
                kfldrq0 = null,
                kfldrq1 = null,
                kfldrq2 = null,
                kfldrl = kfldrlMap,
                kfldimx = kfldimxMap
            )
            rpm to result
        }
    }

    Column(modifier = modifier.padding(top = 8.dp)) {
        Text(
            text = "PID Stability Analysis",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Synthetic WOT pulls at each RPM breakpoint \u2014 tests PID convergence with computed KFLDRL",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Surface(
            shape = MaterialTheme.shapes.small,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("RPM", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(60.dp))
                    Text("Status", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(120.dp))
                    Text("Convergence", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(100.dp))
                    Text("Oscillations", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(90.dp))
                    Text("Overshoot", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(90.dp))
                    Text("Avg |Error|", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(90.dp))
                }

                HorizontalDivider()

                for (entry in analysisResults) {
                    val rpm = entry.first
                    val d = entry.second.diagnosis
                    val rating = when {
                        d.oscillationDetected -> StabilityRating.UNSTABLE
                        d.slowConvergence || d.overshootDetected || d.windupDetected -> StabilityRating.MARGINAL
                        else -> StabilityRating.STABLE
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${rpm.toInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(60.dp)
                        )
                        Row(modifier = Modifier.width(120.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = rating.color.copy(alpha = 0.2f),
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Text(
                                    rating.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = rating.color,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            "${String.format("%.0f", d.convergenceTimeMs)} ms",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(100.dp)
                        )
                        Text(
                            "${d.oscillationCount}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(90.dp)
                        )
                        Text(
                            if (d.overshootDetected) "${String.format("%.0f", d.overshootMagnitude)} mbar" else "\u2014",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(90.dp)
                        )
                        Text(
                            "${String.format("%.1f", d.avgAbsLde)} mbar",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(90.dp)
                        )
                    }
                }
            }
        }

        // Recommendations
        val allRecommendations: List<String> = analysisResults.flatMap { pair -> pair.second.diagnosis.recommendations }.distinct()
        if (allRecommendations.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Recommendations",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    for (rec in allRecommendations) {
                        Text(
                            "\u2022 $rec",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Build a synthetic WOT pull at a fixed RPM for PID stability probing. */
private fun buildSyntheticPull(
    rpm: Double,
    targetBoostMbar: Double = 2200.0,
    barometricPressure: Double = 1013.0,
    count: Int = 60
): List<OptimizerCalculator.WotLogEntry> {
    return (0 until count).map { i ->
        val rampFraction = (i.toDouble() / (count / 2)).coerceAtMost(1.0)
        val actualMap = barometricPressure + (targetBoostMbar - barometricPressure) * rampFraction
        OptimizerCalculator.WotLogEntry(
            rpm = rpm,
            requestedLoad = 191.0,
            actualLoad = 191.0,
            requestedMap = targetBoostMbar,
            actualMap = actualMap,
            barometricPressure = barometricPressure,
            wgdc = 60.0,
            throttleAngle = 100.0
        )
    }
}

// ── Write to Binary Section ───────────────────────────────────────────

@Composable
private fun LdrpidWriteSection(
    binLoaded: Boolean,
    binFileName: String?,
    kfldrlConfigured: Boolean,
    kfldrlName: String?,
    kfldimxConfigured: Boolean,
    kfldimxName: String?,
    canWriteKfldrl: Boolean,
    canWriteKfldimx: Boolean,
    writeKfldrlStatus: WriteStatus,
    writeKfldimxStatus: WriteStatus,
    onWriteKfldrl: () -> Unit,
    onWriteKfldimx: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Write to Binary",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            PrerequisiteRow(label = "BIN file", detail = if (binLoaded) binFileName!! else "Not loaded", met = binLoaded)
            PrerequisiteRow(label = "KFLDRL map", detail = if (kfldrlConfigured) kfldrlName!! else "Not configured", met = kfldrlConfigured)
            PrerequisiteRow(label = "KFLDIMX map", detail = if (kfldimxConfigured) kfldimxName!! else "Not configured", met = kfldimxConfigured)

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onWriteKfldrl, enabled = canWriteKfldrl) { Text("Write KFLDRL") }
                WriteStatusIndicator(writeKfldrlStatus)
                Button(onClick = onWriteKfldimx, enabled = canWriteKfldimx) { Text("Write KFLDIMX") }
                WriteStatusIndicator(writeKfldimxStatus)
            }

            if (!canWriteKfldrl || !canWriteKfldimx) {
                val message = when {
                    !binLoaded -> "Load a BIN file to write."
                    !kfldrlConfigured && !kfldimxConfigured -> "Select KFLDRL and KFLDIMX map definitions in Configuration."
                    !kfldrlConfigured -> "Select the KFLDRL map definition in Configuration."
                    !kfldimxConfigured -> "Select the KFLDIMX map definition in Configuration."
                    else -> "Load log data to generate maps."
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun WriteStatusIndicator(status: WriteStatus) {
    AnimatedVisibility(visible = status != WriteStatus.Idle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (status == WriteStatus.Success) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = if (status == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (status == WriteStatus.Success) "Written" else "Failed",
                style = MaterialTheme.typography.bodySmall,
                color = if (status == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PrerequisiteRow(label: String, detail: String, met: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (met) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = if (met) "Ready" else "Not ready",
            tint = if (met) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "$label:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(100.dp))
        Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
