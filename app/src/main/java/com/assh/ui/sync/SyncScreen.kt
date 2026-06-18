package com.assh.ui.sync

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.sync.SyncMode
import com.assh.ui.hosts.asshFieldColors
import com.assh.ui.theme.AmberWarning
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebDAV 云同步设置页（功能 7）。三按钮：同步到云端 / 同步到本地 / 智能合并。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    vm: SyncViewModel = viewModel()
) {
    val s by vm.state.collectAsState()
    var showPassword by remember { mutableStateOf(false) }
    var showPassphrase by remember { mutableStateOf(false) }
    var confirmMode by remember { mutableStateOf<SyncMode?>(null) }

    // 同步/测试结果用系统 Toast 呈现，避免在可滚动页面底部看不到
    val context = LocalContext.current
    LaunchedEffect(s.message) {
        s.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 同步", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(Navy900).imePadding().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { vm.testConnection() },
                    enabled = !s.busy, modifier = Modifier.weight(1f)
                ) {
                    if (s.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BlueAccent)
                    else Text("测试连接")
                }
                Button(
                    onClick = { vm.saveWebDav() },
                    enabled = !s.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ===== 三个同步动作（主体，置顶）=====
            SectionCard(title = "同步") {
                SyncActionButton(
                    icon = Icons.Default.CloudUpload, tint = BlueAccent,
                    title = "同步到云端", subtitle = "以本地为准，备份覆盖云端",
                    enabled = s.canSync, onClick = { confirmMode = SyncMode.PUSH }
                )
                SyncActionButton(
                    icon = Icons.Default.CloudDownload, tint = AmberWarning,
                    title = "同步到本地", subtitle = "以云端为准，恢复覆盖本地",
                    enabled = s.canSync, onClick = { confirmMode = SyncMode.PULL }
                )
                SyncActionButton(
                    icon = Icons.Default.Merge, tint = GreenSuccess,
                    title = "智能合并", subtitle = "双向合并，冲突取较新（推荐）",
                    enabled = s.canSync, onClick = { vm.sync(SyncMode.MERGE) }
                )
                if (!s.canSync && !s.busy) {
                    Text("填写服务器、密码与同步口令后即可同步",
                        style = MaterialTheme.typography.bodySmall, color = Slate400)
                }
                s.lastSyncAt?.let {
                    Text("上次同步：" + formatTime(it),
                        style = MaterialTheme.typography.bodySmall, color = Slate400)
                }
            }

            // ===== WebDAV 端点配置 =====
            SectionCard(title = "服务器") {
                OutlinedTextField(
                    value = s.baseUrl,
                    onValueChange = { v -> vm.update { copy(baseUrl = v) } },
                    label = { Text("WebDAV 地址") },
                    placeholder = { Text("https://dav.example.com/dav/") },
                    singleLine = true, colors = asshFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (s.baseUrl.isNotBlank() && !s.baseUrl.trim().startsWith("https://", true)) {
                    WarnLine("使用 HTTP 明文连接，账号密码会被窃听，建议改用 HTTPS")
                }
                OutlinedTextField(
                    value = s.username,
                    onValueChange = { v -> vm.update { copy(username = v) } },
                    label = { Text("账号") }, singleLine = true,
                    colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = s.password,
                    onValueChange = { v -> vm.update { copy(password = v, passwordTouched = true) } },
                    label = { Text("密码") },
                    placeholder = {
                        Text(if (s.hasSavedPassword) "已保存，留空则不修改" else "WebDAV 账号密码")
                    },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null, tint = Slate400
                            )
                        }
                    },
                    colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
            }

            // ===== 同步口令 =====
            SectionCard(title = "同步口令（端到端加密）") {
                OutlinedTextField(
                    value = s.passphrase,
                    onValueChange = { v -> vm.update { copy(passphrase = v) } },
                    label = { Text("同步口令") },
                    placeholder = {
                        Text(if (s.hasSavedPassphrase) "已记住" else "用于加密云端备份")
                    },
                    singleLine = true,
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            Icon(
                                if (showPassphrase) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null, tint = Slate400
                            )
                        }
                    },
                    colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = s.rememberPassphrase,
                        onCheckedChange = { c -> vm.update { copy(rememberPassphrase = c) } },
                        colors = CheckboxDefaults.colors(checkedColor = BlueAccent)
                    )
                    Text("记住口令（加密保存到本机）", style = MaterialTheme.typography.bodyMedium)
                }
                WarnLine("口令不会上传，也无法找回。忘记口令将无法解密云端备份。")
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // 覆盖类操作二次确认
    confirmMode?.let { mode ->
        val (title, body) = when (mode) {
            SyncMode.PUSH -> "同步到云端" to "将用本地配置覆盖云端备份，云端上本地没有的项会被删除。继续？"
            SyncMode.PULL -> "同步到本地" to "将用云端备份覆盖本地配置，本地上云端没有的服务器/私钥/密码/命令会被删除。继续？"
            SyncMode.MERGE -> "" to ""
        }
        AlertDialog(
            onDismissRequest = { confirmMode = null },
            containerColor = Navy800,
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                Button(
                    onClick = { vm.sync(mode); confirmMode = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (mode == SyncMode.PULL) AmberWarning else BlueAccent
                    )
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMode = null }) { Text("取消", color = Slate400) }
            }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = BlueAccent)
            content()
        }
    }
}

@Composable
private fun SyncActionButton(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate400)
        }
    }
}

@Composable
private fun WarnLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AmberWarning)
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
