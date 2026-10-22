package com.assh.data.db

import androidx.room.TypeConverter
import com.assh.data.db.entity.AuthType

/**
 * AuthType ↔ TEXT 转换。库里存的是枚举 name。
 *
 * 读取用容错解析：旧版本 / 其他设备写入的未知值不崩溃，回落 PASSWORD
 * （与 v2 之前只有 PASSWORD / KEY 时的既有行为一致）。
 */
class Converters {
    @TypeConverter
    fun authTypeToString(value: AuthType): String = value.name

    @TypeConverter
    fun stringToAuthType(value: String): AuthType =
        runCatching { AuthType.valueOf(value) }.getOrDefault(AuthType.PASSWORD)
}
