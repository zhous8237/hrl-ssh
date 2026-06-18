package com.assh.sync

import com.assh.sync.vault.VaultDto

/**
 * 三方合并（LWW）的纯函数（C3 深化）。
 *
 * 从 [SyncRepository] 抽出：合并逻辑只依赖 [VaultDto] 与几个取值 lambda，没有任何 Android /
 * Room / Keystore 依赖，因此可在 JVM 上直接单测——而 [SyncRepository] 的构造函数会拖进
 * AsshDatabase + AgentPreferences，过去导致冲突解析逻辑根本无法离开设备测试。
 *
 * 规则：按 uuid（AI 配置按 id）取并集，冲突时按 updatedAt 取较新者（Last-Write-Wins）；
 * deleted 墓碑项不进入结果（写回阶段 applyMirror 据此删除本地对应行）。
 */
object VaultMerger {

    fun merge(local: VaultDto, remote: VaultDto, vaultVersion: Long, deviceId: String): VaultDto {
        return VaultDto(
            vaultVersion = vaultVersion,
            exportedAt = System.currentTimeMillis(),
            deviceId = deviceId,
            hosts = mergeBy(local.hosts, remote.hosts, { it.uuid }, { it.updatedAt }, { it.deleted }),
            keys = mergeBy(local.keys, remote.keys, { it.uuid }, { it.updatedAt }, { it.deleted }),
            credentials = mergeBy(local.credentials, remote.credentials, { it.uuid }, { it.updatedAt }, { it.deleted }),
            commands = mergeBy(local.commands, remote.commands, { it.uuid }, { it.updatedAt }, { it.deleted }),
            aiProfiles = mergeBy(local.aiProfiles, remote.aiProfiles, { it.id }, { it.updatedAt }, { it.deleted }),
            aiActiveProfileId = local.aiActiveProfileId ?: remote.aiActiveProfileId
        )
    }

    fun <T> mergeBy(
        local: List<T>, remote: List<T>,
        uuid: (T) -> String, updatedAt: (T) -> Long, deleted: (T) -> Boolean
    ): List<T> {
        val byUuid = LinkedHashMap<String, T>()
        for (item in local) byUuid[uuid(item)] = item
        for (item in remote) {
            val existing = byUuid[uuid(item)]
            if (existing == null || updatedAt(item) > updatedAt(existing)) {
                byUuid[uuid(item)] = item
            }
        }
        // 墓碑项不进入合并结果（applyMirror 会据此删除本地对应行）
        return byUuid.values.filterNot { deleted(it) }
    }
}
