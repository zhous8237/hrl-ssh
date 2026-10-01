package com.assh.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.assh.AsshApp
import com.assh.data.db.dao.KnownHostDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FingerprintClearState(
    val confirming: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null
)

class ConfigViewModel(private val knownHostDao: KnownHostDao) : ViewModel() {
    private val _state = MutableStateFlow(FingerprintClearState())
    val state = _state.asStateFlow()

    fun requestClear() {
        if (!_state.value.busy) _state.value = FingerprintClearState(confirming = true)
    }

    fun cancelClear() {
        if (!_state.value.busy) _state.value = FingerprintClearState()
    }

    fun confirmClear() {
        if (!_state.value.confirming || _state.value.busy) return
        // 在启动协程前占用操作，避免连续点击排入多次清理。
        _state.value = FingerprintClearState(confirming = true, busy = true)
        viewModelScope.launch {
            try {
                knownHostDao.clear()
                _state.value = FingerprintClearState(message = "已清除服务器指纹，下次连接将重新记录指纹")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = FingerprintClearState(confirming = true, message = "清除失败：${e.message ?: "数据库操作失败"}，请重试")
            } finally {
                if (_state.value.busy) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                ConfigViewModel((this[APPLICATION_KEY] as AsshApp).database.knownHostDao())
            }
        }
    }
}
