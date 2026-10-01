package com.assh.ui.config

import com.assh.data.db.dao.KnownHostDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {
    @Test
    fun `取消不删除且未请求确认不能清空`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val dao = mockk<KnownHostDao>(relaxed = true)
            val vm = ConfigViewModel(dao)
            vm.confirmClear()
            vm.requestClear()
            vm.cancelClear()
            runCurrent()
            coVerify(exactly = 0) { dao.clear() }
            assertFalse(vm.state.value.confirming)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `执行中禁用重复请求且失败允许重试`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val dao = mockk<KnownHostDao>()
            val gate = CompletableDeferred<Unit>()
            coEvery { dao.clear() } coAnswers { gate.await(); throw IllegalStateException("数据库繁忙") }
            val vm = ConfigViewModel(dao)
            vm.requestClear()
            vm.confirmClear()
            vm.confirmClear()
            vm.requestClear()
            vm.cancelClear()
            runCurrent()
            assertTrue(vm.state.value.busy)
            assertTrue(vm.state.value.confirming)
            coVerify(exactly = 1) { dao.clear() }
            gate.complete(Unit)
            runCurrent()
            assertFalse(vm.state.value.busy)
            assertTrue(vm.state.value.confirming)
            assertTrue(vm.state.value.message!!.contains("清除失败"))
            coEvery { dao.clear() } returns Unit
            vm.confirmClear()
            runCurrent()
            coVerify(exactly = 2) { dao.clear() }
            assertFalse(vm.state.value.confirming)
            assertTrue(vm.state.value.message!!.contains("已清除"))
        } finally { Dispatchers.resetMain() }
    }
}
