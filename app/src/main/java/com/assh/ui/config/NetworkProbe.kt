package com.assh.ui.config

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 一条本机地址记录（接口名 + 地址 + 是否回环/链路本地） */
data class LocalAddress(
    val ifaceName: String,
    val address: String,
    val isV6: Boolean,
    val isLoopback: Boolean,
    val isLinkLocal: Boolean
)

/** 网络探测结果 */
data class NetworkReport(
    val locals: List<LocalAddress>,
    val hasGlobalV4: Boolean,        // 本机有可路由 IPv4（非回环/链路本地）
    val hasGlobalV6: Boolean,        // 本机有可路由 IPv6
    val v4Internet: Boolean?,        // IPv4 出网可达（null = 未测/超时）
    val v6Internet: Boolean?         // IPv6 出网可达
) {
    /** 给用户的连接建议 */
    val advice: String
        get() = when {
            v4Internet == true && v6Internet == true ->
                "IPv4 与 IPv6 均可用。连接服务器时两种地址都可以，优先用 IPv4 兼容性更好。"
            v4Internet == true ->
                "仅 IPv4 可用。请用服务器的 IPv4 地址连接；IPv6 地址在当前网络无法连通。"
            v6Internet == true ->
                "仅 IPv6 可用。请用服务器的 IPv6 地址连接；当前网络没有可用的 IPv4 出口。"
            hasGlobalV4 || hasGlobalV6 ->
                "已检测到本机地址，但出网测试未通过。可能是无公网出口或测试被拦截，可仍尝试连接内网服务器。"
            else ->
                "未检测到可用网络地址，请检查 Wi-Fi / 移动数据连接。"
        }
}

/**
 * 网络探测（功能 3）：枚举本机网卡地址 + 分别测试 IPv4/IPv6 出网可达性。
 * 出网测试用 TCP 连接公共 DNS（IPv4: 1.1.1.1:53；IPv6: 2606:4700:4700::1111:53），
 * 直连 IP 不触发 DNS，结果只反映对应协议栈是否真的能出网。
 */
object NetworkProbe {

    suspend fun probe(): NetworkReport = withContext(Dispatchers.IO) {
        val locals = enumerateLocal()
        val hasGlobalV4 = locals.any { !it.isV6 && !it.isLoopback && !it.isLinkLocal }
        val hasGlobalV6 = locals.any { it.isV6 && !it.isLoopback && !it.isLinkLocal }

        val v4 = tcpReachable("1.1.1.1", 53)
        val v6 = tcpReachable("2606:4700:4700::1111", 53)

        NetworkReport(
            locals = locals,
            hasGlobalV4 = hasGlobalV4,
            hasGlobalV6 = hasGlobalV6,
            v4Internet = v4,
            v6Internet = v6
        )
    }

    private fun enumerateLocal(): List<LocalAddress> = buildList {
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp }
                ?.forEach { iface ->
                    iface.inetAddresses.toList().forEach { addr ->
                        val isV6 = addr is Inet6Address
                        // IPv6 去掉 scope id 后缀（%wlan0）便于阅读
                        val text = addr.hostAddress?.substringBefore('%') ?: return@forEach
                        add(
                            LocalAddress(
                                ifaceName = iface.displayName ?: iface.name,
                                address = text,
                                isV6 = isV6,
                                isLoopback = addr.isLoopbackAddress,
                                isLinkLocal = addr.isLinkLocalAddress
                            )
                        )
                    }
                }
        }
    }

    /** TCP 连通性测试：直连指定 IP，3 秒超时；连上即可达 */
    private suspend fun tcpReachable(ip: String, port: Int): Boolean? =
        withTimeoutOrNull(3_500L) {
            runCatching {
                val addr = InetAddress.getByName(ip)
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(addr, port), 3_000)
                    true
                }
            }.getOrDefault(false)
        }
}
