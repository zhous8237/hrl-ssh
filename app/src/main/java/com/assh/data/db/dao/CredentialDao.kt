package com.assh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.assh.data.db.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    @Query("SELECT * FROM credentials ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CredentialEntity>>

    /** 同步导出用：一次性全量 */
    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE uuid = :uuid")
    suspend fun findByUuid(uuid: String): CredentialEntity?

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun findById(id: Long): CredentialEntity?

    @Query("SELECT * FROM credentials WHERE alias = :alias")
    suspend fun findByAlias(alias: String): CredentialEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(credential: CredentialEntity): Long

    @Update
    suspend fun update(credential: CredentialEntity)

    @Delete
    suspend fun delete(credential: CredentialEntity)
}
