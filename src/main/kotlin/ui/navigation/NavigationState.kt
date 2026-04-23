package ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import data.model.EcuPlatform
import data.preferences.platform.EcuPlatformPreference

class NavigationState {
    var railDestination by mutableStateOf(RailDestination.CONFIGURATION)
        private set

    var ecuPlatform by mutableStateOf(EcuPlatformPreference.platform)
        private set

    var calibrationTab by mutableStateOf(CalibrationTab.FUELING)
        private set

    var toolsTab by mutableStateOf(ToolsTab.LOGGER)
        private set

    // Sub-tab state (used by screenshot harness; screens read for initial values)
    var closedLoopTab by mutableStateOf(0)
    var closedLoopCorrectionSubTab by mutableStateOf(0)
    var autoFitDegree: Int? by mutableStateOf(null)
    var openLoopTab by mutableStateOf(0)
    var openLoopLogSubTab by mutableStateOf(0)
    var openLoopCorrectionSubTab by mutableStateOf(0)
    var plsolTab by mutableStateOf(0)
    var dualInjectionTab by mutableStateOf(0)
    var dualInjectionKrktePfi: String? = null
    var dualInjectionKrkteGdi: String? = null

    // Pre-loaded log data for screenshot harness (screens auto-load when non-null)
    var ldrpidLogDir: java.io.File? = null
    var fuelTrimLogFiles: List<java.io.File>? = null

    fun navigateTo(destination: RailDestination) {
        railDestination = destination
    }

    fun navigateToCalibration(tab: CalibrationTab = calibrationTab) {
        calibrationTab = tab
        railDestination = RailDestination.CALIBRATION
    }

    fun navigateToOptimizer() {
        railDestination = RailDestination.OPTIMIZER
    }

    fun selectCalibrationTab(tab: CalibrationTab) {
        calibrationTab = tab
    }

    fun navigateToTools(tab: ToolsTab = toolsTab) {
        toolsTab = tab
        railDestination = RailDestination.TOOLS
    }

    fun selectToolsTab(tab: ToolsTab) {
        toolsTab = tab
    }

    fun selectPlatform(platform: EcuPlatform) {
        ecuPlatform = platform
        EcuPlatformPreference.platform = platform
        // Reset to a safe tab that exists on both platforms
        calibrationTab = CalibrationTab.FUELING
    }
}
