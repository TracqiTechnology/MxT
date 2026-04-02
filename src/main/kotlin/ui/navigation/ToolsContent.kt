package ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ui.components.StabilityBadge
import ui.screens.a2lecu.A2lToEcuScreen
import ui.screens.axisrescaler.AxisRescalerScreen
import ui.screens.logger.LoggerScreen
import ui.screens.sniffer.RamSnifferScreen

@Composable
fun ToolsContent(navState: NavigationState) {
    val selectedTab = navState.toolsTab

    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(
            selectedTabIndex = ToolsTab.entries.indexOf(selectedTab).coerceAtLeast(0)
        ) {
            ToolsTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { navState.selectToolsTab(tab) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(tab.label)
                            StabilityBadge(tab.stability)
                        }
                    }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when (selectedTab) {
                ToolsTab.A2L_GENERATOR -> A2lToEcuScreen()
                ToolsTab.LOGGER -> LoggerScreen(ecuPlatform = navState.ecuPlatform)
                ToolsTab.RAM_SNIFFER -> RamSnifferScreen()
                ToolsTab.AXIS_RESCALER -> AxisRescalerScreen()
            }
        }
    }
}
