package ui.screens.plsol

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import data.contract.Me7LogFileContract
import data.contract.Med17LogFileContract
import data.model.EcuPlatform
import data.parser.me7log.Me7LogParser
import data.parser.med17log.Med17LogParser
import data.preferences.plsol.PlsolPreferences
import data.preferences.platform.EcuPlatformPreference
import domain.model.optimizer.KfurlSolver
import domain.model.plsol.Airflow
import domain.model.plsol.Horsepower
import domain.model.plsol.Plsol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.components.ChartSeries
import ui.components.ColumnParameterField
import ui.components.LineChart
import ui.theme.ChartGreen
import ui.theme.ChartRed
import ui.theme.Primary
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun PlsolScreen(initialTab: Int = 0) {
    var barometricPressure by remember { mutableStateOf(PlsolPreferences.barometricPressure.toString()) }
    var intakeAirTemp by remember { mutableStateOf(PlsolPreferences.intakeAirTemperature.toString()) }
    var kfurl by remember { mutableStateOf(PlsolPreferences.kfurl.toString()) }
    var displacement by remember { mutableStateOf(PlsolPreferences.displacement.toString()) }
    var maxRpm by remember { mutableStateOf(PlsolPreferences.rpm.toString()) }

    var selectedTab by remember { mutableStateOf(initialTab) }
    val tabTitles = listOf("Load", "Airflow", "Power")
    val isMed17 = EcuPlatformPreference.platform == EcuPlatform.MED17

    // Log overlay state
    var loggedPoints by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var progressValue by remember { mutableStateOf(0) }
    var progressMax by remember { mutableStateOf(1) }
    var showProgress by remember { mutableStateOf(false) }
    var logDirName by remember { mutableStateOf("No Directory Selected") }

    val scope = rememberCoroutineScope()

    // Calculate all chart data
    val chartData by remember(barometricPressure, intakeAirTemp, kfurl, displacement, maxRpm) {
        derivedStateOf {
            val pu = barometricPressure.toDoubleOrNull() ?: 1013.0
            val tans = intakeAirTemp.toDoubleOrNull() ?: 20.0
            val kfurlVal = kfurl.toDoubleOrNull() ?: 0.1037
            val dispVal = displacement.toDoubleOrNull() ?: 2.7
            val rpmVal = maxRpm.toIntOrNull() ?: 6000

            val plsol = Plsol(pu, tans, kfurlVal)

            // Pressure/Load chart: absolute and relative (boost) PSI
            val absolutePoints = plsol.points.map { p -> Pair(p.x, p.y * 0.0145038) }
            val relativePoints = plsol.points.map { p -> Pair(p.x, (p.y - pu) * 0.0145038) }

            // Airflow chart
            val airflow = Airflow(plsol.points, dispVal, rpmVal)
            val airflowPoints = airflow.points.map { p -> Pair(p.x, p.y) }

            // Horsepower chart
            val horsepower = Horsepower(airflow.points)
            val horsepowerPoints = horsepower.points.map { p -> Pair(p.x, p.y) }

            PlsolChartData(absolutePoints, relativePoints, airflowPoints, horsepowerPoints)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Log loading row
        Surface(
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    val dialog = FileDialog(Frame(), "Select Log Directory", FileDialog.LOAD)
                    System.setProperty("apple.awt.fileDialogForDirectories", "true")
                    val lastDir = PlsolPreferences.lastDirectory
                    if (lastDir.isNotEmpty()) dialog.directory = lastDir
                    dialog.isVisible = true
                    System.setProperty("apple.awt.fileDialogForDirectories", "false")

                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        val selectedDir = File(dir, file)
                        PlsolPreferences.lastDirectory = selectedDir.parent ?: dir
                        logDirName = selectedDir.path
                        showProgress = true
                        progressValue = 0
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val loadValues: List<Double>
                                val pressureValues: List<Double>
                                val baroValues: List<Double>
                                val throttleValues: List<Double>
                                var fupsrlsValues: List<Double> = emptyList()
                                var intakeTempValues: List<Double> = emptyList()

                                if (isMed17) {
                                    val parser = Med17LogParser()
                                    val values = parser.parseLogDirectory(
                                        Med17LogParser.LogType.PLSOL, selectedDir
                                    ) { value, max ->
                                        progressValue = value
                                        progressMax = max
                                        showProgress = value < max - 1
                                    }
                                    loadValues = values[Med17LogFileContract.Header.ENGINE_LOAD_HEADER] ?: emptyList()
                                    pressureValues = values[Med17LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER] ?: emptyList()
                                    baroValues = values[Med17LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER] ?: emptyList()
                                    throttleValues = values[Med17LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER] ?: emptyList()
                                    fupsrlsValues = values[Med17LogFileContract.Header.FUPSRLS_HEADER] ?: emptyList()
                                    intakeTempValues = values[Med17LogFileContract.Header.INTAKE_TEMPERATURE_HEADER] ?: emptyList()
                                } else {
                                    val parser = Me7LogParser()
                                    val values = parser.parseLogDirectory(
                                        Me7LogParser.LogType.PLSOL, selectedDir
                                    ) { value, max ->
                                        progressValue = value
                                        progressMax = max
                                        showProgress = value < max - 1
                                    }
                                    loadValues = values[Me7LogFileContract.Header.ENGINE_LOAD_HEADER] ?: emptyList()
                                    pressureValues = values[Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER] ?: emptyList()
                                    baroValues = values[Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER] ?: emptyList()
                                    throttleValues = values[Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER] ?: emptyList()
                                }

                                // WOT filter: keep rows where throttle > 90%
                                val wotPoints = mutableListOf<Pair<Double, Double>>()
                                val wotLoads = mutableListOf<Double>()
                                val wotPressures = mutableListOf<Double>()
                                val wotBaros = mutableListOf<Double>()
                                val wotFupsrls = mutableListOf<Double>()
                                val wotIntakeTemps = mutableListOf<Double>()
                                for (i in loadValues.indices) {
                                    if (i < throttleValues.size && throttleValues[i] > 90.0) {
                                        if (i < pressureValues.size) {
                                            val pressurePsi = pressureValues[i] * 0.0145038
                                            wotPoints.add(Pair(loadValues[i], pressurePsi))
                                            wotLoads.add(loadValues[i])
                                            wotPressures.add(pressureValues[i])
                                        }
                                        if (i < baroValues.size) {
                                            wotBaros.add(baroValues[i])
                                        }
                                        if (i < fupsrlsValues.size) {
                                            wotFupsrls.add(fupsrlsValues[i])
                                        }
                                        if (i < intakeTempValues.size) {
                                            wotIntakeTemps.add(intakeTempValues[i])
                                        }
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    loggedPoints = wotPoints
                                    showProgress = false

                                    // Auto-fill baro from mean of logged values
                                    if (wotBaros.isNotEmpty()) {
                                        val meanBaro = wotBaros.average()
                                        barometricPressure = "%.1f".format(meanBaro)
                                    }

                                    // Auto-fill intake temp from log data
                                    if (wotIntakeTemps.isNotEmpty()) {
                                        val meanTemp = wotIntakeTemps.average()
                                        intakeAirTemp = "%.1f".format(meanTemp)
                                    }

                                    // Auto-fill kfurl from log data
                                    if (isMed17 && wotFupsrls.isNotEmpty()) {
                                        val meanFupsrls = wotFupsrls.average()
                                        kfurl = "%.4f".format(meanFupsrls)
                                    } else if (!isMed17 && wotLoads.isNotEmpty()) {
                                        val solvedKfurl = KfurlSolver.solveFromActuals(
                                            wotLoads, wotPressures, wotBaros
                                        )
                                        kfurl = "%.4f".format(solvedKfurl)
                                    }
                                }
                            }
                        }
                    }
                }) {
                    Text(if (isMed17) "Load DS1 Logs" else "Load ME7 Logs")
                }
                if (showProgress) {
                    LinearProgressIndicator(
                        progress = { if (progressMax > 0) progressValue.toFloat() / progressMax.toFloat() else 0f },
                        modifier = Modifier.width(200.dp).height(6.dp)
                    )
                }
                Text(
                    text = logDirName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Charts area with tabs
        Column(modifier = Modifier.weight(1f)) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                when (selectedTab) {
                    0 -> {
                        val series = mutableListOf(
                            ChartSeries(
                                name = "Requested Absolute",
                                points = chartData.absolutePoints,
                                color = Primary
                            ),
                            ChartSeries(
                                name = "Requested Relative (Boost)",
                                points = chartData.relativePoints,
                                color = ChartRed
                            )
                        )
                        if (loggedPoints.isNotEmpty()) {
                            series.add(
                                ChartSeries(
                                    name = "Logged (WOT)",
                                    points = loggedPoints,
                                    color = ChartGreen,
                                    showLine = false,
                                    showPoints = true
                                )
                            )
                        }
                        LineChart(
                            series = series,
                            title = "PLSOL",
                            xAxisLabel = "Requested Load",
                            yAxisLabel = "PSI"
                        )
                    }
                    1 -> {
                        LineChart(
                            series = listOf(
                                ChartSeries(
                                    name = "Airflow",
                                    points = chartData.airflowPoints,
                                    color = Primary
                                )
                            ),
                            title = "Airflow",
                            xAxisLabel = "Requested Load",
                            yAxisLabel = "g/sec"
                        )
                    }
                    2 -> {
                        LineChart(
                            series = listOf(
                                ChartSeries(
                                    name = "Horsepower",
                                    points = chartData.horsepowerPoints,
                                    color = Primary
                                )
                            ),
                            title = "Horsepower",
                            xAxisLabel = "Requested Load",
                            yAxisLabel = "bhp"
                        )
                    }
                }
            }
        }

        // Constants panel at bottom
        Surface(
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ColumnParameterField(
                    label = "Barometric Pressure",
                    value = barometricPressure,
                    unit = "mbar",
                    tooltip = "Ambient atmospheric pressure. Standard sea level = 1013 mbar. Used as the base reference for all boost/load calculations.",
                    onValueChange = {
                        barometricPressure = it
                        it.toDoubleOrNull()?.let { v -> PlsolPreferences.barometricPressure = v }
                    },
                    modifier = Modifier.weight(1f)
                )
                ColumnParameterField(
                    label = "Intake Air Temperature",
                    value = intakeAirTemp,
                    unit = "C",
                    tooltip = "Charge air temperature entering the manifold (°C). Affects air density and VE calculations. Typical ambient = 20°C.",
                    onValueChange = {
                        intakeAirTemp = it
                        it.toDoubleOrNull()?.let { v -> PlsolPreferences.intakeAirTemperature = v }
                    },
                    modifier = Modifier.weight(1f)
                )
                ColumnParameterField(
                    label = if (isMed17) "fupsrl_w (≈KFURL)" else "KFURL",
                    value = kfurl,
                    unit = "%",
                    tooltip = if (isMed17)
                        "MED17 does not have a static KFURL map. The pressure→load conversion factor (fupsrl_w) is computed and adapted at runtime. Enter a representative value from your log data for approximate PLSOL predictions."
                    else
                        "Volumetric Efficiency slope constant from the KFURL map. Scales how much load (%) the ECU calculates per hPa of manifold pressure. Derived from engine displacement and measured VE.",
                    onValueChange = {
                        kfurl = it
                        it.toDoubleOrNull()?.let { v -> PlsolPreferences.kfurl = v }
                    },
                    modifier = Modifier.weight(1f)
                )
                ColumnParameterField(
                    label = "Displacement",
                    value = displacement,
                    unit = "L",
                    tooltip = "Engine total swept displacement in litres. Used to compute theoretical airflow and horsepower estimates.",
                    onValueChange = {
                        displacement = it
                        it.toDoubleOrNull()?.let { v -> PlsolPreferences.displacement = v }
                    },
                    modifier = Modifier.weight(1f)
                )
                ColumnParameterField(
                    label = "RPM",
                    value = maxRpm,
                    unit = "",
                    tooltip = "Maximum RPM used as the upper limit for the airflow and horsepower chart simulations.",
                    onValueChange = {
                        maxRpm = it
                        it.toIntOrNull()?.let { v -> PlsolPreferences.rpm = v }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ColumnParameterField used here is defined in ui.components.InfoTooltip

private data class PlsolChartData(
    val absolutePoints: List<Pair<Double, Double>>,
    val relativePoints: List<Pair<Double, Double>>,
    val airflowPoints: List<Pair<Double, Double>>,
    val horsepowerPoints: List<Pair<Double, Double>>
)
