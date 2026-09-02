package com.aifriend.feature.collection

import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.VoiceCollectionCategory
import com.aifriend.contract.model.VoiceCollectionEnvironment
import com.aifriend.contract.model.VoiceCollectionReviewStatus
import com.aifriend.contract.model.VoiceCollectionSample
import com.aifriend.contract.model.VoiceCollectionTrainingAuthorization

/**
 * 封闭测试语音采集页面状态。不包含音频字节、上传地址或授权密钥。
 *
 * @author codex
 * @since 2026-08-20
 */
data class VoiceCollectionUiState(
    val stage: VoiceCollectionStage = VoiceCollectionStage.IDLE,
    val promptIndex: Int = 0,
    val environment: VoiceCollectionEnvironment = VoiceCollectionEnvironment.QUIET,
    val recordedDurationMs: Int? = null,
    val reviewTranscript: String = "",
    val reviewPlaybackCompleted: Boolean = false,
    val isPlaying: Boolean = false,
    val samples: List<VoiceCollectionSample> = emptyList(),
    val trainingConsentGranted: Boolean = false,
    val pendingTrainingSampleId: String? = null,
    val pendingTrainingDecision: ConsentDecision? = null,
    val revokeTrainingConsentConfirmationPending: Boolean = false,
    val pendingDeleteSampleId: String? = null,
    val revokeConfirmationPending: Boolean = false,
    val microphonePermissionRecoveryRequired: Boolean = false,
    val errorMessage: String? = null,
) {
    val currentPrompt: VoiceCollectionPrompt
        get() = VOICE_COLLECTION_PROMPTS[promptIndex.coerceIn(VOICE_COLLECTION_PROMPTS.indices)]

    fun requestTrainingAuthorization(sampleId: String): VoiceCollectionUiState {
        val sample = samples.firstOrNull { it.sampleId == sampleId } ?: return this
        if (!sample.trainingEligible &&
            sample.reviewStatus != VoiceCollectionReviewStatus.CONFIRMED
        ) {
            return this
        }
        return copy(
            pendingTrainingSampleId = sampleId,
            pendingTrainingDecision = if (sample.trainingEligible) {
                ConsentDecision.REVOKED
            } else {
                ConsentDecision.GRANTED
            },
        )
    }

    fun applyTrainingAuthorization(
        authorization: VoiceCollectionTrainingAuthorization,
    ): VoiceCollectionUiState = copy(
        trainingConsentGranted = trainingConsentGranted || authorization.trainingEligible,
        samples = samples.map { sample ->
            if (sample.sampleId == authorization.sampleId) {
                sample.copy(
                    trainingEligible = authorization.trainingEligible,
                    version = authorization.version,
                )
            } else {
                sample
            }
        },
        pendingTrainingSampleId = null,
        pendingTrainingDecision = null,
    )
}

/** 麦克风权限被拒绝时保留采集阶段与本地录音状态，只开放恢复入口。 */
internal fun VoiceCollectionUiState.withMicrophonePermissionDenied(): VoiceCollectionUiState =
    copy(
        microphonePermissionRecoveryRequired = true,
        errorMessage = "需要允许麦克风权限才能参与语音采集",
    )

/** 页面有限状态。 */
enum class VoiceCollectionStage {
    IDLE,
    LOADING,
    CONSENT_REQUIRED,
    SAVING_CONSENT,
    READY,
    RECORDING,
    CHECKING,
    REVIEW,
    SUBMITTING,
    DELETING,
    UPDATING_TRAINING_AUTHORIZATION,
    REVOKING_TRAINING_CONSENT,
    REVOKING,
}

/**
 * 客户端固定引导提示。服务端只接收 [code]，不接收 [spokenText]。
 *
 * @property code 非敏感固定提示编码
 * @property category 有限样本类别
 * @property spokenText 给测试者看的本地提示
 * @property defaultTranscript 固定提示可预填的人工复核文字；自由称呼为空
 * @property explanation 测试目的说明
 */
data class VoiceCollectionPrompt(
    val code: String,
    val category: VoiceCollectionCategory,
    val spokenText: String,
    val defaultTranscript: String,
    val explanation: String,
)

val VOICE_COLLECTION_PROMPTS = listOf(
    VoiceCollectionPrompt(
        "wake_xiaoyou_01",
        VoiceCollectionCategory.WAKE_WORD,
        "小友",
        "小友",
        "用于评估武冈话唤醒词，不会直接开启正式唤醒能力",
    ),
    VoiceCollectionPrompt(
        "alias_natural_01",
        VoiceCollectionCategory.CONTACT_ALIAS,
        "自然说一个平时称呼亲友的称呼",
        "",
        "不要说真实姓名；可说家里常用称呼",
    ),
    VoiceCollectionPrompt(
        "safety_cancel_01",
        VoiceCollectionCategory.SAFETY_COMMAND,
        "取消",
        "取消",
        "用于评估安全指令发音，不会取消或执行真实任务",
    ),
    VoiceCollectionPrompt(
        "task_call_01",
        VoiceCollectionCategory.FULL_TASK,
        "给二狗子打微信语音电话",
        "给二狗子打微信语音电话",
        "这是固定测试句，不会打开微信或发起通话",
    ),
    VoiceCollectionPrompt(
        "negative_casual_01",
        VoiceCollectionCategory.NEGATIVE,
        "今天天气不错",
        "今天天气不错",
        "用于评估普通闲聊不应误触发任务",
    ),
)

/** 把有限采集分类转换为用户可见中文，不展示 OpenAPI 枚举名。 */
internal fun VoiceCollectionCategory.userLabel(): String = when (this) {
    VoiceCollectionCategory.WAKE_WORD -> "唤醒词"
    VoiceCollectionCategory.CONTACT_ALIAS -> "联系人称呼"
    VoiceCollectionCategory.SAFETY_COMMAND -> "安全指令"
    VoiceCollectionCategory.FULL_TASK -> "完整任务"
    VoiceCollectionCategory.NEGATIVE -> "普通闲聊"
}
