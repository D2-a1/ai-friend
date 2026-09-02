package com.aifriend.feature.guardian.wake

/** 个人“小友”双录页面状态；不包含原始录音或声学模板。 */
data class WakeWordEnrollmentUiState(
    val stage: WakeWordEnrollmentStage = WakeWordEnrollmentStage.IDLE,
    val firstDurationMs: Int? = null,
    val secondDurationMs: Int? = null,
    val playingRecording: WakeWordRecordingSlot? = null,
    val hasExistingTemplate: Boolean = false,
    val errorMessage: String? = null,
    val microphonePermissionRecoveryRequired: Boolean = false,
)

/** 个人“小友”双录的有限阶段。 */
enum class WakeWordEnrollmentStage {
    IDLE,
    READY_FIRST,
    RECORDING_FIRST,
    CHECKING_FIRST,
    FIRST_RECORDED,
    RECORDING_SECOND,
    CHECKING_SECOND,
    REVIEW,
    SAVING,
    COMPLETED,
}

/** 个人“小友”双录序号。 */
enum class WakeWordRecordingSlot {
    FIRST,
    SECOND,
}
