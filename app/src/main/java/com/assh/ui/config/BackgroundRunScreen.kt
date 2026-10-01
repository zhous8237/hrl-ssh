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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.service.AgentForegroundService
import com.assh.service.ForegroundStatus
import com.assh.service.SshForegroundService
import com.assh.ui.common.RefreshOnResume
import com.assh.ui.theme.AmberWarning
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400

@Composable
fun BackgroundRunScreen(onBack: () -> Unit, vm: BackgroundRunViewModel = viewModel(factory = BackgroundRunViewModel.Factory)) {
    val system by vm.system.collectAsState()
    val agent by vm.agentStatus.collectAsState()
    val terminal by vm.terminalStatus.collectAsState()
    val message by vm.message.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::notificationResult)
    RefreshOnResume(vm::refresh)
    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("后台运行") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                BackgroundCard {
                    Text("电池与锁屏", style = MaterialTheme.typography.titleMedium)
                    Text(if (system.batteryExempt) "电池优化：已豁免" else "电池优化：未豁免", color = AmberWarning)
                    Text("锁屏持续联网建议在系统中选择本应用，并允许后台活动或设为不受限制。不同手机的设置名称可能不同。", color = Slate400)
                    TextButton(onClick = vm::openBatterySettings) { Text("打开电池优化设置") }
                }
            }
            item {
                BackgroundCard {
                    Text("任务通知", style = MaterialTheme.typography.titleMedium)
                    Text(if (system.notificationsEnabled) "通知：已允许" else "通知：未允许")
                    Text("AI 渠道：${if (system.agentChannelEnabled) "可用" else "已关闭"}；SSH 渠道：${if (system.terminalChannelEnabled) "可用" else "已关闭"}")
                    Text("拒绝通知不等于禁止前台服务，但可能看不到通知栏中的任务进度和停止按钮。", color = Slate400)
                    if (!system.notificationsEnabled && Build.VERSION.SDK_INT >= 33) {
                        TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("申请通知权限") }
                    }
                    TextButton(onClick = { vm.openNotificationSettings() }) { Text("打开应用通知设置") }
                    TextButton(onClick = { vm.openNotificationSettings(AgentForegroundService.CHANNEL_ID) }) { Text("AI 通知渠道") }
                    TextButton(onClick = { vm.openNotificationSettings(SshForegroundService.CHANNEL_ID) }) { Text("SSH 通知渠道") }
                }
            }
            item {
                BackgroundCard {
                    Text("当前会话保护", style = MaterialTheme.typography.titleMedium)
                    RuntimeStatus("AI", agent)
                    RuntimeStatus("SSH", terminal)
                }
            }
            message?.let { item { Text(it, color = AmberWarning) } }
            item {
                Text("执行时保持 CPU 运行，不会点亮屏幕；等待确认或追加时释放 AI 唤醒锁。已连接的 SSH 终端会持续维持，可能增加耗电，不使用时请主动断开。", color = Slate400)
            }
            item {
                Text("Android 15 的同类后台服务共享系统时限，电池豁免不会取消该限制。强制停止、进程被系统终止或断网仍可能中断本机任务；应用不会自动重放结果未知的远端命令。", color = Slate400)
            }
        }
    }
}

@Composable
private fun BackgroundCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Navy800)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun RuntimeStatus(label: String, status: ForegroundStatus) {
    val text = when {
        status.starting -> "正在准备前台服务"
        status.active -> "前台服务已就绪"
        else -> "前台服务未运行"
    }
    Text("$label：$text")
    status.error?.let { Text("$label 最近错误：$it", color = AmberWarning) }
}
