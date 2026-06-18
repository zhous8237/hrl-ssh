package com.assh.ui.agent

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.assh.ai.AgentPhase
import com.assh.ai.CmdStatus
import com.assh.ai.PendingConfirm
import com.assh.ai.TimelineItem
import com.assh.ai.llm.LlmProfile
import com.assh.ui.hosts.asshFieldColors
import com.assh.ui.theme.AmberWarning
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400

/**
 * AI 运维任务页（聊天式布局）：顶部信息条 + 中间可滚动时间线 + **底部固定输入/控制区**。
 * 输入区始终在底部可见，追加指令无需滚回顶部；执行中只在时间线底部显示进度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    onBack: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    vm: AgentViewModel = viewModel()
) {
    val st by vm.state.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val activeId by vm.activeProfileId.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(st.finishedMessage) {
        st.finishedMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeFinished()
        }
    }

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    var goal by remember { mutableStateOf("") }
    var followup by remember { mutableStateOf("") }
    LaunchedEffect(hosts) {
        if (selectedHostId == null && hosts.isNotEmpty()) selectedHostId = hosts.first().id
    }

    val activeProfile = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    val hasConfig = activeProfile != null && activeProfile.model.isNotBlank() &&
        activeProfile.baseUrl.isNotBlank() && activeProfile.hasKey

    // 仅执行中自动滚到最新；非执行态（等待追加/结束）不强制滚动，返回页面保留位置
    val listState = rememberLazyListState()
    LaunchedEffect(st.timeline.size, st.running) {
        if (st.running && st.timeline.isNotEmpty()) listState.animateScrollToItem(st.timeline.size)
    }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("AI 运维", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "历史", tint = Slate400)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = Slate400)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶部信息条：执行中显示"会话进行中"（不让中途切模型）；等待追加/空闲时显示模型选择器，
            // 这样模型报错或一轮结束后，可在此切换模型再点底部「继续」（问题 2）
            when {
                !hasConfig -> InfoBar("尚未配置可用模型，点右上角设置添加并选中一个配置", AmberWarning)
                st.running -> InfoBar("● 会话进行中 · ${activeProfile?.name?.ifBlank { activeProfile.model } ?: ""}", GreenSuccess)
                else -> ModelSelectorBar(profiles, activeProfile, onSelect = { vm.setActiveProfile(it) })
            }

            // 时间线（主体，可滚动）+ 右下角"回顶/到底"浮动按钮
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (st.timeline.isEmpty() && !st.sessionActive) item {
                    Text(
                        "选择主机、描述目标后点「开始」。AI 会联网查资料、连服务器执行命令并实时显示在这里。",
                        color = Slate400, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
                itemsIndexed(st.timeline) { index, item ->
                    TimelineItemView(item, canDelete = !st.running, onDelete = { vm.removeTimelineItem(index) })
                }
                if (st.running) item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BlueAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(phaseLabel(st.phase, st.step), color = BlueAccent, style = MaterialTheme.typography.bodySmall)
                    }
                }
                st.pendingConfirm?.let { pending ->
                    item { ConfirmCard(pending, onApprove = { vm.approve() }, onReject = { vm.reject() }) }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
                ScrollToEdgeButtons(listState)
            }

            // 底部固定输入/控制区
            BottomControl(
                phase = st.phase,
                goal = goal, onGoalChange = { goal = it },
                followup = followup, onFollowupChange = { followup = it },
                hasConfig = hasConfig,
                hosts = hosts, selectedHostId = selectedHostId, onSelectHost = { selectedHostId = it },
                onStart = { selectedHostId?.let { vm.start(it, goal) } },
                onStop = { vm.stopTurn() },
                onContinue = { vm.continueTask(followup); followup = "" },
                onEnd = { vm.endSession() }
            )
        }

        st.pendingHostKey?.let { pending ->
            HostKeyChangeDialog(
                pending,
                onTrust = { vm.trustHostKey() },
                onReject = { vm.rejectHostKey() }
            )
        }
    }
}

@Composable
private fun InfoBar(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = color, style = MaterialTheme.typography.bodySmall, maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** 顶部"当前模型"行，可点击下拉直接切换已保存的模型配置 */
@Composable
private fun ModelSelectorBar(
    profiles: List<LlmProfile>,
    activeProfile: LlmProfile?,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { if (profiles.isNotEmpty()) expanded = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "当前模型：${activeProfile?.name?.ifBlank { activeProfile.model } ?: "未选择"}",
                color = Slate400, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = "切换模型", tint = Slate400, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name.ifBlank { p.model.ifBlank { "未命名" } }) },
                    onClick = { onSelect(p.id); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControl(
    phase: AgentPhase,
    goal: String, onGoalChange: (String) -> Unit,
    followup: String, onFollowupChange: (String) -> Unit,
    hasConfig: Boolean,
    hosts: List<com.assh.data.db.entity.HostEntity>,
    selectedHostId: Long?, onSelectHost: (Long) -> Unit,
    onStart: () -> Unit, onStop: () -> Unit, onContinue: () -> Unit, onEnd: () -> Unit
) {
    val running = phase == AgentPhase.CONNECTING || phase == AgentPhase.THINKING ||
        phase == AgentPhase.EXECUTING || phase == AgentPhase.WAITING_CONFIRM ||
        phase == AgentPhase.WAITING_HOSTKEY
    val canContinue = phase == AgentPhase.AWAITING_FOLLOWUP

    Surface(color = Navy800, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                running -> {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("停止本轮") }
                }
                canContinue -> {
                    OutlinedTextField(
                        value = followup, onValueChange = onFollowupChange,
                        label = { Text("追加指令（留空=重新执行原任务）") },
                        placeholder = { Text("继续让 AI 做的事；留空直接点继续则重跑原任务") },
                        minLines = 1, maxLines = 4, colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onContinue,
                            colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                            modifier = Modifier.weight(1f)
                        ) { Text("继续") }
                        OutlinedButton(onClick = onEnd, modifier = Modifier.weight(1f)) { Text("结束会话") }
                    }
                }
                else -> {
                    HostDropdown(hosts, selectedHostId, onSelectHost, enabled = true)
                    OutlinedTextField(
                        value = goal, onValueChange = onGoalChange,
                        label = { Text("要完成的目标") },
                        placeholder = { Text("例如：用 Docker 部署 cliproxy，跑在 8080 端口") },
                        minLines = 1, maxLines = 4, colors = asshFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onStart,
                        enabled = hasConfig && selectedHostId != null && goal.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("开始") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostDropdown(
    hosts: List<com.assh.data.db.entity.HostEntity>,
    selectedHostId: Long?,
    onSelect: (Long) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = hosts.firstOrNull { it.id == selectedHostId }
    val label = selected?.let { "${it.label}（${it.username}@${it.host}:${it.port}）" }
        ?: if (hosts.isEmpty()) "无可用主机，请先在主机页添加" else "请选择主机"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = label, onValueChange = {}, readOnly = true,
            label = { Text("目标主机") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled, colors = asshFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            hosts.forEach { h ->
                DropdownMenuItem(
                    text = { Text("${h.label}（${h.username}@${h.host}:${h.port}）") },
                    onClick = { onSelect(h.id); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun TimelineItemView(item: TimelineItem, canDelete: Boolean, onDelete: () -> Unit) {
    when (item) {
        is TimelineItem.UserText -> DeletableCard(BlueAccent.copy(alpha = 0.15f), canDelete, onDelete) {
            SelectionContainer { Text("🧑 ${item.text}", style = MaterialTheme.typography.bodyMedium) }
        }

        is TimelineItem.AiText -> DeletableCard(Navy800, canDelete, onDelete) {
            SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyMedium) }
        }

        is TimelineItem.Notice -> Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(item.text, color = AmberWarning, style = MaterialTheme.typography.bodySmall)
            }
            if (canDelete) IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "隐藏", tint = Slate400, modifier = Modifier.size(16.dp))
            }
        }

        is TimelineItem.Command -> {
            var expanded by remember { mutableStateOf(false) }
            DeletableCard(Navy800, canDelete, onDelete) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "$ ${item.command}",
                                color = BlueAccent, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                            item.why?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = Slate400, style = MaterialTheme.typography.bodySmall)
                            }
                            if (item.status == CmdStatus.DONE) {
                                val res = item.result
                                val ok = res?.success == true
                                val statusLine = buildString {
                                    append(if (ok) "✓ exit=" else "✗ exit=")
                                    append(res?.exitStatus?.toString() ?: "未知")
                                    if (res?.timedOut == true) append(" · 超时")
                                    if (res?.truncated == true) append(" · 输出已截断")
                                }
                                Text(
                                    statusLine,
                                    color = if (ok) GreenSuccess else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                val output = listOfNotNull(
                                    res?.stdout?.takeIf { it.isNotBlank() },
                                    res?.stderr?.takeIf { it.isNotBlank() }?.let { "[stderr]\n$it" }
                                ).joinToString("\n")
                                if (output.isNotBlank()) {
                                    Text(
                                        output,
                                        color = Slate400, fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = if (expanded) Int.MAX_VALUE else 6
                                    )
                                }
                            }
                        }
                    }
                    if (item.status == CmdStatus.RUNNING) {
                        item.partialOutput?.takeIf { it.isNotBlank() }?.let { partial ->
                            Text(
                                partial,
                                color = Slate400, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 8
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = BlueAccent)
                            Spacer(Modifier.width(8.dp))
                            Text("执行中…", color = Slate400, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        val res = item.result
                        val output = listOfNotNull(
                            res?.stdout?.takeIf { it.isNotBlank() },
                            res?.stderr?.takeIf { it.isNotBlank() }
                        ).joinToString("\n")
                        if (output.isNotBlank()) Text(
                            if (expanded) "收起" else "展开输出",
                            color = BlueAccent, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable { expanded = !expanded }
                        )
                    }
                }
            }
        }
    }
}

/** 带可选「隐藏」按钮的卡片：内容在左，删除按钮在右上角 */
@Composable
private fun DeletableCard(
    bg: androidx.compose.ui.graphics.Color,
    canDelete: Boolean,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp)) {
            Box(Modifier.weight(1f)) { content() }
            if (canDelete) IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "隐藏", tint = Slate400, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ConfirmCard(pending: PendingConfirm, onApprove: () -> Unit, onReject: () -> Unit) {
    val dangerous = pending.classification.level == com.assh.ai.RiskLevel.DANGEROUS
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (dangerous) "⚠ 需要确认危险命令" else "请确认是否执行该命令",
                color = if (dangerous) AmberWarning else BlueAccent, fontWeight = FontWeight.Bold
            )
            SelectionContainer {
                Text(
                    "$ ${pending.command}",
                    color = BlueAccent, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            pending.why?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Slate400, style = MaterialTheme.typography.bodySmall)
            }
            pending.classification.matchedRule?.let {
                Text("命中规则：$it", color = AmberWarning, style = MaterialTheme.typography.bodySmall)
            }
            pending.classification.reason?.let {
                Text(it, color = Slate400, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("拒绝") }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dangerous) MaterialTheme.colorScheme.error else BlueAccent
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(if (dangerous) "仍要执行" else "执行") }
            }
        }
    }
}

@Composable
private fun HostKeyChangeDialog(
    pending: com.assh.ai.PendingHostKey,
    onTrust: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = AmberWarning) },
        title = { Text("服务器指纹已变更") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${pending.hostPort} 的 host key 与上次记录的不一致。可能是服务器重装/迁移，也可能是中间人攻击（MITM）。确认确实变更后再信任。",
                    style = MaterialTheme.typography.bodyMedium
                )
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "原指纹：${pending.savedFingerprint}",
                            color = Slate400, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "新指纹：${pending.actualFingerprint}",
                            color = AmberWarning, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) { Text("信任并继续", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onReject) { Text("取消") } },
        containerColor = Navy800
    )
}

/** 时间线右下角的"回顶部 / 到底部"浮动按钮：到顶时隐藏回顶、到底时隐藏到底、不满一屏两者都不显示 */
@Composable
private fun BoxScope.ScrollToEdgeButtons(listState: androidx.compose.foundation.lazy.LazyListState) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (listState.canScrollBackward) {
            ScrollFab(Icons.Default.KeyboardArrowUp, "回到顶部") {
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
        if (listState.canScrollForward) {
            ScrollFab(Icons.Default.KeyboardArrowDown, "到最底部") {
                scope.launch {
                    listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                }
            }
        }
    }
}

@Composable
private fun ScrollFab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Navy800.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, desc, tint = BlueAccent, modifier = Modifier.size(24.dp))
        }
    }
}

private fun phaseLabel(phase: AgentPhase, step: Int): String = when (phase) {
    AgentPhase.IDLE -> ""
    AgentPhase.CONNECTING -> "连接服务器中…"
    AgentPhase.THINKING -> "AI 思考中…（第 $step 步）"
    AgentPhase.EXECUTING -> "执行命令中…（第 $step 步）"
    AgentPhase.WAITING_CONFIRM -> "等待确认命令…"
    AgentPhase.WAITING_HOSTKEY -> "等待确认服务器指纹变更…"
    AgentPhase.AWAITING_FOLLOWUP -> "本轮完成，可追加指令继续"
    AgentPhase.DONE -> "会话已结束"
    AgentPhase.ERROR -> "已出错"
    AgentPhase.CANCELLED -> "已取消"
}
