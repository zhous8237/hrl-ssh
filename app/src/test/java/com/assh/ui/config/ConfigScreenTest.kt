package com.assh.ui.config

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.assh.data.db.dao.KnownHostDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `取消保留指纹确认显示失败后可重试并反馈成功`() {
        val dao = mockk<KnownHostDao>()
        var attempts = 0
        coEvery { dao.clear() } answers { if (attempts++ == 0) throw IllegalStateException("数据库繁忙") }
        val vm = ConfigViewModel(dao)
        compose.setContent { MaterialTheme { ConfigScreen(vm = vm) } }

        // 设置项在 LazyColumn 中且位于「凭据与安全」组（可能在视口外），先滚动过去
        fun scrollToCredentials() {
            compose.onNode(hasScrollToNodeAction())
                .performScrollToNode(hasTestTag("settings_group_credentials"))
        }
        // 该组是合并的可点击行：用 hasText(substring) 命中整行
        fun credentialRow() = compose.onNode(hasText("清除服务器指纹", substring = true))

        scrollToCredentials()
        credentialRow().performClick()
        compose.onNodeWithText("取消").performClick()
        coVerify(exactly = 0) { dao.clear() }

        credentialRow().performClick()
        compose.onNodeWithText("确认清除").performClick()
        compose.onNodeWithText("清除失败：数据库繁忙，请重试").assertExists()
        compose.onNodeWithText("确认清除").performClick()
        compose.onNodeWithText("已清除服务器指纹，下次连接将重新记录指纹").assertExists()
        compose.onNodeWithText("清除服务器指纹？").assertDoesNotExist()
        coVerify(exactly = 2) { dao.clear() }
    }
}
