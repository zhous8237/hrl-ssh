package com.assh.ui.agent

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.assh.ai.AgentRunRecord
import com.assh.ai.RecordEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentHistoryScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun show(entries: List<RecordEntry>) {
        val record = AgentRunRecord("测试", "主机", "历史详情", 0, 0, true, "完成", entries, 1)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 560.dp)) {
                    RecordDetailScreen(record, onBack = {}, onResume = {})
                }
            }
        }
    }

    @Test
    fun `空历史不显示无效滚动按钮`() {
        show(emptyList())
        compose.onNodeWithContentDescription("回到顶部").assertDoesNotExist()
        compose.onNodeWithContentDescription("到最底部").assertDoesNotExist()
    }

    @Test
    fun `短历史不显示无效滚动按钮`() {
        show(listOf(RecordEntry("ai", "短记录")))
        compose.onNodeWithText("短记录").assertExists()
        compose.onNodeWithContentDescription("回到顶部").assertDoesNotExist()
        compose.onNodeWithContentDescription("到最底部").assertDoesNotExist()
    }

    @Test
    fun `顶部中间和底部按钮正确且可越过超长末条`() {
        show(List(30) { RecordEntry("ai", "记录 $it") } + RecordEntry("command", command = "长输出", stdout = (1..120).joinToString("\n") { "输出第 $it 行" }))
        compose.onNodeWithContentDescription("回到顶部").assertDoesNotExist()
        compose.onNodeWithContentDescription("到最底部").assertExists()
        compose.onNode(hasScrollAction()).performScrollToIndex(15)
        compose.onNodeWithContentDescription("回到顶部").assertExists()
        compose.onNodeWithContentDescription("到最底部").assertExists()
        compose.onNodeWithContentDescription("到最底部").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("到最底部").assertDoesNotExist()
        compose.onNodeWithContentDescription("回到顶部").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("回到顶部").assertDoesNotExist()
        compose.onNodeWithContentDescription("到最底部").assertExists()
    }
}
