package ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import data.parser.csv.WinOlsCsvParser
import data.parser.kp.KpHintParser
import data.parser.xdf.TableDefinition

@Composable
fun MapPickerDialog(
    title: String,
    tableDefinitions: List<TableDefinition>,
    initialValue: TableDefinition?,
    onSelected: (TableDefinition) -> Unit,
    onDismiss: () -> Unit,
    // Derive the default filter from the title: "Select KRKTE" → "KRKTE",
    // "Select KFMIOP Map" → "KFMIOP". Callers can override if needed.
    initialFilter: String = title
        .removePrefix("Select ")
        .removeSuffix(" Map")
        .trim(),
    // Optional: returns true for tables that should be sorted to the top and badged as recommended
    recommendedPredicate: ((TableDefinition) -> Boolean)? = null
) {
    // Observe KP hints, KP definitions, and CSV definitions.
    val kpHints by KpHintParser.hints.collectAsState()
    val kpDefinitions by KpHintParser.definitions.collectAsState()
    val csvDefinitions by WinOlsCsvParser.definitions.collectAsState()

    // Find the KP hint (if any) that matches the map we're looking for.
    val kpHint = remember(initialFilter, kpHints) {
        kpHints.firstOrNull { it.name.equals(initialFilter, ignoreCase = true) }
    }

    // Find a matching WinOLS CSV definition for richer hint metadata.
    val csvHint = remember(initialFilter, csvDefinitions) {
        csvDefinitions.firstOrNull { it.id.equals(initialFilter, ignoreCase = true) }
    }

    // Find a matching KP full definition (richer than hint — has dimensions/units/scaling).
    val kpDef = remember(initialFilter, kpDefinitions) {
        kpDefinitions.firstOrNull { it.name.equals(initialFilter, ignoreCase = true) }
    }

    // Use TextFieldValue so we can place the cursor at the end of the pre-populated text,
    // making it easy to append or clear without requiring an extra click.
    var filterField by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialFilter,
                selection = TextRange(initialFilter.length)
            )
        )
    }
    val filterText = filterField.text

    val filteredDefinitions = remember(filterText, tableDefinitions, recommendedPredicate) {
        val base = if (filterText.isBlank()) tableDefinitions
        else {
            val filter = filterText.lowercase()
            tableDefinitions.filter {
                it.tableName.lowercase().contains(filter) ||
                    it.tableDescription.lowercase().contains(filter)
            }
        }
        if (recommendedPredicate != null) {
            base.sortedByDescending { recommendedPredicate(it) }
        } else base
    }

    // When a KP hint/definition or CSV hint with an address is available,
    // prefer the table definition whose z-axis address matches.
    val kpPreferredDefinition = remember(kpHint, kpDef, csvHint, tableDefinitions) {
        // Prefer CSV address (most reliable), then KP definition z-address, then KP hint AR address
        val preferredAddress = when {
            csvHint != null && csvHint.hasAddress   -> csvHint.address
            kpDef != null && kpDef.hasAddress        -> kpDef.effectiveAddress
            kpHint != null && kpHint.hasAddress      -> kpHint.arAddress
            else                                     -> -1
        }
        if (preferredAddress > 0) {
            tableDefinitions.firstOrNull { def -> def.zAxis.address == preferredAddress }
        } else null
    }

    // Start with: KP-address-matched definition > existing selection >
    // first recommended item (if predicate provided) > first filtered result
    val firstRecommended = remember(filteredDefinitions, recommendedPredicate) {
        if (recommendedPredicate != null) filteredDefinitions.firstOrNull { recommendedPredicate(it) }
        else null
    }
    var selectedItem by remember(filteredDefinitions, kpPreferredDefinition, firstRecommended) {
        mutableStateOf(
            kpPreferredDefinition
                ?: initialValue
                ?: firstRecommended
                ?: if (initialFilter.isNotBlank()) filteredDefinitions.firstOrNull() else null
        )
    }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the filter field so the user can type immediately.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title)

                // WinOLS CSV hint — richer than KP because it has dimensions, units, scaling
                if (csvHint != null) {
                    val addrStr = if (csvHint.hasAddress) " @ 0x${csvHint.address.toString(16).uppercase()}" else ""
                    val dimStr = if (csvHint.is2D) " [${csvHint.dimensionString}]"
                                 else " [${maxOf(csvHint.columns, csvHint.rows)}]"
                    val unitStr = if (csvHint.units.isNotBlank()) " — ${csvHint.units}" else ""
                    val scaleStr = if (csvHint.scale != 1.0) " × ${csvHint.scale}" else ""
                    Text(
                        text = "WinOLS CSV: ${csvHint.id}$addrStr$dimStr$unitStr$scaleStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (csvHint.name.isNotBlank() && csvHint.name != "-") {
                        Text(
                            text = csvHint.name.take(80),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // KP badge — shown when a WinOLS KP file is loaded and has a match
                // (shown alongside CSV hint if both are available)
                if (csvHint == null && (kpDef != null || kpHint != null)) {
                    if (kpDef != null) {
                        // Rich badge with dimensions, units, scaling (from full KP parsing)
                        val addrStr = if (kpDef.hasAddress) " @ 0x${kpDef.effectiveAddress.toString(16).uppercase()}" else ""
                        val dimStr = if (kpDef.is2D) " [${kpDef.dimensionString}]"
                                     else " [${maxOf(kpDef.columns, kpDef.rows)}]"
                        val unitStr = if (kpDef.units.isNotBlank()) " — ${kpDef.units}" else ""
                        val scaleStr = if (kpDef.scale != 1.0) " × ${kpDef.scale}" else ""
                        Text(
                            text = "WinOLS KP: ${kpDef.name}$addrStr$dimStr$unitStr$scaleStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (kpHint != null) {
                        // Basic badge (name + address only)
                        val addrStr = if (kpHint.hasAddress) " @ 0x${kpHint.arAddress.toString(16).uppercase()}" else ""
                        Text(
                            text = "WinOLS KP: ${kpHint.name}$addrStr — ${kpHint.description.take(60)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (kpDef != null && kpDef.description.isNotBlank()) {
                        Text(
                            text = kpDef.description.take(80),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.width(500.dp).height(400.dp)) {
                OutlinedTextField(
                    value = filterField,
                    onValueChange = { new ->
                        filterField = new
                        // When the user changes the filter, auto-select the first match
                        // so pressing Set immediately picks the best result.
                        val lc = new.text.lowercase()
                        selectedItem = filteredDefinitions.firstOrNull {
                            it.tableName.lowercase().contains(lc) ||
                                it.tableDescription.lowercase().contains(lc)
                        }
                    },
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .focusRequester(focusRequester)
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    if (filteredDefinitions.isEmpty()) {
                        item {
                            val message = if (tableDefinitions.isEmpty()) {
                                "No table definitions available. Load an XDF file first (File \u2192 Select XDF...)."
                            } else {
                                "No matching definitions."
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    items(filteredDefinitions) { definition ->
                        val isKpMatch = kpPreferredDefinition == definition
                        val isRecommended = recommendedPredicate?.invoke(definition) == true
                        ListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(definition.toString())
                                    if (isRecommended) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = MaterialTheme.shapes.extraSmall
                                        ) {
                                            Text(
                                                text = "★ Recommended",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    if (isKpMatch) {
                                        Spacer(Modifier.width(6.dp))
                                        val badgeLabel = if (csvHint != null && csvHint.hasAddress) "CSV" else "KP"
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = MaterialTheme.shapes.extraSmall
                                        ) {
                                            Text(
                                                text = badgeLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { selectedItem = definition },
                            colors = if (selectedItem == definition) {
                                ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                            } else {
                                ListItemDefaults.colors()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedItem?.let { onSelected(it) }
                    onDismiss()
                },
                enabled = selectedItem != null
            ) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

