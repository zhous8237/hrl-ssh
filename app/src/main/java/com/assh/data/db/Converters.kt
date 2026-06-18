package com.assh.data.db

import androidx.room.TypeConverter
import com.assh.data.db.entity.AuthType

class Converters {
    @TypeConverter
    fun authTypeToString(value: AuthType): String = value.name

    @TypeConverter
    fun stringToAuthType(value: String): AuthType = AuthType.valueOf(value)
}
