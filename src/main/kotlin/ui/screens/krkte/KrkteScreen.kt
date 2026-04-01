package ui.screens.krkte

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import data.model.EcuPlatform
import data.preferences.bin.BinFilePreferences
import data.preferences.krkte.KrktePreferences
import data.preferences.krkte.KrktePfiPreferences
import data.preferences.platform.EcuPlatformPreference
import data.preferences.primaryfueling.PrimaryFuelingPreferences
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.krkte.KrkteCalculator
import domain.model.presets.EnginePresets
import domain.model.presets.FuelPreset
import domain.model.presets.FuelPresets
import kotlinx.coroutines.delay
import ui.components.LabeledParameterRow
import java.text.DecimalFormat

private val decimalFormat = DecimalFormat("#.####")

private enum class WriteStatus { Idle, Success, Error }

@Composable
fun KrkteScreen() {
    val scrollState = rememberScrollState()

    var airDensity by remember { mutableStateOf(PrimaryFuelingPreferences.airDensity.toString()) }
    var displacement by remember { mutableStateOf(PrimaryFuelingPreferences.displacement.toString()) }
    var numCylinders by remember { mutableStateOf(PrimaryFuelingPreferences.numCylinders.toString()) }
    var gasolineDensity by remember { mutableStateOf(PrimaryFuelingPreferences.gasolineGramsPerCubicCentimeter.toString()) }
    var stoichiometricAfr by remember { mutableStateOf(PrimaryFuelingPreferences.stoichiometricAfr.toString()) }
    var fuelInjectorSize by remember { mutableStateOf(PrimaryFuelingPreferences.fuelInjectorSize.toString()) }

    val cylinderDisplacement by remember(displacement, numCylinders) {
        derivedStateOf {
            val disp = displacement.toDoubleOrNull() ?: 0.0
            val cyl = numCylinders.toIntOrNull() ?: 1
            if (cyl > 0) disp / cyl else 0.0
        }
    }

    val krkteResult by remember(displacement, numCylinders, airDensity, gasolineDensity, stoichiometricAfr, fuelInjectorSize) {
        derivedStateOf {
            val airDensityVal = airDensity.toDoubleOrNull() ?: 0.0
            val dispVal = displacement.toDoubleOrNull() ?: 0.0
            val cylVal = numCylinders.toIntOrNull() ?: 1
            val fuelDensityVal = gasolineDensity.toDoubleOrNull() ?: 0.0
            val afrVal = stoichiometricAfr.toDoubleOrNull() ?: 0.0
            val injectorVal = fuelInjectorSize.toDoubleOrNull() ?: 0.0
            val cylDisp = if (cylVal > 0) dispVal / cylVal else 0.0
            KrkteCalculator.calculateKrkte(airDensityVal, cylDisp, injectorVal, fuelDensityVal, afrVal)
        }
    }

    // Write prerequisites
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile

    val krktePreference = if (EcuPlatformPreference.platform == EcuPlatform.MED17)
        KrktePfiPreferences else KrktePreferences

    var mapVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { krktePreference.mapChanged.collect { mapVersion++ } }
    val krkteMap = remember(mapVersion) { krktePreference.getSelectedMap() }
    val krkteMapConfigured = krkteMap != null
    val krkteMapName = krkteMap?.first?.tableName

    val canWrite = binLoaded && krkteMapConfigured

    var showWriteConfirmation by remember { mutableStateOf(false) }
    var writeStatus by remember { mutableStateOf(WriteStatus.Idle) }

    LaunchedEffect(writeStatus) {
        if (writeStatus != WriteStatus.Idle) {
            delay(3000)
            writeStatus = WriteStatus.Idle
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Result card at top
        KrkteResultCard(krkteResult)

        // Two-column layout for inputs
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            EngineParametersSection(
                airDensity = airDensity,
                displacement = displacement,
                numCylinders = numCylinders,
                cylinderDisplacement = cylinderDisplacement,
                onDisplacementChange = { newValue ->
                    displacement = newValue
                    newValue.toDoubleOrNull()?.let { PrimaryFuelingPreferences.displacement = it }
                },
                onNumCylindersChange = { newValue ->
                    numCylinders = newValue
                    newValue.toIntOrNull()?.let { PrimaryFuelingPreferences.numCylinders = it }
                },
                modifier = Modifier.weight(1f)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FuelPropertiesSection(
                    gasolineDensity = gasolineDensity,
                    stoichiometricAfr = stoichiometricAfr,
                    onGasolineDensityChange = { newValue ->
                        gasolineDensity = newValue
                        newValue.toDoubleOrNull()?.let { PrimaryFuelingPreferences.gasolineGramsPerCubicCentimeter = it }
                    },
                    onStoichiometricAfrChange = { newValue ->
                        stoichiometricAfr = newValue
                        newValue.toDoubleOrNull()?.let { PrimaryFuelingPreferences.stoichiometricAfr = it }
                    }
                )

                InjectorSection(
                    fuelInjectorSize = fuelInjectorSize,
                    onFuelInjectorSizeChange = { newValue ->
                        fuelInjectorSize = newValue
                        newValue.toDoubleOrNull()?.let { PrimaryFuelingPreferences.fuelInjectorSize = it }
                    }
                )
            }
        }

        // Write to binary section
        WriteToBinarySection(
            binLoaded = binLoaded,
            binFileName = if (binLoaded) binFile.name else null,
            krkteMapConfigured = krkteMapConfigured,
            krkteMapName = krkteMapName,
            canWrite = canWrite,
            writeStatus = writeStatus,
            onWriteClick = { showWriteConfirmation = true }
        )
    }

    if (showWriteConfirmation) {
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write KRKTE") },
            text = { Text("Are you sure you want to write KRKTE to the binary?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWriteConfirmation = false
                        val krkteTable = krktePreference.getSelectedMap()
                        if (krkteTable != null) {
                            try {
                                val tableDefinition = krkteTable.first
                                val map = Map3d()
                                map.zAxis = arrayOf(arrayOf(krkteResult))
                                BinWriter.write(BinFilePreferences.file.value, tableDefinition, map)
                                writeStatus = WriteStatus.Success
                            } catch (e: Exception) {
                                writeStatus = WriteStatus.Error
                            }
                        }
                    }
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWriteConfirmation = false }) {
                    Text("No")
                }
            }
        )
    }
}

@Composable
private fun KrkteResultCard(krkteResult: Double) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Calculated KRKTE",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Primary fueling constant (injector open time per % load)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = decimalFormat.format(krkteResult),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ms/%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun EngineParametersSection(
    airDensity: String,
    displacement: String,
    numCylinders: String,
    cylinderDisplacement: Double,
    onDisplacementChange: (String) -> Unit,
    onNumCylindersChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var engineDropdownExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxHeight()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Engine Parameters",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Physical engine characteristics",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Engine preset dropdown
            Box {
                OutlinedButton(onClick = { engineDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Engine Preset")
                }
                DropdownMenu(expanded = engineDropdownExpanded, onDismissRequest = { engineDropdownExpanded = false }) {
                    EnginePresets.all.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                onDisplacementChange((preset.displacementCc / 1000.0).toString())
                                onNumCylindersChange(preset.cylinderCount.toString())
                                engineDropdownExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            DerivedRow(
                label = "Air Density",
                value = airDensity,
                unit = "g/dm\u00B3",
                description = "Constant at 0\u00B0C and 1013 hPa"
            )

            LabeledParameterRow(
                label = "Engine Displacement",
                value = displacement,
                unit = "dm\u00B3",
                tooltip = "Total swept displacement of the engine in litres (dm³). Determines cylinder volume and is used directly in the KRKTE fuel constant calculation.",
                onValueChange = onDisplacementChange
            )

            LabeledParameterRow(
                label = "Number of Cylinders",
                value = numCylinders,
                unit = "",
                tooltip = "Number of engine cylinders. Divides total displacement into per-cylinder volume for the KRKTE calculation.",
                onValueChange = onNumCylindersChange
            )

            DerivedRow(
                label = "Cylinder Displacement",
                value = decimalFormat.format(cylinderDisplacement),
                unit = "dm\u00B3",
                description = "Displacement / Cylinders"
            )
        }
    }
}

@Composable
private fun FuelPropertiesSection(
    gasolineDensity: String,
    stoichiometricAfr: String,
    onGasolineDensityChange: (String) -> Unit,
    onStoichiometricAfrChange: (String) -> Unit
) {
    var fuelDropdownExpanded by remember { mutableStateOf(false) }
    var blendSliderValue by remember { mutableStateOf(0f) }
    var showBlendSlider by remember { mutableStateOf(false) }
    var expertMode by remember { mutableStateOf(false) }

    // E0/E100 endpoint state, loaded from preferences
    var e0Density by remember { mutableStateOf(PrimaryFuelingPreferences.e0Density.toString()) }
    var e0Afr by remember { mutableStateOf(PrimaryFuelingPreferences.e0Afr.toString()) }
    var e100Density by remember { mutableStateOf(PrimaryFuelingPreferences.e100Density.toString()) }
    var e100Afr by remember { mutableStateOf(PrimaryFuelingPreferences.e100Afr.toString()) }

    fun applyBlend(ethPct: Float) {
        val e0 = if (expertMode) FuelPreset(
            "E0", e0Density.toDoubleOrNull() ?: 0.755, e0Afr.toDoubleOrNull() ?: 14.7
        ) else FuelPresets.E0
        val e100 = if (expertMode) FuelPreset(
            "E100", e100Density.toDoubleOrNull() ?: 0.789, e100Afr.toDoubleOrNull() ?: 9.0
        ) else FuelPresets.E100
        val blended = FuelPresets.blend(ethPct.toDouble(), e0, e100)
        onGasolineDensityChange(blended.densityGPerCc.toString())
        onStoichiometricAfrChange(blended.stoichAfr.toString())
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Fuel Properties",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Fuel density and stoichiometric ratio",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Fuel preset dropdown
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(onClick = { fuelDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Apply Fuel Preset")
                    }
                    DropdownMenu(expanded = fuelDropdownExpanded, onDismissRequest = { fuelDropdownExpanded = false }) {
                        FuelPresets.all.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text("${preset.name} (ρ=${preset.densityGPerCc}, AFR=${preset.stoichAfr})") },
                                onClick = {
                                    onGasolineDensityChange(preset.densityGPerCc.toString())
                                    onStoichiometricAfrChange(preset.stoichAfr.toString())
                                    fuelDropdownExpanded = false
                                    showBlendSlider = false
                                    expertMode = false
                                    blendSliderValue = if (preset.name.contains("E85")) 85f else 0f
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Ethanol Blend (E0–E100 slider)") },
                            onClick = {
                                showBlendSlider = true
                                fuelDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            if (showBlendSlider) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Ethanol Blend: E${blendSliderValue.toInt()}", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = blendSliderValue,
                    onValueChange = {
                        blendSliderValue = it
                        applyBlend(it)
                    },
                    valueRange = 0f..100f,
                    steps = 19
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Expert", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.width(4.dp))
                    Checkbox(
                        checked = expertMode,
                        onCheckedChange = {
                            expertMode = it
                            applyBlend(blendSliderValue)
                        }
                    )
                }
            }

            AnimatedVisibility(visible = expertMode && showBlendSlider) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("E0 Endpoint", style = MaterialTheme.typography.labelLarge)
                    LabeledParameterRow(
                        label = "Gasoline Density",
                        value = e0Density,
                        unit = "g/cc",
                        tooltip = "E0 (pure gasoline) density for flex fuel blending. Default 0.755 g/cc per FR BGKV; " +
                            "some references use 0.7135 g/cc (see gasoline density tooltip for details).",
                        onValueChange = {
                            e0Density = it
                            it.toDoubleOrNull()?.let { v -> PrimaryFuelingPreferences.e0Density = v }
                            applyBlend(blendSliderValue)
                        }
                    )
                    LabeledParameterRow(
                        label = "Stoichiometric AFR",
                        value = e0Afr,
                        unit = "",
                        tooltip = "E0 (pure gasoline) stoichiometric air-fuel ratio.",
                        onValueChange = {
                            e0Afr = it
                            it.toDoubleOrNull()?.let { v -> PrimaryFuelingPreferences.e0Afr = v }
                            applyBlend(blendSliderValue)
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("E100 Endpoint", style = MaterialTheme.typography.labelLarge)
                    LabeledParameterRow(
                        label = "Ethanol Density",
                        value = e100Density,
                        unit = "g/cc",
                        tooltip = "E100 (pure ethanol) density for flex fuel blending.",
                        onValueChange = {
                            e100Density = it
                            it.toDoubleOrNull()?.let { v -> PrimaryFuelingPreferences.e100Density = v }
                            applyBlend(blendSliderValue)
                        }
                    )
                    LabeledParameterRow(
                        label = "Stoichiometric AFR",
                        value = e100Afr,
                        unit = "",
                        tooltip = "E100 (pure ethanol) stoichiometric air-fuel ratio.",
                        onValueChange = {
                            e100Afr = it
                            it.toDoubleOrNull()?.let { v -> PrimaryFuelingPreferences.e100Afr = v }
                            applyBlend(blendSliderValue)
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Current Blend (derived)", style = MaterialTheme.typography.labelLarge)
                    DerivedRow(
                        label = "Density",
                        value = gasolineDensity,
                        unit = "g/cc"
                    )
                    DerivedRow(
                        label = "Stoichiometric AFR",
                        value = stoichiometricAfr,
                        unit = ""
                    )
                }
            }

            // Only show manual density/AFR fields when NOT in expert blend mode
            if (!(expertMode && showBlendSlider)) {
                Spacer(modifier = Modifier.height(8.dp))

                LabeledParameterRow(
                    label = "Gasoline Density",
                    value = gasolineDensity,
                    unit = "g/cc\u00B3",
                    tooltip = "Mass of gasoline per cubic centimetre (g/cc). Default: 0.755 g/cc per the Funktionsrahmen " +
                        "BGKV section (ρ₀ₖₛ = 755 g/dm³). Note: the ME7.5 guide and the FR KRKATE section derive " +
                        "the 1.05 valve correction factor from 0.7135 g/cc, creating a contradiction. " +
                        "Using 0.7135 produces a richer calibration (safer for modified injectors). " +
                        "Editable — enter any value to match your fuel or preference.",
                    onValueChange = onGasolineDensityChange
                )

                LabeledParameterRow(
                    label = "Stoichiometric A/F Ratio",
                    value = stoichiometricAfr,
                    unit = "",
                    tooltip = "Mass ratio of air to fuel at complete combustion. Gasoline ≈ 14.7:1. Affects the calculated fuel mass per cycle in KRKTE.",
                    onValueChange = onStoichiometricAfrChange
                )
            }
        }
    }
}

@Composable
private fun InjectorSection(
    fuelInjectorSize: String,
    onFuelInjectorSizeChange: (String) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Fuel Injector",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Injector flow rate",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LabeledParameterRow(
                label = "Injector Size",
                value = fuelInjectorSize,
                unit = "cc/min",
                tooltip = "Static injector flow rate at rated fuel pressure (cc/min). Combined with fuel density and stoichiometry to derive the KRKTE constant.",
                onValueChange = onFuelInjectorSizeChange
            )
        }
    }
}

@Composable
private fun WriteToBinarySection(
    binLoaded: Boolean,
    binFileName: String?,
    krkteMapConfigured: Boolean,
    krkteMapName: String?,
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
                label = "KRKTE map",
                detail = if (krkteMapConfigured) krkteMapName!! else "Not configured",
                met = krkteMapConfigured
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onWriteClick,
                    enabled = canWrite
                ) {
                    Text("Write KRKTE")
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
                    !binLoaded && !krkteMapConfigured -> "Load a BIN file and configure the KRKTE map in the Configuration screen."
                    !binLoaded -> "Load a BIN file to write."
                    else -> "Configure the KRKTE map definition in the Configuration screen."
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
            modifier = Modifier.width(80.dp)
        )

        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DerivedRow(label: String, value: String, unit: String, description: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.width(120.dp)
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).width(60.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(68.dp))
            }
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// LabeledParameterRow used here is defined in ui.components.InfoTooltip
