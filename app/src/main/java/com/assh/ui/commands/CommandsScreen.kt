package com.assh.ui.commands

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.data.db.entity.CommandEntity
import com.assh.data.db.entity.HostEntity
import com.assh.ui.common.TopBarAddButton
import com.assh.ui.hosts.asshFieldColors
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400

/** 自定义命令管理 Tab：顶栏标题即作用域切换（全局/各主机），单作用域列表 + 搜索 + CRUD */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(
    onBack: (() -> Unit)? = null,
    vm: CommandsViewModel = viewModel()
) {
    val commands by vm.commands.collectAsState()
    val hosts by vm.hosts.collectAsState()
    var editTarget by remember { mutableStateOf<CommandEntity?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CommandEntity?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var scopeMenu by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    // 作用域选项由现有命令派生：有全局命令→显示「全局」；某主机有命令→显示该主机别名。
    // commands 是 Room 的 Flow，增删改实时驱动重组，作用域列表随之实时更新。
    val scopes: List<ScopeOption> = remember(commands, hosts) {
        buildList {
            if (commands.any { it.hostId == null }) add(ScopeOption("global", "全局", null))
            val hostIdsWithCmds = commands.mapNotNull { it.hostId }.toSet()
            hosts.filter { it.id in hostIdsWithCmds }
                .forEach { add(ScopeOption("h${it.id}", it.label, it.id)) }
            val known = hosts.mapTo(HashSet()) { it.id }
            hostIdsWithCmds.filter { it !in known }
                .forEach { add(ScopeOption("h$it", "主机 #$it", it)) }
        }
    }
    // 选中作用域若被删空则回退到第一个，保证标题与列表始终有效
    val current = scopes.firstOrNull { it.key == selectedKey } ?: scopes.firstOrNull()

    // 进入搜索态时自动聚焦输入框、弹出键盘
    LaunchedEffect(searching) {
        if (searching) runCatching { searchFocus.requestFocus() }
    }
    // 搜索态下按返回键先退出搜索（不离开页面）
    BackHandler(enabled = searching) { searching = false; query = "" }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("在「${current?.label ?: "当前"}」内搜索") }, singleLine = true,
                            colors = asshFieldColors(),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                        )
                    } else if (current == null) {
                        Text("自定义命令")
                    } else {
                        Box {
                            Row(
                                Modifier.clickable(enabled = scopes.size > 1) { scopeMenu = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(current.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                if (scopes.size > 1) {
                                    Icon(Icons.Default.ExpandMore, contentDescription = "切换作用域", tint = BlueAccent)
                                }
                            }
                            DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
                                scopes.forEach { s ->
                                    val count = commands.count { it.hostId == s.hostId }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${s.label}（$count）",
                                                color = if (s.key == current.key) BlueAccent else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = { selectedKey = s.key; scopeMenu = false; query = "" }
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "退出搜索", tint = Slate400)
                        }
                    } else {
                        if (current != null) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Default.Search, contentDescription = "搜索命令", tint = Slate400)
                            }
                        }
                        TopBarAddButton(onClick = { showNew = true }, contentDescription = "新增命令")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (commands.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无自定义命令，点 + 新增", color = Slate400)
                }
            } else {
                val q = query.trim()
                val scoped = commands.filter { it.hostId == current?.hostId }
                val filtered = if (q.isBlank()) scoped
                    else scoped.filter { it.label.contains(q, true) || it.command.contains(q, true) }

                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (q.isBlank()) "该作用域暂无命令" else "无匹配命令", color = Slate400)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { cmd ->
                            CommandCard(cmd, onEdit = { editTarget = cmd }, onDelete = { deleteTarget = cmd })
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        CommandEditDialog(
            initial = null, hosts = hosts,
            fixedHostId = null,
            onDismiss = { showNew = false },
            onSave = { vm.save(it); showNew = false }
        )
    }
    editTarget?.let { cmd ->
        CommandEditDialog(
            initial = cmd, hosts = hosts,
            onDismiss = { editTarget = null },
            onSave = { vm.save(it); editTarget = null }
        )
    }
    deleteTarget?.let { cmd ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Navy800,
            title = { Text("删除命令") },
            text = { Text("确认删除命令「${cmd.label}」？") },
            confirmButton = {
                TextButton(onClick = { vm.delete(cmd); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消", color = Slate400) } }
        )
    }
}

/** 顶栏作用域选项：全局（hostId=null）或某台主机 */
private data class ScopeOption(val key: String, val label: String, val hostId: Long?)

@Composable
private fun CommandCard(
    command: CommandEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(command.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (command.appendEnter) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = "自动回车",
                            tint = GreenSuccess, modifier = Modifier.width(16.dp)
                        )
                    }
                }
                Text(
                    command.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Slate400,
                    maxLines = 1
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = Slate400,
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** 新增/编辑命令对话框；也被终端屏的「+」长按复用 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandEditDialog(
    initial: CommandEntity?,
    hosts: List<HostEntity>,
    onDismiss: () -> Unit,
    onSave: (CommandEntity) -> Unit,
    fixedHostId: Long? = null   // 终端屏新增时固定为当前主机
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var appendEnter by remember { mutableStateOf(initial?.appendEnter ?: true) }
    var hostId by remember { mutableStateOf(initial?.hostId ?: fixedHostId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Navy800,
        title = { Text(if (initial == null) "新增命令" else "编辑命令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("显示名") }, placeholder = { Text("如：查看日志") },
                    singleLine = true, colors = asshFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = command, onValueChange = { command = it },
                    label = { Text("命令") }, placeholder = { Text("tail -f /var/log/syslog") },
                    colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = appendEnter, onCheckedChange = { appendEnter = it },
                        colors = CheckboxDefaults.colors(checkedColor = BlueAccent)
                    )
                    Text("自动追加回车执行")
                }
                if (fixedHostId == null) {
                    var expanded by remember { mutableStateOf(false) }
                    val selectedLabel = hostId?.let { id -> hosts.firstOrNull { it.id == id }?.label } ?: "全局"
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selectedLabel, onValueChange = {}, readOnly = true,
                            label = { Text("作用域") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            colors = asshFieldColors(),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("全局") },
                                onClick = { hostId = null; expanded = false }
                            )
                            hosts.forEach { h ->
                                DropdownMenuItem(
                                    text = { Text(h.label) },
                                    onClick = { hostId = h.id; expanded = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        CommandEntity(
                            id = initial?.id ?: 0,
                            label = label.trim(),
                            command = command,
                            appendEnter = appendEnter,
                            hostId = hostId,
                            sortOrder = initial?.sortOrder ?: 0
                        )
                    )
                },
                enabled = label.isNotBlank() && command.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Slate400) }
        }
    )
}
