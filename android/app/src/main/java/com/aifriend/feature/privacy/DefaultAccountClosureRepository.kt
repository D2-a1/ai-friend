package com.aifriend.feature.privacy

import com.aifriend.contract.api.PrivacyApi
import com.aifriend.contract.model.ConfirmedAccountClosureRequest
import com.aifriend.core.network.ApiErrorCodeReader
import com.aifriend.feature.auth.AccountClosureAcceptedException
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 使用同一幂等键和正文完成一次 401 刷新；不自动重试其他失败。 */
@Singleton
class DefaultAccountClosureRepository @Inject constructor(
    private val api: PrivacyApi,
    private val auth: AuthSessionRepository,
    private val errorCodeReader: ApiErrorCodeReader,
) : AccountClosureRepository {
    override suspend fun close(): AccountClosureAcceptance {
        val idempotencyKey = UUID.randomUUID().toString()
        val request = ConfirmedAccountClosureRequest(confirmed = true)
        var response = api.closeMyAccount(idempotencyKey, request)
        if (response.code() == 401) {
            try {
                auth.refresh()
            } catch (_: AccountClosureAcceptedException) {
                return AccountClosureAcceptance.Recovered
            }
            response = api.closeMyAccount(idempotencyKey, request)
        }
        if (response.isSuccessful && response.body()?.data != null) {
            return AccountClosureAcceptance.Accepted
        }
        if (errorCodeReader.read(response) == ACCOUNT_CLOSURE_ACCEPTED) {
            return AccountClosureAcceptance.Recovered
        }
        throw AuthApiException(response.code(), "注销申请没有可靠受理，请稍后重新确认")
    }

    private companion object {
        const val ACCOUNT_CLOSURE_ACCEPTED = "ACCOUNT_CLOSURE_ACCEPTED"
    }
}
