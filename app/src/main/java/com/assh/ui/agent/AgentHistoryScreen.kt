package com.assh.ui.agent

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.ai.AgentRunRecord
import com.assh.ai.RecordEntry
import com.assh.ui.theme.AmberWarning
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** AI 运维任务历史：列表 → 详情（只读时间线），可删除单条 / 清空。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHistoryScreen(
    onBack: () -> Unit,
    onResume: (AgentRunRecord) -> Unit,
    vm: AgentViewModel = viewModel()
) {
    val history by vm.history.collectAsState()
    var detail by remember { mutableStateOf<AgentRunRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<AgentRunRecord?>(null) }

    val current = detail
    if (current != null) {
        BackHandler { detail = null }
        RecordDetailScreen(current, onBack = { detail = null }, onResume = { onResume(current) })
        return
    }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("任务历史", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (history.isNotEmpty()) IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空", tint = Slate400)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无历史记录", color = Slate400)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(history, key = { it.id }) { r ->
                    HistoryCard(r, onClick = { detail = r }, onDelete = { confirmDelete = r })
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Navy800,
            title = { Text("清空历史") },
            text = { Text("将删除全部任务历史记录，无法恢复。继续？") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) {
                    Text("清空", color = AmberWarning)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消", color = Slate400) } }
        )
    }

    confirmDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Navy800,
            title = { Text("删除记录") },
            text = { Text("确认删除这条任务历史？无法恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteHistory(rec.id); confirmDelete = null }) {
                    Text("删除", color = AmberWarning)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消", color = Slate400) } }
        )
    }
}

@Composable
private fun HistoryCard(record: AgentRunRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    "${record.hostLabel.ifBlank { "(主机未知)" }} · ${formatTime(record.startedAt)} · ${record.phaseLabel}",
                    color = Slate400, style = MaterialTheme.typography.bodySmall, maxLines = 1
                )
            }
            Text(
                if (record.success) "✓" else "·",
                color = if (record.success) GreenSuccess else Slate400,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除", tint = AmberWarning) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordDetailScreen(record: AgentRunRecord, onBack: () -> Unit, onResume: () -> Unit) {
    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text(record.title, fontWeight = FontWeight.Bold, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (record.hostId >= 0) IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "继续此会话", tint = GreenSuccess)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "${record.hostLabel.ifBlank { "(主机未知)" }} · ${formatTime(record.startedAt)}" +
                        if (record.hostId >= 0) " · 点右上角 ▶ 可继续此会话" else "",
                    color = Slate400, style = MaterialTheme.typography.bodySmall
                )
            }
            items(record.entries) { e -> RecordEntryView(e) }
        }
    }
}

@Composable
private fun RecordEntryView(e: RecordEntry) {
    when (e.kind) {
        "user" -> Card(
            colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
        ) {
            SelectionContainer { Text("🧑 ${e.text}", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        "notice" -> SelectionContainer { Text(e.text, color = AmberWarning, style = MaterialTheme.typography.bodySmall) }
        "command" -> Card(
            colors = CardDefaults.cardColors(containerColor = Navy800),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
        ) {
            SelectionContainer {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$ ${e.command}", color = BlueAccent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    val statusLine = buildString {
                        append(if (e.exit == 0 && !e.timedOut) "✓ exit=" else "✗ exit=")
                        append(e.exit?.toString() ?: "未知")
                        if (e.timedOut) append(" · 超时")
                        if (e.truncated) append(" · 截断")
                    }
                    Text(statusLine, color = if (e.exit == 0 && !e.timedOut) GreenSuccess else AmberWarning, style = MaterialTheme.typography.bodySmall)
                    val output = listOf(e.stdout, e.stderr).filter { it.isNotBlank() }.joinToString("\n")
                    if (output.isNotBlank()) {
                        Text(output, color = Slate400, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        else -> Card( // "ai"
            colors = CardDefaults.cardColors(containerColor = Navy800),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
        ) {
            SelectionContainer { Text(e.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
