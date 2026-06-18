package com.assh.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.assh.AsshApp
import com.assh.ui.nav.AsshNavGraph
import com.assh.ui.theme.AsshTheme
import com.assh.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：状态栏与内容背景对齐（视觉设计方案 §3.2）
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val themePrefs = (application as AsshApp).themePreferences

        setContent {
            val themeMode by themePrefs.mode.collectAsState(initial = ThemeMode.SYSTEM)
            AsshTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AsshNavGraph()
                }
            }
        }
    }
}
