package com.assh.ui.commands

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assh.AsshApp
import com.assh.data.db.entity.CommandEntity
import com.assh.data.db.entity.HostEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CommandsViewModel(app: Application) : AndroidViewModel(app) {

    private val asshApp = app as AsshApp
    private val repo = asshApp.commandRepository

    val commands: StateFlow<List<CommandEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hosts: StateFlow<List<HostEntity>> = asshApp.hostRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(command: CommandEntity) {
        viewModelScope.launch { repo.save(command) }
    }

    fun delete(command: CommandEntity) {
        viewModelScope.launch { repo.delete(command) }
    }
}
