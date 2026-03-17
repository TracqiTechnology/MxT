package ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class RailDestination(
    val label: String,
    val icon: ImageVector,
    val description: String
) {
    CONFIGURATION(
        label = "Configuration",
        icon = Icons.Default.Settings,
        description = "Load BIN/XDF files and configure map definitions"
    ),
    CALIBRATION(
        label = "Calibration",
        icon = Icons.Default.Build,
        description = "Calibrate base fueling, torque, ignition, and boost maps"
    ),
    TOOLS(
        label = "Tools",
        icon = Icons.Default.Handyman,
        description = "A2L→ECU generator, data logger, and utilities"
    ),
    OPTIMIZER(
        label = "Optimizer",
        icon = Icons.AutoMirrored.Filled.TrendingUp,
        description = "Optimize boost and load targets from WOT logs"
    )
}
