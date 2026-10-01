package com.assh.ai.ssh

import com.assh.ssh.HostKeyChangedException
import com.assh.ssh.hostKeyChange
import com.assh.ssh.ResolvedHostConfig
import com.assh.data.db.entity.AuthType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException

class HostKeyReconnectTest {
    @Test
    fun `命令中断后重连必须透传指纹变更而非普通断线`() = runTest {
        val changed = HostKeyChangedException("host:22", "旧指纹", "新指纹", "ssh-rsa", byteArrayOf(1))
        val wrapped = IOException("传输层失败", changed)
        var connected = true
        val command = mockk<Session.Command>(relaxed = true)
        every { command.inputStream } returns ByteArrayInputStream(byteArrayOf())
        every { command.errorStream } returns ByteArrayInputStream(byteArrayOf())
        every { command.join(any(), any()) } answers {
            connected = false
            throw EOFException("连接关闭")
        }
        val session = mockk<Session>(relaxed = true)
        every { session.exec(any()) } returns command
        val client = object : AgentSshClient {
            override val isConnected get() = connected
            override fun startSession() = session
            override fun disconnect() { connected = false }
        }
        var calls = 0
        val factory = AgentSshClientFactory {
            if (calls++ == 0) client else throw wrapped
        }
        val runner = SshAgentRunner(mockk(relaxed = true), factory, ioDispatcher = StandardTestDispatcher(testScheduler))
        runner.connect(ResolvedHostConfig(1, "测试", "host", 22, "root", AuthType.PASSWORD, "pw", null))
        val error = runCatching { runner.runCommand("测试命令") }.exceptionOrNull()
        assertSame("原因链必须保留指纹异常供引擎显示恢复入口", changed, error?.hostKeyChange())
    }
}
