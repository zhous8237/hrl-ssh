package com.assh.sync

/** 三种同步动作（对应 UI 三个按钮） */
enum class SyncMode {
    /** 同步到云端：以本地为准，整包覆盖云端（备份） */
    PUSH,

    /** 同步到本地：以云端为准，整包覆盖本地（恢复） */
    PULL,

    /** 智能合并：双向按 uuid + updatedAt(LWW) 合并，再写回两端 */
    MERGE
}

/** 单次同步的增删改统计 */
data class SyncStats(
    val hosts: Int = 0,
    val keys: Int = 0,
    val credentials: Int = 0,
    val commands: Int = 0,
    val aiProfiles: Int = 0
) {
    val total: Int get() = hosts + keys + credentials + commands + aiProfiles
}

/** 同步结果 */
sealed interface SyncResult {
    data class Ok(val mode: SyncMode, val stats: SyncStats, val message: String) : SyncResult
    data class Err(val message: String) : SyncResult
}
