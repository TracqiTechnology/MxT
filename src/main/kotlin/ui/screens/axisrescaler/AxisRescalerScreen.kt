package ui.screens.axisrescaler

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import domain.math.AxisRescaler
import domain.math.ResamplingMethod
import domain.math.map.Map3d
import ui.components.MapAxis
import ui.components.MapTable
import java.text.DecimalFormat

private val formatter = DecimalFormat("#.##")
private const val DEFAULT_ROWS = 8
private const val DEFAULT_COLS = 8
private const val MAX_DIMENSION = 50

@Composable
fun AxisRescalerScreen(preloadedMap: Map3d? = null) {
    val clipboardManager = LocalClipboardManager.current

    // Input map dimensions and data
    val initRows = preloadedMap?.yAxis?.size ?: DEFAULT_ROWS
    val initCols = preloadedMap?.xAxis?.size ?: DEFAULT_COLS
    var inputRowsText by remember { mutableStateOf(initRows.toString()) }
    var inputColsText by remember { mutableStateOf(initCols.toString()) }
    var inputRows by remember { mutableStateOf(initRows) }
    var inputCols by remember { mutableStateOf(initCols) }

    var inputMap by remember {
        mutableStateOf(preloadedMap ?: buildEmptyMap(DEFAULT_ROWS, DEFAULT_COLS))
    }

    var outputRowsText by remember { mutableStateOf(initRows.toString()) }
    var outputColsText by remember { mutableStateOf(initCols.toString()) }
    var outputRows by remember { mutableStateOf(initRows) }
    var outputCols by remember { mutableStateOf(initCols) }
    var outputXAxis by remember {
        mutableStateOf(deriveAxis(inputMap.xAxis, outputCols))
    }
    var outputYAxis by remember {
        mutableStateOf(deriveAxis(inputMap.yAxis, outputRows))
    }
    var resamplingMethod by remember { mutableStateOf(ResamplingMethod.BILINEAR) }

    var rescaleResult by remember { mutableStateOf<AxisRescaler.RescaleResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun updateInputDimensions(rows: Int, cols: Int) {
        val r = rows.coerceIn(1, MAX_DIMENSION)
        val c = cols.coerceIn(1, MAX_DIMENSION)
        inputRows = r
        inputCols = c
        inputMap = resizeMap(inputMap, r, c)
        rescaleResult = null
    }

    fun updateOutputDimensions(rows: Int, cols: Int) {
        val r = rows.coerceIn(1, MAX_DIMENSION)
        val c = cols.coerceIn(1, MAX_DIMENSION)
        outputRows = r
        outputCols = c
        outputXAxis = resizeAxis(outputXAxis, c)
        outputYAxis = resizeAxis(outputYAxis, r)
        rescaleResult = null
    }

    fun handlePasteInputMap() {
        errorMessage = null
        try {
            val text = clipboardManager.getText()?.text
                ?: throw IllegalArgumentException("Clipboard is empty")
            val pasted = MapClipboardParser.parseTsvOrThrow(text)
            inputMap = pasted
            inputRows = pasted.yAxis.size
            inputCols = pasted.xAxis.size
            inputRowsText = inputRows.toString()
            inputColsText = inputCols.toString()
            outputXAxis = deriveAxis(pasted.xAxis, outputCols)
            outputYAxis = deriveAxis(pasted.yAxis, outputRows)
            rescaleResult = null
            statusMessage = "Pasted ${inputRows}×${inputCols} source map"
        } catch (e: IllegalArgumentException) {
            errorMessage = e.message
        }
    }

    fun handleRescale() {
        errorMessage = null
        try {
            val result = AxisRescaler.rescaleMap(
                original = inputMap,
                newXAxis = outputXAxis,
                newYAxis = outputYAxis,
                method = resamplingMethod
            )
            rescaleResult = result
            statusMessage = "Rescaled: ${result.exactMatchCount} exact, " +
                "${result.extrapolatedCount} extrapolated, " +
                "${result.totalCells} total cells using ${result.method.displayName()}"
        } catch (e: IllegalArgumentException) {
            errorMessage = e.message
            rescaleResult = null
        }
    }

    fun handleCopyOutput() {
        val result = rescaleResult ?: return
        val map = result.rescaledMap
        val sb = StringBuilder()

        sb.append("\t")
        sb.append(map.xAxis.joinToString("\t") { formatter.format(it) })
        sb.appendLine()

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
            text = "Set input dimensions, paste map data and axis values, set output dimensions, and rescale.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        errorMessage?.let {
            Text(
                text = it,
                modifier = Modifier.testTag("axis-rescaler-error"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        statusMessage?.let {
            Text(
                text = it,
                modifier = Modifier.testTag("axis-rescaler-status"),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ── Input section ─────────────────────────────────────────────
        SectionCard("Input Map") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dimensions:", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = inputRowsText,
                    onValueChange = { value ->
                        inputRowsText = value
                        val n = value.toIntOrNull()
                        if (n != null && n in 1..MAX_DIMENSION) {
                            updateInputDimensions(n, inputCols)
                        }
                    },
                    label = { Text("Rows") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(80.dp).testTag("axis-input-rows"),
                    singleLine = true,
                )
                Text("×", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = inputColsText,
                    onValueChange = { value ->
                        inputColsText = value
                        val n = value.toIntOrNull()
                        if (n != null && n in 1..MAX_DIMENSION) {
                            updateInputDimensions(inputRows, n)
                        }
                    },
                    label = { Text("Cols") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(80.dp).testTag("axis-input-cols"),
                    singleLine = true,
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.testTag("axis-paste-map"),
                onClick = { handlePasteInputMap() }
            ) {
                Text("Paste Complete TSV Map")
            }

            Spacer(Modifier.height(8.dp))

            Text("X-Axis (paste or edit values)", style = MaterialTheme.typography.labelMedium)
            MapAxis(
                data = arrayOf(inputMap.xAxis),
                editable = true,
                onDataChanged = { newData ->
                    if (newData.isNotEmpty() && newData[0].isNotEmpty()) {
                        inputMap = Map3d(newData[0], inputMap.yAxis, inputMap.zAxis)
                    }
                },
                testTagPrefix = "axis-input-x"
            )

            Spacer(Modifier.height(4.dp))

            Text("Y-Axis (paste or edit values)", style = MaterialTheme.typography.labelMedium)
            MapAxis(
                data = arrayOf(inputMap.yAxis),
                editable = true,
                onDataChanged = { newData ->
                    if (newData.isNotEmpty() && newData[0].isNotEmpty()) {
                        inputMap = Map3d(inputMap.xAxis, newData[0], inputMap.zAxis)
                    }
                },
                testTagPrefix = "axis-input-y"
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Map Data (${inputRows}×${inputCols} — select a cell and paste with ${modifierKeyName()}+V)",
                style = MaterialTheme.typography.labelMedium
            )
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                MapTable(
                    map = inputMap,
                    editable = true,
                    onMapChanged = { newMap -> inputMap = newMap },
                    testTagPrefix = "axis-input-map"
                )
            }
        }

        // ── Output section ────────────────────────────────────────────
        SectionCard("Output") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dimensions:", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = outputRowsText,
                    onValueChange = { value ->
                        outputRowsText = value
                        value.toIntOrNull()?.takeIf { it in 1..MAX_DIMENSION }?.let {
                            updateOutputDimensions(it, outputCols)
                        }
                    },
                    label = { Text("Rows") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(80.dp).testTag("axis-output-rows"),
                    singleLine = true
                )
                Text("×")
                OutlinedTextField(
                    value = outputColsText,
                    onValueChange = { value ->
                        outputColsText = value
                        value.toIntOrNull()?.takeIf { it in 1..MAX_DIMENSION }?.let {
                            updateOutputDimensions(outputRows, it)
                        }
                    },
                    label = { Text("Cols") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(80.dp).testTag("axis-output-cols"),
                    singleLine = true
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Output X-Axis (editable/pasteable)", style = MaterialTheme.typography.labelMedium)
            MapAxis(
                data = arrayOf(outputXAxis),
                editable = true,
                onDataChanged = { outputXAxis = it[0] },
                testTagPrefix = "axis-output-x"
            )

            Spacer(Modifier.height(4.dp))
            Text("Output Y-Axis (editable/pasteable)", style = MaterialTheme.typography.labelMedium)
            MapAxis(
                data = arrayOf(outputYAxis),
                editable = true,
                onDataChanged = { outputYAxis = it[0] },
                testTagPrefix = "axis-output-y"
            )

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Interpolation:", style = MaterialTheme.typography.labelMedium)
                FilterChip(
                    selected = resamplingMethod == ResamplingMethod.BILINEAR,
                    onClick = { resamplingMethod = ResamplingMethod.BILINEAR },
                    label = { Text("Bilinear (default)") }
                )
                FilterChip(
                    selected = resamplingMethod == ResamplingMethod.MONOTONE_CUBIC,
                    onClick = { resamplingMethod = ResamplingMethod.MONOTONE_CUBIC },
                    label = { Text("Monotone cubic") }
                )
            }
            Text(
                "Exact source intersections are copied unchanged; values outside the source range clamp to the nearest edge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    modifier = Modifier.testTag("axis-rescale"),
                    onClick = { handleRescale() }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rescale")
                }
            }

            // Rescaled result
            val result = rescaleResult
            if (result != null) {
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DiagnosticChip("Total", result.totalCells.toString())
                    DiagnosticChip("Exact", result.exactMatchCount.toString())
                    DiagnosticChip(
                        "Extrapolated", result.extrapolatedCount.toString(),
                        highlight = result.extrapolatedCount > 0
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                    MapTable(
                        map = result.rescaledMap,
                        editable = false,
                        testTagPrefix = "axis-output-map",
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

private fun buildEmptyMap(rows: Int, cols: Int): Map3d {
    val xAxis = buildDefaultAxis(cols)
    val yAxis = buildDefaultAxis(rows)
    val zAxis = Array(rows) { Array(cols) { 0.0 } }
    return Map3d(xAxis, yAxis, zAxis)
}

private fun buildDefaultAxis(size: Int): Array<Double> =
    Array(size) { (it + 1).toDouble() }

/** Linearly space [count] breakpoints across the range of [inputAxis]. */
private fun deriveAxis(inputAxis: Array<Double>, count: Int): Array<Double> {
    if (count <= 0 || inputAxis.isEmpty()) return buildDefaultAxis(count.coerceAtLeast(1))
    if (count == 1) return arrayOf(inputAxis.first())

    val min = inputAxis.first()
    val max = inputAxis.last()
    val step = (max - min) / (count - 1)
    return Array(count) { i -> min + step * i }
}

private fun resizeMap(current: Map3d, newRows: Int, newCols: Int): Map3d {
    val xAxis = resizeAxis(current.xAxis, newCols)
    val yAxis = resizeAxis(current.yAxis, newRows)
    val zAxis = Array(newRows) { r ->
        Array(newCols) { c ->
            if (r < current.zAxis.size && c < current.zAxis[0].size) {
                current.zAxis[r][c]
            } else {
                0.0
            }
        }
    }
    return Map3d(xAxis, yAxis, zAxis)
}

private fun resizeAxis(current: Array<Double>, newSize: Int): Array<Double> {
    if (newSize <= 0) return current
    if (newSize == current.size) return current
    if (current.isEmpty()) return buildDefaultAxis(newSize)

    return if (newSize < current.size) {
        current.copyOfRange(0, newSize)
    } else {
        val step = if (current.size >= 2) {
            current.last() - current[current.size - 2]
        } else {
            1.0
        }
        Array(newSize) { i ->
            if (i < current.size) current[i]
            else current.last() + step * (i - current.size + 1)
        }
    }
}

private fun modifierKeyName(): String =
    if (System.getProperty("os.name").lowercase().contains("mac")) "⌘" else "Ctrl"

private fun ResamplingMethod.displayName(): String = when (this) {
    ResamplingMethod.BILINEAR -> "bilinear interpolation"
    ResamplingMethod.MONOTONE_CUBIC -> "monotone cubic interpolation"
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
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (highlight) {
        MaterialTheme.colorScheme.onTertiaryContainer
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
