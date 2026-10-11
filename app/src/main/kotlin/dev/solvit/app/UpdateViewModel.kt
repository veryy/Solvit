package dev.solvit.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.solvit.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Hidden : UpdateUiState
    data object Checking : UpdateUiState
    data object Current : UpdateUiState
    data class Available(val release: AppRelease) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

class UpdateViewModel(private val service: UpdateService) : ViewModel() {
    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state = mutableState.asStateFlow()
    init { check(manual = false) }

    fun dismiss() { if (mutableState.value != UpdateUiState.Checking) mutableState.value = UpdateUiState.Hidden }
    fun check(manual: Boolean = true) {
        if (mutableState.value == UpdateUiState.Checking) return
        mutableState.value = UpdateUiState.Checking
        viewModelScope.launch {
            try {
                mutableState.value = when (val result = service.check(BuildConfig.VERSION_NAME)) {
                    is UpdateResult.Available -> UpdateUiState.Available(result.release)
                    UpdateResult.Current -> if (manual) UpdateUiState.Current else UpdateUiState.Hidden
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.value = UpdateUiState.Failed(e.message ?: "无法检查更新，请稍后重试") }
        }
    }
}
