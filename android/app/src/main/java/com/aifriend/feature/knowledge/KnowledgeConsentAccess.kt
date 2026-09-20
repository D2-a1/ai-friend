package com.aifriend.feature.knowledge

import com.aifriend.contract.api.PrivacyApi
import com.aifriend.contract.model.*
import com.aifriend.feature.auth.AuthSessionRepository
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import retrofit2.Response

/** 两个独立用途固定政策版本；不是自动授权。真实调用使用绑定账号的PrivacyApi。 */
enum class KnowledgeConsentPurpose(val type: ConsentType, val policy: String, val title: String, val explanation: String) {
    MODEL(ConsentType.KNOWLEDGE_MODEL, "knowledge-model-v1", "知识问答外部模型处理",
        "同意后，在服务端启用模型时，问题文字、必要的公开知识片段和有限公开问答上下文可发送给配置的外部模型。不要输入隐私或账号信息。不同意时不会外发，模型模式可能不可用。"),
    GRAPH(ConsentType.CONTACT_GRAPH, "contact-graph-v1", "亲友关系查询",
        "同意后，可在服务端查询您已绑定亲友的关系与称呼。关系图谱和私人查询不会发给外部模型，不用于授权发消息或拨号。"),
}

class KnowledgeConsentAccess private constructor(
    private val apiFor: (KnowledgeLogin) -> PrivacyApi,
    private val auth: AuthSessionRepository,
) {
    @Inject constructor(factory: KnowledgeApiFactory, auth: AuthSessionRepository) : this(factory::privacy, auth)
    internal constructor(api: PrivacyApi, auth: AuthSessionRepository) : this({ api }, auth)

    internal suspend fun list(login: KnowledgeLogin): Set<KnowledgeConsentPurpose> {
        val response = authenticated(login) { apiFor(login).listMyConsents() }
        val values = response.body()?.takeIf { response.isSuccessful && it.code == ConsentListResponse.Code.OK }?.data ?: throw failure(response)
        if (values.map { it.type }.distinct().size != values.size) throw KnowledgeException(KnowledgeFailure.PROTOCOL_INVALID)
        return KnowledgeConsentPurpose.entries.filter { purpose -> values.any {
            it.type == purpose.type && it.policyVersion == purpose.policy && it.decision == ConsentDecision.GRANTED
        } }.toSet()
    }

    internal suspend fun change(login: KnowledgeLogin, purpose: KnowledgeConsentPurpose, decision: ConsentDecision) {
        val key = UUID.randomUUID().toString()
        val request = UpdateConsentRequest(decision, purpose.policy, OffsetDateTime.now(ZoneOffset.UTC))
        val response = authenticated(login) { apiFor(login).updateMyConsent(purpose.type, key, request) }
        val result = response.body()?.takeIf { response.isSuccessful && it.code == ConsentResponse.Code.OK }?.data ?: throw failure(response)
        if (result.type != purpose.type || result.policyVersion != purpose.policy || result.decision != decision)
            throw KnowledgeException(KnowledgeFailure.PROTOCOL_INVALID)
    }

    private suspend fun requireCurrent(login: KnowledgeLogin) {
        currentCoroutineContext().ensureActive()
        if (auth.loginEpoch != login.epoch || auth.session.value?.userId != login.expected.userId || auth.session.value?.userStatus != "ACTIVE")
            throw KnowledgeException(KnowledgeFailure.AUTH_CHANGED)
    }
    private suspend fun <T> authenticated(login: KnowledgeLogin, block: suspend () -> Response<T>): Response<T> {
        try {
            requireCurrent(login)
            var response = block()
            checkResponse(login, response)
            if (response.code() == 401) {
                response.errorBody()?.close()
                auth.refresh(); requireCurrent(login)
                response = block(); checkResponse(login, response)
            }
            return response
        } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: KnowledgeException) { throw failure }
            catch (_: IOException) { requireCurrent(login); throw KnowledgeException(KnowledgeFailure.NETWORK_UNCERTAIN) }
            catch (_: Exception) { throw KnowledgeException(KnowledgeFailure.UNAVAILABLE) }
    }
    private suspend fun checkResponse(login: KnowledgeLogin, response: Response<*>) {
        try { requireCurrent(login) } catch (failure: Exception) { response.errorBody()?.close(); throw failure }
    }
    private fun failure(response: Response<*>): KnowledgeException {
        response.errorBody()?.close()
        return KnowledgeException(when (response.code()) {
            401 -> KnowledgeFailure.AUTH_REQUIRED
            403 -> KnowledgeFailure.ACCESS_DENIED
            409 -> KnowledgeFailure.CONFLICT
            429 -> KnowledgeFailure.RATE_LIMITED
            in 200..299 -> KnowledgeFailure.PROTOCOL_INVALID
            else -> KnowledgeFailure.UNAVAILABLE
        })
    }
}
