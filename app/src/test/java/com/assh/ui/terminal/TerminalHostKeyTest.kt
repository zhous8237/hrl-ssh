package com.assh.ui.terminal

import androidx.lifecycle.ViewModelStore
import com.assh.AsshApp
import com.assh.data.db.dao.KnownHostDao
import com.assh.data.db.entity.AuthType
import com.assh.data.repo.HostRepository
import com.assh.service.readyForegroundSession
import com.assh.ssh.ConnState
import com.assh.ssh.HostKeyChangedException
import com.assh.ssh.ResolvedHostConfig
import com.assh.ssh.SshConnectionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalHostKeyTest {
    @Test
    fun `初连和缓存重连均显示包装指纹异常且退出弹窗不删除`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val app = mockk<AsshApp>(relaxed = true)
            every { app.terminalForegroundSession } returns readyForegroundSession()
            val repo = mockk<HostRepository>(relaxed = true)
            val manager = mockk<SshConnectionManager>(relaxed = true)
            val dao = mockk<KnownHostDao>(relaxed = true)
            val cfg = ResolvedHostConfig(1, "测试", "host", 22, "root", AuthType.PASSWORD, "pw", null)
            val changed = HostKeyChangedException("host:22", "旧", "新", "rsa", byteArrayOf())
            every { app.hostRepository } returns repo
            every { app.connectionManager } returns manager
            every { app.database.knownHostDao() } returns dao
            every { app.commandRepository.observeForHost(any()) } returns flowOf(emptyList())
            every { manager.get(any()) } returns null
            every { manager.cachedConfig(any()) } returns cfg
            every { app.terminalRegistry.get(any()) } returns null
            every { app.terminalRegistry.remove(any()) } returns null
            coEvery { repo.resolveForConnect(1) } returns cfg
            coEvery { manager.connect(cfg) } throws IOException("外层", changed)
            coEvery { manager.reconnect(1) } throws IllegalStateException("外层", changed)
            val vm = TerminalViewModel(app)
            store.put("terminal", vm)
            vm.init(1)
            runCurrent()
            assertSame(changed, vm.ui.value.hostKeyChanged)
            vm.dismissHostKeyDialog()
            assertNull(vm.ui.value.hostKeyChanged)
            vm.reconnect()
            runCurrent()
            assertSame(changed, vm.ui.value.hostKeyChanged)
            assertEquals(ConnState.ERROR, vm.ui.value.connState)
            coVerify(exactly = 1) { manager.connect(cfg) }
            coVerify(exactly = 1) { manager.reconnect(1) }
            coVerify(exactly = 0) { dao.clear() }
            coVerify(exactly = 0) { dao.delete(any()) }
            vm.dismissHostKeyDialog()
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `信任删除失败可重试且网络错误不误报指纹`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val app = mockk<AsshApp>(relaxed = true)
            every { app.terminalForegroundSession } returns readyForegroundSession()
            val manager = mockk<SshConnectionManager>(relaxed = true)
            val dao = mockk<KnownHostDao>(relaxed = true)
            val cfg = ResolvedHostConfig(1, "测试", "host", 22, "root", AuthType.PASSWORD, "pw", null)
            val changed = HostKeyChangedException("host:22", "旧", "新", "rsa", byteArrayOf())
            every { app.connectionManager } returns manager
            every { app.database.knownHostDao() } returns dao
            every { app.commandRepository.observeForHost(any()) } returns flowOf(emptyList())
            every { manager.get(any()) } returns null
            every { manager.cachedConfig(any()) } returns cfg
            every { app.terminalRegistry.get(any()) } returns null
            every { app.terminalRegistry.remove(any()) } returns null
            coEvery { app.hostRepository.resolveForConnect(1) } returns cfg
            coEvery { manager.connect(cfg) } throws changed
            coEvery { manager.reconnect(1) } throws IOException("网络断线")
            coEvery { dao.delete("host:22") } throws IllegalStateException("数据库繁忙")
            val vm = TerminalViewModel(app)
            store.put("terminal", vm)
            vm.init(1)
            runCurrent()
            vm.trustNewHostKey()
            runCurrent()
            assertSame(changed, vm.ui.value.hostKeyChanged)
            assertTrue(vm.ui.value.error!!.contains("更新指纹失败"))
            coEvery { dao.delete("host:22") } returns Unit
            vm.trustNewHostKey()
            runCurrent()
            assertNull(vm.ui.value.hostKeyChanged)
            assertEquals("网络断线", vm.ui.value.error)
            coVerify(exactly = 2) { dao.delete("host:22") }
            coVerify(exactly = 0) { dao.clear() }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
