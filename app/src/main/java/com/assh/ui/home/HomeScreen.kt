package com.assh.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.assh.ui.agent.AgentTab
import com.assh.ui.config.ConfigScreen
import com.assh.ui.hosts.HostsScreen
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400

/** 底部导航 Tab 定义 */
private enum class HomeTab(val label: String, val icon: ImageVector) {
    HOSTS("主机", Icons.Default.Dns),
    AGENT("AI 运维", Icons.Default.SmartToy),
    CONFIG("配置", Icons.Default.Settings)
}

/**
 * 主界面：底部 Tab 导航（主机 / 私钥 / 配置），取代原先 TopBar 小图标入口。
 * 各 Tab 内容自带 TopAppBar 与 FAB。配置内含自定义命令，后续接入 WebDAV 同步与 AI。
 */
@Composable
fun HomeScreen(
    onOpenTerminal: (Long) -> Unit,
    onEditHost: (Long) -> Unit,
    onAddHost: () -> Unit
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Navy900,
        bottomBar = {
            NavigationBar(containerColor = Navy800) {
                HomeTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BlueAccent,
                            selectedTextColor = BlueAccent,
                            unselectedIconColor = Slate400,
                            unselectedTextColor = Slate400,
                            indicatorColor = BlueAccent.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (HomeTab.entries[selected]) {
                HomeTab.HOSTS -> HostsScreen(
                    onOpenTerminal = onOpenTerminal,
                    onEditHost = onEditHost,
                    onAddHost = onAddHost
                )
                HomeTab.AGENT -> AgentTab()
                HomeTab.CONFIG -> ConfigScreen()
            }
        }
    }
}
