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
import androidx.compose.material.icons.filled.BatteryChargingFull
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.ui.commands.CommandsScreen
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy800
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.assh.ui.common.SettingsDivider
import com.assh.ui.common.SettingsGroup
import com.assh.ui.common.SettingsRow
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400

/** 配置子页面 */
private enum class ConfigPage { MENU, COMMANDS, NETWORK, THEME, SYNC, KEYS, BACKGROUND }

/** 配置中心：底部标签页与指纹恢复路由共用，子页面优先返回配置菜单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(onBack: (() -> Unit)? = null, vm: ConfigViewModel = viewModel(factory = ConfigViewModel.Factory)) {
    var page by rememberSaveable { mutableStateOf(ConfigPage.MENU) }
    val clearState by vm.state.collectAsState()

    if (clearState.confirming) {
        AlertDialog(
            onDismissRequest = vm::cancelClear,
            containerColor = Navy800,
            title = { Text("清除服务器指纹？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("仅删除全部已保存的服务器指纹，不影响主机、密码、私钥、AI 配置和任务历史。下次连接将重新记录指纹。")
                    clearState.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmClear, enabled = !clearState.busy) {
                    Text(if (clearState.busy) "清除中…" else "确认清除")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelClear, enabled = !clearState.busy) { Text("取消") }
            }
        )
    }

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
        ConfigPage.BACKGROUND -> {
            BackHandler { page = ConfigPage.MENU }
            BackgroundRunScreen(onBack = { page = ConfigPage.MENU })
        }
        ConfigPage.MENU -> {
            Scaffold(
                containerColor = Navy900,
                topBar = {
                    TopAppBar(
                        title = { Text("配置", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            if (onBack != null) IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
                    )
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 外观
                    item {
                        SettingsGroup(title = "外观") {
                            SettingsRow(
                                icon = Icons.Default.Palette,
                                iconTint = BlueAccent,
                                title = "主题",
                                subtitle = "白天 / 夜晚 / 跟随系统",
                                onClick = { page = ConfigPage.THEME }
                            )
                        }
                    }

                    // 运行
                    item {
                        SettingsGroup(title = "运行") {
                            SettingsRow(
                                icon = Icons.Default.BatteryChargingFull,
                                iconTint = GreenSuccess,
                                title = "后台运行",
                                subtitle = "锁屏任务、通知与电池优化设置",
                                onClick = { page = ConfigPage.BACKGROUND }
                            )
                            SettingsDivider()
                            SettingsRow(
                                icon = Icons.Default.NetworkCheck,
                                iconTint = BlueAccent,
                                title = "网络检测",
                                subtitle = "检测当前网络的 IPv4 / IPv6 可用性",
                                onClick = { page = ConfigPage.NETWORK }
                            )
                        }
                    }

                    // 数据
                    item {
                        SettingsGroup(title = "数据") {
                            SettingsRow(
                                icon = Icons.Default.Terminal,
                                iconTint = BlueAccent,
                                title = "自定义命令",
                                subtitle = "管理终端快捷命令",
                                onClick = { page = ConfigPage.COMMANDS }
                            )
                            SettingsDivider()
                            SettingsRow(
                                icon = Icons.Default.CloudSync,
                                iconTint = GreenSuccess,
                                title = "WebDAV 同步",
                                subtitle = "云端备份与多设备同步",
                                onClick = { page = ConfigPage.SYNC }
                            )
                        }
                    }

                    // 凭据与安全
                    item {
                        SettingsGroup(title = "凭据与安全", tag = "settings_group_credentials") {
                            SettingsRow(
                                icon = Icons.Default.Password,
                                iconTint = BlueAccent,
                                title = "密码（私钥）配置",
                                subtitle = "管理 SSH 私钥与密码凭据",
                                onClick = { page = ConfigPage.KEYS }
                            )
                            SettingsDivider()
                            SettingsRow(
                                icon = Icons.Default.Fingerprint,
                                iconTint = BlueAccent,
                                title = "清除服务器指纹",
                                subtitle = clearState.message?.takeUnless { clearState.confirming }
                                    ?: "指纹不匹配时清除，下次连接重新记录",
                                enabled = !clearState.busy,
                                showChevron = false,
                                onClick = vm::requestClear
                            )
                        }
                    }
                }
            }
        }
    }
}
