package com.assh.sync.webdav

/**
 * 内存 [RemoteStore]，让 [com.assh.sync.SyncEngine] 的 PUSH/PULL/MERGE 编排
 * 无需真实 WebDAV 服务器即可单测（C4 seam 的测试 adapter）。
 */
class FakeRemoteStore : RemoteStore {
    /** 远端文件区：name -> (bytes, etag) */
    private val files = HashMap<String, Pair<ByteArray, String?>>()

    var testConnectionCalls = 0; private set
    var uploadCalls = 0; private set
    var downloadCalls = 0; private set
    var deleteCalls = 0; private set
    private var etagSeq = 0

    /** 测试预置：把一份已加密的同步包放进远端 */
    fun seed(name: String, bytes: ByteArray, etag: String? = "seed-etag") {
        files[name] = bytes to etag
    }

    override fun testConnection() { testConnectionCalls++ }

    override fun download(name: String): Pair<ByteArray, String?>? {
        downloadCalls++
        return files[name]
    }

    override fun upload(name: String, data: ByteArray): String? {
        uploadCalls++
        val etag = "etag-${++etagSeq}"
        files[name] = data to etag
        return etag
    }

    override fun delete(name: String) {
        deleteCalls++
        files.remove(name)
    }
}
