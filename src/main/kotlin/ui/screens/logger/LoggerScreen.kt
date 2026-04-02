package ui.screens.logger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.logger.*
import data.logger.kwp2000.Kwp2000NativeLogger
import data.logger.protocol.JSerialCommProvider
import data.logger.protocol.SerialPortEnumerator
import data.logger.uds.*
import data.model.EcuPlatform
import ui.components.ChartSeries
import ui.components.LineChart
import ui.components.niceTickValues
import ui.theme.GridColor
import java.text.DecimalFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun LoggerScreen(ecuPlatform: EcuPlatform = EcuPlatform.ME7, loggerManager: LoggerManager? = null) {
    val scope = rememberCoroutineScope()

    // Filter logger modes to those available on the current platform
    val availableModes = remember(ecuPlatform) {
        LoggerMode.entries.filter { ecuPlatform in it.platforms }
    }

    // Logger mode selection — auto-select first valid mode when platform changes
    var loggerMode by remember(ecuPlatform) {
        mutableStateOf(availableModes.first())
    }

    // CAN adapter type — declared before logger so it can be a remember key
    var canAdapterType by remember { mutableStateOf(CanAdapterType.SLCAN) }

    // Create logger based on mode and adapter type (re-created when either changes)
    val modeLogger: LoggerManager = remember(loggerMode, canAdapterType) {
        loggerManager ?: when (loggerMode) {
            LoggerMode.ME7LOGGER_EXE -> Me7LoggerProcess()
            LoggerMode.NATIVE_KWP2000 -> Kwp2000NativeLogger(JSerialCommProvider())
            LoggerMode.NATIVE_UDS -> {
                val transport: CanTransport = when (canAdapterType) {
                    CanAdapterType.SLCAN -> SlcanTransport(JSerialCommProvider())
                    CanAdapterType.PCAN -> PcanTransport()
                }
                UdsNativeLogger(transport)
            }
        }
    }

    // Dev mode state — Ctrl+Shift+D toggles mock replay
    var devMode by remember { mutableStateOf(false) }
    var devLogger by remember { mutableStateOf<MockLoggerProcess?>(null) }
    val logger: LoggerManager = devLogger ?: modeLogger

    // Focus for keyboard events
    val focusRequester = remember { FocusRequester() }

    // Bundled log fixtures for dev mode (cycled through)
    val devLogFiles = remember {
        listOf("open_loop_log.csv", "closed_loop_log.csv", "ldrpid_log.csv")
    }
    var devLogIndex by remember { mutableStateOf(0) }

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
    var connectionType by remember { mutableStateOf(ConnectionType.COM_PORT) }
    var ftdiMode by remember { mutableStateOf("serial") }
    var ftdiValue by remember { mutableStateOf("") }
    var overrideSps by remember { mutableStateOf(false) }
    var samplesPerSecond by remember { mutableStateOf("20") }
    var overrideBaud by remember { mutableStateOf(false) }
    var baudRate by remember { mutableStateOf("56000") }
    var syncTimestamp by remember { mutableStateOf(false) }
    var absoluteTimestamps by remember { mutableStateOf(false) }
    var millisecondTimestamps by remember { mutableStateOf(false) }
    var outputLogFile by remember { mutableStateOf("") }
    var realTimeWrite by remember { mutableStateOf(false) }

    // Native mode state
    var canBitrate by remember { mutableStateOf("500000") }
    var canTxId by remember { mutableStateOf("7E0") }
    var canRxId by remember { mutableStateOf("7E8") }
    val serialPorts = remember { mutableStateListOf<String>() }

    // Refresh serial ports on mode change
    LaunchedEffect(loggerMode) {
        if (loggerMode != LoggerMode.ME7LOGGER_EXE) {
            withContext(Dispatchers.IO) {
                val ports = SerialPortEnumerator.listPorts().map { it.displayName }
                serialPorts.clear()
                serialPorts.addAll(ports)
            }
        }
    }

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

    // Request focus on first composition for keyboard events
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.isCtrlPressed && event.isShiftPressed &&
                    event.key == Key.D
                ) {
                    scope.launch {
                        if (!devMode || loggerStatus != LoggerStatus.LOGGING) {
                            // Start dev mode
                            val mock = MockLoggerProcess(replayDelayMs = 50)
                            val logFile = File("example/med9/logs/${devLogFiles[devLogIndex % devLogFiles.size]}")
                            devLogIndex++

                            if (logFile.exists()) {
                                devLogger = mock
                                devMode = true
                                recentSamples.clear()
                                latestSample = null
                                mock.connectWithFile(logFile)
                                mock.startLogging()
                                selectedTab = 2  // Switch to Chart tab
                            }
                        } else {
                            // Stop dev mode — save log
                            val mock = devLogger ?: return@launch
                            mock.stopLogging()

                            val session = mock.session.value
                            if (session != null && session.sampleCount > 0) {
                                withContext(Dispatchers.IO) {
                                    val ts = LocalDateTime.now().format(
                                        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                                    )
                                    val desktop = File(System.getProperty("user.home"), "Desktop")
                                    val outDir = if (desktop.exists()) desktop else File(".")
                                    val outFile = File(outDir, "mxt_demo_$ts.csv")
                                    CsvExporter.export(session, outFile)
                                }
                            }

                            mock.disconnect()
                            devLogger = null
                            devMode = false
                        }
                    }
                    true
                } else false
            }
    ) {
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

                if (devMode) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = " \uD83D\uDD27 DEV MODE — Ctrl+Shift+D to stop ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

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
                    ecuPlatform = ecuPlatform,
                    loggerMode = loggerMode,
                    me7loggerPath = me7loggerPath,
                    comPort = comPort,
                    ecuFile = ecuFile,
                    cfgFile = cfgFile,
                    connectionType = connectionType,
                    ftdiMode = ftdiMode,
                    ftdiValue = ftdiValue,
                    overrideSps = overrideSps,
                    samplesPerSecond = samplesPerSecond,
                    overrideBaud = overrideBaud,
                    baudRate = baudRate,
                    syncTimestamp = syncTimestamp,
                    absoluteTimestamps = absoluteTimestamps,
                    millisecondTimestamps = millisecondTimestamps,
                    outputLogFile = outputLogFile,
                    realTimeWrite = realTimeWrite,
                    loggerStatus = loggerStatus,
                    canAdapterType = canAdapterType,
                    canBitrate = canBitrate,
                    canTxId = canTxId,
                    canRxId = canRxId,
                    serialPorts = serialPorts,
                    onLoggerModeChange = { loggerMode = it },
                    onMe7loggerPathChange = { me7loggerPath = it },
                    onComPortChange = { comPort = it },
                    onEcuFileChange = { ecuFile = it },
                    onCfgFileChange = { cfgFile = it },
                    onConnectionTypeChange = { connectionType = it },
                    onFtdiModeChange = { ftdiMode = it },
                    onFtdiValueChange = { ftdiValue = it },
                    onOverrideSpsChange = { overrideSps = it },
                    onSamplesPerSecondChange = { samplesPerSecond = it },
                    onOverrideBaudChange = { overrideBaud = it },
                    onBaudRateChange = { baudRate = it },
                    onSyncTimestampChange = { syncTimestamp = it },
                    onAbsoluteTimestampsChange = { absoluteTimestamps = it },
                    onMillisecondTimestampsChange = { millisecondTimestamps = it },
                    onOutputLogFileChange = { outputLogFile = it },
                    onRealTimeWriteChange = { realTimeWrite = it },
                    onCanAdapterTypeChange = { canAdapterType = it },
                    onCanBitrateChange = { canBitrate = it },
                    onCanTxIdChange = { canTxId = it },
                    onCanRxIdChange = { canRxId = it },
                    onConnect = {
                        scope.launch {
                            val ftdiIdentifier = when (ftdiMode) {
                                "serial" -> if (ftdiValue.isNotEmpty()) FtdiIdentifier.Serial(ftdiValue) else FtdiIdentifier.None
                                "description" -> if (ftdiValue.isNotEmpty()) FtdiIdentifier.Description(ftdiValue) else FtdiIdentifier.None
                                "location" -> if (ftdiValue.isNotEmpty()) FtdiIdentifier.Location(ftdiValue) else FtdiIdentifier.None
                                else -> FtdiIdentifier.None
                            }
                            logger.connect(
                                LoggerConfig(
                                    loggerMode = loggerMode,
                                    me7loggerPath = me7loggerPath,
                                    comPort = comPort,
                                    ecuFile = ecuFile,
                                    cfgFile = cfgFile,
                                    connectionType = connectionType,
                                    ftdiIdentifier = ftdiIdentifier,
                                    overrideSamplesPerSecond = overrideSps,
                                    samplesPerSecond = samplesPerSecond.toIntOrNull() ?: 20,
                                    overrideBaudRate = overrideBaud,
                                    baudRate = baudRate.toIntOrNull() ?: 56000,
                                    syncTimestamp = syncTimestamp,
                                    absoluteTimestamps = absoluteTimestamps,
                                    millisecondTimestamps = millisecondTimestamps,
                                    outputLogFile = outputLogFile,
                                    realTimeWrite = realTimeWrite,
                                    canAdapterType = canAdapterType,
                                    canBitrate = canBitrate.toIntOrNull() ?: 500_000,
                                    canTxId = canTxId.toIntOrNull(16) ?: 0x7E0,
                                    canRxId = canRxId.toIntOrNull(16) ?: 0x7E8
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun ConnectionTab(
    ecuPlatform: EcuPlatform,
    loggerMode: LoggerMode,
    me7loggerPath: String,
    comPort: String,
    ecuFile: String,
    cfgFile: String,
    connectionType: ConnectionType,
    ftdiMode: String,
    ftdiValue: String,
    overrideSps: Boolean,
    samplesPerSecond: String,
    overrideBaud: Boolean,
    baudRate: String,
    syncTimestamp: Boolean,
    absoluteTimestamps: Boolean,
    millisecondTimestamps: Boolean,
    outputLogFile: String,
    realTimeWrite: Boolean,
    loggerStatus: LoggerStatus,
    canAdapterType: CanAdapterType,
    canBitrate: String,
    canTxId: String,
    canRxId: String,
    serialPorts: List<String>,
    onLoggerModeChange: (LoggerMode) -> Unit,
    onMe7loggerPathChange: (String) -> Unit,
    onComPortChange: (String) -> Unit,
    onEcuFileChange: (String) -> Unit,
    onCfgFileChange: (String) -> Unit,
    onConnectionTypeChange: (ConnectionType) -> Unit,
    onFtdiModeChange: (String) -> Unit,
    onFtdiValueChange: (String) -> Unit,
    onOverrideSpsChange: (Boolean) -> Unit,
    onSamplesPerSecondChange: (String) -> Unit,
    onOverrideBaudChange: (Boolean) -> Unit,
    onBaudRateChange: (String) -> Unit,
    onSyncTimestampChange: (Boolean) -> Unit,
    onAbsoluteTimestampsChange: (Boolean) -> Unit,
    onMillisecondTimestampsChange: (Boolean) -> Unit,
    onOutputLogFileChange: (String) -> Unit,
    onRealTimeWriteChange: (Boolean) -> Unit,
    onCanAdapterTypeChange: (CanAdapterType) -> Unit,
    onCanBitrateChange: (String) -> Unit,
    onCanTxIdChange: (String) -> Unit,
    onCanRxIdChange: (String) -> Unit,
    onConnect: () -> Unit,
    onStartLogging: () -> Unit,
    onStopLogging: () -> Unit,
    onDisconnect: () -> Unit,
    onLoadLogFile: (File) -> Unit,
    onExportCsv: (File) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {

        // Section 0: Logger Mode
        SectionTitle("Logger Mode")

        val visibleModes = remember(ecuPlatform) {
            LoggerMode.entries.filter { ecuPlatform in it.platforms }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(bottom = 8.dp)) {
            visibleModes.forEachIndexed { index, mode ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = visibleModes.size),
                    onClick = { onLoggerModeChange(mode) },
                    selected = loggerMode == mode
                ) { Text(mode.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
        }

        Text(
            loggerMode.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Section 1: Files
        SectionTitle("Files")

        if (loggerMode == LoggerMode.ME7LOGGER_EXE) {
            FilePickerRow(
                label = "ME7Logger.exe",
                value = me7loggerPath,
                onValueChange = onMe7loggerPathChange,
                filterDescription = "ME7Logger executable"
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        FilePickerRow(
            label = ".ecu File",
            value = ecuFile,
            onValueChange = onEcuFileChange,
            filterDescription = "ECU characteristics"
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (loggerMode == LoggerMode.ME7LOGGER_EXE) {
            FilePickerRow(
                label = ".cfg File",
                value = cfgFile,
                onValueChange = onCfgFileChange,
                filterDescription = "Log configuration"
            )
        } else {
            FilePickerRow(
                label = ".cfg File (optional)",
                value = cfgFile,
                onValueChange = onCfgFileChange,
                filterDescription = "Log configuration"
            )
        }

        // Section 2: Connection
        SectionTitle("Connection")

        when (loggerMode) {
            LoggerMode.ME7LOGGER_EXE -> {
                // Existing COM / FTDI selector
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        onClick = { onConnectionTypeChange(ConnectionType.COM_PORT) },
                        selected = connectionType == ConnectionType.COM_PORT
                    ) { Text("COM Port", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        onClick = { onConnectionTypeChange(ConnectionType.FTDI) },
                        selected = connectionType == ConnectionType.FTDI
                    ) { Text("FTDI", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }

                if (connectionType == ConnectionType.COM_PORT) {
                    OutlinedTextField(
                        value = comPort,
                        onValueChange = onComPortChange,
                        label = { Text("COM Port") },
                        singleLine = true,
                        modifier = Modifier.width(200.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                } else {
                    val ftdiOptions = listOf("serial" to "Serial Number", "description" to "Description", "location" to "USB Location")
                    for ((key, label) in ftdiOptions) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = ftdiMode == key,
                                onClick = { onFtdiModeChange(key) }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            OutlinedTextField(
                                value = if (ftdiMode == key) ftdiValue else "",
                                onValueChange = { if (ftdiMode == key) onFtdiValueChange(it) },
                                label = { Text(label) },
                                singleLine = true,
                                enabled = ftdiMode == key,
                                modifier = Modifier.width(300.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }

            LoggerMode.NATIVE_KWP2000 -> {
                // Serial port dropdown for K-line
                Text("Serial Port", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
                if (serialPorts.isEmpty()) {
                    OutlinedTextField(
                        value = comPort,
                        onValueChange = onComPortChange,
                        label = { Text("Port name (e.g. /dev/ttyUSB0, COM3)") },
                        singleLine = true,
                        modifier = Modifier.width(350.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedTextField(
                            value = comPort,
                            onValueChange = onComPortChange,
                            label = { Text("Serial Port") },
                            singleLine = true,
                            readOnly = true,
                            modifier = Modifier.width(350.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            trailingIcon = {
                                IconButton(onClick = { expanded = !expanded }) {
                                    Icon(
                                        if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        )
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            serialPorts.forEach { port ->
                                DropdownMenuItem(
                                    text = { Text(port, style = MaterialTheme.typography.bodySmall) },
                                    onClick = { onComPortChange(port.substringBefore(" -")); expanded = false }
                                )
                            }
                        }
                    }
                }
            }

            LoggerMode.NATIVE_UDS -> {
                // CAN adapter type selector
                Text("CAN Adapter", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        onClick = { onCanAdapterTypeChange(CanAdapterType.SLCAN) },
                        selected = canAdapterType == CanAdapterType.SLCAN
                    ) { Text("SLCAN", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        onClick = { onCanAdapterTypeChange(CanAdapterType.PCAN) },
                        selected = canAdapterType == CanAdapterType.PCAN
                    ) { Text("PCAN", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }

                if (canAdapterType == CanAdapterType.PCAN) {
                    Text(
                        "Requires Peak PCAN-USB drivers — peak-system.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Port selection (SLCAN uses serial port)
                if (canAdapterType == CanAdapterType.SLCAN) {
                    if (serialPorts.isEmpty()) {
                        OutlinedTextField(
                            value = comPort,
                            onValueChange = onComPortChange,
                            label = { Text("SLCAN Port (e.g. /dev/ttyACM0, COM3)") },
                            singleLine = true,
                            modifier = Modifier.width(350.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedTextField(
                                value = comPort,
                                onValueChange = onComPortChange,
                                label = { Text("SLCAN Serial Port") },
                                singleLine = true,
                                readOnly = true,
                                modifier = Modifier.width(350.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                trailingIcon = {
                                    IconButton(onClick = { expanded = !expanded }) {
                                        Icon(
                                            if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                serialPorts.forEach { port ->
                                    DropdownMenuItem(
                                        text = { Text(port, style = MaterialTheme.typography.bodySmall) },
                                        onClick = { onComPortChange(port.substringBefore(" -")); expanded = false }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // CAN bitrate
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = canBitrate,
                        onValueChange = onCanBitrateChange,
                        label = { Text("CAN Bitrate") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = canTxId,
                        onValueChange = onCanTxIdChange,
                        label = { Text("TX ID (hex)") },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = canRxId,
                        onValueChange = onCanRxIdChange,
                        label = { Text("RX ID (hex)") },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }

        // Section 3: Sampling (shown for all modes)
        SectionTitle("Sampling")

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(checked = overrideSps, onCheckedChange = onOverrideSpsChange)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Override samples/sec", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = samplesPerSecond,
                onValueChange = onSamplesPerSecondChange,
                label = { Text("SPS (1-50)") },
                singleLine = true,
                enabled = overrideSps,
                modifier = Modifier.width(120.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(checked = overrideBaud, onCheckedChange = onOverrideBaudChange)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Override baud rate", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = baudRate,
                onValueChange = onBaudRateChange,
                label = { Text("Baud") },
                singleLine = true,
                enabled = overrideBaud,
                modifier = Modifier.width(120.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }

        // Section 4: Advanced (ME7Logger.exe only)
        if (loggerMode == LoggerMode.ME7LOGGER_EXE) {
            SectionTitle("Advanced")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = syncTimestamp, onCheckedChange = onSyncTimestampChange)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sync to next full second (-t)", style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = absoluteTimestamps, onCheckedChange = onAbsoluteTimestampsChange)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Absolute timestamps (-a)", style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = millisecondTimestamps, onCheckedChange = onMillisecondTimestampsChange)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Millisecond precision (-m)", style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = realTimeWrite, onCheckedChange = onRealTimeWriteChange)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Write to disk immediately (-r)", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(8.dp))

            FilePickerRow(
                label = "Save log to file (-o)",
                value = outputLogFile,
                onValueChange = onOutputLogFileChange,
                filterDescription = "Log output file"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        val connectEnabled = when (loggerMode) {
            LoggerMode.ME7LOGGER_EXE -> me7loggerPath.isNotEmpty() && ecuFile.isNotEmpty() && cfgFile.isNotEmpty()
            LoggerMode.NATIVE_KWP2000 -> ecuFile.isNotEmpty() && comPort.isNotEmpty()
            LoggerMode.NATIVE_UDS -> ecuFile.isNotEmpty() && (canAdapterType == CanAdapterType.PCAN || comPort.isNotEmpty())
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (loggerStatus) {
                LoggerStatus.DISCONNECTED, LoggerStatus.ERROR -> {
                    Button(
                        onClick = onConnect,
                        enabled = connectEnabled
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

        // Section 5: Offline Log Viewer
        SectionTitle("Offline Log Viewer")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

            OutlinedButton(
                onClick = {
                    val dialog = FileDialog(Frame(), "Export CSV", FileDialog.SAVE)
                    dialog.file = "mxt_log.csv"
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

    val chartColors = listOf(
        Color(0xFFFFB300),  // Amber
        Color(0xFF42A5F5),  // Blue
        Color(0xFF66BB6A),  // Green
        Color(0xFFEF5350),  // Red
        Color(0xFFAB47BC),  // Purple
        Color(0xFF26C6DA),  // Cyan
        Color(0xFFFF7043),  // Deep Orange
        Color(0xFF78909C),  // Blue Grey
    )

    var combinedView by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toggle bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (combinedView) "Combined View" else "Individual Charts",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    onClick = { combinedView = true },
                    selected = combinedView
                ) { Text("Combined", style = MaterialTheme.typography.labelSmall) }
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    onClick = { combinedView = false },
                    selected = !combinedView
                ) { Text("Individual", style = MaterialTheme.typography.labelSmall) }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        if (combinedView) {
            CombinedChart(variables, recentSamples, chartColors)
        } else {
            IndividualCharts(variables, recentSamples, chartColors)
        }
    }
}

@Composable
private fun IndividualCharts(
    variables: List<LogVariable>,
    recentSamples: List<LogSample>,
    chartColors: List<Color>
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        for ((idx, variable) in variables.withIndex()) {
            val color = chartColors[idx % chartColors.size]
            val timestamps = recentSamples.map { it.timestamp }
            val values = recentSamples.mapNotNull { it.values.getOrNull(variable.index) }

            if (values.isEmpty() || timestamps.size != values.size) continue

            val points = timestamps.zip(values).map { (t, v) -> t to v }
            val series = listOf(
                ChartSeries(
                    name = variable.alias.ifEmpty { variable.name },
                    points = points,
                    color = color,
                    strokeWidth = 1.5f
                )
            )

            LineChart(
                series = series,
                title = "${variable.alias.ifEmpty { variable.name }} (${variable.unit})",
                xAxisLabel = "Time (s)",
                yAxisLabel = variable.unit,
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CombinedChart(
    variables: List<LogVariable>,
    recentSamples: List<LogSample>,
    chartColors: List<Color>
) {
    if (variables.isEmpty()) return

    // Group variables by unit for dual-axis assignment
    val unitGroups = variables.groupBy { it.unit.lowercase().trim() }
    val sortedUnits = unitGroups.entries.sortedByDescending { it.value.size }
    val leftUnit = sortedUnits.firstOrNull()?.key ?: ""
    val rightUnit = if (sortedUnits.size > 1) sortedUnits[1].key else null

    // Assign each variable to left or right axis
    data class AxisVar(val variable: LogVariable, val color: Color, val isLeft: Boolean)
    val axisVars = variables.mapIndexed { idx, v ->
        val unit = v.unit.lowercase().trim()
        AxisVar(v, chartColors[idx % chartColors.size], isLeft = unit == leftUnit || rightUnit == null)
    }

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // Legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (av in axisVars) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(av.color, radius = 5f)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${av.variable.alias.ifEmpty { av.variable.name }} (${av.variable.unit})" +
                                if (!av.isLeft && rightUnit != null) " ▸" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = av.color,
                        maxLines = 1
                    )
                }
            }
        }

        // Axis labels
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "◀ ${unitGroups[leftUnit]?.firstOrNull()?.unit ?: leftUnit}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (rightUnit != null) {
                Text(
                    "${unitGroups[rightUnit]?.firstOrNull()?.unit ?: rightUnit} ▶",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Precompute per-variable data and ranges
        data class VarData(val av: AxisVar, val values: List<Double>, val min: Double, val max: Double)
        val timestamps = recentSamples.map { it.timestamp }
        val varDataList = axisVars.mapNotNull { av ->
            val vals = recentSamples.map { it.values.getOrElse(av.variable.index) { 0.0 } }
            if (vals.isEmpty()) null
            else {
                val mn = vals.min()
                val mx = vals.max()
                VarData(av, vals, mn, mx)
            }
        }

        // Compute axis ranges (union of all signals on each axis)
        val leftVars = varDataList.filter { it.av.isLeft }
        val rightVars = varDataList.filter { !it.av.isLeft }
        val leftMin = leftVars.minOfOrNull { it.min } ?: 0.0
        val leftMax = leftVars.maxOfOrNull { it.max } ?: 1.0
        val rightMin = rightVars.minOfOrNull { it.min } ?: 0.0
        val rightMax = rightVars.maxOfOrNull { it.max } ?: 1.0

        val leftMarginPx = with(density) { 64.dp.toPx() }
        val rightMarginPx = with(density) { if (rightUnit != null) 64.dp.toPx() else 16.dp.toPx() }
        val bottomMarginPx = with(density) { 32.dp.toPx() }
        val topMarginPx = with(density) { 8.dp.toPx() }

        Canvas(modifier = Modifier.fillMaxSize().weight(1f)) {
            val chartW = size.width - leftMarginPx - rightMarginPx
            val chartH = size.height - topMarginPx - bottomMarginPx
            if (chartW <= 0 || chartH <= 0) return@Canvas

            val formatter = DecimalFormat("#.##")
            val tickLabelStyle = TextStyle(color = Color(0xFFF8F8F2), fontSize = 10.sp)

            // Time axis
            val tMin = timestamps.firstOrNull() ?: 0.0
            val tMax = timestamps.lastOrNull() ?: 1.0
            val tRange = if (tMax > tMin) tMax - tMin else 1.0

            fun mapX(t: Double) = (leftMarginPx + (t - tMin) / tRange * chartW).toFloat()

            // Left Y axis mapping
            val lRange = if (leftMax > leftMin) leftMax - leftMin else 1.0
            val lPad = lRange * 0.05
            val lMin = leftMin - lPad
            val lMax = leftMax + lPad
            val lRangeP = lMax - lMin
            fun mapYLeft(y: Double) = (topMarginPx + chartH - (y - lMin) / lRangeP * chartH).toFloat()

            // Right Y axis mapping
            val rRange = if (rightMax > rightMin) rightMax - rightMin else 1.0
            val rPad = rRange * 0.05
            val rMin = rightMin - rPad
            val rMax = rightMax + rPad
            val rRangeP = rMax - rMin
            fun mapYRight(y: Double) = (topMarginPx + chartH - (y - rMin) / rRangeP * chartH).toFloat()

            // Draw grid and border
            drawRect(
                GridColor,
                topLeft = Offset(leftMarginPx, topMarginPx),
                size = androidx.compose.ui.geometry.Size(chartW, chartH),
                style = Stroke(1f)
            )

            // X axis ticks
            val xTicks = niceTickValues(tMin, tMax, 8)
            for (tick in xTicks) {
                val x = mapX(tick)
                drawLine(GridColor, Offset(x, topMarginPx), Offset(x, topMarginPx + chartH), strokeWidth = 0.5f)
                val label = textMeasurer.measure(formatter.format(tick), tickLabelStyle)
                drawText(label, topLeft = Offset(x - label.size.width / 2f, topMarginPx + chartH + 4f))
            }

            // Left Y axis ticks
            val leftTicks = niceTickValues(lMin, lMax, 6)
            for (tick in leftTicks) {
                val y = mapYLeft(tick)
                drawLine(GridColor, Offset(leftMarginPx, y), Offset(leftMarginPx + chartW, y), strokeWidth = 0.5f)
                val label = textMeasurer.measure(formatter.format(tick), tickLabelStyle)
                drawText(label, topLeft = Offset(leftMarginPx - label.size.width - 4f, y - label.size.height / 2f))
            }

            // Right Y axis ticks
            if (rightUnit != null) {
                val rightTicks = niceTickValues(rMin, rMax, 6)
                for (tick in rightTicks) {
                    val y = mapYRight(tick)
                    val label = textMeasurer.measure(formatter.format(tick), tickLabelStyle)
                    drawText(label, topLeft = Offset(leftMarginPx + chartW + 6f, y - label.size.height / 2f))
                }
            }

            // X axis label
            val xLabel = textMeasurer.measure("Time (s)", tickLabelStyle)
            drawText(xLabel, topLeft = Offset(
                leftMarginPx + chartW / 2f - xLabel.size.width / 2f,
                topMarginPx + chartH + 16f
            ))

            // Draw signals
            for (vd in varDataList) {
                if (vd.values.size < 2 || timestamps.size < 2) continue
                val path = Path()
                val mapY: (Double) -> Float = if (vd.av.isLeft) ::mapYLeft else ::mapYRight

                for (i in vd.values.indices) {
                    if (i >= timestamps.size) break
                    val x = mapX(timestamps[i])
                    val y = mapY(vd.values[i])
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(path, vd.av.color, style = Stroke(width = 1.5f))
            }
        }

        // Time axis label at bottom
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                "Time: ${formatLiveValue(timestamps.firstOrNull() ?: 0.0)}s — ${formatLiveValue(timestamps.lastOrNull() ?: 0.0)}s  (${recentSamples.size} samples)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatLiveValue(value: Double): String {
    if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e9) {
        return value.toLong().toString()
    }
    return String.format("%.2f", value)
}
