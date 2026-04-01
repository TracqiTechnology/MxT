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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.text.DecimalFormat

private val AXIS_CELL_WIDTH = 58.dp
private val AXIS_CELL_HEIGHT = 24.dp
private val axisFormatter = DecimalFormat("#.00")

private fun axisHsbColor(value: Double): Color {
    val h = (value * 0.4).toFloat()
    val s = 0.9f
    val b = 0.9f
    val rgb = java.awt.Color.HSBtoRGB(h, s, b)
    return Color(rgb or (0xFF shl 24))
}

@Composable
fun MapAxis(
    data: Array<Array<Double>>,
    editable: Boolean = true,
    onDataChanged: ((Array<Array<Double>>) -> Unit)? = null
) {
    if (data.isEmpty() || data[0].isEmpty()) return

    val rowCount = data.size
    val colCount = data[0].size

    var minValue by remember { mutableStateOf(Double.MAX_VALUE) }
    var maxValue by remember { mutableStateOf(Double.MIN_VALUE) }

    LaunchedEffect(data) {
        var mn = Double.MAX_VALUE
        var mx = Double.MIN_VALUE
        for (row in data) {
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

    var selectedRow by remember { mutableStateOf(-1) }
    var selectedCol by remember { mutableStateOf(-1) }
    var editingRow by remember { mutableStateOf(-1) }
    var editingCol by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }

    fun notifyChanged(newData: Array<Array<Double>>, debounce: Boolean = false) {
        debounceJob?.cancel()
        if (debounce) {
            debounceJob = scope.launch {
                delay(100)
                onDataChanged?.invoke(newData)
            }
        } else {
            onDataChanged?.invoke(newData)
        }
    }

    fun commitEdit() {
        if (editingRow >= 0 && editingCol >= 0) {
            val newVal = editText.toDoubleOrNull()
            if (newVal != null) {
                val newData = Array(rowCount) { r -> Array(colCount) { c -> data[r][c] } }
                newData[editingRow][editingCol] = newVal
                notifyChanged(newData)
            }
            editingRow = -1
            editingCol = -1
        }
    }

    fun handlePaste() {
        if (selectedRow < 0 || selectedCol < 0) return
        val text = clipboardManager.getText()?.text ?: return
        val newData = Array(rowCount) { r -> Array(colCount) { c -> data[r][c] } }
        val values = text.trim().split("\t", "\n")
        for ((j, value) in values.withIndex()) {
            val c = selectedCol + j
            if (c < colCount) {
                val parsed = value.trim().toDoubleOrNull()
                if (parsed != null) newData[selectedRow][c] = parsed
            }
        }
        notifyChanged(newData, debounce = true)
    }

    val horizontalScroll = rememberScrollState()

    Row(
        modifier = Modifier
            .horizontalScroll(horizontalScroll)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val meta = event.isMetaPressed || event.isCtrlPressed
                    when {
                        meta && event.key == Key.C -> {
                            if (selectedRow >= 0 && selectedCol >= 0)
                                clipboardManager.setText(AnnotatedString(axisFormatter.format(data[selectedRow][selectedCol])))
                            true
                        }
                        meta && event.key == Key.V -> { handlePaste(); true }
                        event.key == Key.Enter || event.key == Key.Tab -> { commitEdit(); true }
                        event.key == Key.Escape -> { editingRow = -1; editingCol = -1; true }
                        else -> false
                    }
                } else false
            }
    ) {
        for (r in 0 until rowCount) {
            for (c in 0 until colCount) {
                val value = data[r][c]
                val isEditing = editingRow == r && editingCol == c
                val isSelected = selectedRow == r && selectedCol == c

                val norm = if (maxValue - minValue != 0.0) {
                    1.0 - (value - minValue) / (maxValue - minValue)
                } else 0.5

                val bgColor = when {
                    isSelected -> Color.Cyan.copy(alpha = 0.3f)
                    else -> axisHsbColor(norm)
                }

                Box(
                    modifier = Modifier
                        .size(AXIS_CELL_WIDTH, AXIS_CELL_HEIGHT)
                        .testTag("axis_cell_${r}_${c}")
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
                                editText = axisFormatter.format(value)
                            } else {
                                commitEdit()
                                selectedRow = r
                                selectedCol = c
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
                            text = axisFormatter.format(value),
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
