package com.assh.ui.hosts

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.data.db.entity.HostEntity
import com.assh.ssh.ConnState
import com.assh.ui.common.TopBarAddButton
import com.assh.ui.theme.AvatarGradients
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.RedError
import com.assh.ui.theme.Slate400
import kotlin.math.abs

/**
 * 服务器列表 Tab：彩色缩写头像 + 状态呼吸灯 + FAB。
 * 点击卡片：已连接 → 直接回到会话；未连接 → 弹操作单（立即连接 / 编辑 / 克隆 / 删除）。
 * 长按卡片：始终弹操作单（已连接时含「断开连接」）。删除有二次确认。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HostsScreen(
    onOpenTerminal: (Long) -> Unit,
    onEditHost: (Long) -> Unit,
    onAddHost: () -> Unit,
    vm: HostsViewModel = viewModel()
) {
    val hosts by vm.hosts.collectAsState()
    val connStates by vm.connStates.collectAsState()
    var sheetHost by remember { mutableStateOf<HostEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<HostEntity?>(null) }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("hrl-ssh", fontWeight = FontWeight.Bold) },
                actions = {
                    TopBarAddButton(onClick = onAddHost, contentDescription = "新建服务器")
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Navy900,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        if (hosts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有服务器", color = Slate400, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("点击右上角 + 添加第一台", color = Slate400.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(hosts, key = { it.id }) { host ->
                    val state = connStates[host.id] ?: ConnState.IDLE
                    HostCard(
                        host = host,
                        state = state,
                        // 已连接：点击直接回会话（终端屏会复用活跃 TerminalSession）；
                        // 其他状态：点击弹操作单。长按始终弹操作单。
                        onClick = {
                            if (state == ConnState.CONNECTED) onOpenTerminal(host.id)
                            else sheetHost = host
                        },
                        onLongClick = { sheetHost = host }
                    )
                }
            }
        }
    }

    // 点击/长按卡片弹出底部操作单
    sheetHost?.let { host ->
        val hostConnected = (connStates[host.id] ?: ConnState.IDLE) == ConnState.CONNECTED
        ModalBottomSheet(
            onDismissRequest = { sheetHost = null },
            containerColor = Navy800
        ) {
            Text(
                host.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Text(
                "${host.username}@${displayHost(host.host)}:${host.port}",
                style = MaterialTheme.typography.bodySmall,
                color = Slate400,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Navy900)
            ListItem(
                headlineContent = {
                    Text(
                        if (hostConnected) "回到会话" else "立即连接",
                        color = GreenSuccess, fontWeight = FontWeight.SemiBold
                    )
                },
                leadingContent = { Icon(Icons.Default.PlayArrow, null, tint = GreenSuccess) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Navy800),
                modifier = Modifier.clickable {
                    sheetHost = null; onOpenTerminal(host.id)
                }
            )
            if (hostConnected) {
                ListItem(
                    headlineContent = { Text("断开连接", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.PowerSettingsNew, null, tint = MaterialTheme.colorScheme.error) },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Navy800),
                    modifier = Modifier.clickable {
                        vm.disconnect(host.id); sheetHost = null
                    }
                )
            }
            ListItem(
                headlineContent = { Text("编辑") },
                leadingContent = { Icon(Icons.Default.Edit, null, tint = BlueAccent) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Navy800),
                modifier = Modifier.clickable {
                    sheetHost = null; onEditHost(host.id)
                }
            )
            ListItem(
                headlineContent = { Text("复制") },
                leadingContent = { Icon(Icons.Default.ContentCopy, null, tint = Slate400) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Navy800),
                modifier = Modifier.clickable {
                    vm.clone(host); sheetHost = null
                }
            )
            ListItem(
                headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingContent = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Navy800),
                modifier = Modifier.clickable {
                    sheetHost = null; deleteTarget = host
                }
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    // 删除二次确认
    deleteTarget?.let { host ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Navy800,
            title = { Text("删除服务器") },
            text = { Text("确定删除「${host.label}」？已建立的连接会被断开，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(host); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = Slate400) }
            }
        )
    }
}

/** IPv6 地址显示时补上 [] 便于阅读（存储与连接用裸地址） */
private fun displayHost(host: String): String =
    if (host.contains(':')) "[$host]" else host

@Composable
private fun HostCard(
    host: HostEntity,
    state: ConnState,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HostAvatar(host.label)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    host.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${host.username}@${displayHost(host.host)}:${host.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }
            StatusLabel(state)
        }
    }
}

/** 彩色缩写头像：主机名取缩写（"Prod-DB" → "PD"），低饱和度渐变背景 */
@Composable
private fun HostAvatar(label: String) {
    val initials = remember(label) {
        label.split(Regex("[\\s\\-_./]+"))
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
    }
    val gradient = AvatarGradients[abs(label.hashCode()) % AvatarGradients.size]

    Box(
        modifier = Modifier
            .size(46.dp)
            .background(
                Brush.linearGradient(listOf(gradient.first, gradient.second)),
                RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** 连接状态中文标签：连接中（闪烁蓝）/ 已连接（绿）/ 已断开 / 连接失败 / 未连接 */
@Composable
private fun StatusLabel(state: ConnState) {
    val (text, color) = when (state) {
        ConnState.CONNECTING -> "连接中" to BlueAccent
        ConnState.CONNECTED -> "已连接" to GreenSuccess
        ConnState.DISCONNECTED -> "已断开" to Slate400
        ConnState.ERROR -> "连接失败" to RedError
        ConnState.IDLE -> "未连接" to Slate400.copy(alpha = 0.6f)
    }

    val alpha = if (state == ConnState.CONNECTING) {
        val transition = rememberInfiniteTransition(label = "status")
        val animated by transition.animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "statusAlpha"
        )
        animated
    } else 1f

    Box(
        modifier = Modifier
            .alpha(alpha)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}
