package com.assh.ui.config

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assh.ui.theme.AmberWarning
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.RedError
import com.assh.ui.theme.Slate200
import com.assh.ui.theme.Slate400
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect

/**
 * 网络检测页（功能 3）：枚举本机 IPv4/IPv6 地址，测试两种协议栈出网可达性，
 * 给出"该用哪种地址连接服务器"的建议。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkCheckScreen(onBack: () -> Unit) {
    var report by remember { mutableStateOf<NetworkReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runProbe() {
        if (loading) return
        report = null          // 清掉上次结果，避免检测中仍显示旧数据
        loading = true
        scope.launch {
            report = NetworkProbe.probe()
            loading = false
        }
    }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("网络检测", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { runProbe() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新检测", tint = BlueAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        if (report == null && !loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { runProbe() },
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始网络检测")
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // —— 协议栈出网状态 ——
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProtocolCard(
                        label = "IPv4",
                        reachable = report?.v4Internet,
                        loading = loading,
                        modifier = Modifier.weight(1f)
                    )
                    ProtocolCard(
                        label = "IPv6",
                        reachable = report?.v6Internet,
                        loading = loading,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // —— 建议 ——
            report?.let { r ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            r.advice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate200,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            // —— 本机地址列表 ——
            item {
                Text(
                    "本机地址",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate400,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            val locals = report?.locals.orEmpty()
            if (locals.isEmpty() && !loading) {
                item {
                    Text(
                        "未检测到网络地址",
                        color = Slate400.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(locals) { addr ->
                    LocalAddressCard(addr)
                }
            }
        }
    }
}

@Composable
private fun ProtocolCard(
    label: String,
    reachable: Boolean?,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    val (statusText, statusColor) = when {
        loading -> "检测中…" to Slate400
        reachable == true -> "可出网" to GreenSuccess
        reachable == false -> "不可达" to RedError
        else -> "未测" to Slate400
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, fontWeight = FontWeight.Bold, color = Slate200)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(
                        color = BlueAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(statusText, color = statusColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LocalAddressCard(addr: LocalAddress) {
    val tagColor = if (addr.isV6) AmberWarning else BlueAccent
    val tag = if (addr.isV6) "IPv6" else "IPv4"
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .background(tagColor.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(tag, color = tagColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    addr.address,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate200
                )
                val notes = buildList {
                    add(addr.ifaceName)
                    if (addr.isLoopback) add("回环")
                    if (addr.isLinkLocal) add("链路本地")
                }
                Text(
                    notes.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate400
                )
            }
        }
    }
}
