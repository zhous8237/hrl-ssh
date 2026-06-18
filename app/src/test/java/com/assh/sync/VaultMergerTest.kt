package com.assh.sync

import com.assh.sync.vault.CommandDto
import com.assh.sync.vault.CredentialDto
import com.assh.sync.vault.HostDto
import com.assh.sync.vault.KeyDto
import com.assh.sync.vault.VaultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VaultMerger] LWW 合并的纯函数测试——此前项目完全缺失的冲突解析测试套件（C3）。 */
class VaultMergerTest {

    private fun host(uuid: String, label: String, updatedAt: Long, deleted: Boolean = false) =
        HostDto(uuid = uuid, label = label, host = "h", port = 22, username = "u",
            authType = "PASSWORD", updatedAt = updatedAt, deleted = deleted)

    private fun vault(hosts: List<HostDto> = emptyList(), aiActive: String? = null) =
        VaultDto(vaultVersion = 1, exportedAt = 0, deviceId = "dev", hosts = hosts, aiActiveProfileId = aiActive)

    @Test
    fun `union by uuid keeps both distinct entities`() {
        val merged = VaultMerger.merge(
            vault(listOf(host("a", "A", 100))),
            vault(listOf(host("b", "B", 100))),
            2, "dev"
        )
        assertEquals(setOf("a", "b"), merged.hosts.mapTo(HashSet()) { it.uuid })
    }

    @Test
    fun `conflict resolves to newer updatedAt (remote wins)`() {
        val merged = VaultMerger.merge(
            vault(listOf(host("a", "local-old", 100))),
            vault(listOf(host("a", "remote-new", 200))),
            2, "dev"
        )
        assertEquals(1, merged.hosts.size)
        assertEquals("remote-new", merged.hosts.first().label)
    }

    @Test
    fun `conflict resolves to newer updatedAt (local wins)`() {
        val merged = VaultMerger.merge(
            vault(listOf(host("a", "local-new", 300))),
            vault(listOf(host("a", "remote-old", 200))),
            2, "dev"
        )
        assertEquals("local-new", merged.hosts.first().label)
    }

    @Test
    fun `equal updatedAt keeps local (strictly-greater wins rule)`() {
        val merged = VaultMerger.merge(
            vault(listOf(host("a", "local", 100))),
            vault(listOf(host("a", "remote", 100))),
            2, "dev"
        )
        assertEquals("updatedAt 相等时不替换，保留本地", "local", merged.hosts.first().label)
    }

    @Test
    fun `tombstone is excluded from result even when newer`() {
        val merged = VaultMerger.merge(
            vault(listOf(host("a", "local", 100))),
            vault(listOf(host("a", "remote-deleted", 200, deleted = true))),
            2, "dev"
        )
        assertTrue("较新的墓碑项应使该条目从结果中消失", merged.hosts.isEmpty())
    }

    @Test
    fun `merge carries through version and deviceId`() {
        val merged = VaultMerger.merge(vault(), vault(), 7, "deviceX")
        assertEquals(7, merged.vaultVersion)
        assertEquals("deviceX", merged.deviceId)
    }

    @Test
    fun `aiActiveProfileId prefers local then remote`() {
        assertEquals("L", VaultMerger.merge(vault(aiActive = "L"), vault(aiActive = "R"), 1, "d").aiActiveProfileId)
        assertEquals("R", VaultMerger.merge(vault(aiActive = null), vault(aiActive = "R"), 1, "d").aiActiveProfileId)
        assertNull(VaultMerger.merge(vault(), vault(), 1, "d").aiActiveProfileId)
    }

    @Test
    fun `mergeBy is order-stable - local order preserved, new remotes appended`() {
        val local = listOf(host("a", "A", 1), host("b", "B", 1))
        val remote = listOf(host("b", "B2", 2), host("c", "C", 1))
        val out = VaultMerger.mergeBy(local, remote, { it.uuid }, { it.updatedAt }, { it.deleted })
        assertEquals(listOf("a", "b", "c"), out.map { it.uuid })
        assertEquals("B2", out.first { it.uuid == "b" }.label)
    }
}
