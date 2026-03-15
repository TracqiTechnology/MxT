package ui.screens.a2lecu

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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import data.parser.a2l.A2lParser
import data.parser.a2l.EcuEntry
import data.writer.EcuWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun A2lToEcuScreen() {
    val scope = rememberCoroutineScope()

    // State
    var a2lFile by remember { mutableStateOf<File?>(null) }
    var ecuEntries by remember { mutableStateOf<List<EcuEntry>>(emptyList()) }
    var statusMessages by remember { mutableStateOf<List<StatusMessage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // ECU metadata fields
    var partNumber by remember { mutableStateOf("1K0907115S") }
    var swNumber by remember { mutableStateOf("0261S02469") }
    var engineId by remember { mutableStateOf("2.0L R4 TFSI") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // Header
        Text(
            text = "A2L → ECU Generator",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Generate ME7Logger-compatible .ecu and .cfg files from DAMOS A2L definitions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Controls row
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Load A2L button
            Button(
                onClick = {
                    val dialog = FileDialog(Frame(), "Select A2L File", FileDialog.LOAD)
                    dialog.setFilenameFilter { _, name -> name.endsWith(".A2L", ignoreCase = true) || name.endsWith(".a2l") }
                    dialog.isVisible = true

                    if (dialog.directory != null && dialog.file != null) {
                        val selectedFile = File(dialog.directory, dialog.file)
                        a2lFile = selectedFile
                        isLoading = true
                        statusMessages = listOf(StatusMessage("Parsing ${selectedFile.name}...", StatusLevel.INFO))

                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val parseResult = A2lParser.parse(selectedFile)
                                    val entries = A2lParser.buildEcuEntries(
                                        parseResult.measurements,
                                        parseResult.compuMethods
                                    )
                                    ParseSuccess(parseResult.compuMethods.size, parseResult.measurements.size, entries)
                                } catch (e: Exception) {
                                    ParseFailure(e.message ?: "Unknown error")
                                }
                            }

                            when (result) {
                                is ParseSuccess -> {
                                    ecuEntries = result.entries
                                    statusMessages = listOf(
                                        StatusMessage("OK: Parsed ${result.methodCount} COMPU_METHODs, ${result.measurementCount} MEASUREMENTs", StatusLevel.OK),
                                        StatusMessage("OK: Generated ${result.entries.size} .ecu entries (filtered to 1-2 byte variables)", StatusLevel.OK),
                                        StatusMessage("OK: ${result.entries.count { it.alias.isNotEmpty() }} entries have aliases", StatusLevel.OK)
                                    )
                                }
                                is ParseFailure -> {
                                    ecuEntries = emptyList()
                                    statusMessages = listOf(StatusMessage("ERROR: ${result.error}", StatusLevel.ERROR))
                                }
                            }
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Load A2L")
            }

            // Save .ecu + .cfg button
            Button(
                onClick = {
                    val dialog = FileDialog(Frame(), "Save .ecu File", FileDialog.SAVE)
                    dialog.file = "MED9_${swNumber}.ecu"
                    dialog.isVisible = true

                    if (dialog.directory != null && dialog.file != null) {
                        val ecuFile = File(dialog.directory, dialog.file)
                        val baseName = ecuFile.nameWithoutExtension

                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    EcuWriter.writeEcuFile(
                                        ecuEntries, ecuFile,
                                        partNumber = partNumber,
                                        swNumber = swNumber,
                                        engineId = engineId
                                    )

                                    val ecuFilename = ecuFile.name
                                    val cfgBasic = File(ecuFile.parentFile, "${baseName}_basic.cfg")
                                    EcuWriter.writeCfgFile(
                                        cfgBasic, ecuFilename, EcuWriter.BASIC_VARIABLES,
                                        description = "Basic tuning log config"
                                    )

                                    val cfgLdrpid = File(ecuFile.parentFile, "${baseName}_ldrpid.cfg")
                                    EcuWriter.writeCfgFile(
                                        cfgLdrpid, ecuFilename, EcuWriter.LDRPID_VARIABLES,
                                        description = "LDRPID/boost tuning log config"
                                    )

                                    withContext(Dispatchers.Main) {
                                        statusMessages = statusMessages + listOf(
                                            StatusMessage("OK: Wrote ${ecuEntries.size} entries to ${ecuFile.name}", StatusLevel.OK),
                                            StatusMessage("OK: Wrote ${cfgBasic.name} (${EcuWriter.BASIC_VARIABLES.size} vars)", StatusLevel.OK),
                                            StatusMessage("OK: Wrote ${cfgLdrpid.name} (${EcuWriter.LDRPID_VARIABLES.size} vars)", StatusLevel.OK)
                                        )
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        statusMessages = statusMessages +
                                            StatusMessage("ERROR: ${e.message}", StatusLevel.ERROR)
                                    }
                                }
                            }
                        }
                    }
                },
                enabled = ecuEntries.isNotEmpty() && !isLoading
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save .ecu + .cfg")
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            Spacer(modifier = Modifier.weight(1f))

            // File info
            if (a2lFile != null) {
                Text(
                    text = a2lFile!!.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ECU Metadata fields
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Status messages
        if (statusMessages.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    for (msg in statusMessages) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = when (msg.level) {
                                    StatusLevel.ERROR -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Signal browser
        if (ecuEntries.isNotEmpty()) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Filter signals") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )

            Spacer(modifier = Modifier.height(8.dp))

            val filteredEntries = remember(ecuEntries, searchQuery) {
                if (searchQuery.isBlank()) ecuEntries
                else {
                    val q = searchQuery.lowercase()
                    ecuEntries.filter {
                        it.name.lowercase().contains(q) ||
                            it.alias.lowercase().contains(q) ||
                            it.comment.lowercase().contains(q) ||
                            it.unit.lowercase().contains(q)
                    }
                }
            }

            Text(
                text = "${filteredEntries.size} of ${ecuEntries.size} entries",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Table header
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Name", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.5f))
                    Text("Alias", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(2f))
                    Text("Address", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("Size", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.5f))
                    Text("Unit", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.8f))
                    Text("Factor", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("Description", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(2.5f))
                }
            }

            // Signal list
            Box(modifier = Modifier.weight(1f)) {
                val listState = rememberLazyListState()

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(filteredEntries, key = { "${it.name}_${it.address}" }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1.5f)
                            )
                            Text(
                                entry.alias,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.alias.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(2f)
                            )
                            Text(
                                "0x${String.format("%06X", entry.address)}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${entry.size}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(0.5f)
                            )
                            Text(
                                entry.unit,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(0.8f)
                            )
                            Text(
                                if (entry.inverse == 1) "1/${formatCompact(entry.factor)}"
                                else formatCompact(entry.factor),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                entry.comment,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(2.5f),
                                maxLines = 1
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }
}

private fun formatCompact(value: Double): String {
    if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e6) {
        return value.toLong().toString()
    }
    return value.toBigDecimal().stripTrailingZeros().toPlainString()
}

private enum class StatusLevel { OK, ERROR, INFO }

private data class StatusMessage(val text: String, val level: StatusLevel)

private sealed class ParseResult
private data class ParseSuccess(val methodCount: Int, val measurementCount: Int, val entries: List<EcuEntry>) : ParseResult()
private data class ParseFailure(val error: String) : ParseResult()
