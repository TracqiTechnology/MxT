package ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import data.model.EcuPlatform
import data.model.StabilityLevel
import data.preferences.bin.BinFilePreferences
import data.preferences.xdf.XdfFilePreferences
import ui.components.StabilityBadge
import ui.screens.configuration.ConfigurationScreen
import ui.screens.optimizer.OptimizerScreen

@Composable
fun MxTApp(navState: NavigationState = remember { NavigationState() }) {

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
                                EcuPlatform.ME7 -> "MxT"
                                EcuPlatform.MED9 -> "MED9Tuner"
                                EcuPlatform.MED17 -> "MED17Tuner"
                                EcuPlatform.MOTRONIC -> "MotronicTuner"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // ECU Platform toggle (2×2 grid)
                        PlatformGrid(
                            selected = navState.ecuPlatform,
                            onSelect = { navState.selectPlatform(it) },
                            modifier = Modifier.padding(horizontal = 4.dp).width(140.dp)
                        )
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
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(destination.label)
                                if (destination == RailDestination.TOOLS) {
                                    StabilityBadge(StabilityLevel.ALPHA)
                                }
                            }
                        },
                        enabled = enabled
                    )
                }
            }

            VerticalDivider()

            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when (navState.railDestination) {
                    RailDestination.CONFIGURATION -> {
                        ConfigurationScreen(
                            navState = navState,
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
                            OptimizerScreen(preloadedLogDir = navState.optimizerLogDir)
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
private fun PlatformGrid(
    selected: EcuPlatform,
    onSelect: (EcuPlatform) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val outlineColor = MaterialTheme.colorScheme.outline
    val platforms = EcuPlatform.entries

    Surface(
        modifier = modifier.clip(shape),
        shape = shape,
        border = BorderStroke(1.dp, outlineColor),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            // Row 1: MOTRONIC, ME7
            Row(modifier = Modifier.height(48.dp)) {
                PlatformCell(
                    platform = platforms[0],
                    selected = selected == platforms[0],
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                VerticalDivider(thickness = 1.dp, color = outlineColor)
                PlatformCell(
                    platform = platforms[1],
                    selected = selected == platforms[1],
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
            HorizontalDivider(thickness = 1.dp, color = outlineColor)
            // Row 2: MED9, MED17
            Row(modifier = Modifier.height(48.dp)) {
                PlatformCell(
                    platform = platforms[2],
                    selected = selected == platforms[2],
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                VerticalDivider(thickness = 1.dp, color = outlineColor)
                PlatformCell(
                    platform = platforms[3],
                    selected = selected == platforms[3],
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun PlatformCell(
    platform: EcuPlatform,
    selected: Boolean,
    onSelect: (EcuPlatform) -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .clickable { onSelect(platform) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = textColor
                    )
                }
                Text(
                    text = platform.shortName,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
            }
            if (platform.stability != StabilityLevel.STABLE) {
                StabilityBadge(platform.stability, compact = true)
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
