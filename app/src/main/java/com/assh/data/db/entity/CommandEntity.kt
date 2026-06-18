package com.assh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/** 自定义命令（功能 5），终端工具条 Chip 一键执行 */
@Entity(tableName = "commands")
data class CommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,               // 按钮显示名，如 "查看日志"
    val command: String,             // 实际下发文本，如 "tail -f /var/log/syslog"
    val appendEnter: Boolean = true, // 是否自动追加回车执行
    // 作用域：全局命令 or 绑定某主机（null = 全局）
    val hostId: Long? = null,
    val sortOrder: Int = 0,
    // —— v2 同步字段（功能 7）——
    val uuid: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis()
)
