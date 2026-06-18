package com.assh.terminal

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * TerminalSessionClient 基础实现：日志走 logcat，
 * 屏幕刷新/会话结束等关键回调交给构造时传入的钩子（由终端屏 ViewModel 提供）。
 */
class AsshTerminalSessionClient(
    private val onTextChangedHook: () -> Unit,
    private val onSessionFinishedHook: () -> Unit,
    private val onCopyToClipboardHook: (String) -> Unit,
    private val onPasteFromClipboardHook: () -> Unit,
    private val onBellHook: () -> Unit = {}
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) = onTextChangedHook()

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) = onSessionFinishedHook()

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) =
        onCopyToClipboardHook(text)

    override fun onPasteTextFromClipboard(session: TerminalSession?) = onPasteFromClipboardHook()

    override fun onBell(session: TerminalSession) = onBellHook()

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = null

    private val tag = "a-ssh-term"
    override fun logError(tag: String?, message: String?) { Log.e(this.tag, "$tag: $message") }
    override fun logWarn(tag: String?, message: String?) { Log.w(this.tag, "$tag: $message") }
    override fun logInfo(tag: String?, message: String?) { Log.i(this.tag, "$tag: $message") }
    override fun logDebug(tag: String?, message: String?) { Log.d(this.tag, "$tag: $message") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(this.tag, "$tag: $message") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(this.tag, "$tag: $message", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(this.tag, "$tag", e) }
}
