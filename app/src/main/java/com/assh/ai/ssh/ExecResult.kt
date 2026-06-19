package com.assh.ai.ssh

/**
 * 一条远程命令的执行结果（非交互 exec channel）。
 *
 * @param exitStatus 退出码；null 表示未知（超时被打断 / 连接中断 / 服务器未返回）
 * @param truncated stdout 或 stderr 超过上限被截断（仅保留前若干字节）
 * @param timedOut 命令在超时窗口内未结束，已被打断
 * @param interrupted 命令执行途中连接断开（与超时区分：transport 死亡而非超时窗口到点）
 * @param reconnected 执行途中断开后，已原地退避重连成功（连接可继续用，但本命令未跑完）
 */
data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitStatus: Int?,
    val truncated: Boolean,
    val timedOut: Boolean,
    val durationMs: Long,
    val interrupted: Boolean = false,
    val reconnected: Boolean = false
) {
    val success: Boolean get() = exitStatus == 0 && !timedOut && !interrupted
}
