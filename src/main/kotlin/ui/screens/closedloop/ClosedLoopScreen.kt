package ui.screens.closedloop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import data.contract.Me7LogFileContract
import data.parser.bin.BinParser
import data.parser.me7log.ClosedLoopLogParser
import data.parser.xdf.TableDefinition
import data.preferences.bin.BinFilePreferences
import data.preferences.closedloopfueling.ClosedLoopFuelingLogPreferences
import data.preferences.mlhfm.MlhfmPreferences
import data.writer.BinWriter
import domain.derivative.Derivative
import domain.math.map.Map3d
import domain.model.closedloopfueling.ClosedLoopFuelingCorrection
import domain.model.closedloopfueling.ClosedLoopFuelingCorrectionManager
import domain.model.mlhfm.MlhfmFitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.commons.math3.stat.descriptive.moment.Mean
import ui.components.*
import ui.components.ParameterField
import ui.theme.ChartBlue
import ui.theme.ChartGreen
import ui.theme.ChartMagenta
import ui.theme.ChartRed
import ui.theme.Primary
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

private enum class WriteStatus { Idle, Success, Error }

private fun findMlhfmMap(mapList: List<Pair<TableDefinition, Map3d>>): Pair<TableDefinition, Map3d>? {
    val selected = MlhfmPreferences.getSelectedMap()
    return if (selected != null) {
        mapList.find { it.first.tableName == selected.first.tableName }
    } else null
}

@Composable
fun ClosedLoopScreen(
    initialTab: Int = 0,
    initialCorrectionSubTab: Int = 0,
    autoFitDegree: Int? = null
) {
    val mapList by BinParser.mapList.collectAsState()
    val mlhfmPair = remember(mapList) { findMlhfmMap(mapList) }

    var me7LogMap by remember { mutableStateOf<Map<Me7LogFileContract.Header, List<Double>>?>(null) }
    var correction by remember { mutableStateOf<ClosedLoopFuelingCorrection?>(null) }

    LaunchedEffect(Unit) {
        ClosedLoopLogParser.logs.collect { logs ->
            me7LogMap = logs
            val pair = findMlhfmMap(BinParser.mapList.value)
            if (pair != null && logs.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    val manager = ClosedLoopFuelingCorrectionManager(
                        ClosedLoopFuelingLogPreferences.minThrottleAngle,
                        ClosedLoopFuelingLogPreferences.minRpm,
                        ClosedLoopFuelingLogPreferences.maxDerivative
                    )
                    manager.correct(logs, pair.second)
                    correction = manager.closedLoopMlhfmCorrection
                }
            }
        }
    }

    val hasLogs = me7LogMap != null && me7LogMap!!.isNotEmpty()
    val hasCorrection = correction != null
    val hasMap = mlhfmPair != null

    // Write prerequisites
    val binFile by BinFilePreferences.file.collectAsState()
    val binLoaded = binFile.exists() && binFile.isFile
    val mlhfmMapConfigured = mlhfmPair != null
    val mlhfmMapName = mlhfmPair?.first?.tableName

    var selectedTab by remember { mutableStateOf(initialTab) }
    val tabTitles = listOf("ME7 Logs", "MLHFM", "Correction")

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    val enabled = when (index) {
                        0 -> hasMap
                        1 -> hasMap && hasCorrection
                        2 -> hasMap && hasLogs && hasCorrection
                        else -> true
                    }
                    Tab(
                        selected = selectedTab == index,
                        onClick = { if (enabled) selectedTab = index },
                        enabled = enabled,
                        text = {
                            Text(
                                title,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    )
                }
            }

            IconButton(
                onClick = {
                    try {
                        Desktop.getDesktop().browse(URI("https://github.com/TracqiTechnology/MxT#closed-loop-mlhfm"))
                    } catch (_: Exception) { }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Help",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when (selectedTab) {
            0 -> ClosedLoopLogsTab(
                me7LogMap = me7LogMap,
                mlhfm = mlhfmPair?.second,
                mlhfmMapConfigured = mlhfmMapConfigured,
                mlhfmMapName = mlhfmMapName,
                onLogsLoaded = { selectedTab = if (correction != null) 1 else 0 }
            )
            1 -> ClosedLoopMlhfmTab(
                correction = correction,
                binLoaded = binLoaded,
                binFileName = if (binLoaded) binFile.name else null,
                mlhfmMapConfigured = mlhfmMapConfigured,
                mlhfmMapName = mlhfmMapName
            )
            2 -> ClosedLoopCorrectionTab(
                correction = correction,
                onCorrectionUpdated = { correction = it },
                initialSubTab = initialCorrectionSubTab,
                autoFitDegree = autoFitDegree,
                binLoaded = binLoaded,
                binFileName = if (binLoaded) binFile.name else null,
                mlhfmMapConfigured = mlhfmMapConfigured,
                mlhfmMapName = mlhfmMapName
            )
        }
    }
}

// -- ME7 Logs Tab --

@Composable
private fun ClosedLoopLogsTab(
    me7LogMap: Map<Me7LogFileContract.Header, List<Double>>?,
    mlhfm: Map3d?,
    mlhfmMapConfigured: Boolean,
    mlhfmMapName: String?,
    onLogsLoaded: () -> Unit
) {
    var showFilterDialog by remember { mutableStateOf(false) }
    val loadProgress by ClosedLoopLogParser.progress.collectAsState()

    val chartSeries = remember(me7LogMap, mlhfm) {
        if (me7LogMap != null && mlhfm != null) {
            val dtMap = Derivative.getMlfhm(me7LogMap, mlhfm)
            val maxFilterVoltage = ClosedLoopFuelingLogPreferences.maxDerivative
            val excludedPoints = mutableListOf<Pair<Double, Double>>()
            val includedPoints = mutableListOf<Pair<Double, Double>>()

            for (voltage in mlhfm.yAxis) {
                val values = dtMap[voltage] ?: continue
                for (value in values) {
                    if (value > maxFilterVoltage) {
                        excludedPoints.add(voltage to value)
                    } else {
                        includedPoints.add(voltage to value)
                    }
                }
            }
            listOf(
                ChartSeries("Excluded Sample", excludedPoints, ChartRed, showLine = false, showPoints = true),
                ChartSeries("Included Sample", includedPoints, ChartGreen, showLine = false, showPoints = true)
            )
        } else emptyList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ScatterPlot(
                series = chartSeries,
                title = "Derivative",
                xAxisLabel = "MAF Voltage",
                yAxisLabel = "dMAFv/dt"
            )
        }

        // Action bar with prerequisite
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                PrerequisiteRow(
                    label = "MLHFM map",
                    detail = if (mlhfmMapConfigured) mlhfmMapName!! else "Not configured",
                    met = mlhfmMapConfigured
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { showFilterDialog = true }) {
                        Text("Configure Filter")
                    }

                    Spacer(Modifier.width(24.dp))

                    Button(
                        onClick = {
                            val dialog = FileDialog(Frame(), "Select Log Directory", FileDialog.LOAD)
                            System.setProperty("apple.awt.fileDialogForDirectories", "true")
                            val lastDir = ClosedLoopFuelingLogPreferences.lastDirectory
                            if (lastDir.isNotEmpty()) dialog.directory = lastDir
                            dialog.isVisible = true
                            System.setProperty("apple.awt.fileDialogForDirectories", "false")
                            val dir = dialog.directory
                            val file = dialog.file
                            if (dir != null && file != null) {
                                val selectedDir = File(dir, file)
                                ClosedLoopFuelingLogPreferences.lastDirectory = selectedDir.parent ?: dir
                                ClosedLoopLogParser.loadDirectory(selectedDir)
                                onLogsLoaded()
                            }
                        },
                        enabled = loadProgress == null && mlhfmMapConfigured
                    ) {
                        Text("Load Logs")
                    }

                    if (loadProgress != null) {
                        Spacer(Modifier.width(24.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val progress = loadProgress!!
                            if (progress.total > 0) {
                                Text(
                                    text = "Loading log ${progress.loaded} of ${progress.total}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                LinearProgressIndicator(
                                    progress = { progress.loaded.toFloat() / progress.total.toFloat() },
                                    modifier = Modifier.width(200.dp)
                                )
                            } else {
                                Text(
                                    text = "Preparing...",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                LinearProgressIndicator(modifier = Modifier.width(200.dp))
                            }
                        }
                    }
                }

                if (!mlhfmMapConfigured) {
                    Text(
                        text = "Configure the MLHFM map definition in the Configuration screen to load logs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showFilterDialog) {
        ClosedLoopFilterDialog(
            onDismiss = { showFilterDialog = false },
            onApply = { minTps, minRpm, maxDt ->
                ClosedLoopFuelingLogPreferences.minThrottleAngle = minTps
                ClosedLoopFuelingLogPreferences.minRpm = minRpm
                ClosedLoopFuelingLogPreferences.maxDerivative = maxDt
                showFilterDialog = false
            }
        )
    }
}

@Composable
private fun ClosedLoopFilterDialog(
    onDismiss: () -> Unit,
    onApply: (Double, Double, Double) -> Unit
) {
    var minTps by remember { mutableStateOf(ClosedLoopFuelingLogPreferences.minThrottleAngle.toString()) }
    var minRpm by remember { mutableStateOf(ClosedLoopFuelingLogPreferences.minRpm.toString()) }
    var maxDt by remember { mutableStateOf(ClosedLoopFuelingLogPreferences.maxDerivative.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure Closed Loop Filter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ParameterField(
                    value = minTps,
                    onValueChange = { minTps = it },
                    label = "Minimum Throttle Angle",
                    tooltip = "Minimum throttle plate opening angle (%) to include a log sample. Filters out idle and light-load data — set to your WOT or part-throttle threshold.",
                    modifier = Modifier.fillMaxWidth()
                )
                ParameterField(
                    value = minRpm,
                    onValueChange = { minRpm = it },
                    label = "Minimum RPM",
                    tooltip = "Minimum engine speed (RPM) to include a sample. Excludes cranking, idle, and low-speed data that would skew the MLHFM correction.",
                    modifier = Modifier.fillMaxWidth()
                )
                ParameterField(
                    value = maxDt,
                    onValueChange = { maxDt = it },
                    label = "Max dMAFv/dt",
                    tooltip = "Maximum rate of change of MAF sensor voltage per sample interval. Filters out transient spikes during rapid throttle movements that produce unreliable AFR readings.",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tps = minTps.toDoubleOrNull() ?: return@TextButton
                val rpm = minRpm.toDoubleOrNull() ?: return@TextButton
                val dt = maxDt.toDoubleOrNull() ?: return@TextButton
                onApply(tps, rpm, dt)
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// -- Correction Tab --

@Composable
private fun ClosedLoopCorrectionTab(
    correction: ClosedLoopFuelingCorrection?,
    onCorrectionUpdated: (ClosedLoopFuelingCorrection) -> Unit,
    initialSubTab: Int = 0,
    autoFitDegree: Int? = null,
    binLoaded: Boolean,
    binFileName: String?,
    mlhfmMapConfigured: Boolean,
    mlhfmMapName: String?
) {
    if (correction == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No correction data available.")
        }
        return
    }

    var polynomialDegree by remember { mutableStateOf(autoFitDegree ?: 6) }
    var correctionSubTab by remember { mutableStateOf(initialSubTab) }

    // Auto-fit for screenshot harness
    LaunchedEffect(autoFitDegree, correction) {
        if (autoFitDegree != null && correction.correctedMlhfm.yAxis.isNotEmpty()) {
            val fitMlhfm = MlhfmFitter.fitMlhfm(correction.correctedMlhfm, autoFitDegree)
            onCorrectionUpdated(correction.copy(fitMlhfm = fitMlhfm))
        }
    }
    val correctionSubTabs = listOf("AFR Correction %", "dMAFv/dt", "MLHFM")

    var showWriteConfirmation by remember { mutableStateOf(false) }
    var writeStatus by remember { mutableStateOf(WriteStatus.Idle) }

    LaunchedEffect(writeStatus) {
        if (writeStatus != WriteStatus.Idle) {
            delay(3000)
            writeStatus = WriteStatus.Idle
        }
    }

    val canWrite = binLoaded && mlhfmMapConfigured

    Column(modifier = Modifier.fillMaxSize()) {
        // Sub-tab row
        PrimaryTabRow(selectedTabIndex = correctionSubTab) {
            correctionSubTabs.forEachIndexed { index, title ->
                Tab(
                    selected = correctionSubTab == index,
                    onClick = { correctionSubTab = index },
                    text = { Text(title, style = MaterialTheme.typography.bodySmall) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (correctionSubTab) {
                0 -> AfrCorrectionChart(correction)
                1 -> DerivativeChart(correction)
                2 -> CorrectionMlhfmChart(correction)
            }
        }

        // Polynomial fit + write controls
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (correctionSubTab == 2) {
                    Text(
                        text = "Polynomial Fit",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Polynomial Degree: ", style = MaterialTheme.typography.bodyMedium)
                        var degreeText by remember { mutableStateOf(polynomialDegree.toString()) }
                        ParameterField(
                            value = degreeText,
                            onValueChange = {
                                degreeText = it
                                it.toIntOrNull()?.let { v -> polynomialDegree = v }
                            },
                            label = "Degree",
                            tooltip = "Degree of the polynomial curve fit applied to the MLHFM correction. Higher values fit more closely but may overfit noise. 5–7 is typical for a smooth result.",
                            modifier = Modifier.width(100.dp).height(56.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            val fitMlhfm = MlhfmFitter.fitMlhfm(correction.correctedMlhfm, polynomialDegree)
                            onCorrectionUpdated(
                                correction.copy(fitMlhfm = fitMlhfm)
                            )
                        }) {
                            Text("Fit MLHFM")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                }

                Text(
                    text = "Write to Binary",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                PrerequisiteRow(
                    label = "BIN file",
                    detail = if (binLoaded) binFileName!! else "Not loaded",
                    met = binLoaded
                )

                PrerequisiteRow(
                    label = "MLHFM map",
                    detail = if (mlhfmMapConfigured) mlhfmMapName!! else "Not configured",
                    met = mlhfmMapConfigured
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { showWriteConfirmation = true },
                        enabled = canWrite
                    ) {
                        Text("Write MLHFM")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    AnimatedVisibility(visible = writeStatus != WriteStatus.Idle) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (writeStatus == WriteStatus.Success) Icons.Default.Check else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (writeStatus == WriteStatus.Success) "Written successfully" else "Write failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (!canWrite) {
                    val message = when {
                        !binLoaded && !mlhfmMapConfigured -> "Load a BIN file and configure the MLHFM map in the Configuration screen."
                        !binLoaded -> "Load a BIN file to write."
                        else -> "Configure the MLHFM map definition in the Configuration screen."
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showWriteConfirmation) {
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write MLHFM") },
            text = { Text("Are you sure you want to write the corrected MLHFM to the binary?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWriteConfirmation = false
                        val pair = MlhfmPreferences.getSelectedMap()
                        if (pair != null) {
                            try {
                                val mlhfmToWrite = correction.fitMlhfm.let {
                                    if (it.yAxis.isNotEmpty()) it else correction.correctedMlhfm
                                }
                                BinWriter.write(BinFilePreferences.file.value, pair.first, mlhfmToWrite)
                                writeStatus = WriteStatus.Success
                            } catch (e: Exception) {
                                writeStatus = WriteStatus.Error
                            }
                        }
                    }
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWriteConfirmation = false }) {
                    Text("No")
                }
            }
        )
    }
}

@Composable
private fun AfrCorrectionChart(correction: ClosedLoopFuelingCorrection) {
    val mean = remember { Mean() }

    val rawPoints = remember(correction) {
        correction.correctionsAfrMap.flatMap { (voltage, values) ->
            values.map { voltage to it }
        }
    }
    val meanLine = remember(correction) {
        correction.meanAfrMap.mapNotNull { (voltage, value) ->
            if (value.isNaN()) null else voltage to value
        }
    }
    val modeLine = remember(correction) {
        correction.modeAfrMap.mapNotNull { (voltage, modes) ->
            if (modes.isEmpty()) null
            else {
                val value = mean.evaluate(modes, 0, modes.size)
                if (value.isNaN()) null else voltage to value
            }
        }
    }
    val correctedLine = remember(correction) {
        correction.correctedAfrMap.map { (voltage, value) -> voltage to value }
    }

    val series = listOf(
        ChartSeries("AFR Corrections %", rawPoints, Primary, showLine = false, showPoints = true),
        ChartSeries("Final AFR Correction %", correctedLine, ChartRed, showLine = true, showPoints = false),
        ChartSeries("Mean AFR Correction %", meanLine, ChartGreen, showLine = true, showPoints = false),
        ChartSeries("Mode AFR Correction %", modeLine, ChartMagenta, showLine = true, showPoints = false)
    )

    ScatterPlot(
        series = series,
        title = "AFR Correction %",
        xAxisLabel = "Voltage",
        yAxisLabel = "Correction %"
    )
}

@Composable
private fun DerivativeChart(correction: ClosedLoopFuelingCorrection) {
    val points = remember(correction) {
        correction.filteredVoltageDt.flatMap { (voltage, values) ->
            values.map { voltage to it }
        }
    }
    val series = listOf(
        ChartSeries("dMAFv/dt", points, Primary, showLine = false, showPoints = true)
    )

    ScatterPlot(
        series = series,
        title = "Derivative",
        xAxisLabel = "Voltage",
        yAxisLabel = "dMAFv/dt"
    )
}

@Composable
private fun CorrectionMlhfmChart(correction: ClosedLoopFuelingCorrection) {
    val inputPoints = remember(correction) {
        correction.inputMlhfm.yAxis.indices.map { i ->
            correction.inputMlhfm.yAxis[i] to correction.inputMlhfm.zAxis[i][0]
        }
    }
    val correctedPoints = remember(correction) {
        val mlhfm = correction.fitMlhfm
        if (mlhfm.yAxis.isNotEmpty()) {
            mlhfm.yAxis.indices.map { i ->
                mlhfm.yAxis[i] to mlhfm.zAxis[i][0]
            }
        } else {
            correction.correctedMlhfm.yAxis.indices.map { i ->
                correction.correctedMlhfm.yAxis[i] to correction.correctedMlhfm.zAxis[i][0]
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            LineChart(
                series = listOf(
                    ChartSeries("MLHFM", inputPoints, Primary, showLine = true, showPoints = false),
                    ChartSeries("Corrected MLHFM", correctedPoints, ChartRed, showLine = true, showPoints = false)
                ),
                title = "Corrected MLHFM",
                xAxisLabel = "Voltage",
                yAxisLabel = "kg/hr"
            )
        }

        // MapTable for corrected values
        val displayMlhfm = correction.fitMlhfm.let {
            if (it.yAxis.isNotEmpty()) it else correction.correctedMlhfm
        }
        if (displayMlhfm.zAxis.isNotEmpty()) {
            Box(modifier = Modifier.width(140.dp).fillMaxHeight()) {
                MapTable(map = displayMlhfm, editable = false)
            }
        }
    }
}

// -- MLHFM Tab --

@Composable
private fun ClosedLoopMlhfmTab(
    correction: ClosedLoopFuelingCorrection?,
    binLoaded: Boolean,
    binFileName: String?,
    mlhfmMapConfigured: Boolean,
    mlhfmMapName: String?
) {
    if (correction == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No correction data available.")
        }
        return
    }

    var showWriteConfirmation by remember { mutableStateOf(false) }
    var writeStatus by remember { mutableStateOf(WriteStatus.Idle) }

    LaunchedEffect(writeStatus) {
        if (writeStatus != WriteStatus.Idle) {
            delay(3000)
            writeStatus = WriteStatus.Idle
        }
    }

    val canWrite = binLoaded && mlhfmMapConfigured

    val inputPoints = remember(correction) {
        correction.inputMlhfm.yAxis.indices.map { i ->
            correction.inputMlhfm.yAxis[i] to correction.inputMlhfm.zAxis[i][0]
        }
    }
    val correctedPoints = remember(correction) {
        correction.correctedMlhfm.yAxis.indices.map { i ->
            correction.correctedMlhfm.yAxis[i] to correction.correctedMlhfm.zAxis[i][0]
        }
    }
    val fitPoints = remember(correction) {
        if (correction.fitMlhfm.yAxis.isNotEmpty()) {
            correction.fitMlhfm.yAxis.indices.map { i ->
                correction.fitMlhfm.yAxis[i] to correction.fitMlhfm.zAxis[i][0]
            }
        } else emptyList()
    }

    val allSeries = remember(correction) {
        buildList {
            add(ChartSeries("Original MLHFM", inputPoints, Primary, showLine = true, showPoints = false))
            add(ChartSeries("Corrected MLHFM", correctedPoints, ChartRed, showLine = true, showPoints = false))
            if (fitPoints.isNotEmpty()) {
                add(ChartSeries("Fitted MLHFM", fitPoints, ChartBlue, showLine = true, showPoints = false))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                LineChart(
                    series = allSeries,
                    title = "MLHFM",
                    xAxisLabel = "Voltage",
                    yAxisLabel = "kg/hr"
                )
            }

            val displayMlhfm = correction.fitMlhfm.let {
                if (it.yAxis.isNotEmpty()) it else correction.correctedMlhfm
            }
            if (displayMlhfm.zAxis.isNotEmpty()) {
                Box(modifier = Modifier.width(140.dp).fillMaxHeight()) {
                    MapTable(map = displayMlhfm, editable = false)
                }
            }
        }

        // Write to Binary section
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Write to Binary",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                PrerequisiteRow(
                    label = "BIN file",
                    detail = if (binLoaded) binFileName!! else "Not loaded",
                    met = binLoaded
                )

                PrerequisiteRow(
                    label = "MLHFM map",
                    detail = if (mlhfmMapConfigured) mlhfmMapName!! else "Not configured",
                    met = mlhfmMapConfigured
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { showWriteConfirmation = true },
                        enabled = canWrite
                    ) {
                        Text("Write MLHFM")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    AnimatedVisibility(visible = writeStatus != WriteStatus.Idle) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (writeStatus == WriteStatus.Success) Icons.Default.Check else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (writeStatus == WriteStatus.Success) "Written successfully" else "Write failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (writeStatus == WriteStatus.Success) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (!canWrite) {
                    val message = when {
                        !binLoaded && !mlhfmMapConfigured -> "Load a BIN file and configure the MLHFM map in the Configuration screen."
                        !binLoaded -> "Load a BIN file to write."
                        else -> "Configure the MLHFM map definition in the Configuration screen."
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showWriteConfirmation) {
        AlertDialog(
            onDismissRequest = { showWriteConfirmation = false },
            title = { Text("Write MLHFM") },
            text = { Text("Are you sure you want to write MLHFM to the binary?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWriteConfirmation = false
                        val pair = MlhfmPreferences.getSelectedMap()
                        if (pair != null) {
                            try {
                                val mlhfmToWrite = correction.fitMlhfm.let {
                                    if (it.yAxis.isNotEmpty()) it else correction.correctedMlhfm
                                }
                                BinWriter.write(BinFilePreferences.file.value, pair.first, mlhfmToWrite)
                                writeStatus = WriteStatus.Success
                            } catch (e: Exception) {
                                writeStatus = WriteStatus.Error
                            }
                        }
                    }
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWriteConfirmation = false }) {
                    Text("No")
                }
            }
        )
    }
}

// -- Shared Private Composables --

@Composable
private fun PrerequisiteRow(label: String, detail: String, met: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (met) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = if (met) "Ready" else "Not ready",
            tint = if (met) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(80.dp)
        )

        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
