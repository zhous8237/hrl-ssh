package com.assh.service

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

class TestSessionForegroundService : SessionForegroundService() {
    override val foregroundSession get() = session
    override val notificationId = 100
    override val channelId = "background_test"
    override val channelName = "后台运行测试"
    internal override val workChanges get() = work
    internal override fun currentWork() = work.value
    override fun isStopAction(action: String?) = action == "停止测试任务"
    override fun stopByUser() { userStops++ }
    override fun protectionLost(generation: Long, reason: String) { failures += reason }
    override fun notification(description: String): Notification {
        if (notificationFails) throw IllegalStateException("通知创建被拒绝")
        return Notification.Builder(this, channelId).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("本地任务").setContentText(description).build()
    }

    companion object {
        lateinit var session: ForegroundSession
        internal lateinit var work: MutableStateFlow<ForegroundWork>
        var userStops = 0
        var notificationFails = false
        val failures = mutableListOf<String>()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionForegroundServiceTest {
    private var controller: ServiceController<TestSessionForegroundService>? = null

    @Before
    fun prepare() {
        TestSessionForegroundService.session = ForegroundSession { }
        TestSessionForegroundService.work = MutableStateFlow(ForegroundWork(true, true, "执行中"))
        TestSessionForegroundService.userStops = 0
        TestSessionForegroundService.notificationFails = false
        TestSessionForegroundService.failures.clear()
    }

    @After
    fun cleanup() { controller?.destroy() }

    private fun start(): Pair<TestSessionForegroundService, ForegroundSession.Lease> {
        val lease = TestSessionForegroundService.session.request()
        val intent = Intent().putExtra(SessionForegroundService.EXTRA_GENERATION, lease.generation)
        controller = Robolectric.buildService(TestSessionForegroundService::class.java, intent).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get() to lease
    }

    @Test
    fun `进入前台后就绪且等待确认释放锁仍保留通知`() = runBlocking {
        val (service, lease) = start()
        lease.awaitReady()
        lease.close()
        assertEquals(100, shadowOf(service).lastForegroundNotificationId)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        TestSessionForegroundService.work.value = ForegroundWork(true, false, "等待确认")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)
        TestSessionForegroundService.work.value = ForegroundWork(false, false, "结束")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(TestSessionForegroundService.session.status.value.active)
    }

    @Test
    fun `旧启动编号的超时不能停止当前服务`() {
        val (service, lease) = start()
        lease.close()
        service.onTimeout(0, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertTrue(TestSessionForegroundService.session.status.value.active)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(TestSessionForegroundService.failures.isEmpty())
    }

    @Test
    fun `系统超时立即停止并释放锁保留可见错误`() {
        val (service, lease) = start()
        lease.close()
        service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(TestSessionForegroundService.session.status.value.error!!.contains("时限"))
        assertEquals(1, TestSessionForegroundService.failures.size)
    }

    @Test
    fun `服务内部失败不能完成就绪握手`() = runBlocking {
        TestSessionForegroundService.notificationFails = true
        val (service, lease) = start()
        assertTrue(runCatching { lease.awaitReady() }.exceptionOrNull() is ForegroundUnavailableException)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(1, TestSessionForegroundService.failures.size)
        lease.close()
    }

    @Test
    fun `通知停止不被状态订阅再次启动`() {
        val (service, lease) = start()
        lease.close()
        service.onStartCommand(Intent().setAction("停止测试任务"), 0, 2)
        TestSessionForegroundService.work.value = ForegroundWork(true, true, "过期工作更新")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, TestSessionForegroundService.userStops)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertFalse(TestSessionForegroundService.session.status.value.active)
    }

    @Test
    fun `意外销毁反馈保护丢失并释放资源`() {
        val (_, lease) = start()
        lease.close()
        controller!!.destroy()
        controller = null
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(1, TestSessionForegroundService.failures.size)
        assertFalse(TestSessionForegroundService.session.status.value.active)
    }
}
