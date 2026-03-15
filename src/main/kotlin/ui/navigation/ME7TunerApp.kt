package ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import data.model.EcuPlatform
import data.preferences.bin.BinFilePreferences
import data.preferences.xdf.XdfFilePreferences
import ui.screens.configuration.ConfigurationScreen
import ui.screens.optimizer.OptimizerScreen

@Composable
fun ME7TunerApp(navState: NavigationState = remember { NavigationState() }) {

    val xdfFile by XdfFilePreferences.file.collectAsState()
    val binFile by BinFilePreferences.file.collectAsState()

    val isConfigured = xdfFile.exists() && xdfFile.isFile &&
        binFile.exists() && binFile.isFile

    Surface(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                header = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        Image(
                            painter = painterResource("pistons_0.png"),
                            contentDescription = "TracQi Logo",
                            modifier = Modifier.size(40.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primaryContainer)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "TracQi",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primaryContainer
                        )
                        Text(
                            text = when (navState.ecuPlatform) {
                                EcuPlatform.ME7 -> "ME7Tuner"
                                EcuPlatform.MED9 -> "MED9Tuner"
                                EcuPlatform.MED17 -> "MED17Tuner"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // ECU Platform toggle
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 4.dp)) {
                            EcuPlatform.entries.forEachIndexed { index, platform ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = EcuPlatform.entries.size
                                    ),
                                    onClick = { navState.selectPlatform(platform) },
                                    selected = navState.ecuPlatform == platform
                                ) {
                                    Text(
                                        text = platform.shortName,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                RailDestination.entries.forEach { destination ->
                    val enabled = when (destination) {
                        RailDestination.CONFIGURATION -> true
                        RailDestination.TOOLS -> true
                        else -> isConfigured
                    }

                    NavigationRailItem(
                        selected = navState.railDestination == destination,
                        onClick = {
                            if (enabled) navState.navigateTo(destination)
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) },
                        enabled = enabled
                    )
                }
            }

            VerticalDivider()

            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when (navState.railDestination) {
                    RailDestination.CONFIGURATION -> {
                        ConfigurationScreen(
                            trailingContent = {
                                WorkflowGuidanceCards(
                                    onStartCalibration = { navState.navigateToCalibration() },
                                    onStartOptimizer = { navState.navigateToOptimizer() }
                                )
                            }
                        )
                    }
                    RailDestination.CALIBRATION -> {
                        if (isConfigured) {
                            CalibrationContent(navState = navState)
                        } else {
                            ConfigurationRequiredPlaceholder()
                        }
                    }
                    RailDestination.TOOLS -> {
                        ToolsContent(navState = navState)
                    }
                    RailDestination.OPTIMIZER -> {
                        if (isConfigured) {
                            OptimizerScreen()
                        } else {
                            ConfigurationRequiredPlaceholder()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationRequiredPlaceholder() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Configuration Required",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Load an XDF and BIN file in Configuration to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
