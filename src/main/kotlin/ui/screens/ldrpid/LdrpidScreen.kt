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
import androidx.compose.ui.platform.testTag
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

private fun matchesBinaryShape(tableDefinition: TableDefinition, map: Map3d?): Boolean {
    if (map == null) return false
    val rows = maxOf(tableDefinition.zAxis.rowCount, 1)
    val columns = maxOf(tableDefinition.zAxis.columnCount, 1)
    return (tableDefinition.xAxis == null || map.xAxis.isEmpty() ||
        map.xAxis.size == tableDefinition.xAxis.indexCount) &&
        (tableDefinition.yAxis == null || map.yAxis.isEmpty() ||
            map.yAxis.size == tableDefinition.yAxis.indexCount) &&
        map.zAxis.size == rows &&
        map.zAxis.all { it.size == columns }
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
    var logDiagnostics by remember {
        mutableStateOf<List<LdrpidCalculator.LogDiagnosticRow>>(emptyList())
    }

    // A definition/BIN change invalidates all outputs derived from the old inputs.
    LaunchedEffect(kfldrlPair, kfldimxPair) {
        nonLinearMap = null
        linearMap = null
        kfldrlMap = null
        kfldimxMap = null
        kfldimxXAxis = null
        nonLinearSampleCounts = null
        kfldrlSampleCounts = null
        kfldimxSampleCounts = null
        logDiagnostics = emptyList()
    }

    fun recomputeFromNonLinear(editedNonLinear: Map3d) {
        val kfldrlDef = kfldrlPair ?: return
        val kfldimxDef = kfldimxPair ?: return
        nonLinearMap = editedNonLinear
        val linear = LdrpidCalculator.calculateLinearTable(
            editedNonLinear.zAxis,
            kfldrlDef.second,
            editedNonLinear.xAxis,
            editedNonLinear.yAxis
        )
        linearMap = linear
        val newKfldrl = LdrpidCalculator.calculateKfldrl(
            editedNonLinear.zAxis,
            linear.zAxis,
            kfldrlDef.second,
            editedNonLinear.xAxis,
            editedNonLinear.yAxis
        )
        kfldrlMap = newKfldrl
        val newKfldimx = LdrpidCalculator.calculateKfldimx(
            editedNonLinear.zAxis,
            linear.zAxis,
            kfldrlDef.second,
            kfldimxDef.second,
            editedNonLinear.xAxis,
            editedNonLinear.yAxis
        )
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
            nonLinearMap = null
            linearMap = null
            kfldrlMap = null
            kfldimxMap = null
            nonLinearSampleCounts = null
            kfldrlSampleCounts = null
            kfldimxSampleCounts = null
            logDiagnostics = emptyList()
            showProgress = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val med17Parser = Med17LogParser()
                    val med17Values = med17Parser.parseLogDirectory(
                        Med17LogParser.LogType.LDRPID, preloadedLogDir
                    ) { _, _ -> }
                    val values = Med17LogAdapter.toMe7LdrpidFormat(med17Values)
                    val result = LdrpidCalculator.calculateWithCounts(
                        values,
                        kfldrlPair!!.second,
                        kfldimxPair!!.second
                    )
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        nonLinearMap = result.nonLinearOutput
                        linearMap = result.linearOutput
                        kfldrlMap = result.kfldrl
                        kfldimxMap = result.kfldimx
                        kfldimxXAxis = arrayOf(result.kfldimx.xAxis)
                        nonLinearSampleCounts = result.nonLinearSampleCounts
                        kfldrlSampleCounts = result.kfldrlSampleCounts
                        kfldimxSampleCounts = result.kfldimxSampleCounts
                        logDiagnostics = result.logDiagnostics
                        logDirName = preloadedLogDir.name
                        showProgress = false
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        logDirName = "Error: ${e.message}"
                        showProgress = false
                    }
                }
            }
        }
    }

    // Write state
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile
    val kfldrlConfigured = kfldrlPair != null
    val kfldimxConfigured = kfldimxPair != null
    val hasMeasuredCoverage =
        nonLinearSampleCounts?.any { row -> row.any { it > 0 } } == true
    val kfldrlShapeMatches = kfldrlPair?.first?.let { matchesBinaryShape(it, kfldrlMap) } == true
    val kfldimxShapeMatches = kfldimxPair?.first?.let { matchesBinaryShape(it, kfldimxMap) } == true

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
                    nonLinearMap = null
                    linearMap = null
                    kfldrlMap = null
                    kfldimxMap = null
                    kfldimxXAxis = null
                    nonLinearSampleCounts = null
                    kfldrlSampleCounts = null
                    kfldimxSampleCounts = null
                    logDiagnostics = emptyList()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                val values = if (EcuPlatformPreference.platform == EcuPlatform.MED17) {
                                    // Parse each file individually for consistency check
                                    val csvFiles = selectedDir.listFiles()
                                        ?.filter { it.isFile && it.name.endsWith(".csv", ignoreCase = true) }
                                        ?: emptyList()
                                    require(csvFiles.isNotEmpty()) {
                                        "No CSV log files found in ${selectedDir.name}"
                                    }
                                    val perFileData = mutableListOf<Map<data.contract.Me7LogFileContract.Header, List<Double>>>()
                                    csvFiles.forEachIndexed { idx, csvFile ->
                                        val parser = Med17LogParser()
                                        val fileData = parser.parseLogFile(
                                            Med17LogParser.LogType.LDRPID,
                                            csvFile
                                        )
                                        perFileData.add(Med17LogAdapter.toMe7LdrpidFormat(fileData))
                                        withContext(Dispatchers.Main) {
                                            progressValue = idx + 1
                                            progressMax = csvFiles.size
                                        }
                                    }
                                    val warning = LdrpidCalculator.checkLogConsistency(perFileData)
                                    withContext(Dispatchers.Main) { consistencyWarning = warning }

                                    val med17Parser = Med17LogParser()
                                    val med17Values = med17Parser.parseLogDirectory(
                                        Med17LogParser.LogType.LDRPID, selectedDir
                                    ) { _, _ -> }
                                    Med17LogAdapter.toMe7LdrpidFormat(med17Values)
                                } else {
                                    withContext(Dispatchers.Main) { consistencyWarning = null }
                                    val parser = Me7LogParser()
                                    parser.parseLogDirectory(
                                        Me7LogParser.LogType.LDRPID, selectedDir
                                    ) { value, max ->
                                        progressValue = value
                                        progressMax = max
                                    }
                                }
                                val kfldimxDef = KfldimxPreferences.getSelectedMap()
                                val kfldrlDef = KfldrlPreferences.getSelectedMap()
                                require(kfldimxDef != null && kfldrlDef != null) {
                                    "Select KFLDRL and KFLDIMX map definitions first"
                                }
                                val result = LdrpidCalculator.calculateWithCounts(
                                    values,
                                    kfldrlDef.second,
                                    kfldimxDef.second
                                )
                                withContext(Dispatchers.Main) {
                                    nonLinearMap = result.nonLinearOutput
                                    linearMap = result.linearOutput
                                    kfldrlMap = result.kfldrl
                                    kfldimxMap = result.kfldimx
                                    kfldimxXAxis = arrayOf(result.kfldimx.xAxis)
                                    nonLinearSampleCounts = result.nonLinearSampleCounts
                                    kfldrlSampleCounts = result.kfldrlSampleCounts
                                    kfldimxSampleCounts = result.kfldimxSampleCounts
                                    logDiagnostics = result.logDiagnostics
                                    showProgress = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    logDirName = "Error: ${e.message}"
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
            logDiagnostics = logDiagnostics,
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
            canWriteKfldrl = binLoaded && kfldrlConfigured && hasMeasuredCoverage && kfldrlShapeMatches,
            canWriteKfldimx = binLoaded && kfldimxConfigured && hasMeasuredCoverage && kfldimxShapeMatches,
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
                        modifier = Modifier.testTag("ldrpid-log-status"),
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
    logDiagnostics: List<LdrpidCalculator.LogDiagnosticRow> = emptyList(),
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
            3 -> PidAnalysisTab(logDiagnostics, Modifier.fillMaxWidth().weight(1f))
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
                        testTagPrefix = "ldrpid-nonlinear",
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
                    MapTable(
                        map = linearMap,
                        editable = false,
                        testTagPrefix = "ldrpid-linear"
                    )
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
                    testTagPrefix = "ldrpid-kfldrl",
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
                    MapAxis(
                        data = kfldimxXAxis,
                        editable = false,
                        testTagPrefix = "ldrpid-kfldimx-x"
                    )
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
                    testTagPrefix = "ldrpid-kfldimx",
                    cellColorProvider = sampleCountColorProvider(sampleCounts)
                )
            }
        } else {
            Text("No map data", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── PID Analysis Tab ──────────────────────────────────────────────────

@Composable
private fun PidAnalysisTab(
    diagnostics: List<LdrpidCalculator.LogDiagnosticRow>,
    modifier: Modifier = Modifier
) {
    if (diagnostics.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Log diagnostics unavailable", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Load WOT log data to inspect measured boost tracking and table coverage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(modifier = modifier.padding(top = 8.dp)) {
        Text(
            text = "Measured WOT Log Diagnostics",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "These values come from the uploaded log. They do not claim predictive PID stability.",
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
                    Text("WOT samples", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(100.dp))
                    Text("Duty cells", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(90.dp))
                    Text("Avg |error|", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(100.dp))
                    Text("Overshoot", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(90.dp))
                    Text("Within ±50", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(90.dp))
                }

                HorizontalDivider()

                for (row in diagnostics) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("ldrpid-diagnostic-${row.rpm.toInt()}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${row.rpm.toInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .width(60.dp)
                                .testTag("ldrpid-diagnostic-${row.rpm.toInt()}-rpm")
                        )
                        Text(
                            row.sampleCount.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .width(100.dp)
                                .testTag("ldrpid-diagnostic-${row.rpm.toInt()}-samples")
                        )
                        Text(
                            row.measuredDutyCells.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .width(90.dp)
                                .testTag("ldrpid-diagnostic-${row.rpm.toInt()}-duty-cells")
                        )
                        Text(
                            row.averageAbsolutePressureErrorMbar?.let { "%.1f mbar".format(it) } ?: "Not logged",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .width(100.dp)
                                .testTag("ldrpid-diagnostic-${row.rpm.toInt()}-avg-error")
                        )
                        Text(
                            row.maximumOvershootMbar?.let { "%.1f mbar".format(it) } ?: "Not logged",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .width(90.dp)
                                .testTag("ldrpid-diagnostic-${row.rpm.toInt()}-overshoot")
                        )
                        Text(
                            row.percentWithinTolerance?.let { "%.0f%%".format(it) } ?: "Not logged",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .width(90.dp)
                                .testTag("ldrpid-diagnostic-${row.rpm.toInt()}-within-tolerance")
                        )
                    }
                }
            }
        }

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
                Button(
                    onClick = onWriteKfldrl,
                    enabled = canWriteKfldrl,
                    modifier = Modifier.testTag("ldrpid-write-kfldrl")
                ) { Text("Write KFLDRL") }
                WriteStatusIndicator(writeKfldrlStatus)
                Button(
                    onClick = onWriteKfldimx,
                    enabled = canWriteKfldimx,
                    modifier = Modifier.testTag("ldrpid-write-kfldimx")
                ) { Text("Write KFLDIMX") }
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
