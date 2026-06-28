package ui.screens.kfmiop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import data.preferences.SharedAxisPreferences
import data.writer.BinWriter
import domain.math.AxisRescaler
import domain.math.map.Map3d
import domain.model.kfmiop.Kfmiop
import domain.model.rlsol.Rlsol
import data.model.EcuPlatform
import data.preferences.platform.EcuPlatformPreference
import kotlinx.coroutines.delay
import ui.components.ChartSeries
import ui.components.LineChart
import ui.components.MapAxis
import ui.components.MapPickerDialog
import ui.components.MapTable
import ui.components.ParameterField
import ui.navigation.CalibrationTab
import ui.theme.ChartRed
import ui.theme.Primary

private enum class WriteStatus { Idle, Success, Error }

private enum class BoostUnit(val label: String) {
    PSI("PSI"),
    MBAR("mbar abs");

    fun convert(psiValue: Double): Double = when (this) {
        PSI -> psiValue
        MBAR -> psiValue / 0.0145038 + 1013.0
    }

    fun convertMap(map: Map3d): Map3d {
        if (this == PSI) return map
        val newZ = Array(map.zAxis.size) { r ->
            Array(map.zAxis[r].size) { c -> convert(map.zAxis[r][c]) }
        }
        return Map3d(map.xAxis, map.yAxis, newZ)
    }

    fun convertPoints(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (this == PSI) return points
        return points.map { (rpm, psi) -> Pair(rpm, convert(psi)) }
    }
}

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
fun KfmiopScreen() {
    val mapList by BinParser.mapList.collectAsState()
    val tableDefinitions by XdfParser.tableDefinitions.collectAsState()

    var showMapPicker by remember { mutableStateOf(false) }

    // Reactive map selection — recompose when user picks a new map
    var mapVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { KfmiopPreferences.mapChanged.collect { mapVersion++ } }
    val kfmiopPair = remember(mapList, mapVersion) { findMap(mapList, KfmiopPreferences) }
    val inputKfmiop = kfmiopPair?.second

    // MED9 KFMIOP stores xAxis=RPM, yAxis=load — opposite of the algorithm convention.
    // Normalize to xAxis=load, yAxis=RPM before any calculation or display.
    val normalizedKfmiop = remember(inputKfmiop) {
        if (inputKfmiop != null) normalizeKfmiopAxes(inputKfmiop) else null
    }

    // Detect scalar KFMIOP (MED17/DS1: 1×1 map with empty axes)
    val isScalar = normalizedKfmiop != null && normalizedKfmiop.xAxis.isEmpty() && normalizedKfmiop.yAxis.isEmpty()
    val platform = EcuPlatformPreference.platform
    val mapLabel = CalibrationTab.KFMIOP.labelFor(platform)

    // --- Scalar mode state (MED17/DS1) ---
    val currentScalarValue = if (isScalar) {
        normalizedKfmiop!!.zAxis.firstOrNull()?.firstOrNull() ?: 0.0
    } else 0.0

    var editedScalarValue by remember(currentScalarValue) {
        mutableStateOf(currentScalarValue.toString())
    }

    val scalarOutputMap = remember(editedScalarValue, kfmiopPair, isScalar) {
        if (isScalar) {
            val newValue = editedScalarValue.toDoubleOrNull() ?: currentScalarValue
            Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(newValue)))
        } else null
    }

    // --- 2D mode state (ME7 / non-DS1) ---
    var desiredMaxMapPressure by remember {
        mutableStateOf(KfmiopPreferences.maxMapPressure.toString())
    }
    var desiredMaxBoostPressure by remember {
        mutableStateOf(KfmiopPreferences.maxBoostPressure.toString())
    }

    val kfmiopResult = remember(normalizedKfmiop, desiredMaxMapPressure, desiredMaxBoostPressure, isScalar) {
        if (!isScalar && normalizedKfmiop != null) {
            val maxMapPressureVal = desiredMaxMapPressure.toDoubleOrNull() ?: KfmiopPreferences.maxMapPressure
            val maxBoostPressureVal = desiredMaxBoostPressure.toDoubleOrNull() ?: KfmiopPreferences.maxBoostPressure

            val maxMapSensorLoad = Rlsol.rlsol(1030.0, maxMapPressureVal, 0.0, 96.0, 0.106, maxMapPressureVal)
            val maxBoostPressureLoad = Rlsol.rlsol(1030.0, maxBoostPressureVal, 0.0, 96.0, 0.106, maxBoostPressureVal)
            Kfmiop.calculateKfmiop(normalizedKfmiop, maxMapSensorLoad, maxBoostPressureLoad)
        } else null
    }

    // Editable Y-axis (RPM breakpoints) for output KFMIOP
    var editedYAxis by remember(normalizedKfmiop, isScalar) {
        mutableStateOf(
            if (!isScalar && normalizedKfmiop != null && normalizedKfmiop.yAxis.isNotEmpty())
                arrayOf(normalizedKfmiop.yAxis.copyOf())
            else arrayOf(emptyArray<Double>())
        )
    }

    // Rescale output when Y-axis is edited
    val yAxisRescaleResult = remember(kfmiopResult, editedYAxis, isScalar) {
        if (isScalar) return@remember null
        val output = kfmiopResult?.outputKfmiop ?: return@remember null
        val hasYAxisEdit = editedYAxis.isNotEmpty() && editedYAxis[0].isNotEmpty()
        if (!hasYAxisEdit) return@remember null
        try {
            AxisRescaler.rescaleMap(output, newYAxis = editedYAxis[0])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    val finalOutputKfmiop = yAxisRescaleResult?.rescaledMap ?: kfmiopResult?.outputKfmiop

    // Emit Y-axis edits for cross-screen sync
    LaunchedEffect(editedYAxis) {
        if (!isScalar && editedYAxis.isNotEmpty() && editedYAxis[0].isNotEmpty()) {
            SharedAxisPreferences.setKfmiopEditedYAxis(editedYAxis[0])
        }
    }

    // Emit output X-axis (load) for KFZW/KFZWOP sync
    LaunchedEffect(finalOutputKfmiop) {
        val xAxis = finalOutputKfmiop?.xAxis
        if (!isScalar && xAxis != null && xAxis.isNotEmpty()) {
            SharedAxisPreferences.setKfmiopEditedXAxis(xAxis)
        }
    }

    // Collect Y-axis edits from KFMIRL for sync
    val kfmirlSyncYAxis by SharedAxisPreferences.kfmirlEditedYAxis.collectAsState(initial = null)

    // Boost comparison chart data — peak boost per RPM
    val boostChartData = remember(kfmiopResult) {
        val result = kfmiopResult ?: return@remember Pair(emptyList<Pair<Double, Double>>(), emptyList<Pair<Double, Double>>())
        val inputBoost = result.inputBoost
        val outputBoost = result.outputBoost

        val currentPeaks = inputBoost.yAxis.mapIndexedNotNull { i, rpm ->
            if (i < inputBoost.zAxis.size) {
                val peakBoost = inputBoost.zAxis[i].maxOrNull() ?: 0.0
                Pair(rpm, peakBoost)
            } else null
        }
        val targetPeaks = outputBoost.yAxis.mapIndexedNotNull { i, rpm ->
            if (i < outputBoost.zAxis.size) {
                val peakBoost = outputBoost.zAxis[i].maxOrNull() ?: 0.0
                Pair(rpm, peakBoost)
            } else null
        }
        Pair(currentPeaks, targetPeaks)
    }

    // Write prerequisites — scalar mode uses scalarOutputMap, 2D uses kfmiopResult
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile
    val kfmiopMapConfigured = kfmiopPair != null
    val canWrite = binLoaded && kfmiopMapConfigured && (
        if (isScalar) scalarOutputMap != null
        else finalOutputKfmiop != null
    )

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
    if (showMapPicker) {
        MapPickerDialog(
            title = "Select $mapLabel Map",
            tableDefinitions = tableDefinitions,
            initialValue = kfmiopPair?.first,
            onSelected = { KfmiopPreferences.setSelectedMap(it) },
            onDismiss = { showMapPicker = false },
            initialFilter = CalibrationTab.KFMIOP.label
        )
    }

    if (showWriteConfirmation) {
        val writeDialogTitle = if (isScalar) "Write Max Load Ceiling" else "Write $mapLabel"
        val writeDialogText = if (isScalar) {
            "Are you sure you want to write the max load ceiling to the binary?"
        } else {
            "Are you sure you want to write $mapLabel to the binary?"
        }
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text(writeDialogTitle) },
            text = { Text(writeDialogText) },
            confirmButton = {
                TextButton(onClick = {
                    showWriteConfirmation = false
                    val outputMap = if (isScalar) scalarOutputMap else finalOutputKfmiop
                    val tableDef = kfmiopPair?.first
                    // For MED9: restore the original axis layout (RPM→x, load→y)
                    // before writing, since the binary expects the swapped convention.
                    val writeMap = if (outputMap != null && !isScalar && inputKfmiop != null) {
                        denormalizeKfmiopAxes(outputMap, inputKfmiop)
                    } else outputMap
                    if (writeMap != null && tableDef != null) {
                        try {
                            BinWriter.write(BinFilePreferences.file.value, tableDef, writeMap)
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
                    "ℹ DS1 Note: DS1 reduces $mapLabel/KFLMIRL to scalar values and bypasses " +
                        "the native torque model. Load values are maxed out; only boost pressure " +
                        "acts as the torque regulator. Use this as a simple inverse calculator " +
                        "if adjusting the maximum load ceiling.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        // Scalar mode: simplified UI for DS1 max load ceiling
        if (isScalar) {
            ScalarConfigurationCard(
                currentValue = currentScalarValue,
                editedValue = editedScalarValue,
                onEditedValueChange = { editedScalarValue = it },
                mapLabel = mapLabel
            )

            // Spacer fills the comparison area
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$mapLabel is a scalar on this ECU (DS1). " +
                            "Edit the max load ceiling above and write to binary.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // 2D mode: full pressure calculator (ME7 / non-DS1)
            ConfigurationCard(
                analyzedMaxMapPressure = kfmiopResult?.maxMapSensorPressure,
                analyzedMaxBoostPressure = kfmiopResult?.maxBoostPressure,
                desiredMaxMapPressure = desiredMaxMapPressure,
                desiredMaxBoostPressure = desiredMaxBoostPressure,
                onDesiredMaxMapPressureChange = {
                    desiredMaxMapPressure = it
                    it.toDoubleOrNull()?.let { v -> KfmiopPreferences.maxMapPressure = v }
                },
                onDesiredMaxBoostPressureChange = {
                    desiredMaxBoostPressure = it
                    it.toDoubleOrNull()?.let { v -> KfmiopPreferences.maxBoostPressure = v }
                },
                mapDefinitionName = kfmiopPair?.first?.tableName,
                onSelectMap = { showMapPicker = true },
                editedYAxis = editedYAxis,
                onYAxisChanged = { editedYAxis = it },
                extrapolatedCount = yAxisRescaleResult?.extrapolatedCount ?: 0,
                totalCells = yAxisRescaleResult?.totalCells ?: 0,
                mapLabel = mapLabel,
                syncYAxis = kfmirlSyncYAxis,
                onApplySyncYAxis = { syncAxis -> editedYAxis = arrayOf(syncAxis) }
            )

            ComparisonArea(
                modifier = Modifier.weight(1f),
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                inputKfmiop = normalizedKfmiop,
                kfmiopResult = kfmiopResult,
                finalOutputKfmiop = finalOutputKfmiop,
                currentPeakBoost = boostChartData.first,
                targetPeakBoost = boostChartData.second
            )
        }

        WriteToBinarySection(
            binLoaded = binLoaded,
            binFileName = if (binLoaded) binFile.name else null,
            kfmiopMapConfigured = kfmiopMapConfigured,
            kfmiopMapName = kfmiopPair?.first?.tableName,
            canWrite = canWrite,
            writeStatus = writeStatus,
            onWriteClick = { showWriteConfirmation = true },
            mapLabel = mapLabel,
            isScalar = isScalar
        )
    }
}

@Composable
private fun ScalarConfigurationCard(
    currentValue: Double,
    editedValue: String,
    onEditedValueChange: (String) -> Unit,
    mapLabel: String = "KFMIOP"
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "$mapLabel — Max Load Ceiling",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "DS1 uses a single scalar value as the maximum load ceiling. " +
                    "Adjust the target value to raise/lower the ceiling.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Current Value", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ParameterField(
                        value = "%.2f".format(currentValue),
                        onValueChange = {},
                        label = "%",
                        tooltip = "Current max load ceiling from the BIN (read-only).",
                        readOnly = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(180.dp).height(56.dp)
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "transforms to",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text("Target Value", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    ParameterField(
                        value = editedValue,
                        onValueChange = onEditedValueChange,
                        label = "%",
                        tooltip = "Target max load ceiling. DS1 OTS is typically ~400%. " +
                            "High-power builds may need 450-470%.",
                        readOnly = false,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(180.dp).height(56.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Rlsol-based boost → load ceiling helper
            var boostPressureInput by remember { mutableStateOf("") }
            val suggestedLoad = remember(boostPressureInput) {
                val pressure = boostPressureInput.toDoubleOrNull()
                if (pressure != null && pressure > 0) {
                    Rlsol.rlsol(1013.0, pressure, 20.0, 90.0, 0.106, pressure)
                } else null
            }

            Text(
                text = "Boost \u2192 Load Helper",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Enter desired manifold pressure to calculate the corresponding load ceiling.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ParameterField(
                    value = boostPressureInput,
                    onValueChange = { boostPressureInput = it },
                    label = "Boost (mbar abs)",
                    tooltip = "Target manifold absolute pressure in mbar (e.g. 3500 for ~2.5 bar boost).",
                    readOnly = false,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(180.dp).height(56.dp)
                )

                if (suggestedLoad != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "yields",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "\u2248 ${"%.1f".format(suggestedLoad)}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = {
                        onEditedValueChange("%.2f".format(suggestedLoad))
                    }) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationCard(
    analyzedMaxMapPressure: Double?,
    analyzedMaxBoostPressure: Double?,
    desiredMaxMapPressure: String,
    desiredMaxBoostPressure: String,
    onDesiredMaxMapPressureChange: (String) -> Unit,
    onDesiredMaxBoostPressureChange: (String) -> Unit,
    mapDefinitionName: String?,
    onSelectMap: () -> Unit,
    editedYAxis: Array<Array<Double>> = arrayOf(emptyArray()),
    onYAxisChanged: (Array<Array<Double>>) -> Unit = {},
    extrapolatedCount: Int = 0,
    totalCells: Int = 0,
    mapLabel: String = "KFMIOP",
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
                text = "KFMIOP Calculator",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Rescale optimal torque table for upgraded MAP sensor",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PressureColumn(
                    title = "Current (Analyzed)",
                    titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    mapPressure = analyzedMaxMapPressure?.toInt()?.toString() ?: "",
                    boostPressure = analyzedMaxBoostPressure?.toInt()?.toString() ?: "",
                    editable = false,
                    onMapPressureChange = {},
                    onBoostPressureChange = {},
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "transforms to",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                PressureColumn(
                    title = "Target (Desired)",
                    titleColor = MaterialTheme.colorScheme.primary,
                    mapPressure = desiredMaxMapPressure,
                    boostPressure = desiredMaxBoostPressure,
                    editable = true,
                    onMapPressureChange = onDesiredMaxMapPressureChange,
                    onBoostPressureChange = onDesiredMaxBoostPressureChange,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Map Definition:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = mapDefinitionName ?: "No Definition Selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onSelectMap) {
                    Text("Select Map")
                }
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
                        text = "$mapLabel RPM Axis (Editable)",
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

            // Sync banner from KFMIRL
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
                            text = "KFMIRL RPM axis was edited.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onApplySyncYAxis(syncYAxis) }) {
                            Text("Apply to $mapLabel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PressureColumn(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    mapPressure: String,
    boostPressure: String,
    editable: Boolean,
    onMapPressureChange: (String) -> Unit,
    onBoostPressureChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = titleColor
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("MAP Sensor Max:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            ParameterField(
                value = mapPressure,
                onValueChange = onMapPressureChange,
                label = "mbar",
                tooltip = "Maximum pressure the MAP sensor can measure (mbar absolute). Sets the upper axis limit for the KFMIOP table. A 4-bar sensor = 4000 mbar, 3.5-bar = 3500 mbar.",
                readOnly = !editable,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(130.dp).height(56.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Boost Pressure Max:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            ParameterField(
                value = boostPressure,
                onValueChange = onBoostPressureChange,
                label = "mbar",
                tooltip = "Maximum boost pressure target (mbar absolute). Sets the top of the KFMIOP suggested correction range. Should be ≤ MAP sensor max and within turbo limits.",
                readOnly = !editable,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(130.dp).height(56.dp)
            )
        }
    }
}

@Composable
private fun ComparisonArea(
    modifier: Modifier = Modifier,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    inputKfmiop: Map3d?,
    kfmiopResult: Kfmiop?,
    finalOutputKfmiop: Map3d?,
    currentPeakBoost: List<Pair<Double, Double>>,
    targetPeakBoost: List<Pair<Double, Double>>
) {
    var boostUnit by remember { mutableStateOf(BoostUnit.PSI) }

    Column(modifier = modifier) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                text = { Text("Torque") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                text = { Text("Boost") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { onTabSelected(2) },
                text = { Text("Boost Comparison") }
            )
        }

        when (selectedTab) {
            0 -> {
                if (finalOutputKfmiop != null) {
                    OutputAxisBanner(outputKfmiop = finalOutputKfmiop)
                }
                SideBySideTables(
                    inputLabel = "KFMIOP (Current)",
                    inputMap = inputKfmiop,
                    outputLabel = "KFMIOP (Rescaled)",
                    outputMap = finalOutputKfmiop,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
            1 -> {
                BoostUnitToggle(boostUnit) { boostUnit = it }
                SideBySideTables(
                    inputLabel = "Boost (Current) — ${boostUnit.label}",
                    inputMap = kfmiopResult?.inputBoost?.let { boostUnit.convertMap(it) },
                    outputLabel = "Boost (Rescaled) — ${boostUnit.label}",
                    outputMap = kfmiopResult?.outputBoost?.let { boostUnit.convertMap(it) },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
            2 -> {
                BoostUnitToggle(boostUnit) { boostUnit = it }
                BoostComparisonChart(
                    currentPeakBoost = boostUnit.convertPoints(currentPeakBoost),
                    targetPeakBoost = boostUnit.convertPoints(targetPeakBoost),
                    yAxisLabel = boostUnit.label,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun OutputAxisBanner(outputKfmiop: Map3d) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Rescaled Load Axis",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "(shared with KFZWOP, KFZW)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            val xAxisData = remember(outputKfmiop) {
                arrayOf(outputKfmiop.xAxis.copyOf())
            }
            MapAxis(data = xAxisData, editable = false)
        }
    }
}

@Composable
private fun SideBySideTables(
    inputLabel: String,
    inputMap: Map3d?,
    outputLabel: String,
    outputMap: Map3d?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Input (current)
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = inputLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (inputMap != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(map = inputMap, editable = false)
                }
            } else {
                Text("No map loaded", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Output (rescaled)
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = outputLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (outputMap != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MapTable(map = outputMap, editable = false)
                }
            } else {
                Text("No data", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BoostUnitToggle(selected: BoostUnit, onSelected: (BoostUnit) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Unit:", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
        SingleChoiceSegmentedButtonRow {
            BoostUnit.entries.forEachIndexed { index, unit ->
                SegmentedButton(
                    selected = selected == unit,
                    onClick = { onSelected(unit) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = BoostUnit.entries.size)
                ) {
                    Text(unit.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun BoostComparisonChart(
    currentPeakBoost: List<Pair<Double, Double>>,
    targetPeakBoost: List<Pair<Double, Double>>,
    yAxisLabel: String = "PSI",
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        LineChart(
            series = listOf(
                ChartSeries(
                    name = "Current Peak Boost",
                    points = currentPeakBoost,
                    color = Primary
                ),
                ChartSeries(
                    name = "Target Peak Boost",
                    points = targetPeakBoost,
                    color = ChartRed
                )
            ),
            title = "Peak Boost Comparison",
            xAxisLabel = "RPM",
            yAxisLabel = yAxisLabel
        )
    }
}

@Composable
private fun WriteToBinarySection(
    binLoaded: Boolean,
    binFileName: String?,
    kfmiopMapConfigured: Boolean,
    kfmiopMapName: String?,
    canWrite: Boolean,
    writeStatus: WriteStatus,
    onWriteClick: () -> Unit,
    mapLabel: String = "KFMIOP",
    isScalar: Boolean = false
) {
    val writeButtonText = if (isScalar) "Write Max Load Ceiling" else "Write $mapLabel"

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
                label = "$mapLabel map",
                detail = if (kfmiopMapConfigured) kfmiopMapName!! else "Not configured",
                met = kfmiopMapConfigured
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onWriteClick,
                    enabled = canWrite
                ) {
                    Text(writeButtonText)
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
                    !binLoaded && !kfmiopMapConfigured -> "Load a BIN file and select the $mapLabel map definition."
                    !binLoaded -> "Load a BIN file to write."
                    else -> "Select the $mapLabel map definition above."
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

// ─────────────────────────────────────────────────────────────────────────────
// KFMIOP axis normalization helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Normalize KFMIOP to algorithm convention: xAxis=load(%), yAxis=RPM.
 *
 * MED9 binary stores KFMIOP with xAxis=nmot_w (RPM, typically 16 pts),
 * yAxis=rl_w (load, 11 pts) — the inverse of what [Kfmiop.calculateKfmiop] expects.
 * ME7 already has xAxis=load, yAxis=RPM and passes through unchanged.
 *
 * Detection heuristic: last x-axis value > 500 → RPM axis, not load %.
 */
private fun normalizeKfmiopAxes(map: Map3d): Map3d {
    if (map.xAxis.isEmpty() || map.xAxis.last() <= 500.0) return map
    val rows = map.zAxis.size
    val cols = if (rows > 0) map.zAxis[0].size else 0
    if (rows == 0 || cols == 0) return map
    // Swap xAxis↔yAxis; transpose zAxis [load_rows × rpm_cols] → [rpm_rows × load_cols]
    return Map3d(
        xAxis = map.yAxis,
        yAxis = map.xAxis,
        zAxis = Array(cols) { j -> Array(rows) { i -> map.zAxis[i][j] } }
    )
}

/**
 * Inverse of [normalizeKfmiopAxes] — restores the original binary layout before
 * writing to file. [original] is used only for the RPM-heuristic check.
 */
private fun denormalizeKfmiopAxes(normalized: Map3d, original: Map3d): Map3d {
    if (original.xAxis.isEmpty() || original.xAxis.last() <= 500.0) return normalized
    val rows = normalized.zAxis.size
    val cols = if (rows > 0) normalized.zAxis[0].size else 0
    if (rows == 0 || cols == 0) return normalized
    // Transpose [rpm_rows × load_cols] → [load_rows × rpm_cols]; swap axes back
    return Map3d(
        xAxis = normalized.yAxis,
        yAxis = normalized.xAxis,
        zAxis = Array(cols) { j -> Array(rows) { i -> normalized.zAxis[i][j] } }
    )
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
