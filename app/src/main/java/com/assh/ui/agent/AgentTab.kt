package com.assh.ui.agent

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class AgentTabPage { MAIN, SETTINGS, HISTORY }

/**
 * AI 运维底部 Tab 的容器：在「任务主页 / 设置 / 历史」三个子页间切换。
 * 三页共用同一个 [AgentViewModel]（即同一进程级引擎与配置/历史流）。
 */
@Composable
fun AgentTab(vm: AgentViewModel = viewModel()) {
    var page by remember { mutableStateOf(AgentTabPage.MAIN) }
    when (page) {
        AgentTabPage.MAIN -> AgentScreen(
            onOpenSettings = { page = AgentTabPage.SETTINGS },
            onOpenHistory = { page = AgentTabPage.HISTORY },
            vm = vm
        )
        AgentTabPage.SETTINGS -> {
            BackHandler { page = AgentTabPage.MAIN }
            AgentSettingsScreen(onBack = { page = AgentTabPage.MAIN }, vm = vm)
        }
        AgentTabPage.HISTORY -> {
            BackHandler { page = AgentTabPage.MAIN }
            AgentHistoryScreen(
                onBack = { page = AgentTabPage.MAIN },
                onResume = { vm.resumeSession(it); page = AgentTabPage.MAIN },
                vm = vm
            )
        }
    }
}
