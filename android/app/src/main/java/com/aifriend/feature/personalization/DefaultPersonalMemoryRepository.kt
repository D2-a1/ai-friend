package com.aifriend.feature.personalization

import com.aifriend.contract.api.PrivacyApi
import com.aifriend.contract.model.AmbiguousCallPreference
import com.aifriend.contract.model.DeletePersonalMemoryRequest
import com.aifriend.contract.model.DialogueStylePreference
import com.aifriend.contract.model.PersonalMemory
import com.aifriend.contract.model.SpeechRatePreference as ContractSpeechRatePreference
import com.aifriend.contract.model.UpdatePersonalMemoryRequest
import com.aifriend.core.settings.SpeechRatePreference
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/** 使用 OpenAPI PrivacyApi 管理当前 owner 的三项有限长期偏好。 */
@Singleton
class DefaultPersonalMemoryRepository @Inject constructor(
    private val api: PrivacyApi,
    private val auth: AuthSessionRepository,
) : PersonalMemoryRepository {
    @Volatile
    private var cachedSnapshot: PersonalMemorySnapshot? = null

    override suspend fun read(): PersonalMemorySnapshot {
        invalidate()
        val response = authenticated { api.getMyPersonalMemory() }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?.toDomain()
            ?.also { cachedSnapshot = it }
            ?: throw AuthApiException(
                response.code(),
                personalMemoryFailure(response.code(), "长期偏好读取失败"),
            )
    }

    override suspend fun update(
        choices: PersonalMemoryChoices,
        expectedVersion: Long,
    ): PersonalMemorySnapshot {
        require(expectedVersion >= 0L) { "长期偏好版本无效" }
        invalidate()
        val key = UUID.randomUUID().toString()
        val request = UpdatePersonalMemoryRequest(
            speechRate = choices.speechRate.toContract(),
            dialogueStyle = choices.dialogueStyle.toContract(),
            ambiguousCall = choices.ambiguousCall.toContract(),
            expectedVersion = expectedVersion,
        )
        val response = authenticated {
            api.updateMyPersonalMemory(key, request)
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?.toDomain()
            ?.also { cachedSnapshot = it }
            ?: throw AuthApiException(
                response.code(),
                personalMemoryFailure(response.code(), "长期偏好保存失败"),
            )
    }

    override suspend fun delete(expectedVersion: Long): PersonalMemorySnapshot {
        require(expectedVersion >= 1L) { "长期偏好版本无效" }
        invalidate()
        val key = UUID.randomUUID().toString()
        val request = DeletePersonalMemoryRequest(
            confirmed = true,
            expectedVersion = expectedVersion,
        )
        val response = authenticated {
            api.deleteMyPersonalMemory(key, request)
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?.toDomain()
            ?.also { cachedSnapshot = it }
            ?: throw AuthApiException(
                response.code(),
                personalMemoryFailure(response.code(), "长期偏好没有可靠删除"),
            )
    }

    override fun currentPromptChoices(): PersonalMemoryChoices =
        cachedSnapshot?.activeChoicesOrNull() ?: PersonalMemoryChoices.SafeDefault

    override fun invalidate() {
        cachedSnapshot = null
    }

    private suspend fun <T> authenticated(block: suspend () -> Response<T>): Response<T> {
        var response = block()
        if (response.code() == 401) {
            auth.refresh()
            response = block()
        }
        return response
    }
}

private fun PersonalMemory.toDomain(): PersonalMemorySnapshot = PersonalMemorySnapshot(
    featureEnabled = featureEnabled,
    consentGranted = consentGranted,
    policyVersion = policyVersion,
    choices = preferences?.let { value ->
        PersonalMemoryChoices(
            speechRate = value.speechRate.toDomain(),
            dialogueStyle = value.dialogueStyle.toDomain(),
            ambiguousCall = value.ambiguousCall.toDomain(),
        )
    },
    version = version,
)

private fun SpeechRatePreference.toContract(): ContractSpeechRatePreference = when (this) {
    SpeechRatePreference.SLOW -> ContractSpeechRatePreference.SLOW
    SpeechRatePreference.NORMAL -> ContractSpeechRatePreference.NORMAL
    SpeechRatePreference.FAST -> ContractSpeechRatePreference.FAST
}

private fun ContractSpeechRatePreference.toDomain(): SpeechRatePreference = when (this) {
    ContractSpeechRatePreference.SLOW -> SpeechRatePreference.SLOW
    ContractSpeechRatePreference.NORMAL -> SpeechRatePreference.NORMAL
    ContractSpeechRatePreference.FAST -> SpeechRatePreference.FAST
}

private fun DialogueStyleChoice.toContract(): DialogueStylePreference = when (this) {
    DialogueStyleChoice.BRIEF -> DialogueStylePreference.BRIEF
    DialogueStyleChoice.STANDARD -> DialogueStylePreference.STANDARD
}

private fun DialogueStylePreference.toDomain(): DialogueStyleChoice = when (this) {
    DialogueStylePreference.BRIEF -> DialogueStyleChoice.BRIEF
    DialogueStylePreference.STANDARD -> DialogueStyleChoice.STANDARD
}

private fun AmbiguousCallChoice.toContract(): AmbiguousCallPreference = when (this) {
    AmbiguousCallChoice.ASK_EVERY_TIME -> AmbiguousCallPreference.ASK_EVERY_TIME
    AmbiguousCallChoice.VOICE -> AmbiguousCallPreference.VOICE
    AmbiguousCallChoice.VIDEO -> AmbiguousCallPreference.VIDEO
}

private fun AmbiguousCallPreference.toDomain(): AmbiguousCallChoice = when (this) {
    AmbiguousCallPreference.ASK_EVERY_TIME -> AmbiguousCallChoice.ASK_EVERY_TIME
    AmbiguousCallPreference.VOICE -> AmbiguousCallChoice.VOICE
    AmbiguousCallPreference.VIDEO -> AmbiguousCallChoice.VIDEO
}

private fun personalMemoryFailure(status: Int, fallback: String): String = when (status) {
    401 -> "登录状态已经失效，请重新登录"
    403 -> "请先明确同意长期个人偏好授权"
    404 -> "当前没有可删除的长期偏好"
    409 -> "长期偏好已在其他页面变化，请刷新后重试"
    422 -> "服务端尚未开启长期个人偏好"
    else -> fallback
}