package com.assh.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assh.AsshApp
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.Slate400
import com.assh.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * 主题设置页：白天 / 夜晚 / 跟随系统三选一。
 * 选中即写入 DataStore，整 app 实时切换（含状态栏图标明暗）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = (context.applicationContext as AsshApp).themePreferences
    val current by prefs.mode.collectAsState(initial = ThemeMode.SYSTEM)
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("主题", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
            item {
                ThemeOption(
                    icon = Icons.Default.Brightness7,
                    title = "白天",
                    subtitle = "始终使用浅色",
                    selected = current == ThemeMode.LIGHT,
                    onClick = { scope.launch { prefs.setMode(ThemeMode.LIGHT) } }
                )
            }
            item {
                ThemeOption(
                    icon = Icons.Default.Brightness4,
                    title = "夜晚",
                    subtitle = "始终使用深色",
                    selected = current == ThemeMode.DARK,
                    onClick = { scope.launch { prefs.setMode(ThemeMode.DARK) } }
                )
            }
            item {
                ThemeOption(
                    icon = Icons.Default.SettingsBrightness,
                    title = "跟随系统",
                    subtitle = "随系统深色模式自动切换",
                    selected = current == ThemeMode.SYSTEM,
                    onClick = { scope.launch { prefs.setMode(ThemeMode.SYSTEM) } }
                )
            }
        }
    }
}

@Composable
private fun ThemeOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, null,
                tint = if (selected) BlueAccent else Slate400,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "已选中", tint = BlueAccent)
            }
        }
    }
}
