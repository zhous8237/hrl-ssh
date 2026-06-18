package com.assh.ui.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import com.assh.ui.terminal.TerminalViewModel
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * TerminalViewClient 实现：把 TerminalView 的回调接到 ViewModel。
 * 粘滞键（工具条 Ctrl/Alt）通过 readControlKey/readAltKey 注入硬件键路径。
 */
class AsshTerminalViewClient(
    private val vm: TerminalViewModel,
    private val view: TerminalView,
    private val onSingleTap: () -> Unit
) : TerminalViewClient {

    private val density = view.resources.displayMetrics.scaledDensity
    private val minFontSize = 8 * density
    private val maxFontSize = 36 * density
    private var fontSize = 13 * density

    /**
     * 双指捏合缩放字号（连续跟手）。
     * TerminalView 传入的是累乘后的缩放因子；这里按比例平滑调整浮点字号，
     * 仅当跨过整数像素边界时才真正重建渲染器（setTextSize 接收 Int），
     * 始终返回 1.0 复位累积因子，使下次回调拿到的是增量缩放。
     */
    override fun onScale(scale: Float): Float {
        val newSize = (fontSize * scale).coerceIn(minFontSize, maxFontSize)
        if (newSize.toInt() != fontSize.toInt()) {
            view.setTextSize(newSize.toInt())
        }
        fontSize = newSize
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) = onSingleTap()

    override fun shouldBackButtonBeMappedToEscape() = false

    override fun shouldEnforceCharBasedInput() = true   // 兼容多数 IME

    override fun shouldUseCtrlSpaceWorkaround() = false

    override fun isTerminalViewSelected() = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            // 会话已结束，回车触发重连
            vm.reconnect()
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false

    override fun onLongPress(event: MotionEvent) = false

    // 粘滞键注入：TerminalView 每次按键都会读这些标志
    override fun readControlKey() = vm.ui.value.ctrlActive

    override fun readAltKey() = vm.ui.value.altActive

    override fun readShiftKey() = false

    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        // 字符已发出，消费一次性粘滞状态
        if (vm.ui.value.ctrlActive || vm.ui.value.altActive) {
            vm.consumeSticky()
        }
        return false
    }

    override fun onEmulatorSet() {
        vm.onEmulatorReady()
    }

    private val tag = "a-ssh-view"
    override fun logError(tag: String?, message: String?) { android.util.Log.e(this.tag, "$message") }
    override fun logWarn(tag: String?, message: String?) { android.util.Log.w(this.tag, "$message") }
    override fun logInfo(tag: String?, message: String?) { android.util.Log.i(this.tag, "$message") }
    override fun logDebug(tag: String?, message: String?) { android.util.Log.d(this.tag, "$message") }
    override fun logVerbose(tag: String?, message: String?) { android.util.Log.v(this.tag, "$message") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        android.util.Log.e(this.tag, "$message", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(this.tag, "", e) }
}
