package com.assh.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.assh.data.db.dao.CommandDao
import com.assh.data.db.dao.CredentialDao
import com.assh.data.db.dao.HostDao
import com.assh.data.db.dao.KeyDao
import com.assh.data.db.dao.KnownHostDao
import com.assh.data.db.entity.CommandEntity
import com.assh.data.db.entity.CredentialEntity
import com.assh.data.db.entity.HostEntity
import com.assh.data.db.entity.KeyEntity
import com.assh.data.db.entity.KnownHostEntity

@Database(
    entities = [HostEntity::class, KeyEntity::class, CredentialEntity::class, CommandEntity::class, KnownHostEntity::class],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AsshDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun keyDao(): KeyDao
    abstract fun credentialDao(): CredentialDao
    abstract fun commandDao(): CommandDao
    abstract fun knownHostDao(): KnownHostDao

    companion object {
        @Volatile
        private var instance: AsshDatabase? = null

        /**
         * v4→v5（功能 7 WebDAV 同步）：四张可同步表加 uuid + updatedAt。
         * - uuid：用 SQLite randomblob 为每行生成 UUID 形态的稳定主键（按行求值，互不相同）。
         * - updatedAt：存量数据统一回填迁移时刻，参与 LWW 合并。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                val newUuid =
                    "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || " +
                        "substr(hex(randomblob(2)),2) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(6)))"
                for (table in listOf("hosts", "keys", "commands", "credentials")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE `$table` SET uuid = $newUuid WHERE uuid = ''")
                    db.execSQL("UPDATE `$table` SET updatedAt = $now WHERE updatedAt = 0")
                }
            }
        }

        fun get(context: Context): AsshDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AsshDatabase::class.java,
                    "assh.db"
                ).addMigrations(MIGRATION_4_5)
                    // 不用 fallbackToDestructiveMigration()：本库存着用户唯一一份 SSH 私钥/密码，
                    // 静默清库换不崩溃是不可接受的取舍。缺失迁移时宁可启动即抛 IllegalStateException
                    // （明确暴露问题），也不能悄悄抹掉数据。改 schema 必须随版本号补 Migration。
                    .build().also { instance = it }
            }
    }
}
