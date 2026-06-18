package com.assh.ui.hosts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assh.AsshApp
import com.assh.data.db.entity.HostEntity
import com.assh.ssh.ConnState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HostsViewModel(app: Application) : AndroidViewModel(app) {

    private val asshApp = app as AsshApp
    private val repo = asshApp.hostRepository

    val hosts: StateFlow<List<HostEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 各主机连接状态聚合流（主机列表实时刷新用） */
    val connStates: StateFlow<Map<Long, ConnState>> = asshApp.connectionManager.states

    /** 列表操作单「断开连接」：同时清理终端会话与 SSH 连接 */
    fun disconnect(hostId: Long) {
        viewModelScope.launch {
            asshApp.terminalRegistry.remove(hostId)?.finishIfRunning()
            asshApp.connectionManager.disconnect(hostId)
        }
    }

    fun delete(host: HostEntity) {
        viewModelScope.launch {
            asshApp.terminalRegistry.remove(host.id)?.finishIfRunning()
            asshApp.connectionManager.disconnect(host.id)
            repo.delete(host)
        }
    }

    fun clone(host: HostEntity) {
        viewModelScope.launch { repo.clone(host) }
    }
}
