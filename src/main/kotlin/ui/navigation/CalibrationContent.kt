package ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ui.components.StabilityBadge
import ui.screens.closedloop.ClosedLoopScreen
import ui.screens.dualinjection.DualInjectionScreen
import ui.screens.fueling.FuelingScreen
import ui.screens.fueltrim.FuelTrimScreen
import ui.screens.kfmiop.KfmiopScreen
import ui.screens.kfmirl.KfmirlScreen
import ui.screens.kfvpdksd.KfvpdksdScreen
import ui.screens.kfzw.KfzwScreen
import ui.screens.kfzwop.KfzwopScreen
import ui.screens.ldrpid.LdrpidScreen
import ui.screens.openloop.OpenLoopScreen
import ui.screens.plsol.PlsolScreen
import ui.screens.wdkugdn.WdkugdnScreen

@Composable
fun CalibrationContent(navState: NavigationState) {
    val selectedTab = navState.calibrationTab
    val platform = navState.ecuPlatform

    // Filter calibration tabs to those available on the active platform
    val visibleTabs = remember(platform) {
        CalibrationTab.entries.filter { platform in it.platforms }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(
            selectedTabIndex = visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
        ) {
            visibleTabs.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { navState.selectCalibrationTab(tab) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(tab.labelFor(platform))
                            StabilityBadge(tab.stability)
                        }
                    }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when (selectedTab) {
                CalibrationTab.FUELING -> FuelingScreen()
                CalibrationTab.CLOSED_LOOP -> ClosedLoopScreen(
                    initialTab = navState.closedLoopTab,
                    initialCorrectionSubTab = navState.closedLoopCorrectionSubTab,
                    autoFitDegree = navState.autoFitDegree
                )
                CalibrationTab.OPEN_LOOP -> OpenLoopScreen(
                    initialTab = navState.openLoopTab,
                    initialLogSubTab = navState.openLoopLogSubTab,
                    initialCorrectionSubTab = navState.openLoopCorrectionSubTab,
                    autoFitDegree = navState.autoFitDegree
                )
                CalibrationTab.DUAL_INJECTION -> DualInjectionScreen()
                CalibrationTab.FUEL_TRIM -> FuelTrimScreen()
                CalibrationTab.PLSOL -> PlsolScreen(initialTab = navState.plsolTab)
                CalibrationTab.KFMIOP -> KfmiopScreen()
                CalibrationTab.KFMIRL -> KfmirlScreen()
                CalibrationTab.KFZWOP -> KfzwopScreen()
                CalibrationTab.KFZW -> KfzwScreen()
                CalibrationTab.KFVPDKSD -> KfvpdksdScreen()
                CalibrationTab.WDKUGDN -> WdkugdnScreen()
                CalibrationTab.LDRPID -> LdrpidScreen()
            }
        }
    }
}
