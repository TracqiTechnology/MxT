package ui.screens.axisrescaler

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import domain.math.AxisRescaler
import domain.math.map.Map3d
import ui.components.MapAxis
import ui.components.MapTable
import java.text.DecimalFormat

private val formatter = DecimalFormat("#.##")

@Composable
fun AxisRescalerScreen() {
    val clipboardManager = LocalClipboardManager.current

    var inputMap by remember { mutableStateOf<Map3d?>(null) }
    var editedXAxis by remember { mutableStateOf<Array<Double>>(emptyArray()) }
    var editedYAxis by remember { mutableStateOf<Array<Double>>(emptyArray()) }
    var rescaleResult by remember { mutableStateOf<AxisRescaler.RescaleResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun resetAxes(map: Map3d) {
        editedXAxis = map.xAxis.copyOf()
        editedYAxis = map.yAxis.copyOf()
        rescaleResult = null
        errorMessage = null
        statusMessage = null
    }

    fun handlePaste() {
        val text = clipboardManager.getText()?.text
        if (text.isNullOrBlank()) {
            errorMessage = "Clipboard is empty"
            return
        }
        val parsed = MapClipboardParser.parseTsv(text)
        if (parsed == null) {
            errorMessage = "Could not parse clipboard content as a map table"
            return
        }
        inputMap = parsed
        resetAxes(parsed)
        statusMessage = "Loaded ${parsed.yAxis.size}×${parsed.xAxis.size} map from clipboard"
    }

    fun handleRescale() {
        val map = inputMap ?: return
        errorMessage = null
        try {
            val result = AxisRescaler.rescaleMap(
                original = map,
                newXAxis = editedXAxis,
                newYAxis = editedYAxis
            )
            rescaleResult = result
            statusMessage = "Rescaled: ${result.exactMatchCount} exact, " +
                "${result.extrapolatedCount} extrapolated, " +
                "${result.totalCells} total cells"
        } catch (e: IllegalArgumentException) {
            errorMessage = e.message
            rescaleResult = null
        }
    }

    fun handleCopyOutput() {
        val result = rescaleResult ?: return
        val map = result.rescaledMap
        val sb = StringBuilder()

        // Header row: empty cell + X-axis values
        sb.append("\t")
        sb.append(map.xAxis.joinToString("\t") { formatter.format(it) })
        sb.appendLine()

        // Data rows: Y-axis value + Z values
        for (r in map.yAxis.indices) {
            sb.append(formatter.format(map.yAxis[r]))
            for (c in map.xAxis.indices) {
                sb.append("\t")
                sb.append(formatter.format(map.zAxis[r][c]))
            }
            if (r < map.yAxis.size - 1) sb.appendLine()
        }

        clipboardManager.setText(AnnotatedString(sb.toString()))
        statusMessage = "Copied ${map.yAxis.size}×${map.xAxis.size} map to clipboard"
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Title & status ────────────────────────────────────────────
        Text(
            text = "Axis Rescaler",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Paste any map, edit the X/Y axis breakpoints, and rescale using bilinear interpolation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        statusMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }

        // ── Input section ─────────────────────────────────────────────
        SectionCard("Input Map") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { handlePaste() }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Paste Map")
                }
            }

            val map = inputMap
            if (map != null) {
                Spacer(Modifier.height(8.dp))
                Text("X-Axis", style = MaterialTheme.typography.labelMedium)
                MapAxis(
                    data = arrayOf(map.xAxis),
                    editable = false
                )

                Spacer(Modifier.height(4.dp))
                Text("Y-Axis", style = MaterialTheme.typography.labelMedium)
                MapAxis(
                    data = arrayOf(map.yAxis),
                    editable = false
                )

                Spacer(Modifier.height(8.dp))
                Text("Map Data (${map.yAxis.size} rows × ${map.xAxis.size} cols)", style = MaterialTheme.typography.labelMedium)
                Box(modifier = Modifier.heightIn(max = 300.dp)) {
                    MapTable(map = map, editable = false)
                }
            }
        }

        // ── Edit axes section ─────────────────────────────────────────
        if (inputMap != null) {
            SectionCard("Edit Axes") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("New X-Axis", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(
                        onClick = { inputMap?.let { editedXAxis = it.xAxis.copyOf() } },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset X")
                    }
                }
                if (editedXAxis.isNotEmpty()) {
                    MapAxis(
                        data = arrayOf(editedXAxis),
                        editable = true,
                        onDataChanged = { newData ->
                            if (newData.isNotEmpty()) editedXAxis = newData[0]
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("New Y-Axis", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(
                        onClick = { inputMap?.let { editedYAxis = it.yAxis.copyOf() } },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset Y")
                    }
                }
                if (editedYAxis.isNotEmpty()) {
                    MapAxis(
                        data = arrayOf(editedYAxis),
                        editable = true,
                        onDataChanged = { newData ->
                            if (newData.isNotEmpty()) editedYAxis = newData[0]
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(onClick = { handleRescale() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rescale")
                }
            }
        }

        // ── Output section ────────────────────────────────────────────
        val result = rescaleResult
        if (result != null) {
            SectionCard("Rescaled Output") {
                // Diagnostics row
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DiagnosticChip("Total", result.totalCells.toString())
                    DiagnosticChip("Exact", result.exactMatchCount.toString())
                    DiagnosticChip(
                        "Extrapolated", result.extrapolatedCount.toString(),
                        highlight = result.extrapolatedCount > 0
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(modifier = Modifier.heightIn(max = 300.dp)) {
                    MapTable(
                        map = result.rescaledMap,
                        editable = false,
                        cellColorProvider = { rowIdx, colIdx ->
                            if (result.extrapolatedCells[rowIdx][colIdx]) {
                                Color(0xFFFF9800).copy(alpha = 0.45f)
                            } else {
                                null
                            }
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Button(onClick = { handleCopyOutput() }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy to Clipboard")
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DiagnosticChip(label: String, value: String, highlight: Boolean = false) {
    val containerColor = if (highlight) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (highlight) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = "$label:", style = MaterialTheme.typography.labelSmall, color = textColor)
            Text(text = value, style = MaterialTheme.typography.labelMedium, color = textColor)
        }
    }
}
