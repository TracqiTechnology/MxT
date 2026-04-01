package ui.screens.fueltrim

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.contract.Med17LogFileContract
import data.parser.bin.BinParser
import data.parser.med17log.Med17LogParser
import data.parser.xdf.TableDefinition
import data.preferences.MapPreference
import data.preferences.bin.BinFilePreferences
import data.preferences.rkw.RkwPreferences
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.fueltrim.FuelTrimAnalyzer
import domain.model.fueltrim.FuelTrimDiagnosticResult
import domain.model.fueltrim.FuelTrimResult
import domain.model.fueltrim.RkwTableMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * MED17 fuel trim analysis screen.
 *
 * Workflow: load rk_w map from BIN (input) → load fuel trim logs → compute
 * corrections → apply to rk_w → show corrected map (output) → write to BIN.
 */
@Composable
fun FuelTrimScreen() {
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
                            BinWriter.write(BinFilePreferences.file.value, tableDef, outputRkw)
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
                        MapTable(map = inputRkw, editable = false)
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
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val parser = Med17LogParser()
                                        var merged: Map<Med17LogFileContract.Header, List<Double>>? = null
                                        for (f in files) {
                                            val logData = parser.parseLogFile(
                                                Med17LogParser.LogType.FUEL_TRIM, f
                                            )
                                            if (merged == null) {
                                                merged = logData.toMutableMap()
                                            } else {
                                                val m = merged!!.toMutableMap()
                                                for ((key, list) in logData) {
                                                    val existing = m[key]
                                                    if (existing != null) {
                                                        m[key] = existing + list
                                                    } else {
                                                        m[key] = list
                                                    }
                                                }
                                                merged = m
                                            }
                                        }
                                        val allLogData = merged ?: emptyMap()
                                        // Use rk_w table axes if available, else defaults
                                        val rpmBins = inputRkw?.yAxis?.map { it }?.toDoubleArray()
                                            ?: FuelTrimAnalyzer.DEFAULT_RPM_BINS
                                        val loadBins = inputRkw?.xAxis?.map { it }?.toDoubleArray()
                                            ?: FuelTrimAnalyzer.DEFAULT_LOAD_BINS
                                        val diagResult = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
                                            allLogData, rpmBins, loadBins
                                        )
                                        val analyzed = diagResult.toFuelTrimResult()
                                        withContext(Dispatchers.Main) {
                                            trimResult = analyzed
                                            diagnosticResult = diagResult
                                            val totalBins = analyzed.corrections.sumOf { row ->
                                                row.count { it != 0.0 }
                                            }
                                            logStatus = if (analyzed.isEmpty) {
                                                "✓ Loaded ${files.size} file(s) — all trims within ±3% threshold"
                                            } else {
                                                "✓ Loaded ${files.size} file(s) — $totalBins bins with corrections"
                                            }
                                            showProgress = false
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
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
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        MapTable(map = avgMap, editable = false)
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
                                cellColorProvider = colorProvider
                            )
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
                enabled = canWrite
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
