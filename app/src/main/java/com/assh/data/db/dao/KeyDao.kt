package com.assh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.assh.data.db.entity.KeyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyDao {

    @Query("SELECT * FROM `keys` ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<KeyEntity>>

    /** 同步导出用：一次性全量 */
    @Query("SELECT * FROM `keys`")
    suspend fun getAll(): List<KeyEntity>

    @Query("SELECT * FROM `keys` WHERE uuid = :uuid")
    suspend fun findByUuid(uuid: String): KeyEntity?

    @Query("SELECT * FROM `keys` WHERE id = :id")
    suspend fun findById(id: Long): KeyEntity?

    @Query("SELECT * FROM `keys` WHERE alias = :alias")
    suspend fun findByAlias(alias: String): KeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(key: KeyEntity): Long

    @Update
    suspend fun update(key: KeyEntity)

    @Delete
    suspend fun delete(key: KeyEntity)
}
