package com.assh.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.data.db.entity.CredentialEntity
import com.assh.data.db.entity.KeyEntity
import com.assh.ui.common.TopBarAddButton
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
 * 凭据管理 Tab：私钥库 + 密码库（功能 4）。
 * 两个区块各自列表 + 顶栏右侧统一加按钮（长按或点击弹出选择新增类型）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(onBack: (() -> Unit)? = null, vm: KeysViewModel = viewModel()) {
    val keys by vm.keys.collectAsState()
    val credentials by vm.credentials.collectAsState()
    val message by vm.message.collectAsState()

    var showKeyImport by remember { mutableStateOf(false) }
    var keyEditTarget by remember { mutableStateOf<KeyEntity?>(null) }
    var keyDeleteTarget by remember { mutableStateOf<KeyEntity?>(null) }

    var showCredImport by remember { mutableStateOf(false) }
    var credEditTarget by remember { mutableStateOf<CredentialEntity?>(null) }
    var credDeleteTarget by remember { mutableStateOf<CredentialEntity?>(null) }

    var showAddMenu by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        containerColor = Navy900,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("凭据管理") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TopBarAddButton(onClick = { showAddMenu = true }, contentDescription = "新增凭据")
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // —— 私钥区块 ——
            item { SectionHeader("私钥（${keys.size}）") }
            if (keys.isEmpty()) {
                item { EmptyHint("暂无私钥，点击右上角 + 新增") }
            } else {
                items(keys, key = { "key_${it.id}" }) { key ->
                    KeyCard(
                        key = key,
                        onEdit = { keyEditTarget = key },
                        onDelete = { keyDeleteTarget = key }
                    )
                }
            }

            // —— 密码区块 ——
            item { SectionHeader("密码（${credentials.size}）") }
            if (credentials.isEmpty()) {
                item { EmptyHint("暂无密码，点击右上角 + 新增") }
            } else {
                items(credentials, key = { "cred_${it.id}" }) { cred ->
                    CredentialCard(
                        credential = cred,
                        onEdit = { credEditTarget = cred },
                        onDelete = { credDeleteTarget = cred }
                    )
                }
            }
        }
    }

    // —— 新增类型选择 ——
    if (showAddMenu) {
        AlertDialog(
            onDismissRequest = { showAddMenu = false },
            containerColor = Navy800,
            title = { Text("新增凭据") },
            text = { Text("选择要新增的凭据类型", color = Slate400) },
            confirmButton = {
                Button(
                    onClick = { showAddMenu = false; showKeyImport = true },
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                ) {
                    Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("私钥")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showAddMenu = false; showCredImport = true }
                ) {
                    Icon(Icons.Default.Password, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("密码")
                }
            }
        )
    }

    // —— 私钥导入/编辑 ——
    if (showKeyImport) {
        KeyEditDialog(
            initial = null,
            onDismiss = { showKeyImport = false },
            onSave = { alias, pem, srcName ->
                vm.import(alias, pem, srcName) { showKeyImport = false }
            }
        )
    }
    keyEditTarget?.let { key ->
        KeyEditDialog(
            initial = key,
            onDismiss = { keyEditTarget = null },
            onSave = { alias, pem, srcName ->
                vm.update(key, alias, pem, srcName) { keyEditTarget = null }
            }
        )
    }
    keyDeleteTarget?.let { key ->
        AlertDialog(
            onDismissRequest = { keyDeleteTarget = null },
            containerColor = Navy800,
            title = { Text("删除私钥") },
            text = { Text("确定删除「${key.alias}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(key); keyDeleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { keyDeleteTarget = null }) { Text("取消", color = Slate400) }
            }
        )
    }

    // —— 密码导入/编辑 ——
    if (showCredImport) {
        CredentialEditDialog(
            initial = null,
            onDismiss = { showCredImport = false },
            onSave = { alias, pwd -> vm.importCredential(alias, pwd) { showCredImport = false } }
        )
    }
    credEditTarget?.let { cred ->
        CredentialEditDialog(
            initial = cred,
            onDismiss = { credEditTarget = null },
            onSave = { alias, pwd -> vm.updateCredential(cred, alias, pwd) { credEditTarget = null } }
        )
    }
    credDeleteTarget?.let { cred ->
        AlertDialog(
            onDismissRequest = { credDeleteTarget = null },
            containerColor = Navy800,
            title = { Text("删除密码") },
            text = { Text("确定删除「${cred.alias}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteCredential(cred); credDeleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { credDeleteTarget = null }) { Text("取消", color = Slate400) }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Slate400,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Slate400.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun KeyCard(
    key: KeyEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(GreenSuccess.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Key, null, tint = GreenSuccess, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(key.alias, fontWeight = FontWeight.SemiBold)
                Text(
                    "${key.keyType.uppercase()} · ${dateFmt.format(Date(key.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = BlueAccent)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CredentialCard(
    credential: CredentialEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(AmberWarning.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Password, null, tint = AmberWarning, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(credential.alias, fontWeight = FontWeight.SemiBold)
                Text(
                    "密码 · ${dateFmt.format(Date(credential.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = BlueAccent)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 导入 / 编辑私钥对话框。
 * 编辑模式（initial != null）：私钥内容留空 = 保持原值不变（密文不回显），但来源文件名会回显。
 *
 * 支持两种来源：手动粘贴私钥内容，或选择私钥文件（SAF，content URI，中文文件名 OK）。
 * 二者同时存在时，优先采用手动输入的私钥内容。
 */
@Composable
private fun KeyEditDialog(
    initial: KeyEntity?,
    onDismiss: () -> Unit,
    onSave: (alias: String, pem: String, sourceName: String?) -> Unit
) {
    val editing = initial != null
    val context = LocalContext.current
    var alias by remember { mutableStateOf(initial?.alias ?: "") }
    var pem by remember { mutableStateOf("") }
    // 选择的文件：本次新选的文件名 + 读出的明文内容；读取失败时给出错误
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var pickedContent by remember { mutableStateOf("") }
    var fileError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("无法打开文件")
            pickedContent = text
            // 完整路径优先（尽量接近文件管理器显示）；取不到则退回文件名
            pickedFileName = decodeFullPath(context, uri)
                ?: queryDisplayName(context, uri)
                ?: uri.lastPathSegment ?: "已选择文件"
            fileError = null
        } catch (e: Exception) {
            fileError = "读取失败：${e.message}"
            pickedContent = ""
            pickedFileName = null
        }
    }

    // 优先级：手动输入的私钥内容 > 本次选择文件读出的内容
    val effectivePem = pem.takeIf { it.isNotBlank() } ?: pickedContent
    // 展示用文件名：本次新选 > 编辑模式下已保存的来源
    val displayName = pickedFileName ?: initial?.sourceName
    // 提交给 repo 的来源名：本次新选了文件才更新（null = 保持原值）
    val sourceToSave = pickedFileName

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Navy800,
        title = { Text(if (editing) "编辑私钥" else "导入私钥") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = alias, onValueChange = { alias = it },
                    label = { Text("别名（唯一）") }, singleLine = true,
                    colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )

                // —— 来源文件：整块卡片，按钮 + 文件名分两行，工整对齐 ——
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Navy900, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (displayName != null) "重新选择文件" else "选择私钥文件")
                    }
                    // 来源完整路径：点击复制到剪贴板
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "来源（点击路径可复制）",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate400
                        )
                        Text(
                            displayName ?: "未选择文件",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (displayName != null) FontWeight.Medium else FontWeight.Normal,
                            color = if (displayName != null) GreenSuccess else Slate400.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (displayName != null) Modifier.combinedClickable(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("source path", displayName))
                                        }
                                    ) else Modifier
                                )
                        )
                    }
                    fileError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (pickedFileName != null && pem.isNotBlank()) {
                        Text(
                            "已同时填写下方私钥内容，将优先使用输入内容（忽略所选文件）",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }
                }

                OutlinedTextField(
                    value = pem, onValueChange = { pem = it },
                    label = { Text("私钥内容（PEM/OpenSSH，可选）") },
                    placeholder = {
                        Text(
                            if (editing) "留空 = 保持原私钥不变"
                            else "粘贴私钥，或上方选择文件",
                            color = Slate400.copy(alpha = 0.5f)
                        )
                    },
                    minLines = 4, maxLines = 6,
                    colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(alias, effectivePem, sourceToSave) },
                enabled = alias.isNotBlank() && (editing || effectivePem.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
            ) { Text(if (editing) "保存" else "导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Slate400) }
        }
    )
}

/**
 * 导入 / 编辑密码对话框。
 * 编辑模式（initial != null）：密码留空 = 保持原值不变（密文不回显）。
 */
@Composable
private fun CredentialEditDialog(
    initial: CredentialEntity?,
    onDismiss: () -> Unit,
    onSave: (alias: String, password: String) -> Unit
) {
    val editing = initial != null
    var alias by remember { mutableStateOf(initial?.alias ?: "") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Navy800,
        title = { Text(if (editing) "编辑密码" else "新增密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = alias, onValueChange = { alias = it },
                    label = { Text("别名（唯一）") },
                    placeholder = { Text("如：生产机通用密码", color = Slate400.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("密码") },
                    placeholder = {
                        Text(
                            if (editing) "留空 = 保持原密码不变" else "输入密码",
                            color = Slate400.copy(alpha = 0.5f)
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(alias, password) },
                enabled = alias.isNotBlank() && (editing || password.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
            ) { Text(if (editing) "保存" else "新增") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Slate400) }
        }
    )
}

/**
 * 把 SAF content URI 解析成尽量可读的完整路径：
 * - DocumentsUI 的 docId 形如 "primary:Download/id_rsa"，转成 "/存储/Download/id_rsa"；
 * - 解析不出时回退到文件名 / URI 末段。
 */
private fun decodeFullPath(context: android.content.Context, uri: android.net.Uri): String {
    val docId = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull()
    if (docId != null && docId.contains(':')) {
        val type = docId.substringBefore(':')
        val rel = docId.substringAfter(':')
        val root = if (type.equals("primary", ignoreCase = true)) "内部存储" else type
        return "/$root/$rel"
    }
    return queryDisplayName(context, uri) ?: uri.lastPathSegment ?: uri.toString()
}

/** 通过 SAF content URI 查询展示用文件名（支持中文）；失败返回 null */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()
