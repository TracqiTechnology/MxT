package ui.screens.fueltrim

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.contract.Med17LogFileContract
import data.parser.bin.BinParser
import data.parser.med17log.Med17LogParser
import data.parser.xdf.TableDefinition
import data.preferences.MapPreference
import data.preferences.bin.BinFilePreferences
import data.preferences.fueltrim.FuelTrimPreferences
import data.preferences.rkw.RkwPreferences
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.fueltrim.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.components.MapTable
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.DecimalFormat

private enum class WriteStatus { Idle, Success, Error }

private val diagFormatter = DecimalFormat("#.#")

private fun findMap(
    mapList: List<Pair<TableDefinition, Map3d>>,
    pref: MapPreference
): Pair<TableDefinition, Map3d>? {
    val selected = pref.getSelectedMap()
    return if (selected != null) {
        mapList.find { it.first.tableName == selected.first.tableName }
    } else null
}

/**
 * MED17 fuel trim analysis screen.
 *
 * Workflow: load rk_w map from BIN (input) → load fuel trim logs → compute
 * corrections → apply to rk_w → show corrected map (output) → write to BIN.
 */
@Composable
fun FuelTrimScreen(preloadedLogFiles: List<java.io.File>? = null) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // ── Map state ──
    val mapList by BinParser.mapList.collectAsState()
    var mapVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { RkwPreferences.mapChanged.collect { mapVersion++ } }

    val rkwPair = remember(mapList, mapVersion) { findMap(mapList, RkwPreferences) }
    val inputRkw = rkwPair?.second

    // ── Parse rk_w table metadata for display ──
    val rkwMetadata: RkwTableMetadata? = remember(rkwPair) {
        rkwPair?.first?.let { def ->
            RkwTableMetadata.parse(def.tableName, def.tableDescription)
        }
    }

    // ── Map-switch warning: detect native rk_w selection when MAP variants exist ──
    val mapSwitchWarning: String? = remember(mapList, mapVersion) {
        val mapSwitchRkwTables = mapList.filter { (def, _) ->
            FuelTrimAnalyzer.isRkwTable(def.tableDescription) &&
                FuelTrimAnalyzer.isMapSwitchTable(def.tableDescription)
        }
        when {
            mapSwitchRkwTables.isEmpty() -> null
            rkwPair == null -> {
                val suggested = mapSwitchRkwTables.firstOrNull { (def, _) ->
                    def.tableDescription.contains("Gasoline 0", ignoreCase = true)
                } ?: mapSwitchRkwTables.first()
                "No rk_w table configured. ${mapSwitchRkwTables.size} map-switch variant(s) detected " +
                    "in this BIN. On DS1 tunes, map-switch tables overwrite native ones — select a " +
                    "map-switch variant (e.g. \"${suggested.first.tableName}\") in Configuration."
            }
            !FuelTrimAnalyzer.isMapSwitchTable(rkwPair.first.tableDescription) -> {
                "You've selected the native rk_w table. On DS1 tunes, map-switch tables overwrite " +
                    "native ones, so editing this table may have no effect. Consider selecting " +
                    "a map-switch variant instead (${mapSwitchRkwTables.size} available)."
            }
            else -> null
        }
    }

    // ── Log analysis state ──
    var trimResult by remember { mutableStateOf<FuelTrimResult?>(null) }
    var diagnosticResult by remember { mutableStateOf<FuelTrimDiagnosticResult?>(null) }
    var logStatus by remember { mutableStateOf<String?>(null) }
    var showProgress by remember { mutableStateOf(false) }
    var loadedLogData by remember {
        mutableStateOf<Map<Med17LogFileContract.Header, List<Double>>?>(null)
    }

    var fuelTrimSettings by remember { mutableStateOf(FuelTrimPreferences.load()) }
    var showFilterSettings by remember { mutableStateOf(false) }
    var trimThresholdText by remember { mutableStateOf(fuelTrimSettings.trimThresholdPercent.toString()) }
    var minimumSamplesText by remember { mutableStateOf(fuelTrimSettings.minimumSamples.toString()) }
    var stdDevLimitText by remember { mutableStateOf(fuelTrimSettings.standardDeviationLimitPercent.toString()) }
    var maximumRpmChangeText by remember { mutableStateOf(fuelTrimSettings.maximumRpmChangePerSecond.toString()) }
    var lambdaDeviationText by remember { mutableStateOf(fuelTrimSettings.maximumLambdaDeviation.toString()) }

    fun settingsFromInputs(): FuelTrimSettings = FuelTrimSettings(
        trimThresholdPercent = trimThresholdText.toDouble(),
        minimumSamples = minimumSamplesText.toInt(),
        standardDeviationLimitPercent = stdDevLimitText.toDouble(),
        maximumRpmChangePerSecond = maximumRpmChangeText.toDouble(),
        requireClosedLoopWhenAvailable = fuelTrimSettings.requireClosedLoopWhenAvailable,
        maximumLambdaDeviation = lambdaDeviationText.toDouble()
    )

    fun analyzeLoadedData(
        logData: Map<Med17LogFileContract.Header, List<Double>>,
        settings: FuelTrimSettings
    ): FuelTrimDiagnosticResult {
        val rpmBins = inputRkw?.yAxis?.map { it }?.toDoubleArray()
            ?: FuelTrimAnalyzer.DEFAULT_RPM_BINS
        val loadBins = inputRkw?.xAxis?.map { it }?.toDoubleArray()
            ?: FuelTrimAnalyzer.DEFAULT_LOAD_BINS
        return FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
            logData = logData,
            rpmBins = rpmBins,
            loadBins = loadBins,
            settings = settings
        )
    }

    fun analysisStatus(
        prefix: String,
        result: FuelTrimDiagnosticResult,
        settings: FuelTrimSettings
    ): String {
        val correctionBins = result.corrections.sumOf { row -> row.count { it != 0.0 } }
        return when {
            correctionBins > 0 -> "$prefix — $correctionBins bins with corrections"
            result.binsWithData == 0 ->
                "$prefix — no usable steady-state trim samples; review the analysis settings"
            result.binsRejected >= result.binsWithData ->
                "$prefix — all ${result.binsWithData} populated bins were rejected; review sample and variance limits"
            else ->
                "$prefix — no accepted bins exceeded ±${settings.trimThresholdPercent}%"
        }
    }

    // ── Output: corrections applied to the input rk_w map ──
    val outputRkw: Map3d? = remember(inputRkw, trimResult) {
        if (inputRkw == null || trimResult == null) return@remember null
        val corrections = trimResult!!.toCorrectionsMap3d()
        val output = Map3d(inputRkw)
        for (r in output.zAxis.indices) {
            val rpmVal = if (r < output.yAxis.size) output.yAxis[r] else 0.0
            for (c in output.zAxis[r].indices) {
                val loadVal = if (c < output.xAxis.size) output.xAxis[c] else 0.0
                val correctionPct = corrections.lookup(loadVal, rpmVal)
                // rk_w is a multiplicative factor around 1.0.
                // correction is in %, so +5 means "5% more fuel needed".
                // Apply: new_factor = old_factor * (1 + correction/100)
                output.zAxis[r][c] = inputRkw.zAxis[r][c] * (1.0 + correctionPct / 100.0)
            }
        }
        output
    }

    // ── Write state ──
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile
    val canWrite = binLoaded && rkwPair != null && outputRkw != null
    var showWriteConfirmation by remember { mutableStateOf(false) }
    var writeStatus by remember { mutableStateOf(WriteStatus.Idle) }

    // ── Per-cell diagnostics state ──
    var selectedDiagCell by remember { mutableStateOf<FuelTrimCellDiagnostic?>(null) }

    // Auto-load log data when preloadedLogFiles is provided (screenshot harness)
    LaunchedEffect(preloadedLogFiles, inputRkw) {
        if (preloadedLogFiles != null && preloadedLogFiles.isNotEmpty()) {
            showProgress = true
            loadedLogData = null
            trimResult = null
            diagnosticResult = null
            selectedDiagCell = null
            withContext(Dispatchers.IO) {
                try {
                    val parser = Med17LogParser()
                    val parsedLogs = preloadedLogFiles.map { file ->
                        parser.parseLogFile(Med17LogParser.LogType.FUEL_TRIM, file)
                    }
                    val allLogData = FuelTrimAnalyzer.mergeAlignedLogs(parsedLogs)
                    val diagResult = analyzeLoadedData(allLogData, fuelTrimSettings)
                    val analyzed = diagResult.toFuelTrimResult()
                    withContext(Dispatchers.Main) {
                        loadedLogData = allLogData
                        trimResult = analyzed
                        diagnosticResult = diagResult
                        logStatus = analysisStatus(
                            "✓ Loaded ${preloadedLogFiles.size} file(s)",
                            diagResult,
                            fuelTrimSettings
                        )
                        showProgress = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        loadedLogData = null
                        trimResult = null
                        diagnosticResult = null
                        selectedDiagCell = null
                        logStatus = "Error: ${e.message}"
                        showProgress = false
                    }
                }
            }
        }
    }

    // ── Bulk apply state ──
    var showBulkApply by remember { mutableStateOf(false) }
    val availableRkwTables: List<RkwTableWithData> = remember(mapList) {
        FuelTrimBulkApply.enumerateRkwTables(mapList)
    }
    var selectedTables by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkApplyProgress by remember { mutableStateOf<String?>(null) }
    var bulkApplyRunning by remember { mutableStateOf(false) }

    LaunchedEffect(writeStatus) {
        if (writeStatus != WriteStatus.Idle) {
            delay(3000)
            writeStatus = WriteStatus.Idle
        }
    }

    // ── Write dialog ──
    if (showWriteConfirmation) {
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write rk_w") },
            text = { Text("Are you sure you want to write rk_w corrections to the binary?") },
            confirmButton = {
                TextButton(onClick = {
                    showWriteConfirmation = false
                    val tableDef = rkwPair?.first
                    if (outputRkw != null && tableDef != null) {
                        try {
                            // Trims only change z — write z ONLY. Never re-write the
                            // x/y axes read from the bin: it's pointless for a trim,
                            // and it would faithfully re-persist axis damage from bins
                            // written by pre-2.1.3 versions (zeroed load axes).
                            val zOnly = Map3d(emptyArray(), emptyArray(), outputRkw.zAxis)
                            BinWriter.write(BinFilePreferences.file.value, tableDef, zOnly)
                            writeStatus = WriteStatus.Success
                        } catch (e: Exception) {
                            e.printStackTrace()
                            writeStatus = WriteStatus.Error
                        }
                    }
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showWriteConfirmation = false }) { Text("No") }
            }
        )
    }

    // ── UI ──
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Description
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Fuel Trim Analysis (STFT / LTFT)", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Load MED17 logs containing fuel trim data (frm_w / fra_w / longft1_w) to " +
                        "analyze per-bin average trims across the rk_w grid. Corrections are applied " +
                        "to the rk_w map (Rel fuel mass fac) and can be written back to the binary. " +
                        "Positive correction = add fuel, negative = remove fuel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Map-switch warning banner
        AnimatedVisibility(visible = mapSwitchWarning != null) {
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
                        mapSwitchWarning ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Input rk_w table
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Input: Current rk_w (Rel Fuel Mass Factor)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (rkwMetadata != null) {
                    Text(
                        rkwMetadata.displayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (inputRkw != null) {
                    val tableHeight = ((inputRkw.yAxis.size + 1) * 24 + 4).dp
                    Box(modifier = Modifier.fillMaxWidth().height(tableHeight)) {
                        MapTable(
                            map = inputRkw,
                            editable = false,
                            testTagPrefix = "fuel-trim-input"
                        )
                    }
                } else {
                    Text(
                        "Not configured — select rk_w map in Configuration",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Log Loading
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Load Log Files", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Reaction: ±${fuelTrimSettings.trimThresholdPercent}% · " +
                            "min ${fuelTrimSettings.minimumSamples} samples · " +
                            "max σ ${fuelTrimSettings.standardDeviationLimitPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showFilterSettings = !showFilterSettings }) {
                        Text(if (showFilterSettings) "Hide Settings" else "Analysis Settings")
                    }
                }
                AnimatedVisibility(showFilterSettings) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                trimThresholdText,
                                { trimThresholdText = it },
                                label = { Text("Reaction threshold (%)") },
                                modifier = Modifier.weight(1f).testTag("fuel-trim-threshold"),
                                singleLine = true
                            )
                            OutlinedTextField(
                                minimumSamplesText,
                                { minimumSamplesText = it },
                                label = { Text("Minimum samples") },
                                modifier = Modifier.weight(1f).testTag("fuel-trim-minimum-samples"),
                                singleLine = true
                            )
                            OutlinedTextField(
                                stdDevLimitText,
                                { stdDevLimitText = it },
                                label = { Text("Max std deviation (%)") },
                                modifier = Modifier.weight(1f).testTag("fuel-trim-max-stddev"),
                                singleLine = true
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                maximumRpmChangeText,
                                { maximumRpmChangeText = it },
                                label = { Text("Max RPM rate (RPM/s)") },
                                modifier = Modifier.weight(1f).testTag("fuel-trim-max-rpm-change"),
                                singleLine = true
                            )
                            OutlinedTextField(
                                lambdaDeviationText,
                                { lambdaDeviationText = it },
                                label = { Text("Max λ deviation") },
                                modifier = Modifier.weight(1f).testTag("fuel-trim-max-lambda-deviation"),
                                singleLine = true
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Checkbox(
                                    checked = fuelTrimSettings.requireClosedLoopWhenAvailable,
                                    onCheckedChange = {
                                        fuelTrimSettings = fuelTrimSettings.copy(
                                            requireClosedLoopWhenAvailable = it
                                        )
                                    }
                                )
                                Text("Require closed loop when logged", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(
                            modifier = Modifier.testTag("fuel-trim-apply-settings"),
                            onClick = {
                            try {
                                val updated = settingsFromInputs()
                                fuelTrimSettings = updated
                                FuelTrimPreferences.save(updated)
                                loadedLogData?.let { data ->
                                    val diagResult = analyzeLoadedData(data, updated)
                                    diagnosticResult = diagResult
                                    trimResult = diagResult.toFuelTrimResult()
                                    logStatus = analysisStatus("Reanalyzed", diagResult, updated)
                                }
                            } catch (e: Exception) {
                                logStatus = "Settings error: ${e.message}"
                            }
                            }
                        ) {
                            Text("Apply Settings")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val dialog = FileDialog(Frame(), "Select MED17 Fuel Trim Log", FileDialog.LOAD)
                        dialog.isMultipleMode = true
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val files = dialog.files
                        if (dir != null && files != null && files.isNotEmpty()) {
                            showProgress = true
                            logStatus = "Loading ${files.size} file(s)..."
                            loadedLogData = null
                            trimResult = null
                            diagnosticResult = null
                            selectedDiagCell = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val parser = Med17LogParser()
                                        val parsedLogs = files.map { file ->
                                            parser.parseLogFile(
                                                Med17LogParser.LogType.FUEL_TRIM, file
                                            )
                                        }
                                        val allLogData = FuelTrimAnalyzer.mergeAlignedLogs(parsedLogs)
                                        val diagResult = analyzeLoadedData(allLogData, fuelTrimSettings)
                                        val analyzed = diagResult.toFuelTrimResult()
                                        withContext(Dispatchers.Main) {
                                            loadedLogData = allLogData
                                            trimResult = analyzed
                                            diagnosticResult = diagResult
                                            logStatus = analysisStatus(
                                                "✓ Loaded ${files.size} file(s)",
                                                diagResult,
                                                fuelTrimSettings
                                            )
                                            showProgress = false
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            loadedLogData = null
                                            trimResult = null
                                            diagnosticResult = null
                                            selectedDiagCell = null
                                            logStatus = "Error: ${e.message}"
                                            showProgress = false
                                        }
                                    }
                                }
                            }
                        }
                    }) {
                        Text("Load Fuel Trim Logs")
                    }

                    if (trimResult != null) {
                        OutlinedButton(onClick = {
                            trimResult = null
                            diagnosticResult = null
                            loadedLogData = null
                            selectedDiagCell = null
                            logStatus = "Cleared"
                        }) {
                            Text("Clear")
                        }
                    }
                }
                if (showProgress) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                logStatus?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        it,
                        modifier = Modifier.testTag("fuel-trim-status"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Results
        trimResult?.let { fuelTrimResult ->
            // Diagnostic summary banner
            diagnosticResult?.let { diag ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Processed %,d samples (%,d filtered: closed-loop only) | %d bins with data, %d rejected (high variance)"
                            .format(diag.totalSamplesProcessed, diag.samplesFilteredOut, diag.binsWithData, diag.binsRejected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Average Trims Grid
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Average Combined Fuel Trim (%)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val avgMap = fuelTrimResult.toAvgTrimsMap3d()
                    val avgHeight = ((avgMap.yAxis.size + 1) * 24 + 4).dp
                    Box(modifier = Modifier.fillMaxWidth().height(avgHeight)) {
                        MapTable(
                            map = avgMap,
                            editable = false,
                            testTagPrefix = "fuel-trim-average"
                        )
                    }
                }
            }

            // Output: corrected rk_w with diagnostic cell coloring
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Output: Corrected rk_w",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (outputRkw != null) {
                        val outHeight = ((outputRkw.yAxis.size + 1) * 24 + 4).dp
                        val colorProvider: ((Int, Int) -> Color?)? = diagnosticResult?.let { diag ->
                            { rowIdx: Int, colIdx: Int ->
                                cellDiagnosticColor(diag, outputRkw, rowIdx, colIdx)
                            }
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(outHeight)) {
                            MapTable(
                                map = outputRkw,
                                editable = false,
                                testTagPrefix = "fuel-trim-output",
                                cellColorProvider = colorProvider,
                                onCellSelected = { rowIdx, colIdx ->
                                    diagnosticResult?.let { diag ->
                                        val rpmVal = if (rowIdx < outputRkw.yAxis.size) outputRkw.yAxis[rowIdx] else return@let
                                        val loadVal = if (colIdx < outputRkw.xAxis.size) outputRkw.xAxis[colIdx] else return@let
                                        val rIdx = FuelTrimAnalyzer.nearestBinIndex(rpmVal, diag.rpmBins)
                                        val lIdx = FuelTrimAnalyzer.nearestBinIndex(loadVal, diag.loadBins)
                                        selectedDiagCell = diag.diagnostics[rIdx][lIdx]
                                    }
                                }
                            )
                        }

                        // Per-cell diagnostics panel
                        selectedDiagCell?.let { cell ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Cell: ${diagFormatter.format(cell.rpmBin)} RPM \u00d7 ${diagFormatter.format(cell.loadBin)}% load",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Samples: ${cell.sampleCount} · effective weight: " +
                                            diagFormatter.format(cell.effectiveSampleWeight),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Mean trim: %+.1f%%".format(cell.meanTrimPercent),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Std deviation: \u00b1${diagFormatter.format(cell.stdDevPercent)}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (cell.rejected) {
                                        Text(
                                            "\u26a0 ${cell.rejectReason ?: "Rejected"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    } else if (cell.correctionApplied != 0.0) {
                                        Text(
                                            "Correction: %+.1f%%".format(cell.correctionApplied),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF00C853.toInt())
                                        )
                                    } else {
                                        Text(
                                            "Within threshold \u2014 no correction",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else if (inputRkw == null) {
                        Text(
                            "Configure rk_w map to see corrected output",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "All trims within threshold — no corrections needed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Diagnostics
            if (fuelTrimResult.warnings.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        for (warning in fuelTrimResult.warnings.take(20)) {
                            Text(
                                "• $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (fuelTrimResult.warnings.size > 20) {
                            Text(
                                "... and ${fuelTrimResult.warnings.size - 20} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Write button + status
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { showWriteConfirmation = true },
                enabled = canWrite,
                modifier = Modifier.testTag("fuel-trim-write")
            ) {
                Text("Write rk_w")
            }
            when (writeStatus) {
                WriteStatus.Success -> Text(
                    "✓ Written successfully",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                WriteStatus.Error -> Text(
                    "✗ Write failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                WriteStatus.Idle -> {}
            }
        }

        // ── Bulk Apply section ──
        if (availableRkwTables.size > 1 && diagnosticResult != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Bulk Apply to Multiple rk_w Tables",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { showBulkApply = !showBulkApply }) {
                            Text(if (showBulkApply) "Hide" else "Show (${availableRkwTables.size} tables)")
                        }
                    }

                    AnimatedVisibility(visible = showBulkApply) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Apply the same corrections to additional rk_w tables in this BIN. " +
                                    "On DS1 tunes, each fuel type / map switch / HO variant has its own table.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Quick Select:", style = MaterialTheme.typography.labelMedium)

                            @Composable
                            fun QuickSelectButton(label: String, tableNames: Set<String>) {
                                FilterChip(
                                    selected = tableNames.isNotEmpty() && tableNames.all { it in selectedTables },
                                    onClick = {
                                        selectedTables = if (tableNames.all { it in selectedTables }) {
                                            selectedTables - tableNames
                                        } else {
                                            selectedTables + tableNames
                                        }
                                    },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                QuickSelectButton("Select All", FuelTrimBulkApply.selectAll(availableRkwTables))
                                QuickSelectButton("All Gasoline",
                                    FuelTrimBulkApply.selectByFuelType(availableRkwTables, RkwTableMetadata.FuelType.GASOLINE))
                                QuickSelectButton("All Ethanol",
                                    FuelTrimBulkApply.selectByFuelType(availableRkwTables, RkwTableMetadata.FuelType.ETHANOL))
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                QuickSelectButton("All MAP Switch",
                                    FuelTrimBulkApply.selectByMapSwitch(availableRkwTables, isMapSwitch = true))
                                QuickSelectButton("All Native",
                                    FuelTrimBulkApply.selectByMapSwitch(availableRkwTables, isMapSwitch = false))
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                for (ho in 1..3) {
                                    val hoTables = FuelTrimBulkApply.selectByHoVariant(availableRkwTables, ho)
                                    if (hoTables.isNotEmpty()) {
                                        QuickSelectButton("All HO$ho", hoTables)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            val grouped = FuelTrimBulkApply.groupByFuelType(availableRkwTables)
                            for ((fuelType, tables) in grouped) {
                                val fuelLabel = when (fuelType) {
                                    RkwTableMetadata.FuelType.GASOLINE -> "Gasoline"
                                    RkwTableMetadata.FuelType.ETHANOL -> "Ethanol"
                                    RkwTableMetadata.FuelType.UNKNOWN -> "Unknown"
                                }
                                Text(fuelLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                for (table in tables.sortedBy { it.metadata.mapSwitchIndex }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = table.metadata.tableName in selectedTables,
                                            onCheckedChange = { checked ->
                                                selectedTables = if (checked) {
                                                    selectedTables + table.metadata.tableName
                                                } else {
                                                    selectedTables - table.metadata.tableName
                                                }
                                            }
                                        )
                                        Text(
                                            table.metadata.displayLabel,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        val diag = diagnosticResult ?: return@Button
                                        val selected = availableRkwTables.filter { it.metadata.tableName in selectedTables }
                                        if (selected.isEmpty()) return@Button
                                        bulkApplyRunning = true
                                        bulkApplyProgress = "Applying to ${selected.size} table(s)..."
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                var successCount = 0
                                                val failures = mutableListOf<String>()
                                                for (table in selected) {
                                                    try {
                                                        val corrected = FuelTrimBulkApply.applyCorrections(
                                                            table.map, diag.corrections, diag.rpmBins, diag.loadBins
                                                        )
                                                        // Trims only change z — write z ONLY, never the axes
                                                        // (see the single-write path for rationale).
                                                        val zOnly = Map3d(emptyArray(), emptyArray(), corrected.zAxis)
                                                        BinWriter.write(
                                                            BinFilePreferences.file.value,
                                                            table.tableDefinition,
                                                            zOnly
                                                        )
                                                        successCount++
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                        failures.add(
                                                            "${table.metadata.displayLabel}: ${e.message ?: e.javaClass.simpleName}"
                                                        )
                                                    }
                                                }
                                                withContext(Dispatchers.Main) {
                                                    bulkApplyProgress = if (failures.isEmpty()) {
                                                        "✓ Applied corrections to $successCount table(s)"
                                                    } else {
                                                        "⚠ $successCount succeeded, ${failures.size} failed — " +
                                                            failures.take(3).joinToString("; ") +
                                                            (if (failures.size > 3) " (+${failures.size - 3} more)" else "")
                                                    }
                                                    bulkApplyRunning = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = selectedTables.isNotEmpty() && binLoaded && !bulkApplyRunning
                                ) {
                                    Text("Apply to Selected (${selectedTables.size})")
                                }
                                if (bulkApplyRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                            bulkApplyProgress?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Map a diagnostic result cell to a background colour for the corrected rk_w MapTable.
 *
 * - **Green** (0x4000C853): correction applied, low std_dev
 * - **Yellow** (0x40FFD600): correction applied, moderate std_dev (> 3%)
 * - **Red** (0x40FF1744): bin rejected (high std_dev or insufficient samples)
 * - **null**: no data — default MapTable colouring
 */
private fun cellDiagnosticColor(
    diag: FuelTrimDiagnosticResult,
    outputRkw: Map3d,
    rowIdx: Int,
    colIdx: Int
): Color? {
    val rpmVal = if (rowIdx < outputRkw.yAxis.size) outputRkw.yAxis[rowIdx] else return null
    val loadVal = if (colIdx < outputRkw.xAxis.size) outputRkw.xAxis[colIdx] else return null

    val rIdx = FuelTrimAnalyzer.nearestBinIndex(rpmVal, diag.rpmBins)
    val lIdx = FuelTrimAnalyzer.nearestBinIndex(loadVal, diag.loadBins)
    val cell = diag.diagnostics[rIdx][lIdx]

    return when {
        cell.sampleCount == 0 -> null
        cell.rejected -> Color(0x40FF1744)                        // red — rejected
        cell.correctionApplied != 0.0 && cell.stdDevPercent > 3.0 ->
            Color(0x40FFD600)                                     // yellow — correction with moderate σ
        cell.correctionApplied != 0.0 -> Color(0x4000C853)        // green — good correction
        else -> null
    }
}
