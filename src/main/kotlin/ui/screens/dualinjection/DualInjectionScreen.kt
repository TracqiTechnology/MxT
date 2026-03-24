package ui.screens.dualinjection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.contract.Med17LogFileContract
import data.parser.med17log.Med17LogParser
import data.preferences.bin.BinFilePreferences
import data.preferences.dualinjection.DualInjectionPreferences
import data.preferences.krkte.KrkteGdiPreferences
import data.preferences.krkte.KrktePfiPreferences
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.injector.InjectorScalingSolver
import domain.model.injector.InjectorSpec
import domain.model.injector.KrkteScalingResult
import domain.model.injector.TvubResult
import domain.model.pfi.PfiShareCalculator
import domain.model.pfi.PfiShareResult
import domain.model.presets.InjectorPresets
import domain.model.presets.InjectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private enum class WriteStatus { Idle, Success, Error }

/**
 * MED17-only screen for dual injection (port + direct) calibration.
 *
 * Three tabs:
 * 1. Port Injector Scaling — KRKTE_PFI ratio calculator + TVUB_PFI dead time
 * 2. Direct Injector Scaling — KRKTE_GDI ratio calculator
 * 3. Split Calculator — port vs DI fuel share at given load/RPM
 */
@Composable
fun DualInjectionScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Port Injector", "Direct Injector", "Split Calculator")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> PortInjectorTab()
            1 -> DirectInjectorTab()
            2 -> SplitCalculatorTab()
        }
    }
}

// ── Port Injector Scaling Tab ─────────────────────────────────────────

@Composable
private fun PortInjectorTab() {
    val scrollState = rememberScrollState()

    var oldFlowRate by remember { mutableStateOf(DualInjectionPreferences.portInjectorFlowRateCcMin.let { if (it > 0) it.toString() else "" }) }
    var oldPressure by remember { mutableStateOf(DualInjectionPreferences.portInjectorFuelPressureBar.toString()) }
    var oldDeadTime by remember { mutableStateOf(DualInjectionPreferences.portInjectorDeadTimeMs.let { if (it > 0) it.toString() else "" }) }
    var newFlowRate by remember { mutableStateOf("") }
    var newPressure by remember { mutableStateOf("4.0") }
    var newDeadTime by remember { mutableStateOf("") }

    var scalingResult by remember { mutableStateOf<KrkteScalingResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Write-to-binary state
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile

    var pfiMapVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { KrktePfiPreferences.mapChanged.collect { pfiMapVersion++ } }
    val pfiMap = remember(pfiMapVersion) { KrktePfiPreferences.getSelectedMap() }
    val pfiMapConfigured = pfiMap != null
    val currentKrktePfi = pfiMap?.second?.zAxis?.firstOrNull()?.firstOrNull()
    val canWrite = binLoaded && pfiMapConfigured && scalingResult != null && currentKrktePfi != null

    var showWriteConfirmation by remember { mutableStateOf(false) }
    var writeStatus by remember { mutableStateOf(WriteStatus.Idle) }
    LaunchedEffect(writeStatus) {
        if (writeStatus != WriteStatus.Idle) { delay(3000); writeStatus = WriteStatus.Idle }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Port Injector (PFI) Scaling", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "MED17 2.5T dual-fuel: port injectors deliver fuel into the intake manifold. " +
                        "Compute KRKTE_PFI scale factor when upgrading port injectors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "te_pfi = rk_w × KRKTE_PFI × portShare + TVUB_PFI(ubat)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Old injector
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stock Port Injector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    var pfiPresetExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { pfiPresetExpanded = true }) { Text("Presets") }
                        DropdownMenu(expanded = pfiPresetExpanded, onDismissRequest = { pfiPresetExpanded = false }) {
                            InjectorPresets.engines.forEach { engine ->
                                val presets = InjectorPresets.byEngineAndType(engine, InjectorType.PFI)
                                if (presets.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text(engine, fontWeight = FontWeight.Bold) }, onClick = {}, enabled = false)
                                    presets.forEach { preset ->
                                        DropdownMenuItem(
                                            text = { Text("  ${preset.name} (${preset.flowRateCcPerMin} cc/min @ ${preset.fuelPressureBar} bar)") },
                                            onClick = {
                                                oldFlowRate = preset.flowRateCcPerMin.toString()
                                                oldPressure = preset.fuelPressureBar.toString()
                                                pfiPresetExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = oldFlowRate, onValueChange = { oldFlowRate = it }, label = { Text("Flow Rate (cc/min)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = oldPressure, onValueChange = { oldPressure = it }, label = { Text("Fuel Pressure (bar, gauge)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = oldDeadTime, onValueChange = { oldDeadTime = it }, label = { Text("Dead Time @ 14V (ms)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        }

        // New injector
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("New Port Injector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newFlowRate, onValueChange = { newFlowRate = it }, label = { Text("Flow Rate (cc/min)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = newPressure, onValueChange = { newPressure = it }, label = { Text("Fuel Pressure (bar, gauge)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = newDeadTime, onValueChange = { newDeadTime = it }, label = { Text("Dead Time @ 14V (ms)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        }

        // Calculate button
        Button(onClick = {
            errorMessage = null
            scalingResult = null
            try {
                val oldSpec = InjectorSpec(
                    flowRateCcPerMin = oldFlowRate.toDouble(),
                    fuelPressureBar = oldPressure.toDouble(),
                    deadTimeMs = oldDeadTime.toDoubleOrNull() ?: 0.0
                )
                val newSpec = InjectorSpec(
                    flowRateCcPerMin = newFlowRate.toDouble(),
                    fuelPressureBar = newPressure.toDouble(),
                    deadTimeMs = newDeadTime.toDoubleOrNull() ?: 0.0
                )
                scalingResult = InjectorScalingSolver.computeKrkteScaling(oldSpec, newSpec)
                // Persist port injector specs
                oldFlowRate.toDoubleOrNull()?.let { DualInjectionPreferences.portInjectorFlowRateCcMin = it }
                oldPressure.toDoubleOrNull()?.let { DualInjectionPreferences.portInjectorFuelPressureBar = it }
                oldDeadTime.toDoubleOrNull()?.let { DualInjectionPreferences.portInjectorDeadTimeMs = it }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Calculation error"
            }
        }) {
            Text("Calculate Port KRKTE Scale Factor")
        }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        scalingResult?.let { result ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Result", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "KRKTE_PFI scale factor: %.6f".format(result.scaleFactor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    if (currentKrktePfi != null) {
                        val newValue = currentKrktePfi * result.scaleFactor
                        Text(
                            "Current KRKTE_PFI: %.6f  →  New: %.6f".format(currentKrktePfi, newValue),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            "KRKTE_PFI_new = KRKTE_PFI_old × %.6f".format(result.scaleFactor),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (result.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        result.warnings.forEach { warning ->
                            Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Write to Binary
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Write to Binary", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))

                WritePfiPrerequisiteRow("BIN file", if (binLoaded) binFile.name else "Not loaded", binLoaded)
                WritePfiPrerequisiteRow("KRKTE_PFI map", if (pfiMapConfigured) pfiMap!!.first.tableName else "Not configured", pfiMapConfigured)
                WritePfiPrerequisiteRow("Scale factor", if (scalingResult != null) "%.6f".format(scalingResult!!.scaleFactor) else "Not calculated", scalingResult != null)

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { showWriteConfirmation = true }, enabled = canWrite) {
                        Text("Write KRKTE_PFI")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    AnimatedVisibility(visible = writeStatus != WriteStatus.Idle) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (writeStatus == WriteStatus.Success) Icons.Default.Check else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (writeStatus == WriteStatus.Success) "Written successfully" else "Write failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (!canWrite) {
                    val message = when {
                        !binLoaded -> "Load a BIN file to write."
                        !pfiMapConfigured -> "Configure the KRKTE_PFI map definition in the Configuration screen."
                        scalingResult == null -> "Calculate a scale factor first."
                        else -> ""
                    }
                    if (message.isNotEmpty()) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    if (showWriteConfirmation && scalingResult != null && currentKrktePfi != null) {
        val newValue = currentKrktePfi * scalingResult!!.scaleFactor
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write KRKTE_PFI") },
            text = { Text("Write %.6f to KRKTE_PFI? (was %.6f)".format(newValue, currentKrktePfi)) },
            confirmButton = {
                TextButton(onClick = {
                    showWriteConfirmation = false
                    val pfiTable = KrktePfiPreferences.getSelectedMap()
                    if (pfiTable != null) {
                        try {
                            val map = Map3d()
                            map.zAxis = arrayOf(arrayOf(newValue))
                            BinWriter.write(BinFilePreferences.file.value, pfiTable.first, map)
                            writeStatus = WriteStatus.Success
                        } catch (e: Exception) {
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
}

@Composable
private fun WritePfiPrerequisiteRow(label: String, detail: String, met: Boolean) {
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
        Text("$label:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(100.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Direct Injector Scaling Tab ───────────────────────────────────────

@Composable
private fun DirectInjectorTab() {
    val scrollState = rememberScrollState()

    var oldFlowRate by remember { mutableStateOf(DualInjectionPreferences.directInjectorFlowRateCcMin.let { if (it > 0) it.toString() else "" }) }
    var oldPressure by remember { mutableStateOf(DualInjectionPreferences.directInjectorFuelPressureBar.toString()) }
    var oldDeadTime by remember { mutableStateOf(DualInjectionPreferences.directInjectorDeadTimeMs.let { if (it > 0) it.toString() else "" }) }
    var newFlowRate by remember { mutableStateOf("") }
    var newPressure by remember { mutableStateOf("200.0") }
    var newDeadTime by remember { mutableStateOf("") }

    var scalingResult by remember { mutableStateOf<KrkteScalingResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Write-to-binary state
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile

    var gdiMapVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { KrkteGdiPreferences.mapChanged.collect { gdiMapVersion++ } }
    val gdiMap = remember(gdiMapVersion) { KrkteGdiPreferences.getSelectedMap() }
    val gdiMapConfigured = gdiMap != null
    val currentKrkteGdi = gdiMap?.second?.zAxis?.firstOrNull()?.firstOrNull()
    val canWrite = binLoaded && gdiMapConfigured && scalingResult != null && currentKrkteGdi != null

    var showWriteConfirmation by remember { mutableStateOf(false) }
    var writeStatus by remember { mutableStateOf(WriteStatus.Idle) }
    LaunchedEffect(writeStatus) {
        if (writeStatus != WriteStatus.Idle) { delay(3000); writeStatus = WriteStatus.Idle }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Direct Injector (GDI) Scaling", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "MED17 2.5T dual-fuel: direct injectors deliver fuel into the combustion chamber " +
                        "at high pressure (nominal 240 bar at full load). Compute KRKTE_GDI scale factor when upgrading DI injectors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "te_gdi = rk_w × KRKTE_GDI × (1 − portShare) + TVUB_GDI(ubat)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Note: DI dead time (TVUB_GDI) may not be tunable on MED17.1.62. " +
                        "The DI driver firmware handles injector timing internally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        // Old DI injector
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stock Direct Injector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    var gdiPresetExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { gdiPresetExpanded = true }) { Text("Presets") }
                        DropdownMenu(expanded = gdiPresetExpanded, onDismissRequest = { gdiPresetExpanded = false }) {
                            InjectorPresets.engines.forEach { engine ->
                                val presets = InjectorPresets.byEngineAndType(engine, InjectorType.GDI)
                                if (presets.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text(engine, fontWeight = FontWeight.Bold) }, onClick = {}, enabled = false)
                                    presets.forEach { preset ->
                                        DropdownMenuItem(
                                            text = { Text("  ${preset.name} (${preset.flowRateCcPerMin} cc/min @ ${preset.fuelPressureBar} bar)") },
                                            onClick = {
                                                oldFlowRate = preset.flowRateCcPerMin.toString()
                                                oldPressure = preset.fuelPressureBar.toString()
                                                gdiPresetExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = oldFlowRate, onValueChange = { oldFlowRate = it }, label = { Text("Flow Rate (cc/min)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = oldPressure, onValueChange = { oldPressure = it }, label = { Text("Fuel Pressure (bar, absolute)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = oldDeadTime, onValueChange = { oldDeadTime = it }, label = { Text("Dead Time @ 14V (ms)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        }

        // New DI injector
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("New Direct Injector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newFlowRate, onValueChange = { newFlowRate = it }, label = { Text("Flow Rate (cc/min)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = newPressure, onValueChange = { newPressure = it }, label = { Text("Fuel Pressure (bar, absolute)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = newDeadTime, onValueChange = { newDeadTime = it }, label = { Text("Dead Time @ 14V (ms)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        }

        // Calculate
        Button(onClick = {
            errorMessage = null
            scalingResult = null
            try {
                val oldSpec = InjectorSpec(
                    flowRateCcPerMin = oldFlowRate.toDouble(),
                    fuelPressureBar = oldPressure.toDouble(),
                    deadTimeMs = oldDeadTime.toDoubleOrNull() ?: 0.0
                )
                val newSpec = InjectorSpec(
                    flowRateCcPerMin = newFlowRate.toDouble(),
                    fuelPressureBar = newPressure.toDouble(),
                    deadTimeMs = newDeadTime.toDoubleOrNull() ?: 0.0
                )
                scalingResult = InjectorScalingSolver.computeKrkteScaling(oldSpec, newSpec)
                // Persist direct injector specs
                oldFlowRate.toDoubleOrNull()?.let { DualInjectionPreferences.directInjectorFlowRateCcMin = it }
                oldPressure.toDoubleOrNull()?.let { DualInjectionPreferences.directInjectorFuelPressureBar = it }
                oldDeadTime.toDoubleOrNull()?.let { DualInjectionPreferences.directInjectorDeadTimeMs = it }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Calculation error"
            }
        }) {
            Text("Calculate DI KRKTE Scale Factor")
        }

        // Results
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        scalingResult?.let { result ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Result", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "KRKTE_GDI scale factor: %.6f".format(result.scaleFactor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    if (currentKrkteGdi != null) {
                        val newValue = currentKrkteGdi * result.scaleFactor
                        Text(
                            "Current KRKTE_GDI: %.6f  →  New: %.6f".format(currentKrkteGdi, newValue),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            "KRKTE_GDI_new = KRKTE_GDI_old × %.6f".format(result.scaleFactor),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (result.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        result.warnings.forEach { warning ->
                            Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Write to Binary
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Write to Binary", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))

                WritePfiPrerequisiteRow("BIN file", if (binLoaded) binFile.name else "Not loaded", binLoaded)
                WritePfiPrerequisiteRow("KRKTE_GDI map", if (gdiMapConfigured) gdiMap!!.first.tableName else "Not configured", gdiMapConfigured)
                WritePfiPrerequisiteRow("Scale factor", if (scalingResult != null) "%.6f".format(scalingResult!!.scaleFactor) else "Not calculated", scalingResult != null)

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { showWriteConfirmation = true }, enabled = canWrite) {
                        Text("Write KRKTE_GDI")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    AnimatedVisibility(visible = writeStatus != WriteStatus.Idle) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (writeStatus == WriteStatus.Success) Icons.Default.Check else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (writeStatus == WriteStatus.Success) "Written successfully" else "Write failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (!canWrite) {
                    val message = when {
                        !binLoaded -> "Load a BIN file to write."
                        !gdiMapConfigured -> "Configure the KRKTE_GDI map definition in the Configuration screen."
                        scalingResult == null -> "Calculate a scale factor first."
                        else -> ""
                    }
                    if (message.isNotEmpty()) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    if (showWriteConfirmation && scalingResult != null && currentKrkteGdi != null) {
        val newValue = currentKrkteGdi * scalingResult!!.scaleFactor
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write KRKTE_GDI") },
            text = { Text("Write %.6f to KRKTE_GDI? (was %.6f)".format(newValue, currentKrkteGdi)) },
            confirmButton = {
                TextButton(onClick = {
                    showWriteConfirmation = false
                    val gdiTable = KrkteGdiPreferences.getSelectedMap()
                    if (gdiTable != null) {
                        try {
                            val map = Map3d()
                            map.zAxis = arrayOf(arrayOf(newValue))
                            BinWriter.write(BinFilePreferences.file.value, gdiTable.first, map)
                            writeStatus = WriteStatus.Success
                        } catch (e: Exception) {
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
}

// ── Split Calculator Tab ──────────────────────────────────────────────

@Composable
private fun SplitCalculatorTab() {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var portKrkte by remember { mutableStateOf("") }
    var diKrkte by remember { mutableStateOf("") }
    var targetLoad by remember { mutableStateOf("150.0") }
    var targetRpm by remember { mutableStateOf("5000.0") }

    var pfiResult by remember { mutableStateOf<PfiShareResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var logStatus by remember { mutableStateOf<String?>(null) }
    var showProgress by remember { mutableStateOf(false) }

    // Initialize default curve on first composition
    LaunchedEffect(Unit) {
        if (pfiResult == null) {
            pfiResult = PfiShareCalculator.calculateRpmDependentShare()
        }
    }

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
                Text("RPM-Dependent PFI Split Calculator", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "The 2.5T EA855 EVO PFI share varies with RPM: port injectors ramp up towards " +
                        "torque peak (~4500 RPM), hold steady through mid-range, then decline towards " +
                        "redline where the GDI share increases to handle the majority of fuel delivery. Load a WOT/cruise log to " +
                        "see your actual InjSys_facPrtnPfi curve overlaid on the default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Log Loading
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Log-Based Refinement", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val dialog = FileDialog(Frame(), "Select MED17 Log File", FileDialog.LOAD)
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val file = dialog.file
                        if (dir != null && file != null) {
                            showProgress = true
                            logStatus = "Loading..."
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val logFile = File(dir, file)
                                        val parser = Med17LogParser()
                                        val logData = parser.parseLogFile(
                                            Med17LogParser.LogType.PFI_SPLIT, logFile
                                        )
                                        val refined = PfiShareCalculator.refineFromLog(logData)
                                        withContext(Dispatchers.Main) {
                                            pfiResult = refined
                                            logStatus = if (refined.loggedRpmAxis != null)
                                                "✓ Loaded ${refined.loggedRpmAxis!!.size} RPM points from ${logFile.name}"
                                            else
                                                "⚠ No PFI split data found in log"
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
                        Text("Load PFI Log")
                    }
                    Button(onClick = {
                        pfiResult = PfiShareCalculator.calculateRpmDependentShare()
                        logStatus = "Reset to default curve"
                    }, colors = ButtonDefaults.outlinedButtonColors()) {
                        Text("Reset to Default")
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

        // RPM-Dependent PFI Curve Table
        pfiResult?.let { result ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Default PFI Share Curve", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("RPM", modifier = Modifier.width(50.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        for (rpm in result.rpmAxis) {
                            Text("%.0f".format(rpm), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("PFI%", modifier = Modifier.width(50.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        for (pfi in result.pfiSharePercent) {
                            Text("%.0f".format(pfi), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Logged curve overlay
                    if (result.loggedRpmAxis != null && result.loggedPfiPercent != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Logged PFI Share (from log)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("RPM", modifier = Modifier.width(50.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            for (rpm in result.loggedRpmAxis!!) {
                                Text("%.0f".format(rpm), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("PFI%", modifier = Modifier.width(50.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            for (pfi in result.loggedPfiPercent!!) {
                                Text("%.1f".format(pfi), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        // On-Time Calculator
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Injection On-Time Calculator", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Computes PFI and GDI on-times at a given RPM using the current PFI curve. " +
                        "Also shows available injector window (ms) = 120000 / RPM for 4-stroke.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = portKrkte, onValueChange = { portKrkte = it }, label = { Text("KRKTE_PFI (ms/%)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = diKrkte, onValueChange = { diKrkte = it }, label = { Text("KRKTE_GDI (ms/%)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = targetRpm, onValueChange = { targetRpm = it }, label = { Text("RPM") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = targetLoad, onValueChange = { targetLoad = it }, label = { Text("Target Load (%)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        }

        // Calculate
        Button(onClick = {
            errorMessage = null
            try {
                val pKrkte = portKrkte.toDouble()
                val dKrkte = diKrkte.toDouble()
                val load = targetLoad.toDouble()
                val rpm = targetRpm.toDouble()

                require(pKrkte > 0) { "KRKTE_PFI must be positive" }
                require(dKrkte > 0) { "KRKTE_GDI must be positive" }
                require(load > 0) { "Target load must be positive" }
                require(rpm > 0) { "RPM must be positive" }

                // Look up PFI share from curve at this RPM
                val curveResult = pfiResult ?: PfiShareCalculator.calculateRpmDependentShare()
                val pfiShare = PfiShareCalculator.interpolateClamped(
                    rpm, curveResult.rpmAxis, curveResult.pfiSharePercent
                ) / 100.0

                val portOnTime = load * pfiShare * pKrkte
                val diOnTime = load * (1.0 - pfiShare) * dKrkte
                val availableWindow = 120000.0 / rpm  // ms per injection event (4-stroke)

                errorMessage = null
                // Build result display inline
                val result = buildString {
                    appendLine("RPM: %.0f  |  PFI Share: %.1f%%  |  Available window: %.2f ms".format(rpm, pfiShare * 100.0, availableWindow))
                    appendLine("Port (PFI) on-time:   %.4f ms".format(portOnTime))
                    appendLine("Direct (GDI) on-time: %.4f ms".format(diOnTime))
                    appendLine("Total on-time:        %.4f ms".format(portOnTime + diOnTime))
                    if (portOnTime > availableWindow * 0.85) {
                        appendLine("⚠ PFI on-time exceeds 85% of available window — consider reducing PFI share at this RPM")
                    }
                    if (diOnTime > availableWindow * 0.85) {
                        appendLine("⚠ GDI on-time exceeds 85% of available window — check DI injector sizing")
                    }
                }
                // Store result in errorMessage field (reusing for simplicity)
                logStatus = result
                DualInjectionPreferences.portSharePercentDefault = pfiShare * 100.0
            } catch (e: Exception) {
                errorMessage = e.message ?: "Calculation error"
            }
        }) {
            Text("Calculate at RPM")
        }

        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // Result display — reuse logStatus but only if it looks like a result (multi-line)
        logStatus?.let { status ->
            if (status.contains("on-time:")) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Result", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        for (line in status.lines()) {
                            if (line.startsWith("⚠")) {
                                Text(line, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            } else {
                                Text(line, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}


