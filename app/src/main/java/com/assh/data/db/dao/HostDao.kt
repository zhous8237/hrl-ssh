package com.assh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.assh.data.db.entity.HostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {

    @Query("SELECT * FROM hosts ORDER BY sortOrder ASC, lastConnectedAt DESC")
    fun observeAll(): Flow<List<HostEntity>>

    /** 同步导出用：一次性全量 */
    @Query("SELECT * FROM hosts")
    suspend fun getAll(): List<HostEntity>

    @Query("SELECT * FROM hosts WHERE uuid = :uuid")
    suspend fun findByUuid(uuid: String): HostEntity?

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun findById(id: Long): HostEntity?

    @Query("SELECT * FROM hosts WHERE id = :id")
    fun observeById(id: Long): Flow<HostEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: HostEntity): Long

    @Update
    suspend fun update(host: HostEntity)

    @Delete
    suspend fun delete(host: HostEntity)

    @Query("UPDATE hosts SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun touchLastConnected(id: Long, timestamp: Long)

    @Query("SELECT COUNT(*) FROM hosts WHERE keyId = :keyId")
    suspend fun countByKeyId(keyId: Long): Int

    @Query("SELECT COUNT(*) FROM hosts WHERE credentialId = :credentialId")
    suspend fun countByCredentialId(credentialId: Long): Int
}
