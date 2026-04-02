package ui.screens.kfzw

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.MapPreference
import data.preferences.bin.BinFilePreferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfzw.KfzwPreferences
import data.preferences.kfzw.KfzwSwitchMapPreferences
import data.writer.BinWriter
import domain.math.AxisRescaler
import domain.math.RescaleAxis
import domain.math.map.Map3d
import domain.model.kfzw.Kfzw
import data.model.EcuPlatform
import data.preferences.platform.EcuPlatformPreference
import kotlinx.coroutines.delay
import ui.components.MapAxis
import ui.components.MapPickerDialog
import ui.components.MapTable
import ui.components.ParameterField
import ui.components.TimingCharts

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

private fun computeRescaledOutput(input: Map3d, targetMax: Double): Map3d? {
    if (input.xAxis.size < 2) return null
    val rescaledXAxis = RescaleAxis.rescaleAxis(input.xAxis, targetMax)
    val newZAxis = Kfzw.generateKfzw(input.xAxis, input.zAxis, rescaledXAxis)
    return Map3d(rescaledXAxis, input.yAxis, newZAxis)
}

@Composable
fun KfzwScreen() {
    val isMed17 = EcuPlatformPreference.platform == EcuPlatform.MED17
    val isMed9 = EcuPlatformPreference.platform == EcuPlatform.MED9
    val mapList by BinParser.mapList.collectAsState()
    val tableDefinitions by XdfParser.tableDefinitions.collectAsState()

    var showKfzwPicker by remember { mutableStateOf(false) }
    var showKfmiopPicker by remember { mutableStateOf(false) }

    // Reactive map selection — recompose when user picks a new map
    var mapVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { KfzwPreferences.mapChanged.collect { mapVersion++ } }
    LaunchedEffect(Unit) { KfmiopPreferences.mapChanged.collect { mapVersion++ } }

    val kfzwPair = remember(mapList, mapVersion) { findMap(mapList, KfzwPreferences) }
    val kfmiopPair = remember(mapList, mapVersion) { findMap(mapList, KfmiopPreferences) }

    val inputKfmiop = kfmiopPair?.second

    // Detect scalar KFMIOP (DS1: 1x1 map with empty axes)
    val kfmiopIsScalar = inputKfmiop != null && inputKfmiop.xAxis.isEmpty() && inputKfmiop.yAxis.isEmpty()

    // Use multi-switch-map mode when MED17 + scalar KFMIOP (DS1 pattern)
    // or MED9 with switch maps loaded (3 KFZW variants)
    val hasSwitchMaps = KfzwSwitchMapPreferences.count > 0
    val useMultiSwitchMode = (isMed17 && kfmiopIsScalar) || (isMed9 && hasSwitchMaps)

    if (useMultiSwitchMode) {
        KfzwMultiSwitchScreen(
            mapList = mapList,
            tableDefinitions = tableDefinitions,
            kfmiopPair = kfmiopPair
        )
    } else {
        KfzwSingleMapScreen(
            mapList = mapList,
            tableDefinitions = tableDefinitions,
            kfzwPair = kfzwPair,
            kfmiopPair = kfmiopPair,
            kfmiopIsScalar = kfmiopIsScalar,
            isMed17 = isMed17,
            showKfzwPicker = showKfzwPicker,
            onShowKfzwPicker = { showKfzwPicker = it },
            showKfmiopPicker = showKfmiopPicker,
            onShowKfmiopPicker = { showKfmiopPicker = it }
        )
    }
}

// ============================================================================
// MED17 Multi-Switch-Map Mode
// ============================================================================

@Composable
private fun KfzwMultiSwitchScreen(
    mapList: List<Pair<TableDefinition, Map3d>>,
    tableDefinitions: List<TableDefinition>,
    kfmiopPair: Pair<TableDefinition, Map3d>?
) {
    // Switch map preferences tracking
    var switchVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { KfzwSwitchMapPreferences.switchMapsChanged.collect { switchVersion++ } }

    val switchMaps = remember(mapList, switchVersion) {
        KfzwSwitchMapPreferences.getAllSelectedMaps()
    }

    var activeSwitchIndex by remember { mutableStateOf(0) }
    var showSwitchMapPicker by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    // Clamp active index
    val validIndex = if (switchMaps.isNotEmpty()) activeSwitchIndex.coerceIn(0, switchMaps.lastIndex) else 0
    if (validIndex != activeSwitchIndex) activeSwitchIndex = validIndex

    // KFMIOP scalar value for target max load default
    val inputKfmiop = kfmiopPair?.second
    val kfmiopScalarValue = if (inputKfmiop != null && inputKfmiop.xAxis.isEmpty() && inputKfmiop.yAxis.isEmpty()) {
        inputKfmiop.zAxis.firstOrNull()?.firstOrNull() ?: 400.0
    } else 400.0

    var targetMaxLoad by remember(kfmiopScalarValue) {
        mutableStateOf(kfmiopScalarValue.toString())
    }

    // Per-map edited inputs keyed by switch index
    var editedInputMaps by remember { mutableStateOf(mapOf<Int, Map3d>()) }

    // Initialize/update edited maps when switch maps change
    LaunchedEffect(switchMaps) {
        val updated = editedInputMaps.toMutableMap()
        for ((idx, pair) in switchMaps) {
            if (pair != null && idx !in updated) {
                updated[idx] = Map3d(pair.second)
            }
        }
        // Remove stale entries
        val validIndices = switchMaps.map { it.first }.toSet()
        updated.keys.removeAll { it !in validIndices }
        editedInputMaps = updated
    }

    // Compute outputs for all switch maps
    val computedOutputs = remember(editedInputMaps, targetMaxLoad) {
        val newMax = targetMaxLoad.toDoubleOrNull() ?: kfmiopScalarValue
        editedInputMaps.mapValues { (_, input) -> computeRescaledOutput(input, newMax) }
    }

    // Active map data
    val activeOriginal = switchMaps.getOrNull(activeSwitchIndex)?.second?.second
    val activeEdited = editedInputMaps[activeSwitchIndex]
    val activeOutput = computedOutputs[activeSwitchIndex]

    // Write prerequisites
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile
    val hasAnyMaps = switchMaps.any { it.second != null }
    val hasAnyOutputs = computedOutputs.values.any { it != null }
    val canWrite = binLoaded && hasAnyMaps && hasAnyOutputs

    var showWriteConfirmation by remember { mutableStateOf(false) }
    var writeStatus by remember { mutableStateOf(WriteStatus.Idle) }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(writeStatus) {
        if (writeStatus != WriteStatus.Idle) {
            delay(3000)
            writeStatus = WriteStatus.Idle
        }
    }

    // Dialogs
    if (showSwitchMapPicker) {
        MapPickerDialog(
            title = "Add Switch Map",
            tableDefinitions = tableDefinitions,
            initialValue = null,
            onSelected = { it?.let { def -> KfzwSwitchMapPreferences.addMap(def) } },
            onDismiss = { showSwitchMapPicker = false }
        )
    }

    if (showWriteConfirmation) {
        val writeCount = switchMaps.count { (idx, pair) -> pair != null && computedOutputs[idx] != null }
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write KFZW Switch Maps") },
            text = { Text("Write $writeCount switch map(s) to the binary?") },
            confirmButton = {
                TextButton(onClick = {
                    showWriteConfirmation = false
                    try {
                        for ((idx, pair) in switchMaps) {
                            val (tableDef, _) = pair ?: continue
                            val output = computedOutputs[idx] ?: continue
                            BinWriter.write(BinFilePreferences.file.value, tableDef, output)
                        }
                        writeStatus = WriteStatus.Success
                    } catch (e: Exception) {
                        e.printStackTrace()
                        writeStatus = WriteStatus.Error
                    }
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showWriteConfirmation = false }) { Text("No") }
            }
        )
    }

    // Main layout
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // DS1 info banner
        Surface(
            shape = MaterialTheme.shapes.small,
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "DS1 overwrites native KFZW with map-switch ignition tables. " +
                    "Add the switch maps from your XDF below to rescale them all to a new max load.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }

        // Configuration card with switch map management
        SwitchMapConfigCard(
            switchMaps = switchMaps,
            activeSwitchIndex = activeSwitchIndex,
            dropdownExpanded = dropdownExpanded,
            onDropdownExpandedChange = { dropdownExpanded = it },
            onActiveSwitchIndexChange = { activeSwitchIndex = it },
            onAddMap = { showSwitchMapPicker = true },
            onRemoveMap = { idx ->
                KfzwSwitchMapPreferences.removeMap(idx)
                // Clear stale edited input
                editedInputMaps = editedInputMaps.toMutableMap().also { it.remove(idx) }
            },
            kfmiopScalarValue = kfmiopScalarValue,
            targetMaxLoad = targetMaxLoad,
            onTargetMaxLoadChange = { targetMaxLoad = it },
            onApplyToAll = {
                // Re-initialize all edited maps from originals to force recompute
                val newMax = targetMaxLoad.toDoubleOrNull() ?: kfmiopScalarValue
                val fresh = mutableMapOf<Int, Map3d>()
                for ((idx, pair) in switchMaps) {
                    if (pair != null) {
                        fresh[idx] = Map3d(pair.second)
                    }
                }
                editedInputMaps = fresh
            }
        )

        // Comparison area — shows active switch map
        ComparisonArea(
            modifier = Modifier.weight(1f),
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            editedInputMap = activeEdited,
            onInputMapChanged = { newMap ->
                editedInputMaps = editedInputMaps.toMutableMap().also { it[activeSwitchIndex] = newMap }
            },
            originalKfzw = activeOriginal,
            outputKfzw = activeOutput
        )

        // Write section
        SwitchMapWriteSection(
            binLoaded = binLoaded,
            binFileName = if (binLoaded) binFile.name else null,
            switchMapCount = switchMaps.count { it.second != null },
            canWrite = canWrite,
            writeStatus = writeStatus,
            onWriteClick = { showWriteConfirmation = true }
        )
    }
}

@Composable
private fun SwitchMapConfigCard(
    switchMaps: List<Pair<Int, Pair<TableDefinition, Map3d>?>>,
    activeSwitchIndex: Int,
    dropdownExpanded: Boolean,
    onDropdownExpandedChange: (Boolean) -> Unit,
    onActiveSwitchIndexChange: (Int) -> Unit,
    onAddMap: () -> Unit,
    onRemoveMap: (Int) -> Unit,
    kfmiopScalarValue: Double,
    targetMaxLoad: String,
    onTargetMaxLoadChange: (String) -> Unit,
    onApplyToAll: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "KFZW \u2014 Rescale Ignition Switch Maps",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Manage and rescale DS1 map-switch ignition tables.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Active map dropdown
            if (switchMaps.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Active Map:", style = MaterialTheme.typography.bodyMedium)
                    Box {
                        OutlinedButton(onClick = { onDropdownExpandedChange(true) }) {
                            val activeLabel = switchMaps.getOrNull(activeSwitchIndex)?.let { (idx, pair) ->
                                "Map $idx: ${pair?.first?.tableName ?: "Not found"}"
                            } ?: "None"
                            Text(activeLabel, style = MaterialTheme.typography.bodySmall)
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { onDropdownExpandedChange(false) }
                        ) {
                            switchMaps.forEachIndexed { listIdx, (idx, pair) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Map $idx: ${pair?.first?.tableName ?: "Not found in XDF"}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    onClick = {
                                        onActiveSwitchIndexChange(listIdx)
                                        onDropdownExpandedChange(false)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Switch map list with remove buttons
            switchMaps.forEach { (idx, pair) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (pair != null) Icons.Default.Check else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (pair != null) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Map $idx: ${pair?.first?.tableName ?: "Not found in XDF"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onRemoveMap(idx) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Add button
            OutlinedButton(
                onClick = onAddMap,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("+ Add Switch Map")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Target max load row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("KFMIOP Value (current):", style = MaterialTheme.typography.bodySmall)
                Text(
                    "%.2f%%".format(kfmiopScalarValue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.weight(1f))

                Text("Target Max Load:", style = MaterialTheme.typography.bodySmall)
                ParameterField(
                    value = targetMaxLoad,
                    onValueChange = onTargetMaxLoadChange,
                    label = "%",
                    tooltip = "Target max load for KFZW axis rescale. " +
                        "Defaults to the KFMIOP scalar. Increase for higher-power builds.",
                    readOnly = false,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(130.dp).height(56.dp)
                )

                OutlinedButton(onClick = onApplyToAll) {
                    Text("Apply to All")
                }
            }
        }
    }
}

@Composable
private fun SwitchMapWriteSection(
    binLoaded: Boolean,
    binFileName: String?,
    switchMapCount: Int,
    canWrite: Boolean,
    writeStatus: WriteStatus,
    onWriteClick: () -> Unit
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

            PrerequisiteRow(
                label = "BIN file",
                detail = if (binLoaded) binFileName!! else "Not loaded",
                met = binLoaded
            )

            PrerequisiteRow(
                label = "Switch maps",
                detail = if (switchMapCount > 0) "$switchMapCount map(s) configured" else "None configured",
                met = switchMapCount > 0
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onWriteClick,
                    enabled = canWrite
                ) {
                    Text("Write All Switch Maps")
                }

                Spacer(modifier = Modifier.width(12.dp))

                AnimatedVisibility(visible = writeStatus != WriteStatus.Idle) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (writeStatus == WriteStatus.Success) Icons.Default.Check else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (writeStatus == WriteStatus.Success) "Written successfully" else "Write failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (!canWrite) {
                Text(
                    text = when {
                        !binLoaded -> "Load a BIN file to write."
                        switchMapCount == 0 -> "Add at least one switch map above."
                        else -> "Configure all prerequisites above."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

// ============================================================================
// Single-Map Mode (ME7 + MED17 non-scalar)
// ============================================================================

@Composable
private fun KfzwSingleMapScreen(
    mapList: List<Pair<TableDefinition, Map3d>>,
    tableDefinitions: List<TableDefinition>,
    kfzwPair: Pair<TableDefinition, Map3d>?,
    kfmiopPair: Pair<TableDefinition, Map3d>?,
    kfmiopIsScalar: Boolean,
    isMed17: Boolean,
    showKfzwPicker: Boolean,
    onShowKfzwPicker: (Boolean) -> Unit,
    showKfmiopPicker: Boolean,
    onShowKfmiopPicker: (Boolean) -> Unit
) {
    val inputKfzw = kfzwPair?.second
    val inputKfmiop = kfmiopPair?.second

    // --- DS1 scalar mode: target max load input ---
    val kfmiopScalarValue = if (kfmiopIsScalar) {
        inputKfmiop!!.zAxis.firstOrNull()?.firstOrNull() ?: 400.0
    } else 0.0

    var targetMaxLoad by remember(kfmiopScalarValue) {
        mutableStateOf(kfmiopScalarValue.toString())
    }

    // --- ME7 / non-DS1 mode: editable KFMIOP xAxis ---
    val kfmiopXAxis = inputKfmiop?.xAxis

    var editedXAxis by remember(kfmiopXAxis, kfmiopIsScalar) {
        mutableStateOf(
            if (!kfmiopIsScalar && kfmiopXAxis != null) arrayOf(kfmiopXAxis.copyOf())
            else arrayOf(emptyArray<Double>())
        )
    }

    // Editable input map
    var editedInputMap by remember(inputKfzw) {
        mutableStateOf(inputKfzw?.let { Map3d(it) })
    }

    // Calculate the rescaled KFZW output — different path for scalar vs 2D KFMIOP
    val outputKfzw = remember(editedInputMap, editedXAxis, kfmiopIsScalar, targetMaxLoad) {
        val input = editedInputMap ?: return@remember null

        if (kfmiopIsScalar) {
            val newMax = targetMaxLoad.toDoubleOrNull() ?: kfmiopScalarValue
            computeRescaledOutput(input, newMax)
        } else {
            if (editedXAxis.isNotEmpty() && editedXAxis[0].isNotEmpty()) {
                val newXAxis = editedXAxis[0]
                val maxValue = newXAxis.last()
                val rescaledXAxis = RescaleAxis.rescaleAxis(input.xAxis, maxValue)
                val newZAxis = Kfzw.generateKfzw(input.xAxis, input.zAxis, rescaledXAxis)
                Map3d(rescaledXAxis, input.yAxis, newZAxis)
            } else null
        }
    }

    // Write prerequisites
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile
    val kfzwMapConfigured = kfzwPair != null
    val kfmiopMapConfigured = kfmiopPair != null
    val canWrite = binLoaded && kfzwMapConfigured && outputKfzw != null &&
        (kfmiopIsScalar || kfmiopMapConfigured)

    var showWriteConfirmation by remember { mutableStateOf(false) }
    var writeStatus by remember { mutableStateOf(WriteStatus.Idle) }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(writeStatus) {
        if (writeStatus != WriteStatus.Idle) {
            delay(3000)
            writeStatus = WriteStatus.Idle
        }
    }

    // Dialogs
    if (showKfzwPicker) {
        MapPickerDialog(
            title = "Select KFZW Map",
            tableDefinitions = tableDefinitions,
            initialValue = kfzwPair?.first,
            onSelected = { KfzwPreferences.setSelectedMap(it) },
            onDismiss = { onShowKfzwPicker(false) }
        )
    }

    if (showKfmiopPicker) {
        MapPickerDialog(
            title = "Select KFMIOP Map",
            tableDefinitions = tableDefinitions,
            initialValue = kfmiopPair?.first,
            onSelected = { KfmiopPreferences.setSelectedMap(it) },
            onDismiss = { onShowKfmiopPicker(false) }
        )
    }

    if (showWriteConfirmation) {
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write KFZW") },
            text = { Text("Are you sure you want to write KFZW to the binary?") },
            confirmButton = {
                TextButton(onClick = {
                    showWriteConfirmation = false
                    val tableDef = kfzwPair?.first
                    if (outputKfzw != null && tableDef != null) {
                        try {
                            BinWriter.write(BinFilePreferences.file.value, tableDef, outputKfzw)
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

    // Main layout: Configure -> Compare -> Write
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // DS1 note for MED17 users
        if (isMed17) {
            Surface(
                shape = MaterialTheme.shapes.small,
                tonalElevation = 1.dp,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (kfmiopIsScalar)
                        "DS1 Note: KFMIOP is a scalar on this ECU. " +
                            "KFZW will be rescaled to the target max load using its own axis."
                    else
                        "DS1 Note: DS1 overwrites native KFZW with map-switch ignition tables. " +
                            "You can still use this tool to rescale the DS1 ignition map \u2014 select the " +
                            "appropriate map-switch table from your XDF instead of the native KFZW.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        if (kfmiopIsScalar) {
            ScalarRescaleConfigCard(
                kfzwMapName = kfzwPair?.first?.tableName,
                onSelectKfzw = { onShowKfzwPicker(true) },
                currentMaxLoad = kfmiopScalarValue,
                targetMaxLoad = targetMaxLoad,
                onTargetMaxLoadChange = { targetMaxLoad = it }
            )
        } else {
            ConfigurationCard(
                kfzwMapName = kfzwPair?.first?.tableName,
                kfmiopMapName = kfmiopPair?.first?.tableName,
                onSelectKfzw = { onShowKfzwPicker(true) },
                onSelectKfmiop = { onShowKfmiopPicker(true) },
                editedXAxis = editedXAxis,
                onXAxisChanged = { newData -> editedXAxis = newData }
            )
        }

        ComparisonArea(
            modifier = Modifier.weight(1f),
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            editedInputMap = editedInputMap,
            onInputMapChanged = { editedInputMap = it },
            originalKfzw = kfzwPair?.second,
            outputKfzw = outputKfzw
        )

        WriteToBinarySection(
            binLoaded = binLoaded,
            binFileName = if (binLoaded) binFile.name else null,
            kfzwMapConfigured = kfzwMapConfigured,
            kfzwMapName = kfzwPair?.first?.tableName,
            kfmiopMapConfigured = kfmiopMapConfigured,
            kfmiopMapName = kfmiopPair?.first?.tableName,
            canWrite = canWrite,
            writeStatus = writeStatus,
            onWriteClick = { showWriteConfirmation = true }
        )
    }
}

// ============================================================================
// Shared Composables
// ============================================================================

@Composable
private fun ScalarRescaleConfigCard(
    kfzwMapName: String?,
    onSelectKfzw: () -> Unit,
    currentMaxLoad: Double,
    targetMaxLoad: String,
    onTargetMaxLoadChange: (String) -> Unit,
    editedYAxis: Array<Array<Double>> = arrayOf(emptyArray()),
    onYAxisChanged: (Array<Array<Double>>) -> Unit = {},
    extrapolatedCount: Int = 0,
    totalCells: Int = 0
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "KFZW \u2014 Rescale Load Axis",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Rescale KFZW's load axis to match a new max load ceiling.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("KFZW:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = kfzwMapName ?: "No Definition Selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onSelectKfzw) { Text("Select Map") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("KFMIOP Value (current):", style = MaterialTheme.typography.bodySmall)
                Text(
                    "%.2f%%".format(currentMaxLoad),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.weight(1f))

                Text("Target Max Load:", style = MaterialTheme.typography.bodySmall)
                ParameterField(
                    value = targetMaxLoad,
                    onValueChange = onTargetMaxLoadChange,
                    label = "%",
                    tooltip = "Target max load for KFZW axis rescale. " +
                        "Defaults to the KFMIOP scalar. Increase for higher-power builds.",
                    readOnly = false,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(130.dp).height(56.dp)
                )
            }

            // Y-axis (RPM) editing
            if (editedYAxis.isNotEmpty() && editedYAxis[0].isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "KFZW RPM Axis (Editable)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "(defines the output RPM breakpoints)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MapAxis(
                    data = editedYAxis,
                    editable = true,
                    onDataChanged = onYAxisChanged
                )
            }

            // Extrapolation warning
            if (extrapolatedCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Extrapolation notice",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$extrapolatedCount of $totalCells cells were extrapolated beyond the original axis range — edge values were held constant",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationCard(
    kfzwMapName: String?,
    kfmiopMapName: String?,
    onSelectKfzw: () -> Unit,
    onSelectKfmiop: () -> Unit,
    editedXAxis: Array<Array<Double>>,
    onXAxisChanged: (Array<Array<Double>>) -> Unit,
    editedYAxis: Array<Array<Double>> = arrayOf(emptyArray()),
    onYAxisChanged: (Array<Array<Double>>) -> Unit = {},
    extrapolatedCount: Int = 0,
    totalCells: Int = 0
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "KFZW Calculator",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Rescale ignition timing for new load axis from KFMIOP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            MapSelectorRow(
                label = "KFZW:",
                mapName = kfzwMapName,
                onSelectMap = onSelectKfzw
            )

            MapSelectorRow(
                label = "KFMIOP (X-Axis):",
                mapName = kfmiopMapName,
                onSelectMap = onSelectKfmiop
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (editedXAxis.isNotEmpty() && editedXAxis[0].isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "KFMIOP Load Axis (Editable)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "(provides the target load breakpoints for rescaling)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MapAxis(
                    data = editedXAxis,
                    editable = true,
                    onDataChanged = onXAxisChanged
                )
            }

            // Y-axis (RPM) editing
            if (editedYAxis.isNotEmpty() && editedYAxis[0].isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "KFZW RPM Axis (Editable)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "(defines the output RPM breakpoints)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MapAxis(
                    data = editedYAxis,
                    editable = true,
                    onDataChanged = onYAxisChanged
                )
            }

            // Extrapolation warning
            if (extrapolatedCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Extrapolation notice",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$extrapolatedCount of $totalCells cells were extrapolated beyond the original axis range — edge values were held constant",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapSelectorRow(
    label: String,
    mapName: String?,
    onSelectMap: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = mapName ?: "No Definition Selected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = onSelectMap) {
            Text("Select Map")
        }
    }
}

@Composable
private fun ComparisonArea(
    modifier: Modifier = Modifier,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    editedInputMap: Map3d?,
    onInputMapChanged: (Map3d) -> Unit,
    originalKfzw: Map3d?,
    outputKfzw: Map3d?
) {
    Column(modifier = modifier) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                text = { Text("KFZW (Input)") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                text = { Text("KFZW (Comparison)") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { onTabSelected(2) },
                text = { Text("Charts") }
            )
        }

        when (selectedTab) {
            0 -> EditableInputSection(
                editedInputMap = editedInputMap,
                onInputMapChanged = onInputMapChanged,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            1 -> SideBySideTables(
                originalKfzw = originalKfzw,
                calculatedKfzw = outputKfzw,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            2 -> TimingCharts(
                originalMap = originalKfzw,
                calculatedMap = outputKfzw,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun EditableInputSection(
    editedInputMap: Map3d?,
    onInputMapChanged: (Map3d) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = "KFZW Values",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Edit values to adjust the interpolation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (editedInputMap != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                MapTable(
                    map = editedInputMap,
                    editable = true,
                    onMapChanged = onInputMapChanged
                )
            }
        } else {
            Text("No map loaded", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SideBySideTables(
    originalKfzw: Map3d?,
    calculatedKfzw: Map3d?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = "KFZW (Original)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (originalKfzw != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(map = originalKfzw, editable = false)
                }
            } else {
                Text("No map loaded", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = "KFZW (Calculated)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (calculatedKfzw != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(map = calculatedKfzw, editable = false)
                }
            } else {
                Text("No data", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun WriteToBinarySection(
    binLoaded: Boolean,
    binFileName: String?,
    kfzwMapConfigured: Boolean,
    kfzwMapName: String?,
    kfmiopMapConfigured: Boolean,
    kfmiopMapName: String?,
    canWrite: Boolean,
    writeStatus: WriteStatus,
    onWriteClick: () -> Unit
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

            PrerequisiteRow(
                label = "BIN file",
                detail = if (binLoaded) binFileName!! else "Not loaded",
                met = binLoaded
            )

            PrerequisiteRow(
                label = "KFZW map",
                detail = if (kfzwMapConfigured) kfzwMapName!! else "Not configured",
                met = kfzwMapConfigured
            )

            PrerequisiteRow(
                label = "KFMIOP map",
                detail = if (kfmiopMapConfigured) kfmiopMapName!! else "Not configured",
                met = kfmiopMapConfigured
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onWriteClick,
                    enabled = canWrite
                ) {
                    Text("Write KFZW")
                }

                Spacer(modifier = Modifier.width(12.dp))

                AnimatedVisibility(visible = writeStatus != WriteStatus.Idle) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (writeStatus == WriteStatus.Success) Icons.Default.Check else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (writeStatus == WriteStatus.Success) "Written successfully" else "Write failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (!canWrite) {
                val message = when {
                    !binLoaded && !kfzwMapConfigured && !kfmiopMapConfigured ->
                        "Load a BIN file and select both KFZW and KFMIOP map definitions."
                    !binLoaded -> "Load a BIN file to write."
                    !kfzwMapConfigured && !kfmiopMapConfigured ->
                        "Select both KFZW and KFMIOP map definitions above."
                    !kfzwMapConfigured -> "Select the KFZW map definition above."
                    !kfmiopMapConfigured -> "Select the KFMIOP map definition above."
                    else -> "Configure all prerequisites above."
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
private fun PrerequisiteRow(label: String, detail: String, met: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (met) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = if (met) "Ready" else "Not ready",
            tint = if (met) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(100.dp)
        )

        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
