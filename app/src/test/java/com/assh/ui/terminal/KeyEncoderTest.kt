package com.assh.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/** [KeyEncoder] 纯函数测试（C7）：控制字符 / sticky / Alt 前缀，覆盖此前两处漂移的边界。 */
class KeyEncoderTest {

    @Test fun `ctrl maps letters to 1-26`() {
        assertEquals(3, KeyEncoder.ctrl('C').code)   // ^C = ETX
        assertEquals(3, KeyEncoder.ctrl('c').code)   // 小写同样
        assertEquals(1, KeyEncoder.ctrl('A').code)
        assertEquals(26, KeyEncoder.ctrl('Z').code)
    }

    @Test fun `ctrl maps boundary punctuation`() {
        assertEquals(0, KeyEncoder.ctrl('@').code)   // ^@ = NUL
        assertEquals(28, KeyEncoder.ctrl('\\').code) // ^\ = FS (0x1C)
        assertEquals(31, KeyEncoder.ctrl('_').code)  // ^_ = US
    }

    @Test fun `ctrlLabel decodes caret form and passes others through`() {
        assertEquals(3, KeyEncoder.ctrlLabel("^C")[0].code)
        assertEquals(28, KeyEncoder.ctrlLabel("^\\")[0].code)
        assertEquals("abc", KeyEncoder.ctrlLabel("abc"))  // 非 ^X 原样
    }

    @Test fun `applySticky with ctrl converts single char`() {
        assertEquals(3, KeyEncoder.applySticky("c", ctrlActive = true, altActive = false)[0].code)
    }

    @Test fun `applySticky ctrl+space is NUL`() {
        assertEquals(0, KeyEncoder.applySticky(" ", ctrlActive = true, altActive = false)[0].code)
    }

    @Test fun `applySticky alt prefixes ESC`() {
        val out = KeyEncoder.applySticky("x", ctrlActive = false, altActive = true)
        assertEquals(27, out[0].code)
        assertEquals('x', out[1])
    }

    @Test fun `applySticky ctrl then alt composes`() {
        val out = KeyEncoder.applySticky("c", ctrlActive = true, altActive = true)
        assertEquals(27, out[0].code)   // ESC 前缀
        assertEquals(3, out[1].code)    // ^C
    }

    @Test fun `applySticky no modifiers returns text unchanged`() {
        assertEquals("hello", KeyEncoder.applySticky("hello", ctrlActive = false, altActive = false))
    }

    @Test fun `applySticky ctrl ignored for multi-char`() {
        // ctrl 只对单字符生效，多字符原样（避免把整段粘贴文本误转）
        assertEquals("ls", KeyEncoder.applySticky("ls", ctrlActive = true, altActive = false))
    }
}
