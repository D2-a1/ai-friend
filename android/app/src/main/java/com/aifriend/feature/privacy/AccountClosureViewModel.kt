package com.aifriend.feature.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.core.network.toChineseUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AccountClosureUiState {
    data object Reviewing : AccountClosureUiState
    data object Confirming : AccountClosureUiState
    data object Submitting : AccountClosureUiState
    data class Error(val message: String) : AccountClosureUiState
}

/** 永久注销两次明确确认和单次提交状态机。 */
@HiltViewModel
class AccountClosureViewModel @Inject constructor(
    private val repository: AccountClosureRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AccountClosureUiState>(AccountClosureUiState.Reviewing)
    val state = mutableState.asStateFlow()

    fun open() {
        mutableState.value = AccountClosureUiState.Reviewing
    }

    fun continueConfirmation() {
        if (mutableState.value == AccountClosureUiState.Reviewing) {
            mutableState.value = AccountClosureUiState.Confirming
        }
    }

    fun cancelConfirmation() {
        if (mutableState.value == AccountClosureUiState.Confirming) {
            mutableState.value = AccountClosureUiState.Reviewing
        }
    }

    fun confirm(onAccepted: () -> Unit) {
        if (mutableState.value != AccountClosureUiState.Confirming) return
        mutableState.value = AccountClosureUiState.Submitting
        viewModelScope.launch {
            runCatching { repository.close() }
                .onSuccess { onAccepted() }
                .onFailure {
                    mutableState.value = AccountClosureUiState.Error(
                        it.toChineseUserMessage("注销申请没有可靠受理，请稍后重新确认"),
                    )
                }
        }
    }

    fun retryReview() {
        if (mutableState.value is AccountClosureUiState.Error) {
            mutableState.value = AccountClosureUiState.Reviewing
        }
    }
}
