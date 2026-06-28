package ui.navigation

import data.model.EcuPlatform
import data.model.StabilityLevel

enum class ToolsTab(
    val label: String,
    val stability: StabilityLevel = StabilityLevel.ALPHA,
    val platforms: Set<EcuPlatform> = EcuPlatform.entries.toSet()
) {
    LOGGER("Data Logger"),
    A2L_GENERATOR("A2L \u2192 ECU Generator",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9, EcuPlatform.MED17)),
    XDF_GENERATOR("A2L \u2192 XDF Generator",
        platforms = setOf(EcuPlatform.MED9)),
    RAM_SNIFFER("RAM Sniffer",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MOTRONIC, EcuPlatform.MED9)),
    AXIS_RESCALER("Axis Rescaler", stability = StabilityLevel.STABLE)
}
