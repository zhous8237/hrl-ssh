package com.assh.data.repo

import com.assh.data.db.AsshDatabase
import com.assh.data.db.entity.CommandEntity
import kotlinx.coroutines.flow.Flow

/** 自定义命令仓库（功能 5） */
class CommandRepository(private val db: AsshDatabase) {

    fun observeAll(): Flow<List<CommandEntity>> = db.commandDao().observeAll()

    fun observeForHost(hostId: Long): Flow<List<CommandEntity>> =
        db.commandDao().observeForHost(hostId)

    suspend fun save(command: CommandEntity): Long {
        val now = System.currentTimeMillis()
        return if (command.id == 0L) {
            db.commandDao().insert(command.copy(updatedAt = now))
        } else {
            // 编辑对话框重建实体时 uuid 会是新随机值，保留库中原 uuid
            val keepUuid = db.commandDao().findById(command.id)?.uuid ?: command.uuid
            db.commandDao().update(command.copy(uuid = keepUuid, updatedAt = now))
            command.id
        }
    }

    suspend fun delete(command: CommandEntity) = db.commandDao().delete(command)
}
