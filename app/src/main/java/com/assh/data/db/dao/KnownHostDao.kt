package com.assh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.assh.data.db.entity.KnownHostEntity
import kotlinx.coroutines.flow.Flow

/**
 * 已知主机密钥（TOFU）。
 *
 * 同一 `hostPort` 可有多条记录（多节点 / 密钥轮换），因此查询一律返回列表，
 * 由 [com.assh.ssh.AsshHostKeyVerifier] 判断“本次出示的密钥是否命中任一已信任指纹”。
 */
@Dao
interface KnownHostDao {

    @Query("SELECT * FROM known_hosts WHERE hostPort = :hostPort ORDER BY addedAt ASC")
    suspend fun findAll(hostPort: String): List<KnownHostEntity>

    /** 校验器在 sshj 线程同步调用，提供阻塞版本 */
    @Query("SELECT * FROM known_hosts WHERE hostPort = :hostPort ORDER BY addedAt ASC")
    fun findAllBlocking(hostPort: String): List<KnownHostEntity>

    /** 全部记录（设置页展示“已信任的指纹”用） */
    @Query("SELECT * FROM known_hosts ORDER BY hostPort ASC, addedAt ASC")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT COUNT(*) FROM known_hosts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBlocking(entity: KnownHostEntity)

    /** 删除某个主机的全部指纹 */
    @Query("DELETE FROM known_hosts WHERE hostPort = :hostPort")
    suspend fun delete(hostPort: String)

    /** 仅清除指定的一条指纹 */
    @Query("DELETE FROM known_hosts WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 仅清除服务器指纹，不影响连接配置、凭据或历史。 */
    @Query("DELETE FROM known_hosts")
    suspend fun clear()
}
