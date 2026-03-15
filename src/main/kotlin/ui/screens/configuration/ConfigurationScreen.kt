package ui.screens.configuration

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import data.contract.Me7LogFileContract
import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.XdfParser
import data.preferences.MapPreference
import data.preferences.MapPreferenceManager
import data.preferences.bin.BinFilePreferences
import data.preferences.filechooser.BinFileChooserPreferences
import data.preferences.filechooser.XdfFileChooserPreferences
import data.preferences.xdf.XdfFilePreferences
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfldiopu.KfldioPuPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.kfldrq0.Kfldrq0Preferences
import data.preferences.kfldrq1.Kfldrq1Preferences
import data.preferences.kfldrq2.Kfldrq2Preferences
import data.preferences.kffwtbr.KffwtbrPreferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import data.preferences.kfpbrk.KfpbrkPreferences
import data.preferences.kfpbrknw.KfpbrknwPreferences
import data.preferences.kfprg.KfprgPreferences
import data.preferences.kfvpdksd.KfvpdksdPreferences
import data.preferences.kfwdkmsn.KfwdkmsnPreferences
import data.preferences.kfzw.KfzwPreferences
import data.preferences.kfzwop.KfzwopPreferences
import data.preferences.krkte.KrktePreferences
import data.preferences.krkte.KrktePfiPreferences
import data.preferences.krkte.KrkteGdiPreferences
import data.preferences.logheaderdefinition.LogHeaderPreference
import data.preferences.mlhfm.MlhfmPreferences
import data.preferences.platform.EcuPlatformPreference
import data.preferences.rkw.RkwPreferences
import data.preferences.tvub.TvubPfiPreferences
import data.preferences.wdkugdn.WdkugdnPreferences
import data.profile.ProfileManager
import ui.components.MapPickerDialog
import ui.components.InfoTooltip
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

private data class MapDefinitionEntry(
    val title: String,
    val preference: MapPreference,
    val platforms: Set<EcuPlatform> = EcuPlatform.entries.toSet()
)

private val allMapDefinitions = listOf(
    // ME7 + MED9 — single injector constant (MED9 uses KRKATE, resolved via profile)
    MapDefinitionEntry("KRKTE", KrktePreferences, platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    MapDefinitionEntry("MLHFM", MlhfmPreferences, platforms = setOf(EcuPlatform.ME7)),
    // MED17-only (dual injection)
    MapDefinitionEntry("KRKTE (Port)", KrktePfiPreferences, platforms = setOf(EcuPlatform.MED17)),
    MapDefinitionEntry("KRKTE (Direct)", KrkteGdiPreferences, platforms = setOf(EcuPlatform.MED17)),
    MapDefinitionEntry("TVUB (Port)", TvubPfiPreferences, platforms = setOf(EcuPlatform.MED17)),
    // Shared — torque/load/ignition/boost
    MapDefinitionEntry("KFMIOP", KfmiopPreferences),
    MapDefinitionEntry("KFMIRL", KfmirlPreferences),
    MapDefinitionEntry("KFZWOP", KfzwopPreferences),
    MapDefinitionEntry("KFZW", KfzwPreferences),
    // ME7 + MED9 — boost transition & throttle body (not in MED17 Funktionsrahmen)
    MapDefinitionEntry("KFVPDKSD", KfvpdksdPreferences, platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    MapDefinitionEntry("WDKUGDN", WdkugdnPreferences, platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    MapDefinitionEntry("KFWDKMSN", KfwdkmsnPreferences, platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    // Shared — boost PID linearization
    MapDefinitionEntry("KFLDRL", KfldrlPreferences),
    MapDefinitionEntry("KFLDIMX", KfldimxPreferences),
    // ME7 + MED9 — VE model maps (MED17 uses adaptive fupsrl_w / pbrint_w)
    MapDefinitionEntry("KFPBRK", KfpbrkPreferences, platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    MapDefinitionEntry("KFPBRKNW", KfpbrknwPreferences, platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    MapDefinitionEntry("KFPRG", KfprgPreferences, platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    // v4: Environmental correction maps
    MapDefinitionEntry("KFLDIOPU", KfldioPuPreferences),
    MapDefinitionEntry("KFFWTBR", KffwtbrPreferences, platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    // v4: PID gain maps
    MapDefinitionEntry("KFLDRQ0", Kfldrq0Preferences),
    MapDefinitionEntry("KFLDRQ1", Kfldrq1Preferences),
    MapDefinitionEntry("KFLDRQ2", Kfldrq2Preferences),
    // MED17-only — fuel trim correction map
    MapDefinitionEntry("rk_w (Fuel Trim)", RkwPreferences, platforms = setOf(EcuPlatform.MED17)),
)

/** Returns map definitions filtered for the active platform. */
private fun mapDefinitionsForPlatform(platform: EcuPlatform): List<MapDefinitionEntry> =
    allMapDefinitions.filter { platform in it.platforms }

private val defaultHeaderValues = mapOf(
    Me7LogFileContract.Header.START_TIME_HEADER to Me7LogFileContract.START_TIME_LABEL,
    Me7LogFileContract.Header.TIME_STAMP_COLUMN_HEADER to Me7LogFileContract.TIME_COLUMN_LABEL,
    Me7LogFileContract.Header.RPM_COLUMN_HEADER to Me7LogFileContract.RPM_COLUMN_LABEL,
    Me7LogFileContract.Header.STFT_COLUMN_HEADER to Me7LogFileContract.STFT_COLUMN_LABEL,
    Me7LogFileContract.Header.LTFT_COLUMN_HEADER to Me7LogFileContract.LTFT_COLUMN_LABEL,
    Me7LogFileContract.Header.MAF_VOLTAGE_HEADER to Me7LogFileContract.MAF_VOLTAGE_LABEL,
    Me7LogFileContract.Header.MAF_GRAMS_PER_SECOND_HEADER to Me7LogFileContract.MAF_GRAMS_PER_SECOND_LABEL,
    Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER to Me7LogFileContract.THROTTLE_PLATE_ANGLE_LABEL,
    Me7LogFileContract.Header.LAMBDA_CONTROL_ACTIVE_HEADER to Me7LogFileContract.LAMBDA_CONTROL_ACTIVE_LABEL,
    Me7LogFileContract.Header.REQUESTED_LAMBDA_HEADER to Me7LogFileContract.REQUESTED_LAMBDA_LABEL,
    Me7LogFileContract.Header.FUEL_INJECTOR_ON_TIME_HEADER to Me7LogFileContract.FUEL_INJECTOR_ON_TIME_LABEL,
    Me7LogFileContract.Header.ENGINE_LOAD_HEADER to Me7LogFileContract.ENGINE_LOAD_LABEL,
    Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER to Me7LogFileContract.WASTEGATE_DUTY_CYCLE_LABEL,
    Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER to Me7LogFileContract.BAROMETRIC_PRESSURE_LABEL,
    Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER to Me7LogFileContract.ABSOLUTE_BOOST_PRESSURE_ACTUAL_LABEL,
    Me7LogFileContract.Header.SELECTED_GEAR_HEADER to Me7LogFileContract.SELECTED_GEAR_LABEL,
    Me7LogFileContract.Header.WIDE_BAND_O2_HEADER to Me7LogFileContract.WIDE_BAND_O2_LABEL,
    Me7LogFileContract.Header.REQUESTED_PRESSURE_HEADER to Me7LogFileContract.REQUESTED_PRESSURE_LABEL,
    Me7LogFileContract.Header.REQUESTED_LOAD_HEADER to Me7LogFileContract.REQUESTED_LOAD_LABEL,
    Me7LogFileContract.Header.ACTUAL_LOAD_HEADER to Me7LogFileContract.ACTUAL_LOAD_LABEL,
)

@Composable
fun ConfigurationScreen(
    trailingContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    val scrollState = rememberScrollState()

    val xdfFile by XdfFilePreferences.file.collectAsState()
    val binFile by BinFilePreferences.file.collectAsState()

    val xdfLoaded = xdfFile.exists() && xdfFile.isFile
    val binLoaded = binFile.exists() && binFile.isFile
    val filesLoaded = xdfLoaded && binLoaded

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        FileLoadSection(xdfFile, xdfLoaded, binFile, binLoaded)

        // Show XDF parse error if present
        val xdfParseError by XdfParser.parseError.collectAsState()
        xdfParseError?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "⚠ XDF parse error: $error",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!filesLoaded) {
            FilesNotLoadedPlaceholder()
        } else {
            // Auto-apply the matching default profile when both files are loaded
            // and no map definitions have been configured yet.
            AutoApplyProfile()

            QuickSetupSection()

            Spacer(modifier = Modifier.height(16.dp))

            ManualConfigDivider()

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MapDefinitionsSection(modifier = Modifier.weight(1f))
                LogHeadersSection(modifier = Modifier.weight(1f))
            }

            if (trailingContent != null) {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                trailingContent()
            }
        }
    }
}

@Composable
private fun FileLoadSection(xdfFile: File, xdfLoaded: Boolean, binFile: File, binLoaded: Boolean) {
    Text(
        text = "Load Files",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 12.dp)
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        FileCard(
            label = "XDF Definition File",
            fileName = if (xdfLoaded) xdfFile.name else "Not loaded",
            isLoaded = xdfLoaded,
            onOpen = {
                val dialog = FileDialog(null as Frame?, "Select XDF File", FileDialog.LOAD)
                dialog.setFilenameFilter { _, name -> name.endsWith(".xdf", ignoreCase = true) }
                val lastDir = XdfFileChooserPreferences.lastDirectory
                if (lastDir.isNotEmpty()) dialog.directory = lastDir
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val selected = File(dir, file)
                    MapPreferenceManager.clear()
                    XdfFilePreferences.setFile(selected)
                    XdfFileChooserPreferences.lastDirectory = selected.parent
                }
            },
            modifier = Modifier.weight(1f)
        )

        FileCard(
            label = "BIN ECU File",
            fileName = if (binLoaded) binFile.name else "Not loaded",
            isLoaded = binLoaded,
            onOpen = {
                val dialog = FileDialog(null as Frame?, "Open Bin File", FileDialog.LOAD)
                dialog.setFilenameFilter { _, name -> name.endsWith(".bin", ignoreCase = true) }
                val lastDir = BinFileChooserPreferences.lastDirectory
                if (lastDir.isNotEmpty()) dialog.directory = lastDir
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val selected = File(dir, file)
                    BinFilePreferences.setFile(selected)
                    BinFileChooserPreferences.lastDirectory = selected.parent
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FileCard(
    label: String,
    fileName: String,
    isLoaded: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isLoaded) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = if (isLoaded) "Loaded" else "Not loaded",
                tint = if (isLoaded) MaterialTheme.colorScheme.tertiary
                       else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(onClick = onOpen) {
                Text(if (isLoaded) "Change\u2026" else "Open\u2026")
            }
        }
    }
}

@Composable
private fun FilesNotLoadedPlaceholder() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(32.dp).fillMaxWidth()
        ) {
            Text(
                text = "Load an XDF and BIN file above to begin configuration.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Automatically applies the matching default profile when both XDF and BIN
 * are loaded (mapList is non-empty) and no map definitions are configured yet.
 * Fires at most once per composition lifecycle — switching files or platform
 * resets the guard via the key parameters.
 */
@Composable
private fun AutoApplyProfile() {
    val mapList by BinParser.mapList.collectAsState()
    val allDefaultProfiles by ProfileManager.defaultProfiles.collectAsState()
    val platform = EcuPlatformPreference.platform

    val matchingProfiles = remember(allDefaultProfiles, platform) {
        allDefaultProfiles.filter { it.ecuPlatform == platform.name }
    }
    val mapDefinitions = remember(platform) { mapDefinitionsForPlatform(platform) }

    LaunchedEffect(mapList, matchingProfiles) {
        if (mapList.isEmpty()) return@LaunchedEffect
        if (matchingProfiles.isEmpty()) return@LaunchedEffect

        // Only auto-apply when no maps are currently configured
        val configuredCount = mapDefinitions.count { it.preference.getSelectedMap() != null }
        if (configuredCount > 0) return@LaunchedEffect

        ProfileManager.applyProfile(matchingProfiles.first())
    }
}

@Composable
private fun QuickSetupSection() {
    val allDefaultProfiles by ProfileManager.defaultProfiles.collectAsState()
    val allUserProfiles by ProfileManager.userProfiles.collectAsState()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val platform = EcuPlatformPreference.platform
    val platformName = platform.name // "ME7" or "MED17"
    val defaultProfiles = remember(allDefaultProfiles, platform) {
        allDefaultProfiles.filter { it.ecuPlatform == platformName }
    }
    val userProfiles = remember(allUserProfiles, platform) {
        allUserProfiles.filter { it.ecuPlatform == platformName }
    }

    Column {
        Text(
            text = "Quick Setup",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Apply a preset profile to configure all map definitions and log headers at once.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (defaultProfiles.isNotEmpty()) {
                    for (profile in defaultProfiles) {
                        ProfileRow(profile = profile, onApply = {
                            ProfileManager.applyProfile(profile)
                            statusMessage = "Applied profile: ${profile.name}"
                        })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                if (userProfiles.isNotEmpty()) {
                    for (profile in userProfiles) {
                        ProfileRow(profile = profile, onApply = {
                            ProfileManager.applyProfile(profile)
                            statusMessage = "Applied profile: ${profile.name}"
                        })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = {
                        val dialog = FileDialog(null as Frame?, "Load Profile", FileDialog.LOAD)
                        dialog.setFilenameFilter { _, name -> name.endsWith(".me7profile.json", ignoreCase = true) }
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val file = dialog.file
                        if (dir != null && file != null) {
                            runCatching {
                                val profile = ProfileManager.loadFromFile(File(dir, file))
                                ProfileManager.applyProfile(profile)
                                ProfileManager.addUserProfile(profile)
                                statusMessage = "Loaded and applied profile: ${profile.name}"
                            }.onFailure {
                                statusMessage = "Failed to load profile: ${it.message}"
                            }
                        }
                    }) {
                        Text("Load Profile\u2026")
                    }

                    OutlinedButton(onClick = {
                        SwingUtilities.invokeLater {
                            val name = JOptionPane.showInputDialog(
                                null,
                                "Profile name:",
                                "Save Profile",
                                JOptionPane.PLAIN_MESSAGE
                            )
                            if (name != null && name.isNotBlank()) {
                                val dialog = FileDialog(null as Frame?, "Save Profile", FileDialog.SAVE)
                                dialog.file = "${name.replace(Regex("[^a-zA-Z0-9_ -]"), "")}.me7profile.json"
                                dialog.isVisible = true
                                val dir = dialog.directory
                                val fileName = dialog.file
                                if (dir != null && fileName != null) {
                                    runCatching {
                                        val profile = ProfileManager.exportCurrentProfile(name)
                                        val targetFile = File(dir, fileName)
                                        ProfileManager.saveToFile(profile, targetFile)
                                        statusMessage = "Saved profile: $name"
                                    }.onFailure {
                                        statusMessage = "Failed to save profile: ${it.message}"
                                    }
                                }
                            }
                        }
                    }) {
                        Text("Save Current as Profile\u2026")
                    }
                }

                if (statusMessage != null) {
                    Text(
                        text = statusMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualConfigDivider() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = "or configure manually",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ProfileRow(profile: data.profile.ConfigurationProfile, onApply: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name.ifEmpty { "Unnamed Profile" },
                style = MaterialTheme.typography.bodyMedium
            )
            if (profile.description.isNotBlank()) {
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (profile.ecuPartNumbers.isNotEmpty()) {
                Text(
                    text = profile.ecuPartNumbers.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(
            onClick = onApply,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text("Apply")
        }
    }
}

@Composable
private fun MapDefinitionsSection(modifier: Modifier = Modifier) {
    val tableDefinitions by XdfParser.tableDefinitions.collectAsState()
    val mapList by BinParser.mapList.collectAsState()
    val platform = EcuPlatformPreference.platform
    val mapDefinitions = remember(platform) { mapDefinitionsForPlatform(platform) }

    var pickerDialogEntry by remember { mutableStateOf<MapDefinitionEntry?>(null) }

    // Force recomposition when maps change by collecting mapChanged for each preference
    var version by remember { mutableStateOf(0) }

    for (entry in mapDefinitions) {
        LaunchedEffect(entry.preference) {
            entry.preference.mapChanged.collect {
                version++
            }
        }
    }

    val configuredCount = remember(version, tableDefinitions, mapList) {
        mapDefinitions.count { it.preference.getSelectedMap() != null }
    }

    Column(modifier = modifier) {
        Text(
            text = "Map Definitions ($configuredCount of ${mapDefinitions.size} configured)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Read version to trigger recomposition
                @Suppress("UNUSED_EXPRESSION")
                version

                for (entry in mapDefinitions) {
                    val selectedMap = remember(version, tableDefinitions, mapList) {
                        entry.preference.getSelectedMap()
                    }
                    val isConfigured = selectedMap != null
                    val selectedName = selectedMap?.first?.tableName ?: "Undefined"
                    val selectedUnit = selectedMap?.first?.zAxis?.unit ?: "-"

                    MapDefinitionRow(
                        title = entry.title,
                        isConfigured = isConfigured,
                        selectedName = selectedName,
                        selectedUnit = selectedUnit,
                        onSelect = { pickerDialogEntry = entry }
                    )
                }
            }
        }
    }

    if (pickerDialogEntry != null) {
        val entry = pickerDialogEntry!!
        val currentSelection = remember { entry.preference.getSelectedMap()?.first }

        MapPickerDialog(
            title = "Select ${entry.title}",
            tableDefinitions = tableDefinitions,
            initialValue = currentSelection,
            onSelected = { tableDefinition ->
                entry.preference.setSelectedMap(tableDefinition)
            },
            onDismiss = { pickerDialogEntry = null }
        )
    }
}

@Composable
private fun MapDefinitionRow(
    title: String,
    isConfigured: Boolean,
    selectedName: String,
    selectedUnit: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isConfigured) Icons.Default.Check else Icons.Default.Close,
            contentDescription = if (isConfigured) "Configured" else "Not configured",
            tint = if (isConfigured) MaterialTheme.colorScheme.tertiary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "$title:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = selectedName,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = selectedUnit,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(60.dp)
        )
        Button(
            onClick = onSelect,
            modifier = Modifier.padding(start = 8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text("Select Definition")
        }
    }
}

@Composable
private fun LogHeadersSection(modifier: Modifier = Modifier) {
    val isMed17 = EcuPlatformPreference.platform == EcuPlatform.MED17
    var expanded by remember { mutableStateOf(false) }
    var headerVersion by remember { mutableStateOf(0) }

    val customizedCount = remember(headerVersion) {
        Me7LogFileContract.Header.entries.count { header ->
            LogHeaderPreference.getHeader(header) != defaultHeaderValues[header]
        }
    }

    val subtitle = if (customizedCount == 0) "(using defaults)" else "($customizedCount customized)"

    Column(modifier = modifier) {
        Text(
            text = "Log Headers",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (isMed17) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "ScorpionEFI / DS1 logs are auto-detected — signal names are matched " +
                        "automatically from CSV headers. No manual log header configuration is needed.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                                         else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(20.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = expanded) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            for (header in Me7LogFileContract.Header.entries) {
                                LogHeaderRow(header) { headerVersion++ }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogHeaderRow(header: Me7LogFileContract.Header, onChanged: () -> Unit) {
    var headerValue by remember {
        mutableStateOf(LogHeaderPreference.getHeader(header))
    }

    val headerTooltip = when (header) {
        Me7LogFileContract.Header.START_TIME_HEADER -> "The CSV column header for the log start timestamp. Must match the exact string in your ME7Logger CSV file."
        Me7LogFileContract.Header.TIME_STAMP_COLUMN_HEADER -> "The CSV column header for the per-row timestamp (elapsed time). Used to compute derivatives and filter transients."
        Me7LogFileContract.Header.RPM_COLUMN_HEADER -> "The CSV column header for engine RPM (nmot_w or similar). Required for all RPM-binned calculations."
        Me7LogFileContract.Header.STFT_COLUMN_HEADER -> "Short-Term Fuel Trim column header. Logged as fr_w in ME7Logger. Used in closed-loop MLHFM correction."
        Me7LogFileContract.Header.LTFT_COLUMN_HEADER -> "Long-Term Fuel Trim column header (frgob_w). Represents the learned O2 adaptation. Used in closed-loop correction."
        Me7LogFileContract.Header.MAF_VOLTAGE_HEADER -> "MAF sensor voltage column header (uhfm_w). Used for MLHFM open/closed-loop correction binning."
        Me7LogFileContract.Header.MAF_GRAMS_PER_SECOND_HEADER -> "MAF mass airflow in g/s (mshfm_w). Used in alpha-n diagnostics to compare against speed-density estimate."
        Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER -> "Throttle position / pedal angle column header (wdkba or similar). Used to filter WOT vs part-throttle samples."
        Me7LogFileContract.Header.LAMBDA_CONTROL_ACTIVE_HEADER -> "Lambda (O2) control active flag column header (b_lr). When 0 the ECU is in open-loop; when 1 it is in closed-loop fuel control."
        Me7LogFileContract.Header.REQUESTED_LAMBDA_HEADER -> "Requested lambda target column header (rl_w or lamsbg_w). Used to identify open-loop enrichment conditions."
        Me7LogFileContract.Header.FUEL_INJECTOR_ON_TIME_HEADER -> "Injector pulse width column header (ti_b1). Used in injector duty-cycle calculations."
        Me7LogFileContract.Header.ENGINE_LOAD_HEADER -> "Engine load column header (rl_w). The primary load signal — compared against LDRXN target in the Optimizer."
        Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER -> "Wastegate duty cycle column header (ldtvm or pvdks_w). Used to detect turbo limits and boost control diagnostics."
        Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER -> "Barometric (ambient) pressure column header (pus_w). Used as the atmospheric reference in boost and VE calculations."
        Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER -> "Actual absolute manifold pressure column header (pvdks_w). The measured boost used in KFURL/KFPBRK diagnostics."
        Me7LogFileContract.Header.SELECTED_GEAR_HEADER -> "Selected gear column header. Used to filter log samples to specific gears for more consistent pull analysis."
        Me7LogFileContract.Header.WIDE_BAND_O2_HEADER -> "Wideband O2 / AFR sensor column header. The actual measured air-fuel ratio — required for open-loop MLHFM correction."
        Me7LogFileContract.Header.REQUESTED_PRESSURE_HEADER -> "Requested boost pressure column header (pssol_w). The ECU's boost target — compared against actual pressure in the Optimizer."
        Me7LogFileContract.Header.REQUESTED_LOAD_HEADER -> "Requested engine load column header (ldrxn_w). The ECU's load target — the primary setpoint for the Optimizer calibration loop."
        Me7LogFileContract.Header.ACTUAL_LOAD_HEADER -> "Actual measured engine load column header (rl_w). Compared against LDRXN target to assess calibration accuracy."
        Me7LogFileContract.Header.THROTTLE_MODEL_AIRFLOW_HEADER -> "Throttle model (alpha-n / speed-density) estimated airflow column header (msdk_w). Compared against mshfm_w to assess alpha-n calibration accuracy."
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(180.dp)
        ) {
            Text(
                text = "${header.title}:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(4.dp))
            InfoTooltip(title = header.title, text = headerTooltip)
        }
        OutlinedTextField(
            value = headerValue,
            onValueChange = { newValue ->
                headerValue = newValue
                LogHeaderPreference.setHeader(header, newValue)
                onChanged()
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).height(48.dp)
        )
    }
}
