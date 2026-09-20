package com.aifriend.feature.personalization

import com.aifriend.core.settings.SpeechRatePreference

/** 只允许长期保存的对话详略偏好。 */
enum class DialogueStyleChoice {
    BRIEF,
    STANDARD,
}

/**
 * 含糊通话说法的草稿偏好。
 *
 * 它不是当前任务授权，不得跳过完整复述和确认。
 */
enum class AmbiguousCallChoice {
    ASK_EVERY_TIME,
    VOICE,
    VIDEO,
}

/** 不包含自由文本、联系人、录音或转写的三项有限偏好。 */
data class PersonalMemoryChoices(
    val speechRate: SpeechRatePreference = SpeechRatePreference.NORMAL,
    val dialogueStyle: DialogueStyleChoice = DialogueStyleChoice.STANDARD,
    val ambiguousCall: AmbiguousCallChoice = AmbiguousCallChoice.ASK_EVERY_TIME,
) {
    companion object {
        val SafeDefault = PersonalMemoryChoices()
    }
}

/** 当前 owner 可查看的长期偏好状态。 */
data class PersonalMemorySnapshot(
    val featureEnabled: Boolean,
    val consentGranted: Boolean,
    val policyVersion: String,
    val choices: PersonalMemoryChoices?,
    val version: Long?,
) {
    fun activeChoicesOrNull(): PersonalMemoryChoices? = choices?.takeIf {
        featureEnabled && consentGranted && version != null
    }
}

/**
 * 长期个人偏好管理仓库。
 *
 * 运行时只允许读取已确认为开启且已授权的有限枚举；缓存缺失、
 * 失效或同步不确定时立即回落到最安全的默认值。
 */
interface PersonalMemoryRepository {
    suspend fun read(): PersonalMemorySnapshot

    suspend fun update(
        choices: PersonalMemoryChoices,
        expectedVersion: Long,
    ): PersonalMemorySnapshot

    suspend fun delete(expectedVersion: Long): PersonalMemorySnapshot

    fun currentPromptChoices(): PersonalMemoryChoices

    fun invalidate()
}

/** 测试和旧构造路径使用的安全默认实现。 */
object DisabledPersonalMemoryRepository : PersonalMemoryRepository {
    private val snapshot = PersonalMemorySnapshot(
        featureEnabled = false,
        consentGranted = false,
        policyVersion = "personal-memory-v1",
        choices = null,
        version = null,
    )

    override suspend fun read(): PersonalMemorySnapshot = snapshot

    override suspend fun update(
        choices: PersonalMemoryChoices,
        expectedVersion: Long,
    ): PersonalMemorySnapshot = error("长期个人偏好能力未启用")

    override suspend fun delete(expectedVersion: Long): PersonalMemorySnapshot = snapshot

    override fun currentPromptChoices(): PersonalMemoryChoices = PersonalMemoryChoices.SafeDefault

    override fun invalidate() = Unit
}