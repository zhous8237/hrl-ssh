package com.assh.ui.config

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.assh.service.AgentForegroundService
import com.assh.service.ForegroundSession
import io.mockk.every
import io.mockk.spyk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackgroundRunScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `刷新读取真实电池状态且系统页面失效时可见提示`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = spyk(app)
        val intents = mutableListOf<Intent>()
        every { context.startActivity(any()) } answers {
            intents += firstArg<Intent>()
            throw ActivityNotFoundException()
        }
        val vm = BackgroundRunViewModel(context, ForegroundSession { }, ForegroundSession { })
        compose.setContent { MaterialTheme { BackgroundRunScreen(onBack = {}, vm = vm) } }
        compose.onNodeWithText("电池优化：未豁免").assertExists()
        compose.onNodeWithText("打开电池优化设置").performClick()
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intents.first().action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
        assertTrue(vm.message.value!!.contains("无法打开系统设置"))
        compose.runOnIdle {
            shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
            vm.refresh()
        }
        compose.onNodeWithText("电池优化：已豁免").assertExists()
    }

    @Test
    fun `通知渠道关闭和服务失败如实显示且拒绝不阻塞服务`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(AgentForegroundService.CHANNEL_ID, "测试", NotificationManager.IMPORTANCE_NONE))
        val session = ForegroundSession { throw IllegalStateException("启动失败") }
        val vm = BackgroundRunViewModel(app, session, ForegroundSession { })
        val lease = session.request()
        lease.close()
        vm.refresh()
        assertFalse(vm.system.value.agentChannelEnabled)
        assertTrue(vm.agentStatus.value.error!!.contains("启动失败"))
        vm.notificationResult(false)
        assertTrue(vm.message.value!!.contains("仍可按系统规则运行"))
        assertFalse(vm.agentStatus.value.active)
    }
}
