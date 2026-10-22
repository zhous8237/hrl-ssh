package com.assh.data.db

import com.assh.data.db.entity.AuthType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AuthType 的 TEXT 持久化：枚举扩充（新增 NONE）不能破坏既有数据库与同步包。
 */
class ConvertersTest {

    private val c = Converters()

    @Test
    fun `枚举与字符串双向一致`() {
        AuthType.values().forEach { t ->
            assertEquals(t, c.stringToAuthType(c.authTypeToString(t)))
        }
    }

    @Test
    fun `NONE 能正确往返`() {
        assertEquals("NONE", c.authTypeToString(AuthType.NONE))
        assertEquals(AuthType.NONE, c.stringToAuthType("NONE"))
    }

    @Test
    fun `未知值回落 PASSWORD 而不是崩溃`() {
        // 旧客户端 / 未来版本写入的未知值不应让 App 启动失败
        assertEquals(AuthType.PASSWORD, c.stringToAuthType("SOMETHING_NEW"))
        assertEquals(AuthType.PASSWORD, c.stringToAuthType(""))
    }
}
