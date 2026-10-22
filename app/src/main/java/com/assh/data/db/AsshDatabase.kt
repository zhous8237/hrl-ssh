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
    version = 6,
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
        /**
         * v5→v6：known_hosts 支持同一 host:port 记录多把主机密钥
         * （负载均衡/多节点域名、密钥轮换）。
         *
         * 旧表主键是 hostPort 单列，新表为 id（hostPort#指纹）单列 + hostPort 索引。
         * SQLite 无法直接改主键，故重建表并搬数据；旧数据天然一对一，可直接迁移。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `known_hosts_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`hostPort` TEXT NOT NULL, " +
                        "`keyType` TEXT NOT NULL, " +
                        "`fingerprintSha256` TEXT NOT NULL, " +
                        "`publicKeyBlob` BLOB NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                // 旧表没有 addedAt，回填迁移时刻；id 用「hostPort#指纹」拼出，与实体一致
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT OR REPLACE INTO `known_hosts_new` " +
                        "(`id`, `hostPort`, `keyType`, `fingerprintSha256`, `publicKeyBlob`, `addedAt`) " +
                        "SELECT `hostPort` || '#' || `fingerprintSha256`, `hostPort`, `keyType`, " +
                        "`fingerprintSha256`, `publicKeyBlob`, $now FROM `known_hosts`"
                )
                db.execSQL("DROP TABLE `known_hosts`")
                db.execSQL("ALTER TABLE `known_hosts_new` RENAME TO `known_hosts`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_known_hosts_hostPort` ON `known_hosts` (`hostPort`)")
            }
        }

        fun get(context: Context): AsshDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AsshDatabase::class.java,
                    "assh.db"
                ).addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    // 不用 fallbackToDestructiveMigration()：本库存着用户唯一一份 SSH 私钥/密码，
                    // 静默清库换不崩溃是不可接受的取舍。缺失迁移时宁可启动即抛 IllegalStateException
                    // （明确暴露问题），也不能悄悄抹掉数据。改 schema 必须随版本号补 Migration。
                    .build().also { instance = it }
            }
    }
}
