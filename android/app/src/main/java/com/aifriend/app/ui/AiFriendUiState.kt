package com.aifriend.app.ui

/**
 * 第一批账号与授权链路的根页面状态。
 *
 * @author codex
 * @since 2026-08-04
 */
sealed interface AiFriendUiState {
    data object Loading : AiFriendUiState

    data object SignedOut : AiFriendUiState

    data object AccountWiping : AiFriendUiState

    data class AccountWipeFailed(val message: String) : AiFriendUiState

    data class ConsentRequired(val userId: String) : AiFriendUiState

    data class Ready(val userId: String) : AiFriendUiState

    data class Error(val message: String) : AiFriendUiState
}
