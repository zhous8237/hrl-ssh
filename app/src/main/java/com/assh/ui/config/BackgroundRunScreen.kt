package com.assh.ui.config

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.service.AgentForegroundService
import com.assh.service.ForegroundStatus
import com.assh.service.SshForegroundService
import com.assh.ui.common.RefreshOnResume
import com.assh.ui.common.SettingsDivider
import com.assh.ui.common.SettingsGroup
import com.assh.ui.common.SettingsStatusRow
import com.assh.ui.theme.AmberWarning
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400

/**
 * 后台运行设置：按系统设置页的分组列表组织，而不是一堆同质卡片堆文案。
 *
 * 结构：运行状态（键值行）→ 权限与设置（可点击项）→ 说明（弱化小字）。
 */
@Composable
fun BackgroundRunScreen(
    onBack: () -> Unit,
    vm: BackgroundRunViewModel = viewModel(factory = BackgroundRunViewModel.Factory)
) {
    val system by vm.system.collectAsState()
    val agent by vm.agentStatus.collectAsState()
    val terminal by vm.terminalStatus.collectAsState()
    val message by vm.message.collectAsState()
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(), vm::notificationResult
    )
    RefreshOnResume(vm::refresh)

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("后台运行") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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
            // —— 运行状态：一眼看清当前能力 ——
            item {
                SettingsGroup(title = "当前状态") {
                    SettingsStatusRow(
                        "电池优化",
                        if (system.batteryExempt) "已豁免" else "未豁免",
                        if (system.batteryExempt) GreenSuccess else AmberWarning
                    )
                    SettingsDivider()
                    SettingsStatusRow(
                        "通知权限",
                        if (system.notificationsEnabled) "已允许" else "未允许",
                        if (system.notificationsEnabled) GreenSuccess else AmberWarning
                    )
                    SettingsDivider()
                    SettingsStatusRow(
                        "AI 通知渠道",
                        if (system.agentChannelEnabled) "可用" else "已关闭",
                        if (system.agentChannelEnabled) GreenSuccess else Slate400
                    )
                    SettingsDivider()
                    SettingsStatusRow(
                        "SSH 通知渠道",
                        if (system.terminalChannelEnabled) "可用" else "已关闭",
                        if (system.terminalChannelEnabled) GreenSuccess else Slate400
                    )
                    SettingsDivider()
                    SettingsStatusRow("AI 前台服务", runtimeText(agent), runtimeColor(agent))
                    SettingsDivider()
                    SettingsStatusRow("SSH 前台服务", runtimeText(terminal), runtimeColor(terminal))
                }
            }

            // —— 设置入口 ——
            item {
                SettingsGroup(title = "系统设置") {
                    TextButton(
                        onClick = vm::openBatterySettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开电池优化设置", modifier = Modifier.fillMaxWidth())
                    }
                    if (!system.notificationsEnabled && Build.VERSION.SDK_INT >= 33) {
                        SettingsDivider()
                        TextButton(
                            onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("申请通知权限", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    SettingsDivider()
                    TextButton(
                        onClick = { vm.openNotificationSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开应用通知设置", modifier = Modifier.fillMaxWidth())
                    }
                    SettingsDivider()
                    TextButton(
                        onClick = { vm.openNotificationSettings(AgentForegroundService.CHANNEL_ID) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("AI 通知渠道", modifier = Modifier.fillMaxWidth())
                    }
                    SettingsDivider()
                    TextButton(
                        onClick = { vm.openNotificationSettings(SshForegroundService.CHANNEL_ID) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("SSH 通知渠道", modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            message?.let { msg ->
                item { Text(msg, color = AmberWarning, style = MaterialTheme.typography.bodySmall) }
            }

            agent.error?.let { err ->
                item {
                    Text("AI 最近错误：$err", color = AmberWarning,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            terminal.error?.let { err ->
                item {
                    Text("SSH 最近错误：$err", color = AmberWarning,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // —— 说明：弱化为组标题下的小字，不再占据卡片 ——
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "说明",
                        style = MaterialTheme.typography.labelMedium,
                        color = Slate400
                    )
                    Text(
                        "执行时保持 CPU 运行，不会点亮屏幕；等待确认或追加时释放 AI 唤醒锁。" +
                            "已连接的 SSH 终端会持续维持，可能增加耗电，不使用时请主动断开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                    Text(
                        "Android 15 的同类后台服务共享系统时限，电池豁免不会取消该限制。" +
                            "强制停止、进程被系统终止或断网仍可能中断本机任务；" +
                            "应用不会自动重放结果未知的远端命令。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
            }
        }
    }
}

private fun runtimeText(status: ForegroundStatus): String = when {
    status.starting -> "正在准备"
    status.active -> "已就绪"
    else -> "未运行"
}

/** 颜色属性是 @Composable get，因此这里也必须标 @Composable */
@Composable
private fun runtimeColor(status: ForegroundStatus): androidx.compose.ui.graphics.Color = when {
    status.starting -> AmberWarning
    status.active -> GreenSuccess
    else -> Slate400
}
