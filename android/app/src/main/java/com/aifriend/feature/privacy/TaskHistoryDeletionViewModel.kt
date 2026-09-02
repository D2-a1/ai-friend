package com.aifriend.feature.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.contract.model.TaskHistoryDeletionStatus
import com.aifriend.core.network.toChineseUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TaskHistoryDeletionUiState {
    data object Idle : TaskHistoryDeletionUiState
    data object Confirming : TaskHistoryDeletionUiState
    data object Submitting : TaskHistoryDeletionUiState
    data object Clearing : TaskHistoryDeletionUiState
    data object Completed : TaskHistoryDeletionUiState
    data class Error(val message: String) : TaskHistoryDeletionUiState
}

/** 任务历史清除二次确认与公开状态 ViewModel。 */
@HiltViewModel
class TaskHistoryDeletionViewModel @Inject constructor(private val repository: TaskHistoryDeletionRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<TaskHistoryDeletionUiState>(TaskHistoryDeletionUiState.Idle)
    val state = mutableState.asStateFlow()
    fun open() { mutableState.value = TaskHistoryDeletionUiState.Idle }
    fun requestConfirmation() { mutableState.value = TaskHistoryDeletionUiState.Confirming }
    fun cancelConfirmation() { mutableState.value = TaskHistoryDeletionUiState.Idle }
    fun confirm(onAccepted: () -> Unit) {
        if (mutableState.value != TaskHistoryDeletionUiState.Confirming) return
        mutableState.value = TaskHistoryDeletionUiState.Submitting
        viewModelScope.launch {
            runCatching { repository.clear() }.onSuccess {
                onAccepted()
                mutableState.value = if (it.status == TaskHistoryDeletionStatus.COMPLETED) {
                    TaskHistoryDeletionUiState.Completed
                } else {
                    TaskHistoryDeletionUiState.Clearing
                }
            }.onFailure {
                mutableState.value = TaskHistoryDeletionUiState.Error(
                    it.toChineseUserMessage("清除请求失败"),
                )
            }
        }
    }
    fun refresh() = viewModelScope.launch {
        runCatching { repository.get() }.onSuccess {
            mutableState.value = if (it.status == TaskHistoryDeletionStatus.COMPLETED) TaskHistoryDeletionUiState.Completed else TaskHistoryDeletionUiState.Clearing
        }.onFailure {
            mutableState.value = TaskHistoryDeletionUiState.Error(
                it.toChineseUserMessage("查询失败"),
            )
        }
    }
}
