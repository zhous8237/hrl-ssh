package com.assh.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assh.ui.commands.CommandsScreen
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400

/** 配置子页面 */
private enum class ConfigPage { MENU, COMMANDS, NETWORK, THEME, SYNC, KEYS }

/**
 * 配置 Tab：设置中心。当前含「自定义命令」管理，
 * 预留「WebDAV 同步」「AI 助手」入口（后续接入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen() {
    var page by remember { mutableStateOf(ConfigPage.MENU) }

    when (page) {
        ConfigPage.COMMANDS -> {
            BackHandler { page = ConfigPage.MENU }
            CommandsScreen(onBack = { page = ConfigPage.MENU })
        }
        ConfigPage.NETWORK -> {
            BackHandler { page = ConfigPage.MENU }
            NetworkCheckScreen(onBack = { page = ConfigPage.MENU })
        }
        ConfigPage.THEME -> {
            BackHandler { page = ConfigPage.MENU }
            ThemeSettingScreen(onBack = { page = ConfigPage.MENU })
        }
        ConfigPage.SYNC -> {
            BackHandler { page = ConfigPage.MENU }
            com.assh.ui.sync.SyncScreen(onBack = { page = ConfigPage.MENU })
        }
        ConfigPage.KEYS -> {
            BackHandler { page = ConfigPage.MENU }
            com.assh.ui.keys.KeysScreen(onBack = { page = ConfigPage.MENU })
        }
        ConfigPage.MENU -> {
            Scaffold(
                containerColor = Navy900,
                topBar = {
                    TopAppBar(
                        title = { Text("配置", fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
                    )
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        ConfigEntry(
                            icon = Icons.Default.Palette,
                            iconTint = BlueAccent,
                            title = "主题",
                            subtitle = "白天 / 夜晚 / 跟随系统",
                            enabled = true,
                            onClick = { page = ConfigPage.THEME }
                        )
                    }
                    item {
                        ConfigEntry(
                            icon = Icons.Default.Terminal,
                            iconTint = BlueAccent,
                            title = "自定义命令",
                            subtitle = "管理终端快捷命令",
                            enabled = true,
                            onClick = { page = ConfigPage.COMMANDS }
                        )
                    }
                    item {
                        ConfigEntry(
                            icon = Icons.Default.NetworkCheck,
                            iconTint = BlueAccent,
                            title = "网络检测",
                            subtitle = "检测当前网络的 IPv4 / IPv6 可用性",
                            enabled = true,
                            onClick = { page = ConfigPage.NETWORK }
                        )
                    }
                    item {
                        ConfigEntry(
                            icon = Icons.Default.CloudSync,
                            iconTint = GreenSuccess,
                            title = "WebDAV 同步",
                            subtitle = "云端备份与多设备同步",
                            enabled = true,
                            onClick = { page = ConfigPage.SYNC }
                        )
                    }
                    item {
                        ConfigEntry(
                            icon = Icons.Default.Password,
                            iconTint = BlueAccent,
                            title = "密码（私钥）配置",
                            subtitle = "管理 SSH 私钥与密码凭据",
                            enabled = true,
                            onClick = { page = ConfigPage.KEYS }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigEntry(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, null,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onBackground
                    else Slate400
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }
            if (enabled) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = Slate400
                )
            }
        }
    }
}
