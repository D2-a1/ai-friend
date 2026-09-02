package com.aifriend.feature.consent

import com.aifriend.contract.api.PrivacyApi
import com.aifriend.contract.model.Consent
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.UpdateConsentRequest
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/**
 * 使用 OpenAPI PrivacyApi 的授权仓库；401 时只轮换一次令牌并重试一次。
 *
 * @author codex
 * @since 2026-08-04
 */
@Singleton
class DefaultConsentRepository @Inject constructor(
    private val privacyApi: PrivacyApi,
    private val authSessionRepository: AuthSessionRepository,
) : ConsentRepository {

    override suspend fun listCurrent(): List<Consent> = executeAuthenticated(
        request = { privacyApi.listMyConsents() },
        body = { it.data },
    )

    override suspend fun update(
        type: ConsentType,
        decision: ConsentDecision,
        policyVersion: String,
    ): Consent {
        val idempotencyKey = UUID.randomUUID().toString()
        val request = UpdateConsentRequest(
            decision = decision,
            policyVersion = policyVersion,
            confirmedAt = OffsetDateTime.now(ZoneOffset.UTC),
        )
        return executeAuthenticated(
            request = { privacyApi.updateMyConsent(type, idempotencyKey, request) },
            body = { it.data },
        )
    }

    private suspend fun <T, R> executeAuthenticated(
        request: suspend () -> Response<T>,
        body: (T) -> R,
    ): R {
        var response = request()
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = request()
        }
        val responseBody = response.body()?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), "授权状态同步失败，请稍后重试")
        return body(responseBody)
    }
}
