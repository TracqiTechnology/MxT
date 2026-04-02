package ui.screens.kfmirl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import data.preferences.kfmirl.KfmirlPreferences
import data.writer.BinWriter
import data.preferences.SharedAxisPreferences
import domain.math.AxisRescaler
import domain.math.Inverse
import domain.math.RescaleMap
import domain.math.map.Map3d
import data.model.EcuPlatform
import data.preferences.platform.EcuPlatformPreference
import kotlinx.coroutines.delay
import ui.components.MapAxis
import ui.components.MapPickerDialog
import ui.components.MapTable
import ui.components.ParameterField
import ui.components.TimingCharts
import ui.navigation.CalibrationTab

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
fun KfmirlScreen() {
    val mapList by BinParser.mapList.collectAsState()
    val tableDefinitions by XdfParser.tableDefinitions.collectAsState()

    var showKfmiopPicker by remember { mutableStateOf(false) }
    var showKfmirlPicker by remember { mutableStateOf(false) }

    // Reactive map selection — recompose when user picks a new map
    var mapVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { KfmiopPreferences.mapChanged.collect { mapVersion++ } }
    LaunchedEffect(Unit) { KfmirlPreferences.mapChanged.collect { mapVersion++ } }

    val kfmiopPair = remember(mapList, mapVersion) { findMap(mapList, KfmiopPreferences) }
    val kfmirlPair = remember(mapList, mapVersion) { findMap(mapList, KfmirlPreferences) }

    val inputKfmiop = kfmiopPair?.second

    // Detect scalar KFMIOP (DS1: 1×1 map with empty axes)
    val kfmiopIsScalar = inputKfmiop != null && inputKfmiop.xAxis.isEmpty() && inputKfmiop.yAxis.isEmpty()

    val platform = EcuPlatformPreference.platform
    val kfmiopLabel = CalibrationTab.KFMIOP.labelFor(platform)
    val kfmirlLabel = CalibrationTab.KFMIRL.labelFor(platform)

    // --- DS1 scalar mode: target max load input ---
    val kfmiopScalarValue = if (kfmiopIsScalar) {
        inputKfmiop!!.zAxis.firstOrNull()?.firstOrNull() ?: 400.0
    } else 0.0

    var targetMaxLoad by remember(kfmiopScalarValue) {
        mutableStateOf(kfmiopScalarValue.toString())
    }

    // --- ME7 / non-DS1 mode: editable KFMIOP xAxis ---
    var editedXAxis by remember(inputKfmiop, kfmiopIsScalar) {
        mutableStateOf(
            if (!kfmiopIsScalar && inputKfmiop != null) arrayOf(inputKfmiop.xAxis.copyOf())
            else arrayOf(emptyArray<Double>())
        )
    }

    var editedInputMap by remember(inputKfmiop, kfmiopIsScalar) {
        mutableStateOf(if (!kfmiopIsScalar) inputKfmiop?.let { Map3d(it) } else null)
    }

    // Editable Y-axis (RPM breakpoints) for KFMIRL output
    var editedYAxis by remember(kfmirlPair, kfmiopIsScalar) {
        val kfmirlY = kfmirlPair?.second?.yAxis
        mutableStateOf(
            if (kfmirlY != null && kfmirlY.isNotEmpty()) arrayOf(kfmirlY.copyOf())
            else arrayOf(emptyArray<Double>())
        )
    }

    // Calculate KFMIRL output — different path for scalar vs 2D KFMIOP
    val outputKfmirl = remember(editedInputMap, editedXAxis, kfmirlPair, kfmiopIsScalar, targetMaxLoad) {
        val kfmirlBase = kfmirlPair?.second

        if (kfmiopIsScalar && kfmirlBase != null) {
            // DS1 path: rescale KFMIRL's own xAxis to the target max load
            val newMax = targetMaxLoad.toDoubleOrNull() ?: kfmiopScalarValue
            RescaleMap.rescaleMapXAxis(kfmirlBase, newMax)
        } else {
            // ME7 path: inverse of KFMIOP
            val kfmiop = editedInputMap
            if (kfmiop != null && kfmirlBase != null && editedXAxis.isNotEmpty() && editedXAxis[0].isNotEmpty()) {
                val kfmiopWithNewXAxis = Map3d(editedXAxis[0], kfmiop.yAxis, kfmiop.zAxis)
                val inverse = Inverse.calculateInverse(kfmiopWithNewXAxis, kfmirlBase)
                // Preserve the first column from the original KFMIRL
                for (i in inverse.zAxis.indices) {
                    if (inverse.zAxis[i].isNotEmpty() && kfmirlBase.zAxis[i].isNotEmpty()) {
                        inverse.zAxis[i][0] = kfmirlBase.zAxis[i][0]
                    }
                }
                inverse
            } else null
        }
    }

    // Rescale KFMIRL output when Y-axis is edited
    val yAxisRescaleResult = remember(outputKfmirl, editedYAxis) {
        val output = outputKfmirl ?: return@remember null
        val hasYAxisEdit = editedYAxis.isNotEmpty() && editedYAxis[0].isNotEmpty()
        if (!hasYAxisEdit) return@remember null
        try {
            AxisRescaler.rescaleMap(output, newYAxis = editedYAxis[0])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    val finalOutputKfmirl = yAxisRescaleResult?.rescaledMap ?: outputKfmirl

    // Emit Y-axis edits for cross-screen sync
    LaunchedEffect(editedYAxis) {
        if (editedYAxis.isNotEmpty() && editedYAxis[0].isNotEmpty()) {
            SharedAxisPreferences.setKfmirlEditedYAxis(editedYAxis[0])
        }
    }

    // Collect Y-axis edits from KFMIOP for sync
    val kfmiopSyncYAxis by SharedAxisPreferences.kfmiopEditedYAxis.collectAsState(initial = null)

    // Write prerequisites — scalar mode only needs KFMIRL configured
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile
    val kfmiopMapConfigured = kfmiopPair != null
    val kfmirlMapConfigured = kfmirlPair != null
    val canWrite = binLoaded && kfmirlMapConfigured && finalOutputKfmirl != null &&
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
    if (showKfmiopPicker) {
        MapPickerDialog(
            title = "Select $kfmiopLabel Map",
            tableDefinitions = tableDefinitions,
            initialValue = kfmiopPair?.first,
            onSelected = { KfmiopPreferences.setSelectedMap(it) },
            onDismiss = { showKfmiopPicker = false },
            initialFilter = CalibrationTab.KFMIOP.label
        )
    }

    if (showKfmirlPicker) {
        MapPickerDialog(
            title = "Select $kfmirlLabel Map",
            tableDefinitions = tableDefinitions,
            initialValue = kfmirlPair?.first,
            onSelected = { KfmirlPreferences.setSelectedMap(it) },
            onDismiss = { showKfmirlPicker = false },
            initialFilter = CalibrationTab.KFMIRL.label
        )
    }

    if (showWriteConfirmation) {
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write $kfmirlLabel") },
            text = { Text("Are you sure you want to write $kfmirlLabel to the binary?") },
            confirmButton = {
                TextButton(onClick = {
                    showWriteConfirmation = false
                    val tableDef = kfmirlPair?.first
                    if (finalOutputKfmirl != null && tableDef != null) {
                        try {
                            BinWriter.write(BinFilePreferences.file.value, tableDef, finalOutputKfmirl)
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
        if (EcuPlatformPreference.platform == EcuPlatform.MED17) {
            Surface(
                shape = MaterialTheme.shapes.small,
                tonalElevation = 1.dp,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (kfmiopIsScalar)
                        "ℹ DS1 Note: $kfmiopLabel is a scalar on this ECU. " +
                            "$kfmirlLabel will be rescaled to the target max load using its own axis."
                    else
                        "ℹ DS1 Note: DS1 reduces $kfmiopLabel/$kfmirlLabel to scalar values and bypasses " +
                            "the native torque model. $kfmirlLabel here works as a simple inverse calculator " +
                            "to convert between load and torque values.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        if (kfmiopIsScalar) {
            // DS1 scalar mode: show target max load input
            ScalarRescaleConfigCard(
                kfmiopLabel = kfmiopLabel,
                kfmirlLabel = kfmirlLabel,
                currentMaxLoad = kfmiopScalarValue,
                targetMaxLoad = targetMaxLoad,
                onTargetMaxLoadChange = { targetMaxLoad = it }
            )
        } else {
            // ME7 mode: full KFMIOP axis editor
            ConfigurationCard(
                kfmiopLabel = kfmiopLabel,
                kfmirlLabel = kfmirlLabel,
                kfmiopMapName = kfmiopPair?.first?.tableName,
                kfmirlMapName = kfmirlPair?.first?.tableName,
                onSelectKfmiop = { showKfmiopPicker = true },
                onSelectKfmirl = { showKfmirlPicker = true },
                editedXAxis = editedXAxis,
                onXAxisChanged = { newData ->
                    editedXAxis = newData
                    editedInputMap?.let { currentMap ->
                        editedInputMap = Map3d(newData[0], currentMap.yAxis, currentMap.zAxis)
                    }
                },
                editedYAxis = editedYAxis,
                onYAxisChanged = { editedYAxis = it },
                extrapolatedCount = yAxisRescaleResult?.extrapolatedCount ?: 0,
                totalCells = yAxisRescaleResult?.totalCells ?: 0,
                syncYAxis = kfmiopSyncYAxis,
                onApplySyncYAxis = { syncAxis -> editedYAxis = arrayOf(syncAxis) }
            )
        }

        ComparisonArea(
            kfmiopLabel = kfmiopLabel,
            kfmirlLabel = kfmirlLabel,
            modifier = Modifier.weight(1f),
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            editedInputMap = editedInputMap,
            onInputMapChanged = { editedInputMap = it },
            originalKfmirl = kfmirlPair?.second,
            outputKfmirl = finalOutputKfmirl
        )

        WriteToBinarySection(
            kfmiopLabel = kfmiopLabel,
            kfmirlLabel = kfmirlLabel,
            binLoaded = binLoaded,
            binFileName = if (binLoaded) binFile.name else null,
            kfmiopMapConfigured = kfmiopMapConfigured,
            kfmiopMapName = kfmiopPair?.first?.tableName,
            kfmirlMapConfigured = kfmirlMapConfigured,
            kfmirlMapName = kfmirlPair?.first?.tableName,
            canWrite = canWrite,
            writeStatus = writeStatus,
            onWriteClick = { showWriteConfirmation = true }
        )
    }
}

@Composable
private fun ScalarRescaleConfigCard(
    kfmiopLabel: String = "KFMIOP",
    kfmirlLabel: String = "KFMIRL",
    currentMaxLoad: Double,
    targetMaxLoad: String,
    onTargetMaxLoadChange: (String) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "$kfmirlLabel — Rescale Load Axis",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Rescale $kfmirlLabel's load axis to match a new max load ceiling.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$kfmiopLabel Value (current):", style = MaterialTheme.typography.bodySmall)
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
                    tooltip = "Target max load for $kfmirlLabel axis rescale. " +
                        "Defaults to the $kfmiopLabel scalar. Increase for higher-power builds.",
                    readOnly = false,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(130.dp).height(56.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfigurationCard(
    kfmiopLabel: String = "KFMIOP",
    kfmirlLabel: String = "KFMIRL",
    kfmiopMapName: String?,
    kfmirlMapName: String?,
    onSelectKfmiop: () -> Unit,
    onSelectKfmirl: () -> Unit,
    editedXAxis: Array<Array<Double>>,
    onXAxisChanged: (Array<Array<Double>>) -> Unit,
    editedYAxis: Array<Array<Double>> = arrayOf(emptyArray()),
    onYAxisChanged: (Array<Array<Double>>) -> Unit = {},
    extrapolatedCount: Int = 0,
    totalCells: Int = 0,
    syncYAxis: Array<Double>? = null,
    onApplySyncYAxis: (Array<Double>) -> Unit = {}
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "$kfmirlLabel Calculator",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Inverse torque-to-load mapping from $kfmiopLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            MapSelectorRow(
                label = "$kfmiopLabel (Input):",
                mapName = kfmiopMapName,
                onSelectMap = onSelectKfmiop
            )

            MapSelectorRow(
                label = "$kfmirlLabel (Target):",
                mapName = kfmirlMapName,
                onSelectMap = onSelectKfmirl
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (editedXAxis.isNotEmpty() && editedXAxis[0].isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "$kfmiopLabel Load Axis (Editable)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "(defines the output load breakpoints)",
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
                        text = "$kfmirlLabel RPM Axis (Editable)",
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
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Extrapolation warning",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "⚠ $extrapolatedCount of $totalCells cells required extrapolation (outside original axis range)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Sync banner from KFMIOP
            if (syncYAxis != null && syncYAxis.isNotEmpty() &&
                editedYAxis.isNotEmpty() && editedYAxis[0].isNotEmpty() &&
                !syncYAxis.contentEquals(editedYAxis[0])) {
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
                        Text(
                            text = "$kfmiopLabel RPM axis was edited.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onApplySyncYAxis(syncYAxis) }) {
                            Text("Apply to $kfmirlLabel")
                        }
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
    kfmiopLabel: String = "KFMIOP",
    kfmirlLabel: String = "KFMIRL",
    modifier: Modifier = Modifier,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    editedInputMap: Map3d?,
    onInputMapChanged: (Map3d) -> Unit,
    originalKfmirl: Map3d?,
    outputKfmirl: Map3d?
) {
    Column(modifier = modifier) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                text = { Text("$kfmiopLabel (Input)") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                text = { Text("$kfmirlLabel (Comparison)") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { onTabSelected(2) },
                text = { Text("Charts") }
            )
        }

        when (selectedTab) {
            0 -> EditableInputSection(
                kfmiopLabel = kfmiopLabel,
                editedInputMap = editedInputMap,
                onInputMapChanged = onInputMapChanged,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            1 -> SideBySideTables(
                kfmirlLabel = kfmirlLabel,
                originalKfmirl = originalKfmirl,
                calculatedKfmirl = outputKfmirl,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            2 -> TimingCharts(
                originalMap = originalKfmirl,
                calculatedMap = outputKfmirl,
                modifier = Modifier.fillMaxWidth().weight(1f),
                heatmapTitle = "Load Delta (Original − Calculated)",
                sliceTitle = "Load vs RPM at Selected Torques",
                sliceYLabel = "Load (%)",
                contourTitle = "Load Contours"
            )
        }
    }
}

@Composable
private fun EditableInputSection(
    kfmiopLabel: String = "KFMIOP",
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
                text = "$kfmiopLabel Values",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Edit values to adjust the inverse calculation.",
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
    kfmirlLabel: String = "KFMIRL",
    originalKfmirl: Map3d?,
    calculatedKfmirl: Map3d?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Side-by-side view of the original $kfmirlLabel from the binary and the calculated inverse. " +
                "Differences highlight where the torque-to-load mapping will change.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = "$kfmirlLabel (Original)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (originalKfmirl != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(map = originalKfmirl, editable = false)
                }
            } else {
                Text("No map loaded", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = "$kfmirlLabel (Calculated)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (calculatedKfmirl != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(map = calculatedKfmirl, editable = false)
                }
            } else {
                Text("No data", style = MaterialTheme.typography.bodyMedium)
            }
        }
        }
    }
}

@Composable
private fun WriteToBinarySection(
    kfmiopLabel: String = "KFMIOP",
    kfmirlLabel: String = "KFMIRL",
    binLoaded: Boolean,
    binFileName: String?,
    kfmiopMapConfigured: Boolean,
    kfmiopMapName: String?,
    kfmirlMapConfigured: Boolean,
    kfmirlMapName: String?,
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
                label = "$kfmiopLabel map",
                detail = if (kfmiopMapConfigured) kfmiopMapName!! else "Not configured",
                met = kfmiopMapConfigured
            )

            PrerequisiteRow(
                label = "$kfmirlLabel map",
                detail = if (kfmirlMapConfigured) kfmirlMapName!! else "Not configured",
                met = kfmirlMapConfigured
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onWriteClick,
                    enabled = canWrite
                ) {
                    Text("Write $kfmirlLabel")
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
                    !binLoaded && !kfmiopMapConfigured && !kfmirlMapConfigured ->
                        "Load a BIN file and select both $kfmiopLabel and $kfmirlLabel map definitions."
                    !binLoaded -> "Load a BIN file to write."
                    !kfmiopMapConfigured && !kfmirlMapConfigured ->
                        "Select both $kfmiopLabel and $kfmirlLabel map definitions above."
                    !kfmiopMapConfigured -> "Select the $kfmiopLabel map definition above."
                    !kfmirlMapConfigured -> "Select the $kfmirlLabel map definition above."
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
