package ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import domain.math.map.Map3d
import kotlinx.coroutines.*
import java.text.DecimalFormat

private val CELL_WIDTH = 58.dp
private val CELL_HEIGHT = 24.dp
private val HEADER_WIDTH = 54.dp
private val formatter = DecimalFormat("#.##")

private fun hsbColor(value: Double): Color {
    val h = (value * 0.4).toFloat()
    val s = 0.9f
    val b = 0.9f
    val rgb = java.awt.Color.HSBtoRGB(h, s, b)
    return Color(rgb or (0xFF shl 24))
}

@Composable
fun MapTable(
    map: Map3d,
    editable: Boolean = true,
    onMapChanged: ((Map3d) -> Unit)? = null,
    cellColorProvider: ((rowIdx: Int, colIdx: Int) -> Color?)? = null,
    onCellSelected: ((rowIdx: Int, colIdx: Int) -> Unit)? = null
) {
    val zAxis = map.zAxis
    if (zAxis.isEmpty() || zAxis[0].isEmpty()) return

    val rowCount = zAxis.size
    val colCount = zAxis[0].size

    var minValue by remember { mutableStateOf(Double.MAX_VALUE) }
    var maxValue by remember { mutableStateOf(Double.MIN_VALUE) }

    LaunchedEffect(zAxis) {
        var mn = Double.MAX_VALUE
        var mx = Double.MIN_VALUE
        for (row in zAxis) {
            for (v in row) {
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        }
        minValue = mn
        maxValue = mx
    }

    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    // Selection state
    var selectedRow by remember { mutableStateOf(-1) }
    var selectedCol by remember { mutableStateOf(-1) }
    var editingRow by remember { mutableStateOf(-1) }
    var editingCol by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }

    fun notifyChanged(newZAxis: Array<Array<Double>>, debounce: Boolean = false) {
        debounceJob?.cancel()
        if (debounce) {
            debounceJob = scope.launch {
                delay(100)
                onMapChanged?.invoke(Map3d(map.xAxis.clone(), map.yAxis.clone(), newZAxis))
            }
        } else {
            onMapChanged?.invoke(Map3d(map.xAxis.clone(), map.yAxis.clone(), newZAxis))
        }
    }

    fun commitEdit() {
        if (editingRow >= 0 && editingCol >= 0) {
            val newVal = editText.toDoubleOrNull()
            if (newVal != null) {
                val newZAxis = Array(rowCount) { r -> Array(colCount) { c -> zAxis[r][c] } }
                newZAxis[editingRow][editingCol] = newVal
                notifyChanged(newZAxis)
            }
            editingRow = -1
            editingCol = -1
        }
    }

    fun handleCopy() {
        if (selectedRow < 0 || selectedCol < 0) return
        val sb = StringBuilder()
        sb.append(formatter.format(zAxis[selectedRow][selectedCol]))
        clipboardManager.setText(AnnotatedString(sb.toString()))
    }

    fun handlePaste() {
        if (selectedRow < 0 || selectedCol < 0) return
        val text = clipboardManager.getText()?.text ?: return
        val newZAxis = Array(rowCount) { r -> Array(colCount) { c -> zAxis[r][c] } }
        val lines = text.trim().split("\n")
        for ((i, line) in lines.withIndex()) {
            val values = line.split("\t")
            for ((j, value) in values.withIndex()) {
                val r = selectedRow + i
                val c = selectedCol + j
                if (r < rowCount && c < colCount) {
                    val parsed = value.trim().toDoubleOrNull()
                    if (parsed != null) newZAxis[r][c] = parsed
                }
            }
        }
        notifyChanged(newZAxis, debounce = true)
    }

    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val meta = event.isMetaPressed || event.isCtrlPressed
                    when {
                        meta && event.key == Key.C -> { handleCopy(); true }
                        meta && event.key == Key.V -> { handlePaste(); true }
                        event.key == Key.Enter || event.key == Key.Tab -> { commitEdit(); true }
                        event.key == Key.Escape -> { editingRow = -1; editingCol = -1; true }
                        else -> false
                    }
                } else false
            }
    ) {
        // Column headers row
        Row {
            // Top-left corner spacer
            Box(modifier = Modifier.size(HEADER_WIDTH, CELL_HEIGHT))

            Row(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                for (c in 0 until colCount) {
                    val headerValue = if (c < map.xAxis.size) formatter.format(map.xAxis[c]) else ""
                    Box(
                        modifier = Modifier.size(CELL_WIDTH, CELL_HEIGHT)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(0.5.dp, Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = headerValue,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Data rows
        Row(modifier = Modifier.weight(1f)) {
            // Row headers
            Column(modifier = Modifier.verticalScroll(verticalScroll)) {
                for (r in 0 until rowCount) {
                    val headerValue = if (r < map.yAxis.size) formatter.format(map.yAxis[r]) else ""
                    Box(
                        modifier = Modifier.size(HEADER_WIDTH, CELL_HEIGHT)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(0.5.dp, Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = headerValue,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Data cells
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScroll)
                    .verticalScroll(verticalScroll)
            ) {
                Column {
                    for (r in 0 until rowCount) {
                        Row {
                            for (c in 0 until colCount) {
                                val value = zAxis[r][c]
                                val isEditing = editingRow == r && editingCol == c
                                val isSelected = selectedRow == r && selectedCol == c

                                val norm = if (maxValue - minValue != 0.0) {
                                    1.0 - (value - minValue) / (maxValue - minValue)
                                } else 0.5

                                val bgColor = when {
                                    isSelected -> Color.Cyan.copy(alpha = 0.3f)
                                    else -> cellColorProvider?.invoke(r, c) ?: hsbColor(norm)
                                }

                                Box(
                                    modifier = Modifier
                                        .size(CELL_WIDTH, CELL_HEIGHT)
                                        .drawBehind { drawRect(bgColor) }
                                        .border(0.5.dp, Color.Black)
                                        .focusProperties { canFocus = false }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            if (editable && selectedRow == r && selectedCol == c) {
                                                editingRow = r
                                                editingCol = c
                                                editText = formatter.format(value)
                                            } else {
                                                commitEdit()
                                                selectedRow = r
                                                selectedCol = c
                                                onCellSelected?.invoke(r, c)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isEditing && editable) {
                                        val focusRequester = remember { FocusRequester() }
                                        var hadFocus by remember { mutableStateOf(false) }
                                        BasicTextField(
                                            value = editText,
                                            onValueChange = { editText = it },
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                fontSize = 11.sp,
                                                textAlign = TextAlign.Center,
                                                color = Color.White
                                            ),
                                            cursorBrush = SolidColor(Color.White),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(2.dp)
                                                .focusRequester(focusRequester)
                                                .onFocusChanged {
                                                    if (it.isFocused) hadFocus = true
                                                    else if (hadFocus) commitEdit()
                                                }
                                        )
                                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                                    } else {
                                        Text(
                                            text = formatter.format(value),
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
