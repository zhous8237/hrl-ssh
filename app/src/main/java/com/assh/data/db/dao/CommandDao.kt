package com.assh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.assh.data.db.entity.CommandEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandDao {

    @Query("SELECT * FROM commands ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CommandEntity>>

    /** 同步导出用：一次性全量 */
    @Query("SELECT * FROM commands")
    suspend fun getAll(): List<CommandEntity>

    @Query("SELECT * FROM commands WHERE uuid = :uuid")
    suspend fun findByUuid(uuid: String): CommandEntity?

    @Query("SELECT * FROM commands WHERE id = :id")
    suspend fun findById(id: Long): CommandEntity?

    /** 终端工具条用：全局命令 + 绑定当前主机的命令 */
    @Query("SELECT * FROM commands WHERE hostId IS NULL OR hostId = :hostId ORDER BY sortOrder ASC, id ASC")
    fun observeForHost(hostId: Long): Flow<List<CommandEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: CommandEntity): Long

    @Update
    suspend fun update(command: CommandEntity)

    @Delete
    suspend fun delete(command: CommandEntity)
}
