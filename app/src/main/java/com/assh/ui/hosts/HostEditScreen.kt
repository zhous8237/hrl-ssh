package com.assh.ui.hosts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.data.db.entity.AuthType
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.Navy700
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400

/**
 * 配置编辑页（视觉设计方案 §2.3）：分组式表单 —— 基础信息 / 凭据设置（选卡）/ 高级设置（默认折叠）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    hostId: Long?,
    onDone: () -> Unit,
    vm: HostEditViewModel = viewModel()
) {
    LaunchedEffect(hostId) { vm.load(hostId) }

    val state by vm.state.collectAsState()
    val keys by vm.keys.collectAsState()
    val credentials by vm.credentials.collectAsState()
    var advancedExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text(if (hostId == null) "新建服务器" else "编辑服务器") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // —— 分组 1：基础信息 ——
            FormSection("基础信息") {
                AsshTextField(state.label, { vm.update { copy(label = it) } }, "标签", "如：生产 web01")
                AsshTextField(
                    state.host, { vm.update { copy(host = it) } },
                    "主机", "域名 / IPv4 / IPv6（无需中括号）"
                )
                AsshTextField(
                    state.username, { vm.update { copy(username = it) } },
                    "用户名", "root"
                )
                AsshTextField(
                    state.port, { vm.update { copy(port = it.filter(Char::isDigit).take(5)) } },
                    "端口", "22",
                    keyboardType = KeyboardType.Number
                )
            }

            // —— 分组 2：凭据设置（选卡切换：密码 / 私钥别名）——
            FormSection("凭据设置") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.authType == AuthType.PASSWORD,
                        onClick = { vm.update { copy(authType = AuthType.PASSWORD) } },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { Icon(Icons.Default.Password, null) },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = BlueAccent.copy(alpha = 0.25f),
                            activeContentColor = BlueAccent
                        )
                    ) { Text("密码") }
                    SegmentedButton(
                        selected = state.authType == AuthType.KEY,
                        onClick = { vm.update { copy(authType = AuthType.KEY) } },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { Icon(Icons.Default.Key, null) },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = BlueAccent.copy(alpha = 0.25f),
                            activeContentColor = BlueAccent
                        )
                    ) { Text("私钥") }
                }

                if (state.authType == AuthType.PASSWORD) {
                    // 密码库别名选择（功能 4）：选中后忽略下方内联密码输入
                    var credExpanded by remember { mutableStateOf(false) }
                    val selectedCred = credentials.firstOrNull { it.id == state.credentialId }
                    ExposedDropdownMenuBox(
                        expanded = credExpanded,
                        onExpandedChange = { credExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedCred?.alias ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("从密码库选择（可选）") },
                            placeholder = {
                                Text(if (credentials.isEmpty()) "暂无密码，可到私钥管理添加" else "不选 = 用下方密码")
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(credExpanded) },
                            colors = asshFieldColors(),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = credExpanded,
                            onDismissRequest = { credExpanded = false }
                        ) {
                            // 「不使用密码库」选项
                            DropdownMenuItem(
                                text = { Text("不使用密码库（手动输入）") },
                                onClick = {
                                    vm.update { copy(credentialId = null) }
                                    credExpanded = false
                                }
                            )
                            credentials.forEach { cred ->
                                DropdownMenuItem(
                                    text = { Text(cred.alias) },
                                    onClick = {
                                        vm.update { copy(credentialId = cred.id) }
                                        credExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // 仅当未选择密码库时，显示内联密码输入
                    if (state.credentialId == null) {
                        AsshTextField(
                            state.password,
                            { vm.update { copy(password = it, passwordTouched = true) } },
                            "密码",
                            if (state.hasStoredPassword && !state.passwordTouched)
                                "已保存（留空保持不变）" else "留空 = 连接时输入",
                            isPassword = true
                        )
                    }
                } else {
                    // 私钥别名下拉选择
                    var expanded by remember { mutableStateOf(false) }
                    val selected = keys.firstOrNull { it.id == state.keyId }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selected?.let { "${it.alias} (${it.keyType})" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("选择私钥") },
                            placeholder = { Text(if (keys.isEmpty()) "暂无私钥，请先到私钥管理添加" else "点击选择") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            colors = asshFieldColors(),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            keys.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text("${key.alias} (${key.keyType})") },
                                    onClick = {
                                        vm.update { copy(keyId = key.id) }
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // —— 分组 3：高级设置（默认折叠）——
            Card(
                colors = CardDefaults.cardColors(containerColor = Navy800),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "高级设置",
                            style = MaterialTheme.typography.titleSmall,
                            color = Slate400,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                            Text(if (advancedExpanded) "收起" else "展开", color = BlueAccent)
                            Icon(
                                if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, tint = BlueAccent
                            )
                        }
                    }
                    AnimatedVisibility(advancedExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsshTextField(
                                state.charset, { vm.update { copy(charset = it) } },
                                "字符集", "UTF-8"
                            )
                            AsshTextField(
                                state.initialCommand, { vm.update { copy(initialCommand = it) } },
                                "初始命令", "登录后自动执行，如 cd /var/www"
                            )
                        }
                    }
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = { vm.save(onDone) },
                enabled = state.valid && !state.saving,
                colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(if (state.saving) "保存中…" else "保存")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FormSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Slate400)
            content()
        }
    }
}

@Composable
private fun AsshTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    modifier: Modifier = Modifier.fillMaxWidth(),
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = Slate400.copy(alpha = 0.5f)) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType
        ),
        colors = asshFieldColors(),
        modifier = modifier
    )
}

@Composable
internal fun asshFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BlueAccent,
    unfocusedBorderColor = Navy700,
    focusedLabelColor = BlueAccent,
    unfocusedLabelColor = Slate400,
    cursorColor = BlueAccent
)
