package com.assh

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.assh.ai.AgentHistoryStore
import com.assh.ai.AgentPreferences
import com.assh.ai.SshAgentEngine
import com.assh.ai.llm.LlmClientFactory
import com.assh.data.crypto.CryptoManager
import com.assh.data.db.AsshDatabase
import com.assh.data.repo.CommandRepository
import com.assh.data.repo.CredentialRepository
import com.assh.data.repo.HostRepository
import com.assh.data.repo.KeyRepository
import com.assh.ssh.SshConnectionManager
import com.assh.sync.SyncEngine
import com.assh.sync.SyncPreferences
import com.assh.sync.SyncRepository
import com.assh.terminal.TerminalSessionRegistry
import com.assh.ui.theme.ThemePreferences
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class AsshApp : Application() {

    val database by lazy { AsshDatabase.get(this) }
    val hostRepository by lazy { HostRepository(database) }
    val keyRepository by lazy { KeyRepository(database) }
    val credentialRepository by lazy { CredentialRepository(database) }
    val commandRepository by lazy { CommandRepository(database) }
    val connectionManager by lazy { SshConnectionManager(database.knownHostDao()) }
    val terminalRegistry by lazy { TerminalSessionRegistry() }
    val themePreferences by lazy { ThemePreferences(this) }

    // 功能 7：WebDAV 同步
    val syncPreferences by lazy { SyncPreferences(this) }
    val syncRepository by lazy { SyncRepository(database, agentPreferences) }
    val syncEngine by lazy { SyncEngine(syncPreferences, syncRepository) }

    // AI 运维 Agent
    val agentPreferences by lazy { AgentPreferences(this) }
    val llmClientFactory by lazy { LlmClientFactory() }
    val agentHistoryStore by lazy { AgentHistoryStore(this) }
    val sshAgentEngine by lazy {
        SshAgentEngine(this, hostRepository, agentPreferences, llmClientFactory, database.knownHostDao(), agentHistoryStore)
    }

    override fun onCreate() {
        super.onCreate()

        // 全局崩溃捕获：堆栈写入 Android/data/<pkg>/files/crash/，便于真机无 logcat 时排查
        CrashHandler.install(this)
        FileLog.init(this)

        // Android 自带裁剪版 BC 与 sshj 冲突，替换为完整版（文档 §3.1/§12.3）
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        // 确保 Keystore 主密钥存在
        CryptoManager.ensureKey(this)

        // 后台保活（功能 6）：退到后台启动 10 分钟延时断开，回前台取消
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                connectionManager.onAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                connectionManager.onAppBackgrounded()
            }
        })
    }
}
