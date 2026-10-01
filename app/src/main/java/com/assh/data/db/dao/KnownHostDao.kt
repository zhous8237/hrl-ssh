package com.assh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.assh.data.db.entity.KnownHostEntity

@Dao
interface KnownHostDao {

    @Query("SELECT * FROM known_hosts WHERE hostPort = :hostPort")
    suspend fun find(hostPort: String): KnownHostEntity?

    /** HostKeyVerifier 在 sshj 线程同步调用，提供阻塞版本 */
    @Query("SELECT * FROM known_hosts WHERE hostPort = :hostPort")
    fun findBlocking(hostPort: String): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBlocking(entity: KnownHostEntity)

    @Query("DELETE FROM known_hosts WHERE hostPort = :hostPort")
    suspend fun delete(hostPort: String)

    /** 仅清除服务器指纹，不影响连接配置、凭据或历史。 */
    @Query("DELETE FROM known_hosts")
    suspend fun clear()
}
