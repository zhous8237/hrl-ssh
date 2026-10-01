package com.assh.service

/** 本地引擎测试替身：同步确认前台服务就绪，不调用 Android 或外部服务。 */
fun readyForegroundSession(): ForegroundSession {
    lateinit var session: ForegroundSession
    session = ForegroundSession { session.markReady(it) }
    return session
}
