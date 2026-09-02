package com.aifriend.feature.privacy

import com.aifriend.contract.api.PrivacyApi
import com.aifriend.contract.model.ConfirmedTaskHistoryDeletionRequest
import com.aifriend.contract.model.TaskHistoryDeletion
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 使用生成 Privacy API 的默认任务历史清除仓库。 */
@Singleton
class DefaultTaskHistoryDeletionRepository @Inject constructor(
    private val api: PrivacyApi,
    private val auth: AuthSessionRepository,
) : TaskHistoryDeletionRepository {
    override suspend fun clear(): TaskHistoryDeletion {
        val key = UUID.randomUUID().toString()
        val body = ConfirmedTaskHistoryDeletionRequest(true)
        var response = api.clearMyTaskHistory(key, body)
        if (response.code() == 401) { auth.refresh(); response = api.clearMyTaskHistory(key, body) }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), "清除请求没有可靠受理，请稍后重试")
    }
    override suspend fun get(): TaskHistoryDeletion {
        var response = api.getMyTaskHistoryDeletion()
        if (response.code() == 401) { auth.refresh(); response = api.getMyTaskHistoryDeletion() }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), "暂时无法查询清除状态")
    }
}
