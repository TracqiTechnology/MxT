package ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ui.screens.a2lecu.A2lToEcuScreen
import ui.screens.logger.LoggerScreen

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
                    text = { Text(tab.label) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when (selectedTab) {
                ToolsTab.A2L_GENERATOR -> A2lToEcuScreen()
                ToolsTab.LOGGER -> LoggerScreen()
            }
        }
    }
}
