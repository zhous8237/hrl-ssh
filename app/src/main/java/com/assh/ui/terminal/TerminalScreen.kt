package com.assh.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.app.Activity
import android.content.ContextWrapper
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assh.data.db.entity.CommandEntity
import com.assh.ssh.ConnState
import com.assh.ui.commands.CommandEditDialog
import com.assh.ui.hosts.asshFieldColors
import com.assh.ui.theme.AmberWarning
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.GreenSuccess
import com.assh.ui.theme.Navy700
import com.assh.ui.theme.Navy800
import com.assh.ui.theme.Navy900
import com.assh.ui.theme.RedError
import com.assh.ui.theme.Slate200
import com.assh.ui.theme.Slate400
import com.termux.view.TerminalView
import kotlinx.coroutines.delay

/**
 * 终端工作区（视觉设计方案 §2.2）：
 * 沉浸式（状态栏对齐 #0F172A）+ TerminalView(AndroidView) + 键盘上方悬浮工具栏
 * （Ctrl/Alt/Esc 粘滞高亮 + 特殊键 + 自定义命令 Chip 横滚 + 长按新增）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    hostId: Long,
    onBack: () -> Unit,
    vm: TerminalViewModel = viewModel()
) {
    LaunchedEffect(hostId) { vm.init(hostId) }

    val ui by vm.ui.collectAsState()
    val commands by vm.commands.collectAsState()
    val copyRequest by vm.copyRequest.collectAsState()
    val termSession by vm.termSessionFlow.collectAsState()
    val context = LocalContext.current
    val composeView = LocalView.current
    val imeVisible = WindowInsets.isImeVisible

    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var showAddCommand by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }

    // 终端屏顶部状态栏区永远是深色（Navy900），状态栏图标须强制浅色，否则白天模式下深图标配深底看不清。
    // 退出终端屏时恢复（由全局 AsshTheme 按主题重设）。
    DisposableEffect(Unit) {
        val activity = composeView.context.findTerminalActivity()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, composeView) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (previous != null) controller.isAppearanceLightStatusBars = previous
            // 解绑重绘回调，避免 ViewModel 持有已销毁的 TerminalView
            vm.onScreenUpdated = null
        }
    }

    // 复制回调 → 系统剪贴板
    LaunchedEffect(copyRequest) {
        copyRequest?.let {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", it))
            vm.copyRequest.value = null
        }
    }

    // 连接成功提示条 2 秒后消失
    LaunchedEffect(ui.connectedToast) {
        if (ui.connectedToast) {
            delay(2000)
            vm.consumeConnectedToast()
        }
    }

    // 连接成功后只聚焦终端（接收工具条/硬件按键），不自动弹系统软键盘。
    // 改为依赖内置工具条键盘；需要输入字母时由用户点工具条「⌨」键或单击终端再调起系统键盘（#3）。
    LaunchedEffect(ui.connState, terminalView) {
        if (ui.connState == ConnState.CONNECTED) {
            terminalView?.let { tv ->
                delay(300)   // 等 attachSession / 布局稳定
                tv.requestFocus()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy900)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
    ) {
        // —— 顶部状态条（功能 6）——
        TerminalTopBar(
            label = ui.host?.label ?: "",
            state = ui.connState,
            onBack = onBack,
            onReconnect = { vm.reconnect() },
            onDisconnect = { showDisconnectConfirm = true }
        )

        // —— 连接成功瞬时通知条（设计方案 §4.3）——
        AnimatedVisibility(
            visible = ui.connectedToast,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(GreenSuccess.copy(alpha = 0.15f))
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("已连接", color = GreenSuccess, style = MaterialTheme.typography.labelMedium)
            }
        }

        // —— 终端主体 ——
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        // 代码创建（无 XML 属性）的 View 默认不可聚焦：requestFocus() 会静默失败，
                        // IME 虽能强制弹出但建立不了 InputConnection → 键盘显示却打不进字。
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setBackgroundColor(0xFF0F172A.toInt())  // 与 Compose 主题对齐
                        setTextSize((13 * ctx.resources.displayMetrics.scaledDensity).toInt())
                        setTypeface(Typeface.MONOSPACE)
                        val client = AsshTerminalViewClient(vm, this) {
                            // 单击终端 → 弹出软键盘
                            requestFocus()
                            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(this, 0)
                        }
                        setTerminalViewClient(client)
                        // 输出回调直驱重绘（主线程 Handler 已保证线程安全），
                        // 不走 StateFlow→重组→LaunchedEffect（每块输出多 2~3 帧延迟，输入回显卡顿根因）
                        vm.onScreenUpdated = { onScreenUpdated() }
                        terminalView = this
                    }
                },
                update = { view ->
                    // termSession 改为可观察状态：连接成功后由此触发重组并 attach（修复黑屏）
                    termSession?.let { session ->
                        if (view.mTermSession != session) {
                            view.attachSession(session)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 连接中遮罩：极细圆形进度条（设计方案 §2.2 交互动效）
            if (ui.connState == ConnState.CONNECTING) {
                Box(
                    Modifier.fillMaxSize().background(Navy900.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = BlueAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // 错误/断开浮层：错误信息可选中复制 + 离线中文翻译（功能 5）
            if (ui.connState == ConnState.ERROR || ui.connState == ConnState.DISCONNECTED) {
                Box(
                    Modifier.fillMaxSize().background(Navy900.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            if (ui.connState == ConnState.ERROR) "连接失败" else "连接已断开",
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate200
                        )
                        ui.error?.let { err ->
                            Spacer(Modifier.height(12.dp))
                            // 原始英文报错：长按可选中部分文字
                            SelectionContainer {
                                Text(
                                    err,
                                    color = Slate400,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Navy800, RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                )
                            }
                            // 实时离线翻译
                            SshErrorTranslator.translate(err)?.let { zh ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    zh,
                                    color = AmberWarning,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(AmberWarning.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("ssh error", err))
                            }) {
                                Icon(
                                    Icons.Default.ContentCopy, null,
                                    tint = Slate400, modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("复制错误信息", color = Slate400, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.reconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重新连接")
                        }
                    }
                }
            }
        }

        // —— 悬浮工具栏（键盘上方）——
        AccessoryBar(
            ctrlActive = ui.ctrlActive,
            altActive = ui.altActive,
            commands = commands,
            onToggleCtrl = { vm.toggleCtrl() },
            onToggleAlt = { vm.toggleAlt() },
            onKey = { keyCode -> terminalView?.handleKeyCode(keyCode, 0) },
            onText = { vm.sendText(it) },
            onSendRaw = { vm.sendRaw(it) },
            onPaste = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { vm.paste(it) }
            },
            onCommand = { vm.sendCommand(it) },
            onAddCommand = { showAddCommand = true },
            onToggleSoftKeyboard = {
                terminalView?.let { tv ->
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    if (imeVisible) {
                        imm.hideSoftInputFromWindow(tv.windowToken, 0)
                    } else {
                        // 先聚焦再 show：不聚焦时 IME 不会和 TerminalView 建立 InputConnection（显示了也打不进字）
                        tv.requestFocus()
                        imm.showSoftInput(tv, 0)
                    }
                }
            },
            onSwitchKeyboard = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                // 弹出系统输入法选择器，切换到用户自己的键盘
                imm.showInputMethodPicker()
            }
        )
    }

    // —— 断开连接二次确认 ——
    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            containerColor = Navy800,
            title = { Text("断开连接") },
            text = { Text("确定断开「${ui.host?.label}」？正在运行的远程命令不会被终止。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirm = false
                        vm.disconnect()
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedError)
                ) { Text("断开") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("取消", color = Slate400) }
            }
        )
    }

    // —— 密码输入弹窗（配置未存密码）——
    if (ui.needPassword) {
        AlertDialog(
            onDismissRequest = onBack,
            containerColor = Navy800,
            title = { Text("输入密码") },
            text = {
                Column {
                    Text(
                        "${ui.host?.username}@${ui.host?.host}",
                        color = Slate400,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("SSH 密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = asshFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.connect(passwordInput)
                        passwordInput = ""
                    },
                    enabled = passwordInput.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                ) { Text("连接") }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text("取消", color = Slate400) }
            }
        )
    }

    // —— HostKey 变更警告（风险色，设计方案 §1）——
    ui.hostKeyChanged?.let { ex ->
        AlertDialog(
            onDismissRequest = { vm.dismissHostKeyDialog() },
            containerColor = Navy800,
            title = { Text("⚠ Host Key 已变更", color = AmberWarning) },
            text = {
                Column {
                    Text("服务器 ${ex.hostPort} 的身份指纹与上次记录不一致。可能是服务器重装，也可能是中间人攻击！")
                    Spacer(Modifier.height(12.dp))
                    Text("已保存：", style = MaterialTheme.typography.labelSmall, color = Slate400)
                    Text(ex.savedFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("当前：", style = MaterialTheme.typography.labelSmall, color = Slate400)
                    Text(ex.actualFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.trustNewHostKey() },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning)
                ) { Text("信任新指纹并连接", color = Navy900) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissHostKeyDialog() }) { Text("取消", color = Slate400) }
            }
        )
    }

    // —— 终端内快速新增命令（长按 + / 点击 +）——
    if (showAddCommand) {
        CommandEditDialog(
            initial = null,
            hosts = emptyList(),
            onDismiss = { showAddCommand = false },
            onSave = { vm.saveCommand(it); showAddCommand = false },
            fixedHostId = hostId
        )
    }
}

@Composable
private fun TerminalTopBar(
    label: String,
    state: ConnState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Navy900)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Slate400)
        }
        // 连接状态点（Shared Element 目标位，设计方案 §4.2）
        val dotColor = when (state) {
            ConnState.CONNECTED -> GreenSuccess
            ConnState.CONNECTING -> BlueAccent
            else -> Slate400.copy(alpha = 0.4f)
        }
        Box(Modifier.size(8.dp).background(dotColor, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            when (state) {
                ConnState.CONNECTED -> "已连接"
                ConnState.CONNECTING -> "连接中…"
                ConnState.DISCONNECTED -> "已断开"
                ConnState.ERROR -> "错误"
                ConnState.IDLE -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = dotColor
        )
        when (state) {
            // 已连接：显示断开按钮（红色电源图标）
            ConnState.CONNECTED -> IconButton(onClick = onDisconnect) {
                Icon(Icons.Default.PowerSettingsNew, "断开连接", tint = RedError)
            }
            ConnState.DISCONNECTED, ConnState.ERROR -> IconButton(onClick = onReconnect) {
                Icon(Icons.Default.Refresh, "重新连接", tint = BlueAccent)
            }
            else -> Spacer(Modifier.width(12.dp))
        }
    }
}

/**
 * 终端键盘面板：
 * - 第一行：自定义命令 Chip 横向滚动 + 新增按钮 + 调出/收起系统输入法 + 切换输入法 + 键盘收缩开关。
 * - 下方：多行特殊键键盘（导航键定宽铺满；功能键 F1-F12 / Ctrl 组合键 / 扩展符号横向滚动），可上下收缩。
 */
@Composable
private fun AccessoryBar(
    ctrlActive: Boolean,
    altActive: Boolean,
    commands: List<CommandEntity>,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
    onSendRaw: (String) -> Unit,
    onPaste: () -> Unit,
    onCommand: (CommandEntity) -> Unit,
    onAddCommand: () -> Unit,
    onToggleSoftKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit
) {
    var keypadExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Navy800.copy(alpha = 0.92f))
    ) {
        // —— 第一行：自定义命令 Chip（横向滚动）+ 输入法按钮 + 键盘收缩开关 ——
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .background(BlueAccent.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = onAddCommand)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Default.Add, "新增命令", tint = BlueAccent, modifier = Modifier.size(16.dp))
                }
                commands.forEach { cmd ->
                    Box(
                        Modifier
                            .background(Navy700.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = { onCommand(cmd) })
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            cmd.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = Slate200
                        )
                    }
                }
            }
            // 调出/收起系统软键盘
            IconButton(
                onClick = onToggleSoftKeyboard,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Keyboard,
                    contentDescription = "调出/收起系统键盘",
                    tint = Slate400
                )
            }
            // 切换系统输入法（弹出 IME 选择器）
            IconButton(
                onClick = onSwitchKeyboard,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = "切换输入法",
                    tint = Slate400
                )
            }
            // 键盘收缩/展开开关
            IconButton(
                onClick = { keypadExpanded = !keypadExpanded },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (keypadExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (keypadExpanded) "收起键盘" else "展开键盘",
                    tint = Slate400
                )
            }
        }

        // —— 多行特殊键键盘（可收缩）——
        AnimatedVisibility(
            visible = keypadExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 第一排：Esc Tab Ctrl Alt + 导航（Home End PgUp PgDn）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    KeypadKey("Esc", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_ESCAPE) }
                    KeypadKey("Tab", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_TAB) }
                    KeypadKey("Ctrl", Modifier.weight(1f), active = ctrlActive, onClick = onToggleCtrl)
                    KeypadKey("Alt", Modifier.weight(1f), active = altActive, onClick = onToggleAlt)
                    KeypadKey("Home", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_MOVE_HOME) }
                    KeypadKey("End", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_MOVE_END) }
                    KeypadKey("PgUp", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_PAGE_UP) }
                    KeypadKey("PgDn", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_PAGE_DOWN) }
                }
                // 第二排：方向键（倒 T）+ Del 退格 + 粘贴 + 空格 + 回车（强调色，执行命令）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    KeypadKey("←", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
                    KeypadKey("↑", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_DPAD_UP) }
                    KeypadKey("↓", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
                    KeypadKey("→", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
                    KeypadKey("Del", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_FORWARD_DEL) }
                    // ⌫ 退格（原 BS，即 Backspace）：删光标左侧字符，常用键
                    KeypadKey("⌫", Modifier.weight(1f)) { onKey(android.view.KeyEvent.KEYCODE_DEL) }
                    Box(
                        Modifier
                            .weight(1f)
                            .background(Navy700, RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = onPaste)
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ContentPaste, "粘贴",
                            tint = Slate400, modifier = Modifier.size(16.dp)
                        )
                    }
                    // 空格：紧挨回车，方便单手敲命令参数（受粘滞 Ctrl 影响：Ctrl+Space=NUL）
                    KeypadKey("空格", Modifier.weight(2f)) { onText(" ") }
                    // 回车：执行已输入的命令（部分 IME 无回车，这里兜底）
                    Box(
                        Modifier
                            .weight(1.4f)
                            .background(BlueAccent, RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = { onKey(android.view.KeyEvent.KEYCODE_ENTER) })
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "↵",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                // 第三排：Ctrl 组合快捷键（^C 等），等宽换行平铺，全部可见
                KeyGrid(
                    keys = listOf(
                        "^C", "^D", "^Z", "^L", "^A", "^E",
                        "^U", "^K", "^W", "^R", "^G", "^\\"
                    ),
                    perRow = 6,
                    accent = true
                ) { label ->
                    // ^X -> 对应控制字符，统一走 KeyEncoder（C7，与 VM sticky-key 同源）
                    onSendRaw(KeyEncoder.ctrlLabel(label))
                }
                // 第四排：功能键 F1–F12，等宽换行平铺
                KeyGrid(
                    keys = (1..12).map { "F$it" },
                    perRow = 6
                ) { label ->
                    val n = label.drop(1).toInt()
                    onKey(android.view.KeyEvent.KEYCODE_F1 + (n - 1))
                }
                // 第五排：扩展符号，等宽换行平铺，全部可见（不再横向滚动）
                KeyGrid(
                    keys = listOf(
                        "/", "\\", "-", "_", "|", "~", "!", "?", "*", "&", "%", "^",
                        "$", "#", "@", "+", "=", ">", "<", ".", ",", ";", ":",
                        "'", "\"", "`", "(", ")", "{", "}", "[", "]"
                    ),
                    perRow = 8
                ) { sym -> onText(sym) }
            }
        }
    }
}

/** 等宽换行平铺的按键网格：每行 [perRow] 个等宽键，自动换行，全部可见（不横向滚动）。 */
@Composable
private fun KeyGrid(
    keys: List<String>,
    perRow: Int,
    accent: Boolean = false,
    onKey: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.chunked(perRow).forEach { rowKeys ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowKeys.forEach { label ->
                    GridKey(label, accent, Modifier.weight(1f)) { onKey(label) }
                }
                // 末行不足 perRow 时补占位，保持等宽对齐
                repeat(perRow - rowKeys.size) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 单个网格键：accent=true 用强调色描边（Ctrl 组合键），否则普通底色。 */
@Composable
private fun GridKey(
    label: String,
    accent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(
                if (accent) BlueAccent.copy(alpha = 0.18f) else Navy700,
                RoundedCornerShape(8.dp)
            )
            .combinedClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
            color = if (accent) BlueAccent else Slate200
        )
    }
}

/** 网格键盘按键：粘滞键（Ctrl/Alt）激活时高亮 */
@Composable
private fun KeypadKey(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(
                if (active) BlueAccent else Navy700,
                RoundedCornerShape(8.dp)
            )
            .combinedClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Color.White else Slate200
        )
    }
}

/** 从 Context 链中安全解包 Activity；找不到返回 null（避免强转崩溃）。 */
private fun android.content.Context.findTerminalActivity(): Activity? {
    var ctx: android.content.Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
