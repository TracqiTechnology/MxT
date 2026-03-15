package ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ui.screens.a2lecu.A2lToEcuScreen

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
                ToolsTab.LOGGER -> LoggerPlaceholderScreen()
            }
        }
    }
}

@Composable
private fun LoggerPlaceholderScreen() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Data Logger",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Real-time ECU data logging via ME7Logger. Coming soon.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
