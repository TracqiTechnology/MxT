package ui.navigation

import data.model.StabilityLevel

enum class ToolsTab(val label: String, val stability: StabilityLevel = StabilityLevel.ALPHA) {
    LOGGER("Data Logger"),
    A2L_GENERATOR("A2L \u2192 ECU Generator"),
    RAM_SNIFFER("RAM Sniffer"),
    AXIS_RESCALER("Axis Rescaler", stability = StabilityLevel.STABLE)
}
