package com.aifriend.feature.settings

import com.aifriend.contract.api.VoiceTemplatesApi
import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.ConfirmedDeletionRequest
import com.aifriend.contract.model.RoutineCommandIntent
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.core.voice.LocalRoutineCommandTemplateStore
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 使用同一幂等键和请求正文完成一次 401 刷新；其他失败不自动重试。 */
@Singleton
class DefaultRoutineCommandDeletionRepository @Inject constructor(
    private val api: VoiceTemplatesApi,
    private val auth: AuthSessionRepository,
    private val localStore: LocalRoutineCommandTemplateStore,
) : RoutineCommandDeletionRepository {
    override suspend fun list(): RoutineCommandTemplateOverview {
        var response = api.listMyVoiceTemplates()
        if (response.code() == 401) {
            auth.refresh()
            response = api.listMyVoiceTemplates()
        }
        val summaries = response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), "日常指令模板清单读取失败")
        val routines = summaries.filter {
            it.category == VoiceTemplateSummary.Category.ROUTINE_COMMAND
        }
        if (routines.any { it.routineCommandIntent == null }) {
            throw AuthApiException(response.code(), "日常指令模板元数据不完整")
        }
        return RoutineCommandTemplateOverview(
            totalCount = routines.size,
            sendMessageCount = routines.count {
                it.routineCommandIntent == RoutineCommandIntent.SEND_MESSAGE
            },
            voiceCallCount = routines.count {
                it.routineCommandIntent == RoutineCommandIntent.VOICE_CALL
            },
            videoCallCount = routines.count {
                it.routineCommandIntent == RoutineCommandIntent.VIDEO_CALL
            },
            compatibleCount = routines.count {
                it.compatibility == AliasCompatibility.COMPATIBLE
            },
        )
    }

    override suspend fun clear(): Int {
        val idempotencyKey = UUID.randomUUID().toString()
        val request = ConfirmedDeletionRequest(
            confirmed = true,
            expectedVersion = null,
        )
        var response = api.deleteMyRoutineCommandTemplates(idempotencyKey, request)
        if (response.code() == 401) {
            auth.refresh()
            response = api.deleteMyRoutineCommandTemplates(idempotencyKey, request)
        }
        val deletion = response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), "日常指令模板没有可靠清除，请重新确认")
        localStore.clearRoutineCommands()
        return deletion.deletedCount
    }
}
