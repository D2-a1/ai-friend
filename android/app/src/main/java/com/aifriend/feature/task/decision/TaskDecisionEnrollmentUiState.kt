package com.aifriend.feature.task.decision

import com.aifriend.core.voice.TaskDecisionTemplateType

data class TaskDecisionEnrollmentUiState(
    val stage: TaskDecisionEnrollmentStage = TaskDecisionEnrollmentStage.IDLE,
    val currentType: TaskDecisionTemplateType = TaskDecisionTemplateType.CONFIRM,
    val currentTake: Int = 1,
    val completedTypes: Set<TaskDecisionTemplateType> = emptySet(),
    val hasExistingTemplates: Boolean = false,
    val errorMessage: String? = null,
    val microphonePermissionRecoveryRequired: Boolean = false,
)

enum class TaskDecisionEnrollmentStage {
    IDLE,
    READY,
    RECORDING,
    CHECKING,
    READY_TO_SAVE,
    SAVING,
    COMPLETED,
}