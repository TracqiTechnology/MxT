package ui.screens.kfzwop

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
import data.preferences.SharedAxisPreferences
import data.preferences.bin.BinFilePreferences
import data.preferences.kfzwop.KfzwopPreferences
import data.writer.BinWriter
import domain.math.AxisRescaler
import domain.math.map.Map3d
import domain.model.kfzw.Kfzw
import data.model.EcuPlatform
import data.preferences.platform.EcuPlatformPreference
import kotlinx.coroutines.delay
import ui.components.MapAxis
import ui.components.MapPickerDialog
import ui.components.MapTable
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

@Composable
fun KfzwopScreen() {
    val mapList by BinParser.mapList.collectAsState()
    val tableDefinitions by XdfParser.tableDefinitions.collectAsState()

    var showKfzwopPicker by remember { mutableStateOf(false) }

    // Reactive map selection — recompose when user picks a new map
    var mapVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { KfzwopPreferences.mapChanged.collect { mapVersion++ } }

    val kfzwopPair = remember(mapList, mapVersion) { findMap(mapList, KfzwopPreferences) }

    val inputKfzwop = kfzwopPair?.second

    // Editable X-axis (KFZWOP load axis)
    var editedXAxis by remember(inputKfzwop) {
        mutableStateOf(
            if (inputKfzwop != null) arrayOf(inputKfzwop.xAxis.copyOf())
            else arrayOf(emptyArray<Double>())
        )
    }

    // Editable Y-axis (KFZWOP RPM axis)
    var editedYAxis by remember(inputKfzwop) {
        mutableStateOf(
            if (inputKfzwop != null && inputKfzwop.yAxis.isNotEmpty()) arrayOf(inputKfzwop.yAxis.copyOf())
            else arrayOf(emptyArray<Double>())
        )
    }

    // Collect KFMIOP axis edits for sync banners
    val kfmiopSyncYAxis by SharedAxisPreferences.kfmiopEditedYAxis.collectAsState(initial = null)
    val kfmiopSyncXAxis by SharedAxisPreferences.kfmiopEditedXAxis.collectAsState(initial = null)

    // Editable input map
    var editedInputMap by remember(inputKfzwop) {
        mutableStateOf(inputKfzwop?.let { Map3d(it) })
    }

    // Calculate the rescaled KFZWOP output
    val rescaleResult = remember(editedInputMap, editedXAxis, editedYAxis) {
        val input = editedInputMap ?: return@remember null
        if (editedXAxis.isEmpty() || editedXAxis[0].isEmpty()) return@remember null

        val newXAxis = editedXAxis[0]
        val hasYAxisEdit = editedYAxis.isNotEmpty() && editedYAxis[0].isNotEmpty()
        val newYAxis = if (hasYAxisEdit) editedYAxis[0] else null

        // X-axis: recompute Z via Kfzw.generateKfzw (row-wise linear interpolation)
        val xRescaledZ = Kfzw.generateKfzw(input.xAxis, input.zAxis, newXAxis)
        val xRescaledMap = Map3d(newXAxis, input.yAxis, xRescaledZ)

        // Y-axis: if edited, use AxisRescaler for bilinear interpolation on the Y dimension
        if (newYAxis != null) {
            AxisRescaler.rescaleMap(xRescaledMap, newYAxis = newYAxis)
        } else {
            AxisRescaler.RescaleResult(
                rescaledMap = xRescaledMap,
                extrapolatedCells = Array(input.yAxis.size) { BooleanArray(newXAxis.size) },
                extrapolatedCount = 0,
                exactMatchCount = newXAxis.size * input.yAxis.size,
                totalCells = newXAxis.size * input.yAxis.size
            )
        }
    }

    val outputKfzwop = rescaleResult?.rescaledMap

    // Write prerequisites
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile
    val kfzwopMapConfigured = kfzwopPair != null
    val canWrite = binLoaded && kfzwopMapConfigured && outputKfzwop != null

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
    if (showKfzwopPicker) {
        MapPickerDialog(
            title = "Select KFZWOP Map",
            tableDefinitions = tableDefinitions,
            initialValue = kfzwopPair?.first,
            onSelected = { KfzwopPreferences.setSelectedMap(it) },
            onDismiss = { showKfzwopPicker = false }
        )
    }

    if (showWriteConfirmation) {
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write KFZWOP") },
            text = { Text("Are you sure you want to write KFZWOP to the binary?") },
            confirmButton = {
                TextButton(onClick = {
                    showWriteConfirmation = false
                    val tableDef = kfzwopPair?.first
                    if (outputKfzwop != null && tableDef != null) {
                        try {
                            BinWriter.write(BinFilePreferences.file.value, tableDef, outputKfzwop)
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
                    "ℹ DS1 Note: DS1 overwrites native KFZWOP with map-switch ignition tables. " +
                        "You can still use this tool to rescale the DS1 ignition map — select the " +
                        "appropriate map-switch table from your XDF instead of the native KFZWOP.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        ConfigurationCard(
            kfzwopMapName = kfzwopPair?.first?.tableName,
            onSelectKfzwop = { showKfzwopPicker = true },
            editedXAxis = editedXAxis,
            onXAxisChanged = { newData -> editedXAxis = newData },
            editedYAxis = editedYAxis,
            onYAxisChanged = { newData -> editedYAxis = newData },
            extrapolatedCount = rescaleResult?.extrapolatedCount ?: 0,
            totalCells = rescaleResult?.totalCells ?: 0,
            syncYAxis = kfmiopSyncYAxis,
            onApplySyncYAxis = { syncAxis -> editedYAxis = arrayOf(syncAxis) },
            syncXAxis = kfmiopSyncXAxis,
            onApplySyncXAxis = { syncAxis ->
                val native = inputKfzwop?.xAxis
                editedXAxis = arrayOf(
                    if (native != null && syncAxis.size != native.size) {
                        domain.math.RescaleAxis.rescaleAxis(native, syncAxis.last())
                    } else {
                        syncAxis.copyOf()
                    }
                )
            }
        )

        ComparisonArea(
            modifier = Modifier.weight(1f),
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            editedInputMap = editedInputMap,
            onInputMapChanged = { editedInputMap = it },
            originalKfzwop = kfzwopPair?.second,
            outputKfzwop = outputKfzwop
        )

        WriteToBinarySection(
            binLoaded = binLoaded,
            binFileName = if (binLoaded) binFile.name else null,
            kfzwopMapConfigured = kfzwopMapConfigured,
            kfzwopMapName = kfzwopPair?.first?.tableName,
            canWrite = canWrite,
            writeStatus = writeStatus,
            onWriteClick = { showWriteConfirmation = true }
        )
    }
}

@Composable
private fun ConfigurationCard(
    kfzwopMapName: String?,
    onSelectKfzwop: () -> Unit,
    editedXAxis: Array<Array<Double>>,
    onXAxisChanged: (Array<Array<Double>>) -> Unit,
    editedYAxis: Array<Array<Double>>,
    onYAxisChanged: (Array<Array<Double>>) -> Unit,
    extrapolatedCount: Int,
    totalCells: Int,
    syncYAxis: Array<Double>? = null,
    onApplySyncYAxis: (Array<Double>) -> Unit = {},
    syncXAxis: Array<Double>? = null,
    onApplySyncXAxis: (Array<Double>) -> Unit = {}
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "KFZWOP Calculator",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Rescale optimal ignition timing for new load and RPM axes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            MapSelectorRow(
                label = "KFZWOP:",
                mapName = kfzwopMapName,
                onSelectMap = onSelectKfzwop
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (editedXAxis.isNotEmpty() && editedXAxis[0].isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "KFZWOP Load Axis (Editable)",
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
                    onDataChanged = onXAxisChanged,
                    testTagPrefix = "kfzwop-output-x"
                )
            }

            if (editedYAxis.isNotEmpty() && editedYAxis[0].isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "KFZWOP RPM Axis (Editable)",
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
                    onDataChanged = onYAxisChanged,
                    testTagPrefix = "kfzwop-output-y"
                )
            }

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
            // Sync banner from KFMIOP — RPM axis
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
                            text = "KFMIOP RPM axis was edited.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onApplySyncYAxis(syncYAxis) }) {
                            Text("Apply to KFZWOP")
                        }
                    }
                }
            }

            // Sync banner from KFMIOP — Load axis
            if (syncXAxis != null && syncXAxis.isNotEmpty() &&
                editedXAxis.isNotEmpty() && editedXAxis[0].isNotEmpty() &&
                !syncXAxis.contentEquals(editedXAxis[0])) {
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
                            text = "KFMIOP load axis was recalculated.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onApplySyncXAxis(syncXAxis) }) {
                            Text("Apply to KFZWOP")
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
    modifier: Modifier = Modifier,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    editedInputMap: Map3d?,
    onInputMapChanged: (Map3d) -> Unit,
    originalKfzwop: Map3d?,
    outputKfzwop: Map3d?
) {
    Column(modifier = modifier) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                text = { Text("KFZWOP (Input)") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                text = { Text("KFZWOP (Comparison)") }
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
                originalKfzwop = originalKfzwop,
                calculatedKfzwop = outputKfzwop,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            2 -> TimingCharts(
                originalMap = originalKfzwop,
                calculatedMap = outputKfzwop,
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
                text = "KFZWOP Values",
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
                    onMapChanged = onInputMapChanged,
                    testTagPrefix = "kfzwop-input"
                )
            }
        } else {
            Text("No map loaded", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SideBySideTables(
    originalKfzwop: Map3d?,
    calculatedKfzwop: Map3d?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = "KFZWOP (Original)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (originalKfzwop != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(
                        map = originalKfzwop,
                        editable = false,
                        testTagPrefix = "kfzwop-original"
                    )
                }
            } else {
                Text("No map loaded", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = "KFZWOP (Calculated)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (calculatedKfzwop != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(
                        map = calculatedKfzwop,
                        editable = false,
                        testTagPrefix = "kfzwop-calculated"
                    )
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
    kfzwopMapConfigured: Boolean,
    kfzwopMapName: String?,
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
                label = "KFZWOP map",
                detail = if (kfzwopMapConfigured) kfzwopMapName!! else "Not configured",
                met = kfzwopMapConfigured
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onWriteClick,
                    enabled = canWrite
                ) {
                    Text("Write KFZWOP")
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
                    !binLoaded && !kfzwopMapConfigured ->
                        "Load a BIN file and select the KFZWOP map definition."
                    !binLoaded -> "Load a BIN file to write."
                    !kfzwopMapConfigured -> "Select the KFZWOP map definition above."
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
