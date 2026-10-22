package com.assh.ui.hosts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.sshconfig.SshConfigImportPlanner
import com.assh.ui.theme.AmberWarning
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy700
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.RedError
import com.assh.ui.theme.Slate400

/**
 * SSH 配置导入页（`~/.ssh/config` 形态）。
 *
 * 流程：选配置文件 → 预览要创建的主机/私钥 → 可选补私钥 → 确认导入。
 * 解析到的私钥路径在 Android 上读不到，所以支持把私钥文件一起选进来（多选）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshConfigImportScreen(
    onBack: () -> Unit,
    vm: SshConfigImportViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    var userInput by remember { mutableStateOf("") }

    val configPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::loadConfig) }

    val keyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addIdentityFile(uris) }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("从 SSH 配置导入", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Navy900,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TipCard()

            // —— 1. 选配置文件 ——
            Card(colors = CardDefaults.cardColors(containerColor = Navy800), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = BlueAccent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("1. 选择 config 文件", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        state.fileName ?: "未选择（PC 上通常是 %USERPROFILE%\\.ssh\\config）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.fileName == null) Slate400 else GreenSuccess
                    )
                    Button(
                        onClick = { configPicker.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                    ) { Text(if (state.fileName == null) "选择文件" else "重新选择") }
                }
            }

            // —— 2. 可选：补私钥 ——
            Card(colors = CardDefaults.cardColors(containerColor = Navy800), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = AmberWarning, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("2. 可选：一起导入私钥", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "配置里的 IdentityFile 只是路径，Android 读不到 PC 上的文件。" +
                            "把私钥文件也选进来（可多选），导入时会按文件名自动绑定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                    if (state.identities.isNotEmpty()) {
                        state.identities.keys.forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("• $name", Modifier.weight(1f), color = GreenSuccess,
                                    style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { vm.removeIdentity(name) }) {
                                    Icon(Icons.Default.Close, contentDescription = "移除", tint = Slate400,
                                        modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { keyPicker.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = Navy700)
                    ) { Text("选择私钥文件") }
                }
            }

            // —— 3. 预览 ——
            val plan = state.plan
            if (plan != null) {
                if (plan.skipped.isNotEmpty()) {
                    // 有“缺 User”的条目时，给一个兜底用户名输入框
                    Card(colors = CardDefaults.cardColors(containerColor = Navy800), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("默认用户名（配置里缺 User 的条目用它）", fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = userInput,
                                onValueChange = {
                                    userInput = it
                                    vm.setDefaultUsername(it)
                                },
                                singleLine = true,
                                label = { Text("如 root") },
                                colors = asshFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = Navy800), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "3. 预览：将导入 ${plan.importableCount} 台主机" +
                                if (plan.keys.isNotEmpty()) "、${plan.keys.size} 个私钥" else "",
                            fontWeight = FontWeight.SemiBold
                        )
                        if (plan.hosts.isEmpty()) {
                            Text("没有可导入的主机条目。", color = AmberWarning,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        plan.hosts.forEach { h -> HostDraftRow(h) }

                        if (plan.keyMissing.isNotEmpty()) {
                            HorizontalDivider(color = Navy700, modifier = Modifier.padding(vertical = 6.dp))
                            Text("需手动补私钥（已创建为主机，认证方式为密码，请稍后编辑）",
                                style = MaterialTheme.typography.bodySmall, color = AmberWarning)
                            plan.keyMissing.forEach { (name, reason) ->
                                Text("• $name：$reason", style = MaterialTheme.typography.bodySmall,
                                    color = Slate400)
                            }
                        }

                        if (plan.skipped.isNotEmpty()) {
                            HorizontalDivider(color = Navy700, modifier = Modifier.padding(vertical = 6.dp))
                            Text("已跳过 ${plan.skipped.size} 项", style = MaterialTheme.typography.bodySmall,
                                color = RedError)
                            plan.skipped.forEach { (name, reason) ->
                                Text("• $name：$reason", style = MaterialTheme.typography.bodySmall,
                                    color = Slate400)
                            }
                        }

                        if (state.warnings.isNotEmpty()) {
                            HorizontalDivider(color = Navy700, modifier = Modifier.padding(vertical = 6.dp))
                            Text("解析提示（${state.warnings.size}）",
                                style = MaterialTheme.typography.bodySmall, color = Slate400)
                            LazyColumn(modifier = Modifier.height((minOf(state.warnings.size, 4) * 22).dp + 4.dp)) {
                                items(state.warnings) { w ->
                                    Text("· $w", style = MaterialTheme.typography.labelSmall, color = Slate400)
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = vm::confirmImport,
                            enabled = plan.importableCount > 0 && !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = GreenSuccess),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                                )
                                Spacer(Modifier.size(8.dp))
                            }
                            Text("确认导入")
                        }
                    }
                }
            } else if (state.busy) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            containerColor = Navy800,
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = vm::clearError) { Text("知道了", color = BlueAccent) }
            }
        )
    }

    state.doneMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearDone(); onBack() },
            containerColor = Navy800,
            title = { Text("导入完成") },
            text = { Text("$msg。私钥已用设备 Keystore 加密保存。") },
            confirmButton = {
                TextButton(onClick = { vm.clearDone(); onBack() }) { Text("好", color = GreenSuccess) }
            }
        )
    }
}

@Composable
private fun TipCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy700),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("支持 OpenSSH 配置的哪些写法", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall)
            Text(
                "Host 多别名、HostName、User、Port、IdentityFile、Include、%h/%r/%d/%n 令牌；" +
                    "Host * 等通配块不生成条目。ProxyCommand / ProxyJump / 端口转发等不支持，会被忽略并提示。",
                style = MaterialTheme.typography.labelSmall, color = Slate400
            )
        }
    }
}

@Composable
private fun HostDraftRow(h: SshConfigImportPlanner.HostDraft) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(BlueAccent, RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.size(8.dp))
            Text(h.label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "   ${h.username}@${h.host}:${h.port}" +
                if (h.keyAlias != null) "  · 私钥 ${h.keyAlias}" else "  · 密码认证",
            style = MaterialTheme.typography.labelSmall,
            color = Slate400
        )
        if (h.ignoredOptions.isNotEmpty()) {
            Text(
                "   已忽略：${h.ignoredOptions.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = AmberWarning
            )
        }
    }
}
