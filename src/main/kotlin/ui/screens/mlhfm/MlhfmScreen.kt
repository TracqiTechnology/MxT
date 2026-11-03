package ui.screens.mlhfm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import data.parser.bin.BinParser
import data.preferences.mlhfm.MlhfmPreferences
import domain.math.map.Map3d
import domain.model.mlhfm.MlhfmCurve
import ui.components.ChartSeries
import ui.components.LineChart
import ui.components.MapTable
import ui.theme.Primary

@Composable
fun MlhfmScreen() {
    val mapList by BinParser.mapList.collectAsState()

    // Re-evaluate selected map whenever mapList changes or preferences emit a change
    var mapChangeVersion by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        MlhfmPreferences.mapChanged.collect {
            mapChangeVersion++
        }
    }

    val selectedMap: Pair<String, Map3d>? = remember(mapList, mapChangeVersion) {
        val result = MlhfmPreferences.getSelectedMap()
        if (result != null) {
            val tableName = result.first.tableName + " " + result.first.tableDescription
            tableName to result.second
        } else null
    }

    val curve = remember(selectedMap) {
        runCatching { selectedMap?.second?.let(MlhfmCurve::toColumn) }
    }
    val chartSeries = remember(curve) {
        val map = curve.getOrNull() ?: return@remember emptyList<ChartSeries>()
        val voltage = map.yAxis
        val kghr = map.zAxis

        if (voltage.isEmpty() || kghr.isEmpty()) return@remember emptyList<ChartSeries>()

        val points = voltage.indices.mapNotNull { i ->
            if (i < kghr.size && kghr[i].isNotEmpty()) {
                Pair(voltage[i], kghr[i][0])
            } else null
        }

        listOf(
            ChartSeries(
                name = "MLHFM",
                points = points,
                color = Primary
            )
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Main content: chart + map table side by side
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            // Chart
            Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
                LineChart(
                    series = chartSeries,
                    title = "Base MLHFM",
                    xAxisLabel = "Voltage",
                    yAxisLabel = "kg/hr"
                )
            }

            // Map table on the right
            if (selectedMap != null) {
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .fillMaxHeight()
                        .padding(8.dp)
                ) {
                    MapTable(
                        map = selectedMap.second,
                        editable = false
                    )
                }
            }
        }

        // Status bar at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = curve.exceptionOrNull()?.message ?: selectedMap?.first ?: "No Definition Selected",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
