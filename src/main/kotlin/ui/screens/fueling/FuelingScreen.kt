package ui.screens.fueling

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import domain.model.injector.InjectorScalingSolver
import domain.model.injector.InjectorSpec
import domain.model.injector.KrkteScalingResult
import domain.model.injector.TvubResult
import data.model.EcuPlatform
import data.preferences.platform.EcuPlatformPreference
import ui.components.ParameterField
import ui.screens.krkte.KrkteScreen

/**
 * Consolidated Fueling screen with tabs for:
 * 1. KRKTE — Fuel injector constant calculator (existing)
 * 2. Injector Scaling — KRKTE cross-check + TVUB dead time generator from injector specs
 */
@Composable
fun FuelingScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val isMed17 = EcuPlatformPreference.platform == EcuPlatform.MED17
    val krkteLabel = if (isMed17) "KRKATE" else "KRKTE"
    val tabTitles = listOf(krkteLabel, "Injector Scaling")

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
            0 -> KrkteScreen()
            1 -> InjectorScalingTab()
        }
    }
}

// ── Injector Scaling Tab ──────────────────────────────────────────────

@Composable
private fun InjectorScalingTab() {
    val scrollState = rememberScrollState()
    val isMed17 = EcuPlatformPreference.platform == EcuPlatform.MED17
    val isMed9 = EcuPlatformPreference.platform == EcuPlatform.MED9

    // Old injector specs
    var oldFlowRate by remember { mutableStateOf("") }
    var oldPressure by remember { mutableStateOf("3.0") }
    var oldDeadTime by remember { mutableStateOf("") }

    // New injector specs
    var newFlowRate by remember { mutableStateOf("") }
    var newPressure by remember { mutableStateOf("3.0") }
    var newDeadTime by remember { mutableStateOf("") }

    // New injector TVUB voltage table (comma-separated pairs)
    var newTvubData by remember { mutableStateOf("") }

    // Results
    var scalingResult by remember { mutableStateOf<KrkteScalingResult?>(null) }
    var tvubResult by remember { mutableStateOf<TvubResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Header ---
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Injector Scaling Calculator", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Compute KRKTE scale factor and TVUB dead time table when swapping " +
                        "injectors. " + when {
                            isMed17 -> "In MED17, injection time for port injectors is computed as:"
                            isMed9 -> "In MED9, injection time for direct injectors is computed as:"
                            else -> "In ME7, injection time is computed as:"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    when {
                        isMed17 -> "te_pfi = rk_w × KRKTE_PFI × portShare + TVUB_PFI(ubat)"
                        isMed9 -> "te = rk_w × KRKATE + TVUB(ubat)"
                        else -> "te = rk_w × KRKTE + TVUB(ubat)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "KRKTE is a scalar constant [ms/%]. When changing injectors:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "KRKTE_new = KRKTE_old × (old_flow / new_flow) × √(P_new / P_old)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // --- Old Injector ---
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Stock Injector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    ParameterField(
                        value = oldFlowRate,
                        onValueChange = { oldFlowRate = it },
                        label = "Flow Rate (cc/min)",
                        tooltip = "Static injector flow rate at the rated fuel pressure (cc/min). Used as the baseline for scaling to new injectors.",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    ParameterField(
                        value = oldPressure,
                        onValueChange = { oldPressure = it },
                        label = when {
                            isMed17 -> "Fuel Pressure (bar, gauge)"
                            else -> "Fuel Pressure (bar, gauge)"
                        },
                        tooltip = when {
                            isMed17 -> "Rail fuel pressure at which the stock injector flow rate was measured. MED17 port injectors typically run 3.0 bar gauge (4.0 bar absolute). Used for pressure-corrected flow scaling."
                            isMed9 -> "Rail fuel pressure at which the stock injector flow rate was measured. MED9 direct injectors run at high pressure (50–110 bar via HPFP). Used for pressure-corrected flow scaling."
                            else -> "Rail fuel pressure at which the stock injector flow rate was measured. ME7 typically runs 3.0 bar gauge. Used for pressure-corrected flow scaling."
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    ParameterField(
                        value = oldDeadTime,
                        onValueChange = { oldDeadTime = it },
                        label = "Dead Time @ 14V (ms)",
                        tooltip = when {
                            isMed17 -> "Injector opening delay at 14V battery voltage (ms). MED17 compensates for this via the TVUB map. Stock port injectors are typically 0.7–1.0 ms."
                            isMed9 -> "Injector opening delay at 14V battery voltage (ms). MED9 compensates for this via the TVUB map. Direct injectors typically have shorter dead times."
                            else -> "Injector opening delay at 14V battery voltage (ms). ME7 compensates for this via the TVUB map. Stock injectors are typically 0.7–1.0 ms."
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // --- New Injector ---
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("New Injector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    ParameterField(
                        value = newFlowRate,
                        onValueChange = { newFlowRate = it },
                        label = "Flow Rate (cc/min)",
                        tooltip = "Static flow rate of the new/upgraded injectors at their rated pressure. Used to compute the KRKTE scaling ratio.",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    ParameterField(
                        value = newPressure,
                        onValueChange = { newPressure = it },
                        label = "Fuel Pressure (bar, gauge)",
                        tooltip = "Rail fuel pressure at which the new injector flow rate was measured (gauge, not absolute). Flow rate is corrected to match the actual rail pressure.",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    ParameterField(
                        value = newDeadTime,
                        onValueChange = { newDeadTime = it },
                        label = "Dead Time @ 14V (ms)",
                        tooltip = "Injector opening delay of the new injectors at 14V. Larger injectors often have longer dead times. Used to generate the TVUB compensation table.",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                ParameterField(
                    value = newTvubData,
                    onValueChange = { newTvubData = it },
                    label = "TVUB Voltage Table (optional)",
                    tooltip = "Comma-separated voltage:deadtime pairs for full battery-voltage compensation (e.g. 6.0:2.8, 8.0:1.9, 14.0:0.8). If omitted, TVUB is estimated from the 14V dead time using 1/V scaling.",
                    placeholder = { Text("6.0:2.8, 8.0:1.9, 10.0:1.3, 12.0:1.0, 14.0:0.8, 16.0:0.7") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Format: voltage:deadtime pairs, comma-separated. " +
                        "If omitted, TVUB is estimated from the 14V dead time using 1/V scaling.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- Calculate / Reset Buttons ---
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    errorMessage = null
                    try {
                        val oldFlow = oldFlowRate.toDouble()
                        val newFlow = newFlowRate.toDouble()
                        val oldPress = oldPressure.toDoubleOrNull() ?: 3.0
                        val newPress = newPressure.toDoubleOrNull() ?: 3.0
                        val oldDt = oldDeadTime.toDoubleOrNull() ?: 0.0
                        val newDt = newDeadTime.toDoubleOrNull() ?: 0.0

                        val oldSpec = InjectorSpec(
                            flowRateCcPerMin = oldFlow,
                            fuelPressureBar = oldPress,
                            deadTimeMs = oldDt
                        )

                        val tvubMap = parsePairData(newTvubData)

                        val newSpec = InjectorSpec(
                            flowRateCcPerMin = newFlow,
                            fuelPressureBar = newPress,
                            deadTimeMs = newDt,
                            deadTimeVoltageTable = tvubMap
                        )

                        scalingResult = InjectorScalingSolver.computeKrkteScaling(oldSpec, newSpec)

                        tvubResult = if (tvubMap.isNotEmpty() || newDt > 0.0) {
                            InjectorScalingSolver.computeTvub(newSpec)
                        } else null

                    } catch (_: NumberFormatException) {
                        errorMessage = "Invalid number format. Check flow rate fields."
                    } catch (e: IllegalArgumentException) {
                        errorMessage = e.message
                    }
                }
            ) {
                Text("Calculate")
            }

            OutlinedButton(
                onClick = {
                    oldFlowRate = ""; oldPressure = "3.0"; oldDeadTime = ""
                    newFlowRate = ""; newPressure = "3.0"; newDeadTime = ""
                    newTvubData = ""
                    scalingResult = null; tvubResult = null; errorMessage = null
                }
            ) {
                Text("Reset")
            }
        }

        // --- Error ---
        errorMessage?.let { msg ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(msg, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        // --- Scaling Result ---
        scalingResult?.let { result ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("KRKTE Scaling Result", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        StatColumn("Scale Factor", "%.4f".format(result.scaleFactor), large = true)
                        StatColumn("Flow Ratio", "%.4f".format(result.flowRatio))
                        StatColumn("Pressure Correction", "%.4f".format(result.pressureCorrection))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text("Apply to KRKTE:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "KRKTE_new = KRKTE_old × %.4f".format(result.scaleFactor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Or use the KRKTE tab to compute the absolute value from first principles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (result.warnings.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        result.warnings.forEach { warning ->
                            Text("⚠ $warning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // --- TVUB Result ---
        tvubResult?.let { result ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TVUB (Dead Time Table)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Voltage-dependent injector opening delay (Ventilverzugszeit). " +
                            "Enter these values in your BIN for the new injectors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Header
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Voltage (V)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Dead Time (ms)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider()

                    result.voltageAxis.zip(result.deadTimes.toList()).forEach { (voltage, deadTime) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text("%.1f".format(voltage), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("%.3f".format(deadTime), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // --- Instructions ---
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How to Use", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "1. Enter stock injector flow rate (cc/min at rated pressure)\n" +
                        "2. Enter new injector flow rate, fuel pressure, and dead time\n" +
                        "3. Optionally provide TVUB voltage table from the injector data sheet\n" +
                        "4. Click Calculate to get the KRKTE scale factor and TVUB table\n" +
                        "5. Update KRKTE in your BIN (or use the KRKTE tab for absolute calculation)\n" +
                        "6. Update TVUB values in your BIN\n" +
                        "7. Reset ECU adaptations after flashing, then drive with MAF connected to re-learn",
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text("Important Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        isMed17 ->
                            "• Changing injectors affects fueling, not air measurement\n" +
                                "• MED17 has separate KRKTE_PFI and KRKTE_GDI scalars — use the Dual Injection tab for separate bank scaling\n" +
                                "• TVUB_PFI is the port injector dead time table\n" +
                                "• Always reset ECU adaptations after an injector change"
                        isMed9 ->
                            "• Changing injectors affects fueling, not air measurement\n" +
                                "• KRKATE is the MED9 equivalent of ME7's KRKTE — same scalar constant concept\n" +
                                "• MED9 uses direct injection only (no port injectors)\n" +
                                "• TVUB is the injector dead time table\n" +
                                "• Always reset ECU adaptations after an injector change\n" +
                                "• Drive with the MAF connected to re-learn adaptation values"
                        else ->
                            "• Changing injectors affects fueling, not air measurement (msdk_w / alpha-n)\n" +
                                "• KRKTE is a scalar constant — ME7 does not have a 2D injection time map\n" +
                                "• TVUB is the only injector-specific table besides KRKTE\n" +
                                "• Always reset ECU adaptations after an injector change\n" +
                                "• Drive with the MAF connected to re-learn msndko_w / fkmsdk_w adaptation values\n" +
                                "• Use the Alpha-N Diagnostic (WDKUGDN tab) to verify msdk_w accuracy afterward"
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    when {
                        isMed17 -> "MED17 Injection Architecture"
                        isMed9 -> "MED9 Injection Architecture"
                        else -> "ME7 Injection Architecture"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        isMed17 ->
                            "MED17 (dual fuel) computes injection time per injector bank:\n" +
                                "• te_pfi = rk_w × KRKTE_PFI × portShare + TVUB_PFI(ubat)\n" +
                                "• te_gdi = rk_w × KRKTE_GDI × (1 − portShare)\n" +
                                "• KRKTE_PFI — port injector constant [ms/%] (XDF: 'Conversion relative Fuel mass rk → te for PFI')\n" +
                                "• KRKTE_GDI — direct injector constant [ms/%] (XDF: 'Conversion relative Fuel mass rk → te')"
                        isMed9 ->
                            "MED9 (direct injection only) computes injection time as: te = rk_w × KRKATE + TVUB(ubat)\n" +
                                "• KRKATE — scalar injector constant [ms/%] (Umrechnung relative Kraftstoffmasse rk in effektive Einspritzzeit te)\n" +
                                "• TVUB — dead time vs battery voltage\n" +
                                "• Same formula as ME7 KRKTE, but for high-pressure direct injectors\n" +
                                "• HPFP rail pressure (typically 50–110 bar) affects effective flow rate"
                        else ->
                            "ME7 computes injection time as: te = rk_w × KRKTE + TVUB(ubat)\n" +
                                "• KRKTE — scalar injector constant [ms/%] (me7-raw.txt line 222175)\n" +
                                "• TVUB — dead time vs battery voltage (me7-raw.txt line 183466)\n" +
                                "• KFLF — misnamed 'Lambda map at partial load'; actually an injection correction factor (1.0=neutral)\n" +
                                "• There is no KFTI (2D injection time map) or KFLFW (linearization) in ME7"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Helper Composables ────────────────────────────────────────────────

@Composable
private fun StatColumn(label: String, value: String, large: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Parse "key:value, key:value" formatted string into a Map.
 */
private fun parsePairData(input: String): Map<Double, Double> {
    if (input.isBlank()) return emptyMap()
    return input.split(",")
        .map { it.trim() }
        .filter { it.contains(":") }
        .associate { pair ->
            val parts = pair.split(":")
            parts[0].trim().toDouble() to parts[1].trim().toDouble()
        }
}
