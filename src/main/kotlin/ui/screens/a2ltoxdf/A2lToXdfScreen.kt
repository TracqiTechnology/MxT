package ui.screens.a2ltoxdf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import data.generator.A2lToXdfGenerator
import data.parser.a2l.A2lCalibrationParser
import data.preferences.a2l.A2lFilePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun A2lToXdfScreen() {
    val scope = rememberCoroutineScope()

    val a2lFile        by A2lFilePreferences.file.collectAsState()
    val tableDefs      by A2lCalibrationParser.tableDefinitions.collectAsState()
    val charCount      by A2lCalibrationParser.characteristicCount.collectAsState()
    val a2lParseError  by A2lCalibrationParser.parseError.collectAsState()

    var statusMessage  by remember { mutableStateOf<StatusMessage?>(null) }
    var isGenerating   by remember { mutableStateOf(false) }

    val a2lLoaded = a2lFile.exists() && a2lFile.isFile && tableDefs.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "A2L → XDF Generator",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Generate a TunerPro-compatible XDF calibration file from MED9.1 A2L CHARACTERISTIC blocks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        // ── A2L Status Card ───────────────────────────────────────────────
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "A2L Source",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!a2lFile.exists() || !a2lFile.isFile) {
                    Text(
                        text = "No A2L file loaded. Load one in Configuration → A2L Calibration File.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (a2lParseError != null) {
                    Text(
                        text = "Parse error: $a2lParseError",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = a2lFile.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "$charCount CHARACTERISTICs → ${tableDefs.size} calibration maps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── What gets generated ───────────────────────────────────────────
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Output",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val valueCount  = tableDefs.count { it.xAxis == null && it.yAxis == null }
                val curveCount  = tableDefs.count { it.xAxis != null && it.yAxis == null }
                val mapCount    = tableDefs.count { it.xAxis != null && it.yAxis != null }
                InfoRow("Scalar values (VALUE)",  valueCount)
                InfoRow("1D curves (CURVE)",       curveCount)
                InfoRow("2D maps (MAP)",           mapCount)
                InfoRow("Total XDFTABLE entries",  tableDefs.size)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Encoding: PowerPC Big-Endian (MED9 MPC562) · BASEOFFSET = 0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Generate button ───────────────────────────────────────────────
        Button(
            onClick = {
                scope.launch {
                    isGenerating = true
                    statusMessage = null
                    try {
                        val xdfContent = withContext(Dispatchers.Default) {
                            A2lToXdfGenerator.generate(
                                tableDefinitions = tableDefs,
                                title = "MED9 — ${a2lFile.nameWithoutExtension}"
                            )
                        }
                        // File save dialog on AWT thread
                        val dialog = FileDialog(Frame(), "Save XDF File", FileDialog.SAVE)
                        dialog.file = "${a2lFile.nameWithoutExtension}.xdf"
                        dialog.isVisible = true
                        val dir  = dialog.directory
                        val name = dialog.file
                        if (dir != null && name != null) {
                            val outFile = File(dir, if (name.endsWith(".xdf", ignoreCase = true)) name else "$name.xdf")
                            withContext(Dispatchers.IO) { outFile.writeText(xdfContent, Charsets.UTF_8) }
                            statusMessage = StatusMessage(
                                "Saved ${outFile.name} — ${tableDefs.size} maps written.",
                                StatusLevel.SUCCESS
                            )
                        }
                    } catch (e: Exception) {
                        statusMessage = StatusMessage("Error: ${e.message}", StatusLevel.ERROR)
                    } finally {
                        isGenerating = false
                    }
                }
            },
            enabled = a2lLoaded && !isGenerating,
            modifier = Modifier.align(Alignment.Start)
        ) {
            if (isGenerating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generating…")
            } else {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generate & Save XDF")
            }
        }

        // ── Status feedback ───────────────────────────────────────────────
        statusMessage?.let { msg ->
            val (color, icon) = when (msg.level) {
                StatusLevel.SUCCESS -> MaterialTheme.colorScheme.primary to Icons.Default.CheckCircle
                StatusLevel.ERROR   -> MaterialTheme.colorScheme.error   to Icons.Default.Error
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}

// ── Small helpers ─────────────────────────────────────────────────────────────

@Composable
private fun InfoRow(label: String, value: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(200.dp)
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private data class StatusMessage(val text: String, val level: StatusLevel)
private enum class StatusLevel { SUCCESS, ERROR }
