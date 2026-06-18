package com.assh.sync.vault

import kotlinx.serialization.json.Json

/**
 * VaultDto ↔ JSON ↔ 加密字节（功能 7，开发文档 v2 §4.2）。
 *
 * encode: DTO --序列化--> JSON 字节 --VaultCrypto.encrypt(口令)--> 上传字节
 * decode: 下载字节 --VaultCrypto.decrypt(口令)--> JSON 字节 --反序列化--> DTO
 */
object VaultCodec {

    private val json = Json {
        ignoreUnknownKeys = true   // 前向兼容：新版本加字段，旧版本忽略
        encodeDefaults = true
    }

    fun encode(dto: VaultDto, passphrase: CharArray): ByteArray {
        val plain = json.encodeToString(VaultDto.serializer(), dto).toByteArray(Charsets.UTF_8)
        return VaultCrypto.encrypt(plain, passphrase)
    }

    fun decode(blob: ByteArray, passphrase: CharArray): VaultDto {
        val plain = VaultCrypto.decrypt(blob, passphrase)
        return try {
            json.decodeFromString(VaultDto.serializer(), String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw VaultFormatException("同步包内容解析失败：${e.message}")
        }
    }
}
