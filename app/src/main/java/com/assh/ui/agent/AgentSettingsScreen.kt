package com.assh.ui.agent

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.ai.ConfirmPolicy
import com.assh.ai.llm.LlmProfile
import com.assh.ai.llm.LlmProvider
import com.assh.ui.hosts.asshFieldColors
import com.assh.ui.theme.AmberWarning
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400

/** AI 助手设置：管理多套模型配置，选中一个作为当前使用。列表 ⇄ 编辑两视图。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(
    onBack: () -> Unit,
    vm: AgentViewModel = viewModel()
) {
    val profiles by vm.profiles.collectAsState()
    val activeId by vm.activeProfileId.collectAsState()
    val msg by vm.settingsMessage.collectAsState()
    val busy by vm.settingsBusy.collectAsState()
    val models by vm.models.collectAsState()
    val fetching by vm.fetchingModels.collectAsState()
    val confirmPolicy by vm.confirmPolicy.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(msg) {
        msg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeSettingsMessage()
        }
    }

    var editing by remember { mutableStateOf<LlmProfile?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }

    val current = editing
    if (current != null) {
        BackHandler { editing = null; vm.clearModels() }
        ProfileEditor(
            profile = current, isNew = editingIsNew, busy = busy, models = models, fetching = fetching,
            vm = vm,
            onDone = { editing = null; vm.clearModels() }
        )
        return
    }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("AI 助手设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editing = LlmProfile(
                            id = "p" + System.currentTimeMillis(),
                            name = "", provider = LlmProvider.OPENAI, baseUrl = "", model = ""
                        )
                        editingIsNew = true
                        vm.clearModels()
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "添加配置", tint = BlueAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "保存多套模型配置，选中其一作为 AI 当前使用。点右上角 + 添加。",
                    color = Slate400, style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                ConfirmPolicyCard(confirmPolicy, onChange = { vm.setConfirmPolicy(it) })
            }
            items(profiles, key = { it.id }) { p ->
                ProfileCard(
                    profile = p, selected = p.id == activeId,
                    onSelect = { vm.setActiveProfile(p.id) },
                    onEdit = { editing = p; editingIsNew = false; vm.clearModels() },
                    onDelete = { vm.deleteProfile(p.id) }
                )
            }
            if (profiles.isEmpty()) item {
                Text("还没有配置，点右上角 + 添加。", color = AmberWarning, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: LlmProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name.ifBlank { profile.model.ifBlank { "未命名" } },
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) GreenSuccess else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (profile.provider == LlmProvider.OPENAI) "OpenAI" else "Claude",
                        color = Slate400, style = MaterialTheme.typography.labelSmall, maxLines = 1
                    )
                }
                Text(
                    "${profile.model.ifBlank { "(未填模型)" }} · ${profile.baseUrl}",
                    color = Slate400, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑", tint = Slate400) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除", tint = AmberWarning) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(
    profile: LlmProfile,
    isNew: Boolean,
    busy: Boolean,
    models: List<String>,
    fetching: Boolean,
    vm: AgentViewModel,
    onDone: () -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var provider by remember { mutableStateOf(profile.provider) }
    var baseUrl by remember { mutableStateOf(profile.baseUrl) }
    var model by remember { mutableStateOf(profile.model) }
    var rpm by remember { mutableStateOf(profile.rpmLimit.toString()) }
    var key by remember { mutableStateOf("") }
    var keyTouched by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    fun keyArg() = key.takeIf { keyTouched }
    val editingId = profile.id.takeIf { !isNew }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加配置" else "编辑配置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(Navy900).imePadding().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { vm.testConnection(provider, baseUrl, model, keyArg(), editingId) },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BlueAccent)
                    else Text("测试连接")
                }
                Button(
                    onClick = {
                        vm.saveProfile(
                            profile.copy(
                                name = name, provider = provider, baseUrl = baseUrl, model = model,
                                rpmLimit = rpm.trim().toIntOrNull()?.coerceAtLeast(0) ?: 5
                            ),
                            keyArg()
                        )
                        onDone()
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("基本") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("配置名称") }, placeholder = { Text("如 我的GPT / 公司中转") },
                    singleLine = true, colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                var providerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = !providerExpanded }
                ) {
                    OutlinedTextField(
                        value = if (provider == LlmProvider.OPENAI) "OpenAI 兼容（自定义 / 中转 / 本地）" else "Claude 官方（Anthropic）",
                        onValueChange = {}, readOnly = true,
                        label = { Text("供应商") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                        colors = asshFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("OpenAI 兼容（自定义 / 中转 / 本地）") },
                            onClick = {
                                if (provider != LlmProvider.OPENAI) { provider = LlmProvider.OPENAI; vm.clearModels() }
                                providerExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Claude 官方（Anthropic）") },
                            onClick = {
                                if (provider != LlmProvider.CLAUDE) { provider = LlmProvider.CLAUDE; vm.clearModels() }
                                providerExpanded = false
                            }
                        )
                    }
                }
            }

            SectionCard("接入") {
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("接口地址") },
                    placeholder = {
                        Text(if (provider == LlmProvider.OPENAI) "https://api.openai.com/v1 或中转/v1" else "https://api.anthropic.com")
                    },
                    singleLine = true, colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                ModelField(model, { model = it }, models, fetching) {
                    vm.fetchModels(provider, baseUrl, model, keyArg(), editingId)
                }
                KeyField(key, profile.hasKey, showKey, { showKey = !showKey }) {
                    key = it; keyTouched = true
                }
                OutlinedTextField(
                    value = rpm,
                    onValueChange = { rpm = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("每分钟请求上限 RPM") },
                    placeholder = { Text("默认 5（适配限流平台）；不限制填 0") },
                    singleLine = true, colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(
    value: String,
    onValueChange: (String) -> Unit,
    models: List<String>,
    fetching: Boolean,
    onFetch: () -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text("模型名") },
        placeholder = { Text("点右侧获取，或手动输入") },
        singleLine = true,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (models.isNotEmpty()) {
                    IconButton(onClick = { showSheet = true }) {
                        Icon(Icons.Default.ExpandMore, contentDescription = "从已获取模型中选择", tint = BlueAccent)
                    }
                }
                if (fetching) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = BlueAccent)
                } else {
                    IconButton(onClick = onFetch) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "获取模型列表", tint = BlueAccent)
                    }
                }
            }
        },
        colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
    )

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Navy800) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "选择模型（${models.size}）",
                    fontWeight = FontWeight.SemiBold, color = BlueAccent,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(models) { m ->
                        Text(
                            m,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onValueChange(m); showSheet = false }
                                .padding(vertical = 14.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyField(
    value: String,
    hasSaved: Boolean,
    show: Boolean,
    onToggleShow: () -> Unit,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text("API Key") },
        placeholder = { Text(if (hasSaved) "已保存，留空则不修改" else "sk-…") },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleShow) {
                Icon(
                    if (show) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = null, tint = Slate400
                )
            }
        },
        colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ConfirmPolicyCard(current: ConfirmPolicy, onChange: (ConfirmPolicy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = Slate400,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "AI 命令执行确认", color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                )
                Text(
                    confirmPolicyLabel(current), color = BlueAccent,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1
                )
                Icon(Icons.Default.ExpandMore, contentDescription = "切换确认方式", tint = Slate400)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ConfirmPolicyItem("每条命令都确认", "AI 每执行一条都暂停等你确认（最谨慎）",
                    current == ConfirmPolicy.ALWAYS) { onChange(ConfirmPolicy.ALWAYS); expanded = false }
                ConfirmPolicyItem("仅危险命令确认（默认）", "删除/格式化/关机等高危命令才需确认",
                    current == ConfirmPolicy.DANGEROUS_ONLY) { onChange(ConfirmPolicy.DANGEROUS_ONLY); expanded = false }
                ConfirmPolicyItem("从不确认", "全部自动执行、不打断（风险自负）",
                    current == ConfirmPolicy.NEVER) { onChange(ConfirmPolicy.NEVER); expanded = false }
            }
        }
    }
}

private fun confirmPolicyLabel(p: ConfirmPolicy): String = when (p) {
    ConfirmPolicy.ALWAYS -> "每条都确认"
    ConfirmPolicy.DANGEROUS_ONLY -> "仅危险命令"
    ConfirmPolicy.NEVER -> "从不确认"
}

@Composable
private fun ConfirmPolicyItem(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, color = if (selected) GreenSuccess else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium)
                Text(desc, color = Slate400, style = MaterialTheme.typography.bodySmall)
            }
        },
        onClick = onClick
    )
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
