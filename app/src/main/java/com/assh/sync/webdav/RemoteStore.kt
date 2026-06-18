package com.assh.sync.webdav

/**
 * 远端同步存储 seam（C4 深化）：把"把加密同步包放到某处/取回来"的能力收在 4 个方法后面。
 *
 * 生产实现是 [WebDavClient];测试可注入内存 fake，从而让 [com.assh.sync.SyncEngine] 的
 * PUSH/PULL/MERGE 编排无需真实 WebDAV 服务器即可单测——此前 SyncEngine 直接 new
 * WebDavClient(cfg)，整条编排都被钉死在网络上。
 *
 * 所有方法为同步阻塞 I/O，调用方需在协程 Dispatchers.IO 中调用。
 */
interface RemoteStore {
    /** 连通性 + 鉴权探测，失败抛 [WebDavException] */
    fun testConnection()

    /** 下载同步包；返回 (字节, ETag)，文件不存在返回 null */
    fun download(name: String): Pair<ByteArray, String?>?

    /** 无条件上传同步包，返回新 ETag（并发控制改用 vault 内部版本号，不再用 If-Match） */
    fun upload(name: String, data: ByteArray): String?

    /** 删除远端同步包（重置同步用） */
    fun delete(name: String)
}

/** 由 [WebDavConfig] 建立 [RemoteStore]；生产为 [WebDavStoreFactory]，测试注入 fake */
fun interface RemoteStoreFactory {
    fun create(cfg: WebDavConfig): RemoteStore
}

/** 生产工厂：WebDAV 实现 */
class WebDavStoreFactory : RemoteStoreFactory {
    override fun create(cfg: WebDavConfig): RemoteStore = WebDavClient(cfg)
}
