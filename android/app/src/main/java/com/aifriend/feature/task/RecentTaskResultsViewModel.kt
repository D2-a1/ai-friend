package com.aifriend.feature.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.contract.model.RecentTaskResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 最近任务结果页面状态。 */
data class RecentTaskResultsUiState(
    val isLoading: Boolean = false,
    val results: List<RecentTaskResult> = emptyList(),
    val errorMessage: String? = null,
)

/** 加载当前账号最近任务结果，只在用户打开页面时联网。 */
@HiltViewModel
class RecentTaskResultsViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RecentTaskResultsUiState())
    val uiState: StateFlow<RecentTaskResultsUiState> = mutableUiState.asStateFlow()

    /** 加载或刷新最近任务结果。 */
    fun load() {
        if (mutableUiState.value.isLoading) return
        val previousResults = mutableUiState.value.results
        mutableUiState.value = mutableUiState.value.copy(
            isLoading = true,
            errorMessage = null,
        )
        viewModelScope.launch {
            mutableUiState.value = runCatching { taskRepository.listRecentResults() }
                .fold(
                    onSuccess = { results ->
                        RecentTaskResultsUiState(results = results)
                    },
                    onFailure = {
                        RecentTaskResultsUiState(
                            results = previousResults,
                            errorMessage = "最近任务结果加载失败，请稍后重试",
                        )
                    },
                )
        }
    }
}
