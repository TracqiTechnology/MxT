package ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
    val ecuPlatform = navState.ecuPlatform
    val selectedTab = navState.toolsTab

    val visibleTabs = remember(ecuPlatform) {
        ToolsTab.entries.filter { ecuPlatform in it.platforms }
    }

    // Auto-select first visible tab if current selection is hidden
    LaunchedEffect(visibleTabs, selectedTab) {
        if (selectedTab !in visibleTabs && visibleTabs.isNotEmpty()) {
            navState.selectToolsTab(visibleTabs.first())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(
            selectedTabIndex = visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
        ) {
            visibleTabs.forEach { tab ->
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
                ToolsTab.AXIS_RESCALER -> AxisRescalerScreen(preloadedMap = navState.axisRescalerPreloadMap)
            }
        }
    }
}
