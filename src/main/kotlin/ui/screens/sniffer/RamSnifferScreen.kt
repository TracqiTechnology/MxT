package ui.screens.sniffer

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import data.model.EcuPlatform
import data.sniffer.RamSnifferEngine
import data.sniffer.SniffResult
import data.sniffer.SnifferPhase
import data.sniffer.SignatureDatabase
import data.writer.EcuWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun RamSnifferScreen() {
    val scope = rememberCoroutineScope()
    val engine = remember { RamSnifferEngine() }
    val snifferState by engine.state.collectAsState()

    // Local UI state
    var selectedPlatform by remember { mutableStateOf(EcuPlatform.ME7) }
    var referenceFile by remember { mutableStateOf<File?>(null) }
    var referenceBinFile by remember { mutableStateOf<File?>(null) }
    var targetBinFile by remember { mutableStateOf<File?>(null) }
    var database by remember { mutableStateOf<SignatureDatabase?>(null) }
    var results by remember { mutableStateOf<List<SniffResult>>(emptyList()) }
    var statusMessages by remember { mutableStateOf<List<StatusMessage>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var minConfidence by remember { mutableStateOf(0.0) }

    // ECU metadata for export
    var partNumber by remember { mutableStateOf("") }
    var swNumber by remember { mutableStateOf("") }
    var engineId by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // Header
        Text(
            text = "RAM Address Sniffer",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Discover RAM addresses in unknown ECU binaries using byte-pattern signatures from a reference binary with known addresses (DAMOS/A2L/.ecu).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Platform selector
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text("Platform:", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow {
                EcuPlatform.entries.forEachIndexed { index, platform ->
                    SegmentedButton(
                        selected = selectedPlatform == platform,
                        onClick = { selectedPlatform = platform },
                        shape = SegmentedButtonDefaults.itemShape(index, EcuPlatform.entries.size)
                    ) {
                        Text(platform.shortName, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Reference controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Button(onClick = {
                val dialog = FileDialog(Frame(), "Select Reference (DAMOS .dam / .ecu / .A2L)", FileDialog.LOAD)
                dialog.isVisible = true
                if (dialog.directory != null && dialog.file != null) {
                    referenceFile = File(dialog.directory, dialog.file)
                    statusMessages = statusMessages + StatusMessage("Loaded reference: ${dialog.file}", StatusLevel.INFO)
                }
            }) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Load Reference")
            }

            Button(onClick = {
                val dialog = FileDialog(Frame(), "Select Reference BIN/HEX", FileDialog.LOAD)
                dialog.isVisible = true
                if (dialog.directory != null && dialog.file != null) {
                    referenceBinFile = File(dialog.directory, dialog.file)
                    statusMessages = statusMessages + StatusMessage("Loaded reference binary: ${dialog.file}", StatusLevel.INFO)
                }
            }) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Load Ref BIN/HEX")
            }

            Button(
                onClick = {
                    val ref = referenceFile ?: return@Button
                    val bin = referenceBinFile ?: return@Button

                    scope.launch {
                        try {
                            val ext = ref.extension.lowercase()
                            val db = when {
                                ext == "dam" -> engine.buildFromDamos(ref, bin, selectedPlatform)
                                ext == "ecu" -> engine.buildFromEcu(ref, bin, selectedPlatform)
                                else -> {
                                    statusMessages = statusMessages + StatusMessage("Unsupported reference format: .$ext", StatusLevel.ERROR)
                                    return@launch
                                }
                            }
                            database = db
                            statusMessages = statusMessages + StatusMessage(
                                "Built ${db.signatures.size} signature sets from ${db.variables.size} variables",
                                StatusLevel.OK
                            )
                        } catch (e: Exception) {
                            statusMessages = statusMessages + StatusMessage("Build failed: ${e.message}", StatusLevel.ERROR)
                        }
                    }
                },
                enabled = referenceFile != null && referenceBinFile != null && snifferState.phase != SnifferPhase.BUILDING
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Build Signatures")
            }

            if (referenceFile != null) {
                Text(referenceFile!!.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Target controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Button(onClick = {
                val dialog = FileDialog(Frame(), "Select Target BIN", FileDialog.LOAD)
                dialog.isVisible = true
                if (dialog.directory != null && dialog.file != null) {
                    targetBinFile = File(dialog.directory, dialog.file)
                    statusMessages = statusMessages + StatusMessage("Loaded target: ${dialog.file}", StatusLevel.INFO)
                }
            }) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Load Target BIN")
            }

            Button(
                onClick = {
                    val target = targetBinFile ?: return@Button
                    val db = database ?: return@Button

                    scope.launch {
                        try {
                            val sniffResults = engine.sniff(target, db)
                            results = sniffResults
                            statusMessages = statusMessages + StatusMessage(
                                "Discovered ${sniffResults.size}/${db.variables.size} variables",
                                StatusLevel.OK
                            )
                        } catch (e: Exception) {
                            statusMessages = statusMessages + StatusMessage("Scan failed: ${e.message}", StatusLevel.ERROR)
                        }
                    }
                },
                enabled = targetBinFile != null && database != null && snifferState.phase != SnifferPhase.SCANNING
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sniff")
            }

            if (targetBinFile != null) {
                Text(targetBinFile!!.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.weight(1f))

            if (snifferState.phase == SnifferPhase.BUILDING || snifferState.phase == SnifferPhase.SCANNING) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(snifferState.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Status messages
        if (statusMessages.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    for (msg in statusMessages.takeLast(6)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(
                                imageVector = when (msg.level) {
                                    StatusLevel.OK -> Icons.Default.Check
                                    StatusLevel.ERROR -> Icons.Default.Close
                                    StatusLevel.INFO -> Icons.Default.Search
                                },
                                contentDescription = null,
                                tint = when (msg.level) {
                                    StatusLevel.OK -> MaterialTheme.colorScheme.primary
                                    StatusLevel.ERROR -> MaterialTheme.colorScheme.error
                                    StatusLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                msg.text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (msg.level == StatusLevel.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Results section
        if (results.isNotEmpty()) {
            // Summary + filter row
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                val highCount = results.count { it.confidence >= 0.9 }
                val medCount = results.count { it.confidence in 0.6..0.9 }
                val lowCount = results.count { it.confidence < 0.6 }

                Text(
                    "Discovered ${results.size}/${database?.variables?.size ?: 0} variables",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "(${highCount} high, ${medCount} med, ${lowCount} low confidence)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.weight(1f))

                // Confidence filter
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Min confidence:", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    listOf(0.0 to "All", 0.6 to ">60%", 0.9 to ">90%").forEach { (threshold, label) ->
                        FilterChip(
                            selected = minConfidence == threshold,
                            onClick = { minConfidence = threshold },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }
            }

            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Filter variables") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.height(8.dp))

            val filteredResults = remember(results, searchQuery, minConfidence) {
                results.filter { r ->
                    r.confidence >= minConfidence &&
                        (searchQuery.isBlank() || r.variableName.lowercase().contains(searchQuery.lowercase()))
                }
            }

            Text(
                "${filteredResults.size} of ${results.size} results",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Table header
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraSmall) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Name", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(2f))
                    Text("Address", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.2f))
                    Text("Confidence", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("Matches", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.8f))
                    Text("Agree", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.7f))
                    Text("Status", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.8f))
                }
            }

            // Results list
            Box(modifier = Modifier.weight(1f)) {
                val listState = rememberLazyListState()

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(filteredResults, key = { it.variableName }) { result ->
                        val confidenceColor = when {
                            result.confidence >= 0.9 -> Color(0xFF4CAF50) // green
                            result.confidence >= 0.6 -> Color(0xFFFF9800) // amber
                            else -> Color(0xFFF44336) // red
                        }
                        val statusLabel = when {
                            result.confidence >= 0.9 -> "HIGH"
                            result.confidence >= 0.6 -> "MED"
                            else -> "LOW"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                result.variableName,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(2f)
                            )
                            Text(
                                "0x${String.format("%06X", result.discoveredAddress)}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1.2f)
                            )
                            Text(
                                "${String.format("%.1f", result.confidence * 100)}%",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = confidenceColor,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${result.matchCount}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(0.8f)
                            )
                            Text(
                                "${result.agreementCount}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(0.7f)
                            )
                            Surface(
                                color = confidenceColor.copy(alpha = 0.15f),
                                shape = MaterialTheme.shapes.extraSmall,
                                modifier = Modifier.weight(0.8f)
                            ) {
                                Text(
                                    statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = confidenceColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }

            Spacer(Modifier.height(12.dp))

            // Export section
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = partNumber,
                    onValueChange = { partNumber = it },
                    label = { Text("Part Number") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                OutlinedTextField(
                    value = swNumber,
                    onValueChange = { swNumber = it },
                    label = { Text("SW Number") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                OutlinedTextField(
                    value = engineId,
                    onValueChange = { engineId = it },
                    label = { Text("Engine ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )

                Button(
                    onClick = {
                        val db = database ?: return@Button
                        val dialog = FileDialog(Frame(), "Save .ecu File", FileDialog.SAVE)
                        dialog.file = if (swNumber.isNotBlank()) "SNIFF_${swNumber}.ecu" else "sniffed.ecu"
                        dialog.isVisible = true

                        if (dialog.directory != null && dialog.file != null) {
                            val ecuFile = File(dialog.directory, dialog.file)
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val ecuEntries = engine.exportToEcuEntries(results.filter { it.confidence >= minConfidence }, db)
                                        EcuWriter.writeEcuFile(
                                            ecuEntries, ecuFile,
                                            partNumber = partNumber,
                                            swNumber = swNumber,
                                            engineId = engineId
                                        )
                                        val baseName = ecuFile.nameWithoutExtension
                                        val cfgBasic = File(ecuFile.parentFile, "${baseName}_basic.cfg")
                                        EcuWriter.writeCfgFile(cfgBasic, ecuFile.name, EcuWriter.BASIC_VARIABLES, description = "Sniffed basic log config")

                                        withContext(Dispatchers.Main) {
                                            statusMessages = statusMessages + listOf(
                                                StatusMessage("Wrote ${ecuEntries.size} entries to ${ecuFile.name}", StatusLevel.OK),
                                                StatusMessage("Wrote ${cfgBasic.name}", StatusLevel.OK)
                                            )
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            statusMessages = statusMessages + StatusMessage("Export failed: ${e.message}", StatusLevel.ERROR)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    enabled = results.isNotEmpty()
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save .ecu + .cfg")
                }
            }
        }
    }
}

private enum class StatusLevel { OK, ERROR, INFO }
private data class StatusMessage(val text: String, val level: StatusLevel)
