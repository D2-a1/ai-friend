package com.aifriend.feature.contact.alias

/**
 * 称呼双录页面状态。不包含原始音频、上传凭证或对象键。
 *
 * @author codex
 * @since 2026-08-12
 */
data class AliasEnrollmentUiState(
    val contactId: String? = null,
    val contactLabel: String = "",
    val existingAliases: List<AliasSummaryUiState> = emptyList(),
    val displayText: String = "",
    val stage: AliasEnrollmentStage = AliasEnrollmentStage.IDLE,
    val firstDurationMs: Int? = null,
    val secondDurationMs: Int? = null,
    val playingRecording: AliasRecordingSlot? = null,
    val errorMessage: String? = null,
    val informationMessage: String? = null,
    val completedAliasText: String? = null,
    val canAddAnotherAlias: Boolean = false,
    val pendingAliasDeletion: AliasSummaryUiState? = null,
    val isDeletingAlias: Boolean = false,
    val microphonePermissionRecoveryRequired: Boolean = false,
)

/** 只供称呼页面展示和选择删除的最小摘要。 */
data class AliasSummaryUiState(
    val id: String,
    val displayText: String,
    val compatible: Boolean = true,
)

/**
 * 称呼双录流程阶段。
 */
enum class AliasEnrollmentStage {
    IDLE,
    CHECKING_CONSENT,
    CONSENT_REQUIRED,
    SAVING_CONSENT,
    READY_FIRST,
    RECORDING_FIRST,
    CHECKING_FIRST,
    FIRST_RECORDED,
    RECORDING_SECOND,
    CHECKING_SECOND,
    REVIEW,
    SUBMITTING,
    COMPLETED,
    UNAVAILABLE,
}

/**
 * 称呼双录的录音序号。
 */
enum class AliasRecordingSlot {
    FIRST,
    SECOND,
}
