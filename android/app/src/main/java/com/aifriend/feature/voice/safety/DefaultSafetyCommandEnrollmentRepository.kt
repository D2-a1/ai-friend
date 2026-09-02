package com.aifriend.feature.voice.safety

import com.aifriend.contract.api.VoiceTemplatesApi
import com.aifriend.contract.model.SafetyCommandEnrollmentItem
import com.aifriend.contract.model.SafetyCommandEnrollmentRequest
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用生成的语音模板 API 注册安全指令；401 时仅刷新并重试一次。
 *
 * <p>同一次调用的幂等键和正文保持不变。除 401 外不自动重试，避免网络结果
 * 不明时重复提交已经失效的录音确认。
 *
 * @author codex
 * @since 2026-08-13
 */
@Singleton
class DefaultSafetyCommandEnrollmentRepository @Inject constructor(
    private val voiceTemplatesApi: VoiceTemplatesApi,
    private val authSessionRepository: AuthSessionRepository,
) : SafetyCommandEnrollmentRepository {

    override suspend fun enroll(
        commands: List<SafetyCommandAudioObjects>,
        consentPolicyVersion: String,
    ): List<VoiceTemplateSummary> {
        require(
            commands.size == REQUIRED_TYPES.size &&
                commands.map { it.type }.toSet() == REQUIRED_TYPES,
        ) {
            "必须一次提交完整的四类安全指令"
        }
        val request = SafetyCommandEnrollmentRequest(
            commands = commands.map { command ->
                SafetyCommandEnrollmentItem(
                    type = command.type,
                    firstAudioObjectId = command.firstAudioObjectId,
                    secondAudioObjectId = command.secondAudioObjectId,
                )
            },
            consentPolicyVersion = consentPolicyVersion,
        )
        val idempotencyKey = UUID.randomUUID().toString()
        var response = voiceTemplatesApi.enrollSafetyCommands(idempotencyKey, request)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = voiceTemplatesApi.enrollSafetyCommands(idempotencyKey, request)
        }
        val result = response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), failureMessage(response.code()))
        val returnedTypes = result.templates
            .filter { it.category == VoiceTemplateSummary.Category.SAFETY_COMMAND }
            .mapNotNull { it.safetyCommandType }
            .toSet()
        if (!result.complete || returnedTypes != REQUIRED_TYPES) {
            throw AuthApiException(200, "安全指令没有完整保存，请重新录制")
        }
        return result.templates
    }

    private fun failureMessage(status: Int): String = when (status) {
        400 -> "录音无效，请重新录制"
        403 -> "请先明确允许保存个人语音模板"
        409 -> "录制状态已变化或当前声学包不可用，安全指令没有保存"
        422 -> "两遍发音不一致或四类指令不容易区分，请重新录制"
        429 -> "操作太频繁，请稍后重新录制"
        else -> "安全指令没有保存，请稍后重新录制"
    }

    private companion object {
        val REQUIRED_TYPES = setOf(
            com.aifriend.contract.model.SafetyCommandType.CONFIRM_SEND,
            com.aifriend.contract.model.SafetyCommandType.CONFIRM_CALL,
            com.aifriend.contract.model.SafetyCommandType.CANCEL,
            com.aifriend.contract.model.SafetyCommandType.REJECT_RETRY,
        )
    }
}
