package ui.screens.logger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import data.logger.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun LoggerScreen(loggerManager: LoggerManager? = null) {
    val scope = rememberCoroutineScope()
    val logger = remember { loggerManager ?: Me7LoggerProcess() }

    // Collect logger state
    val loggerStatus by logger.status.collectAsState()
    val statusMessage by logger.statusMessage.collectAsState()
    val variables by logger.variables.collectAsState()
    val currentSession by logger.session.collectAsState()

    // Config state
    var me7loggerPath by remember { mutableStateOf("") }
    var comPort by remember { mutableStateOf("COM3") }
    var ecuFile by remember { mutableStateOf("") }
    var cfgFile by remember { mutableStateOf("") }

    // UI state
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Connection", "Live Data", "Chart")

    // Track latest samples for live display
    var latestSample by remember { mutableStateOf<LogSample?>(null) }
    val recentSamples = remember { mutableStateListOf<LogSample>() }
    val maxRecentSamples = 500

    // Collect samples
    LaunchedEffect(loggerStatus) {
        if (loggerStatus == LoggerStatus.LOGGING) {
            logger.samples.collect { sample ->
                latestSample = sample
                recentSamples.add(sample)
                if (recentSamples.size > maxRecentSamples) {
                    recentSamples.removeAt(0)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab row
        TabRow(selectedTabIndex = selectedTab) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                    icon = {
                        Icon(
                            when (index) {
                                0 -> Icons.Default.Settings
                                1 -> Icons.Default.Speed
                                else -> Icons.Default.ShowChart
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }

        // Status bar
        Surface(
            color = when (loggerStatus) {
                LoggerStatus.LOGGING -> MaterialTheme.colorScheme.primaryContainer
                LoggerStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                LoggerStatus.CONNECTED -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                // Status indicator dot
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when (loggerStatus) {
                        LoggerStatus.LOGGING -> MaterialTheme.colorScheme.primary
                        LoggerStatus.ERROR -> MaterialTheme.colorScheme.error
                        LoggerStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
                        LoggerStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(8.dp)
                ) {}

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${loggerStatus.name}${if (statusMessage.isNotEmpty()) " — $statusMessage" else ""}",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.weight(1f))

                if (currentSession != null) {
                    Text(
                        text = "${currentSession!!.sampleCount} samples",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Content based on selected tab
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when (selectedTab) {
                0 -> ConnectionTab(
                    me7loggerPath = me7loggerPath,
                    comPort = comPort,
                    ecuFile = ecuFile,
                    cfgFile = cfgFile,
                    loggerStatus = loggerStatus,
                    onMe7loggerPathChange = { me7loggerPath = it },
                    onComPortChange = { comPort = it },
                    onEcuFileChange = { ecuFile = it },
                    onCfgFileChange = { cfgFile = it },
                    onConnect = {
                        scope.launch {
                            logger.connect(
                                LoggerConfig(
                                    me7loggerPath = me7loggerPath,
                                    comPort = comPort,
                                    ecuFile = ecuFile,
                                    cfgFile = cfgFile
                                )
                            )
                        }
                    },
                    onStartLogging = {
                        scope.launch { logger.startLogging() }
                    },
                    onStopLogging = {
                        scope.launch { logger.stopLogging() }
                    },
                    onDisconnect = {
                        scope.launch { logger.disconnect() }
                    },
                    onLoadLogFile = { file ->
                        scope.launch {
                            val session = withContext(Dispatchers.IO) {
                                Me7LoggerProcess().parseLogFile(file)
                            }
                            recentSamples.clear()
                            recentSamples.addAll(session.samples.takeLast(maxRecentSamples))
                            latestSample = session.samples.lastOrNull()
                            selectedTab = 1
                        }
                    },
                    onExportCsv = { file ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                currentSession?.let { CsvExporter.export(it, file) }
                            }
                        }
                    }
                )
                1 -> LiveDataTab(
                    variables = variables,
                    latestSample = latestSample
                )
                2 -> ChartTab(
                    variables = variables,
                    recentSamples = recentSamples
                )
            }
        }
    }
}

@Composable
private fun ConnectionTab(
    me7loggerPath: String,
    comPort: String,
    ecuFile: String,
    cfgFile: String,
    loggerStatus: LoggerStatus,
    onMe7loggerPathChange: (String) -> Unit,
    onComPortChange: (String) -> Unit,
    onEcuFileChange: (String) -> Unit,
    onCfgFileChange: (String) -> Unit,
    onConnect: () -> Unit,
    onStartLogging: () -> Unit,
    onStopLogging: () -> Unit,
    onDisconnect: () -> Unit,
    onLoadLogFile: (File) -> Unit,
    onExportCsv: (File) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        Text(
            "ME7Logger Configuration",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // ME7Logger path
        FilePickerRow(
            label = "ME7Logger.exe",
            value = me7loggerPath,
            onValueChange = onMe7loggerPathChange,
            filterDescription = "ME7Logger executable"
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ECU file
        FilePickerRow(
            label = ".ecu File",
            value = ecuFile,
            onValueChange = onEcuFileChange,
            filterDescription = "ECU characteristics"
        )

        Spacer(modifier = Modifier.height(8.dp))

        // CFG file
        FilePickerRow(
            label = ".cfg File",
            value = cfgFile,
            onValueChange = onCfgFileChange,
            filterDescription = "Log configuration"
        )

        Spacer(modifier = Modifier.height(8.dp))

        // COM port
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = comPort,
                onValueChange = onComPortChange,
                label = { Text("COM Port") },
                singleLine = true,
                modifier = Modifier.width(200.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (loggerStatus) {
                LoggerStatus.DISCONNECTED, LoggerStatus.ERROR -> {
                    Button(
                        onClick = onConnect,
                        enabled = me7loggerPath.isNotEmpty() && ecuFile.isNotEmpty() && cfgFile.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect")
                    }
                }
                LoggerStatus.CONNECTED -> {
                    Button(onClick = onStartLogging) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Logging")
                    }
                    OutlinedButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                }
                LoggerStatus.LOGGING -> {
                    Button(
                        onClick = onStopLogging,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop")
                    }
                }
                LoggerStatus.CONNECTING -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(16.dp))

        // Offline mode
        Text(
            "Offline Log Viewer",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Load log file
            OutlinedButton(onClick = {
                val dialog = FileDialog(Frame(), "Open Log File", FileDialog.LOAD)
                dialog.isVisible = true
                if (dialog.directory != null && dialog.file != null) {
                    onLoadLogFile(File(dialog.directory, dialog.file))
                }
            }) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Log File")
            }

            // Export CSV
            OutlinedButton(
                onClick = {
                    val dialog = FileDialog(Frame(), "Export CSV", FileDialog.SAVE)
                    dialog.file = "me7tuner_log.csv"
                    dialog.isVisible = true
                    if (dialog.directory != null && dialog.file != null) {
                        onExportCsv(File(dialog.directory, dialog.file))
                    }
                },
                enabled = loggerStatus == LoggerStatus.CONNECTED || loggerStatus == LoggerStatus.DISCONNECTED
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export CSV")
            }
        }
    }
}

@Composable
private fun FilePickerRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    filterDescription: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = {
            val dialog = FileDialog(Frame(), "Select $filterDescription", FileDialog.LOAD)
            dialog.isVisible = true
            if (dialog.directory != null && dialog.file != null) {
                onValueChange(File(dialog.directory, dialog.file).absolutePath)
            }
        }) {
            Icon(Icons.Default.FolderOpen, contentDescription = "Browse")
        }
    }
}

@Composable
private fun LiveDataTab(
    variables: List<LogVariable>,
    latestSample: LogSample?
) {
    if (variables.isEmpty() && latestSample == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No data yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Start logging or open a log file to see live data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Table header
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Signal", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.5f))
                Text("Alias", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.5f))
                Text("Value", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text("Unit", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.8f))
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            val listState = rememberLazyListState()

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(variables) { variable ->
                    val value = latestSample?.values?.getOrNull(variable.index)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            variable.name,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1.5f)
                        )
                        Text(
                            variable.alias,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1.5f)
                        )
                        Text(
                            value?.let { formatLiveValue(it) } ?: "—",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            variable.unit,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.8f)
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

@Composable
private fun ChartTab(
    variables: List<LogVariable>,
    recentSamples: List<LogSample>
) {
    if (recentSamples.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                "No data to chart. Start logging or open a log file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // Show first 4 numeric variables as separate line charts
    val chartVariables = variables.take(minOf(4, variables.size))
    val chartColors = listOf(
        Color(0xFFFFB300),  // Amber
        Color(0xFF42A5F5),  // Blue
        Color(0xFF66BB6A),  // Green
        Color(0xFFEF5350),  // Red
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        for ((idx, variable) in chartVariables.withIndex()) {
            if (idx > 0) Spacer(modifier = Modifier.height(8.dp))

            Text(
                "${variable.alias.ifEmpty { variable.name }} (${variable.unit})",
                style = MaterialTheme.typography.labelSmall,
                color = chartColors[idx]
            )

            val values = recentSamples.mapNotNull { sample ->
                sample.values.getOrNull(variable.index)
            }

            if (values.isNotEmpty()) {
                val minVal = values.min()
                val maxVal = values.max()
                val range = if (maxVal > minVal) maxVal - minVal else 1.0

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val w = size.width
                    val h = size.height
                    val color = chartColors[idx]

                    if (values.size < 2) return@Canvas

                    val path = Path()
                    val stepX = w / (values.size - 1).toFloat()

                    for (i in values.indices) {
                        val x = i * stepX
                        val y = h - ((values[i] - minVal) / range * h).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    drawPath(path, color, style = Stroke(width = 1.5f))

                    // Min/Max labels
                    drawLine(
                        color = color.copy(alpha = 0.2f),
                        start = Offset(0f, 0f),
                        end = Offset(w, 0f),
                        strokeWidth = 0.5f
                    )
                    drawLine(
                        color = color.copy(alpha = 0.2f),
                        start = Offset(0f, h),
                        end = Offset(w, h),
                        strokeWidth = 0.5f
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatLiveValue(values.min()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "latest: ${formatLiveValue(values.last())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = chartColors[idx]
                    )
                    Text(
                        formatLiveValue(values.max()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatLiveValue(value: Double): String {
    if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e9) {
        return value.toLong().toString()
    }
    return String.format("%.2f", value)
}
