package com.assh.ui.common

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.assh.ui.config.readBackgroundSystemState
import com.assh.ui.theme.AmberWarning

/** 系统设置可能在外部改变，每次返回都读取真实状态，不保存授权假象。 */
@Composable
fun RefreshOnResume(refresh: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val currentRefresh by rememberUpdatedState(refresh)
    DisposableEffect(owner) {
        currentRefresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentRefresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun BackgroundExecutionHint(onOpenConfig: () -> Unit) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(readBackgroundSystemState(context).batteryExempt) }
    RefreshOnResume { exempt = readBackgroundSystemState(context).batteryExempt }
    if (!exempt) {
        TextButton(onClick = onOpenConfig) {
            Text("锁屏持续运行：建议在配置中允许电池不受限制", color = AmberWarning)
        }
    }
}
