package com.assh.ai.ssh

/**
 * 一条远程命令的执行结果（非交互 exec channel）。
 *
 * @param exitStatus 退出码；null 表示未知（超时被打断 / 服务器未返回）
 * @param truncated stdout 或 stderr 超过上限被截断（仅保留前若干字节）
 * @param timedOut 命令在超时窗口内未结束，已被打断
 */
data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitStatus: Int?,
    val truncated: Boolean,
    val timedOut: Boolean,
    val durationMs: Long
) {
    val success: Boolean get() = exitStatus == 0 && !timedOut
}
