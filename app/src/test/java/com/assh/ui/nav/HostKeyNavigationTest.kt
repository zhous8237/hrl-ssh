package com.assh.ui.nav

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.assh.ai.AgentPhase
import com.assh.ai.AgentState
import com.assh.ai.PendingHostKey
import com.assh.data.db.dao.KnownHostDao
import com.assh.ssh.ConnState
import com.assh.ssh.HostKeyChangedException
import com.assh.ui.agent.AgentScreen
import com.assh.ui.agent.AgentViewModel
import com.assh.ui.config.ConfigScreen
import com.assh.ui.config.ConfigViewModel
import com.assh.ui.terminal.TerminalScreen
import com.assh.ui.terminal.TerminalUiState
import com.assh.ui.terminal.TerminalViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 用真实页面与 Navigation 回栈隔离验证交互，ViewModel 的连接行为另有单元测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HostKeyNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `AI弹窗跳转前结束等待清理后返回可继续`() {
        val vm = mockk<AgentViewModel>(relaxed = true)
        val state = MutableStateFlow(AgentState(phase = AgentPhase.WAITING_HOSTKEY,
            pendingHostKey = PendingHostKey("host:22", "旧指纹", "新指纹")))
        every { vm.state } returns state
        every { vm.hosts } returns MutableStateFlow(emptyList())
        every { vm.profiles } returns MutableStateFlow(emptyList())
        every { vm.activeProfileId } returns MutableStateFlow(null)
        every { vm.prepareHostKeySettings() } answers {
            state.value = state.value.copy(phase = AgentPhase.AWAITING_FOLLOWUP, pendingHostKey = null)
        }
        val dao = mockk<KnownHostDao>(relaxed = true)
        val configVm = ConfigViewModel(dao)
        lateinit var nav: NavHostController
        compose.setContent {
            MaterialTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        AgentScreen(onOpenSettings = {}, onOpenHistory = {}, vm = vm, onOpenConfig = {
                            assertNull(state.value.pendingHostKey)
                            nav.navigate(Routes.CONFIG)
                        })
                    }
                    composable(Routes.CONFIG) { ConfigScreen(onBack = { nav.popBackStack() }, vm = configVm) }
                }
            }
        }
        compose.onNodeWithText("前往设置清除").performClick()
        compose.onNodeWithText("清除服务器指纹").assertExists()
        compose.runOnIdle { assertEquals(Routes.CONFIG, nav.currentDestination?.route) }
        coVerify(exactly = 0) { dao.clear() }
        compose.onNodeWithText("清除服务器指纹").performClick()
        compose.onNodeWithText("确认清除").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("继续").performClick()
        verify(exactly = 1) { vm.prepareHostKeySettings() }
        verify(exactly = 1) { vm.continueTask("") }
        compose.runOnIdle { assertEquals(Routes.HOME, nav.currentDestination?.route) }
    }

    @Test
    fun `终端弹窗跳转同一配置页返回仍可重新连接`() {
        val vm = mockk<TerminalViewModel>(relaxed = true)
        val state = MutableStateFlow(TerminalUiState(connState = ConnState.ERROR,
            hostKeyChanged = HostKeyChangedException("host:22", "旧指纹", "新指纹", "rsa", byteArrayOf())))
        every { vm.ui } returns state
        every { vm.commands } returns MutableStateFlow(emptyList())
        every { vm.copyRequest } returns MutableStateFlow(null)
        every { vm.termSessionFlow } returns MutableStateFlow(null)
        every { vm.dismissHostKeyDialog() } answers { state.value = state.value.copy(hostKeyChanged = null) }
        val configVm = ConfigViewModel(mockk(relaxed = true))
        lateinit var nav: NavHostController
        compose.setContent {
            MaterialTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = Routes.terminal(1)) {
                    composable(Routes.TERMINAL) {
                        TerminalScreen(1, onBack = {}, vm = vm, onOpenConfig = {
                            assertNull(state.value.hostKeyChanged)
                            nav.navigate(Routes.CONFIG)
                        })
                    }
                    composable(Routes.CONFIG) { ConfigScreen(onBack = { nav.popBackStack() }, vm = configVm) }
                }
            }
        }
        compose.onNodeWithText("前往设置清除").performClick()
        compose.onNodeWithText("清除服务器指纹").assertExists()
        compose.runOnIdle { assertEquals(Routes.CONFIG, nav.currentDestination?.route) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("重新连接").performClick()
        verify(exactly = 1) { vm.dismissHostKeyDialog() }
        verify(exactly = 1) { vm.reconnect() }
        compose.runOnIdle { assertEquals(Routes.TERMINAL, nav.currentDestination?.route) }
    }
}
