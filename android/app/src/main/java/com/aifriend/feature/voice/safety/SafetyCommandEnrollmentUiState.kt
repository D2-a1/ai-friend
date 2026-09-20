package com.aifriend.feature.voice.safety

import com.aifriend.contract.model.SafetyCommandType

/**
 * 四类安全指令双录页面状态。不包含音频字节、上传凭证或对象键。
 *
 * @author codex
 * @since 2026-08-13
 */
data class SafetyCommandEnrollmentUiState(
    val microphonePermissionRecoveryRequired: Boolean = false,
    val stage: SafetyCommandEnrollmentStage = SafetyCommandEnrollmentStage.IDLE,
    val currentCommandIndex: Int = 0,
    val commands: List<SafetyCommandProgress> = SafetyCommandDefinition.entries.map {
        SafetyCommandProgress(it)
    },
    /** 服务端已有的固定安全指令类型；只用于展示存在事实，不包含音频或模板正文。 */
    val existingServerTemplateTypes: Set<SafetyCommandType> = emptySet(),
    /** 服务端四类模板材料完整，且与本机当前方言包版本完全一致。 */
    val existingServerTemplatesUsable: Boolean = false,
    /** 四类本机密文均可解密并可继续用于识别。 */
    val existingLocalTemplatesReady: Boolean = false,
    val playingRecording: SafetyRecordingSlot? = null,
    val errorMessage: String? = null,
)

/**
 * 安全指令注册流程阶段。
 */
enum class SafetyCommandEnrollmentStage {
    IDLE,
    CHECKING_CONSENT,
    CONSENT_REQUIRED,
    SAVING_CONSENT,
    EXISTING_STATUS_UNAVAILABLE,
    EXISTING_COMPLETE,
    READY_FIRST,
    RECORDING_FIRST,
    CHECKING_FIRST,
    FIRST_RECORDED,
    RECORDING_SECOND,
    CHECKING_SECOND,
    REVIEW_COMMAND,
    REVIEW_ALL,
    SUBMITTING,
    COMPLETED,
}

/**
 * 固定的四类动作型安全指令及其用户提示。
 */
enum class SafetyCommandDefinition(
    val contractType: SafetyCommandType,
    val phraseOptions: List<String>,
    val explanation: String,
) {
    CONFIRM_SEND(
        SafetyCommandType.CONFIRM_SEND,
        listOf("发送消息", "把消息发出去"),
        "只用于确认发送当前已复述的消息",
    ),
    CONFIRM_CALL(
        SafetyCommandType.CONFIRM_CALL,
        listOf("拨打电话", "现在打电话"),
        "只用于确认当前已复述的微信语音或视频通话",
    ),
    CANCEL(
        SafetyCommandType.CANCEL,
        listOf("取消这次", "这次不要了"),
        "立即取消当前任务，不执行发送或通话",
    ),
    REJECT_RETRY(
        SafetyCommandType.REJECT_RETRY,
        listOf("重新说一遍", "我重新说"),
        "否定当前理解并要求重新说，不执行当前任务",
    ),
}

/**
 * 单类指令的非敏感录音进度。
 */
data class SafetyCommandProgress(
    val definition: SafetyCommandDefinition,
    val selectedPhraseIndex: Int = 0,
    val firstDurationMs: Int? = null,
    val secondDurationMs: Int? = null,
    val reviewed: Boolean = false,
) {
    val selectedSpokenText: String
        get() = definition.phraseOptions.getOrElse(selectedPhraseIndex) {
            definition.phraseOptions.first()
        }
}

/**
 * 页面可定位的录音槽位。
 */
data class SafetyRecordingSlot(
    val commandIndex: Int,
    val take: SafetyRecordingTake,
)

/**
 * 同一指令的录音遍次。
 */
enum class SafetyRecordingTake {
    FIRST,
    SECOND,
}
