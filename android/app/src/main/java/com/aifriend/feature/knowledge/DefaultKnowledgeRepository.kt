package com.aifriend.feature.knowledge

import com.aifriend.contract.api.AssistantKnowledgeApi
import com.aifriend.contract.model.*
import com.aifriend.feature.auth.AuthSessionRepository
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import retrofit2.Response

/** 无磁盘/答案缓存。每个页面会话串行请求；401仅刷新一次，未知提交只查询原key，不重新生成。 */
class DefaultKnowledgeRepository private constructor(
    private val apiFor: (KnowledgeLogin) -> AssistantKnowledgeApi,
    private val auth: AuthSessionRepository,
    private val json: Json,
) : KnowledgeRepository {
    @Inject constructor(factory: KnowledgeApiFactory, auth: AuthSessionRepository, json: Json) : this(factory::assistant, auth, json)
    internal constructor(api: AssistantKnowledgeApi, auth: AuthSessionRepository, json: Json) : this({ api }, auth, json)
    override fun isCurrent(session: KnowledgeSession): Boolean =
        auth.loginEpoch == session.login.epoch && auth.session.value === session.login.expected && session.login.expected.userStatus == "ACTIVE"

    override suspend fun create(purpose: AssistantPurpose, key: String): KnowledgeSession = translate {
        KnowledgeContractPolicy.key(key)
        val epoch = auth.loginEpoch
        val identity = auth.session.value?.takeIf { it.userStatus == "ACTIVE" }
            ?: throw KnowledgeException(KnowledgeFailure.AUTH_REQUIRED)
        val login = KnowledgeLogin(identity, epoch)
        val request = CreateAssistantSessionRequest(purpose, key)
        login.mutex.withLock {
            val response = authenticated(login) { apiFor(login).createAssistantSession(request) }
            val body = response.body()?.takeIf { response.isSuccessful && it.code == "OK" }?.data
                ?: throw failure(response)
            KnowledgeContractPolicy.session(body, purpose)
            requireCurrent(login)
            KnowledgeSession(login, body)
        }
    }

    override suspend fun ask(session: KnowledgeSession, question: KnowledgeQuestion, key: String): KnowledgeExchange = translate {
        val request = KnowledgeContractPolicy.request(session, question, key)
        session.login.mutex.withLock {
            val refresh = RefreshBudget()
            val response = try {
                authenticated(session.login, refresh) { apiFor(session.login).askAssistantQuestion(session.metadata.id, request) }
            } catch (_: IOException) {
                // 请求可能已经提交。只GET原key一次；不再POST，不换key，不把404当作可以重发。
                requireCurrent(session.login)
                authenticated(session.login, refresh) { apiFor(session.login).getAssistantQuestion(session.metadata.id, key) }
            }
            exchange(session, key, response)
        }
    }

    override suspend fun read(session: KnowledgeSession, key: String): KnowledgeExchange = translate {
        KnowledgeContractPolicy.key(key)
        session.login.mutex.withLock {
            exchange(session, key, authenticated(session.login) { apiFor(session.login).getAssistantQuestion(session.metadata.id, key) })
        }
    }

    override suspend fun close(session: KnowledgeSession): Unit = translate {
        session.login.mutex.withLock {
            val response = authenticated(session.login) { apiFor(session.login).closeAssistantSession(session.metadata.id, session.metadata.version) }
            val body = response.body()?.takeIf { response.isSuccessful && it.code == "OK" }?.data
                ?: throw failure(response)
            KnowledgeContractPolicy.session(body, session.metadata.purpose, session.metadata, closing = true)
            requireCurrent(session.login)
        }
    }

    private suspend fun exchange(session: KnowledgeSession, key: String, response: Response<AssistantQuestionResultResponse>): KnowledgeExchange {
        val body = response.body()?.takeIf { response.isSuccessful && it.code == "OK" }?.data
            ?: throw failure(response)
        KnowledgeContractPolicy.result(body, session, key)
        requireCurrent(session.login)
        // 结果只返回当前版本，没有新期限。不要自行伪造/延长expiresAt；服务端继续权威检查空闲期限。
        return KnowledgeExchange(KnowledgeSession(session.login, session.metadata.copy(version = body.version)), body)
    }

    private suspend fun requireCurrent(login: KnowledgeLogin) {
        currentCoroutineContext().ensureActive()
        if (auth.loginEpoch != login.epoch || auth.session.value !== login.expected || login.expected.userStatus != "ACTIVE")
            throw KnowledgeException(KnowledgeFailure.AUTH_CHANGED)
    }

    private class RefreshBudget(var available: Boolean = true)
    private suspend fun <T> authenticated(login: KnowledgeLogin, refresh: RefreshBudget = RefreshBudget(), call: suspend () -> Response<T>): Response<T> {
        requireCurrent(login)
        var response = call()
        validateResponseIdentity(login, response)
        if (response.code() == 401 && refresh.available) {
            refresh.available = false
            response.errorBody()?.close()
            val prior = login.expected
            val refreshed = try { auth.refresh() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { throw KnowledgeException(KnowledgeFailure.NETWORK_UNCERTAIN) }
                catch (_: Exception) { throw KnowledgeException(KnowledgeFailure.AUTH_REQUIRED) }
            currentCoroutineContext().ensureActive()
            val observed = auth.session.value
            if (auth.loginEpoch != login.epoch || refreshed.userId != prior.userId || refreshed.userStatus != "ACTIVE" || observed != refreshed)
                throw KnowledgeException(KnowledgeFailure.AUTH_CHANGED)
            // StateFlow会合并equals相同的值；刷新token时UI元数据不一定发生变化。
            login.expected = observed ?: throw KnowledgeException(KnowledgeFailure.AUTH_CHANGED)
            response = call() // 同一请求对象、同一幂等键/版本，仅此一次刷新重放。
            validateResponseIdentity(login, response)
        }
        return response
    }

    private suspend fun validateResponseIdentity(login: KnowledgeLogin, response: Response<*>) {
        try { requireCurrent(login) } catch (failure: Exception) {
            try { response.errorBody()?.close() } catch (_: IOException) { /* 不覆盖身份变化/取消 */ }
            throw failure
        }
    }

    private fun failure(response: Response<*>): KnowledgeException {
        val reason = response.errorBody()?.use { body ->
            try {
                val buffer = okio.Buffer()
                while (buffer.size < 8193 && body.source().read(buffer, 8193 - buffer.size) != -1L) { /* 有界读取 */ }
                val bytes = buffer.readByteArray()
                try {
                    if (bytes.size > 8192) null else {
                        val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                        val field = (root?.get("data") as? JsonObject)?.get("reasonCode") as? JsonPrimitive
                        field?.takeIf { it.isString }?.content?.let { name -> AssistantReasonCode.entries.firstOrNull { it.value == name } }
                    }
                } finally { bytes.fill(0) }
            } catch (_: Exception) { null }
        }
        val kind = if (response.code() == 503 && reason in setOf(AssistantReasonCode.COMMIT_UNCERTAIN, AssistantReasonCode.NETWORK_UNCERTAIN)) {
            KnowledgeFailure.NETWORK_UNCERTAIN
        } else when (response.code()) {
            401 -> KnowledgeFailure.AUTH_REQUIRED
            403 -> KnowledgeFailure.ACCESS_DENIED
            404 -> KnowledgeFailure.NOT_FOUND
            409 -> KnowledgeFailure.CONFLICT
            413, 422 -> KnowledgeFailure.INPUT_INVALID
            429 -> KnowledgeFailure.RATE_LIMITED
            in 200..299 -> KnowledgeFailure.PROTOCOL_INVALID
            else -> KnowledgeFailure.UNAVAILABLE
        }
        return KnowledgeException(kind, reason)
    }

    private suspend fun <T> translate(block: suspend () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (known: KnowledgeException) { throw known }
        catch (_: SerializationException) { throw KnowledgeException(KnowledgeFailure.PROTOCOL_INVALID) }
        catch (_: java.time.DateTimeException) { throw KnowledgeException(KnowledgeFailure.PROTOCOL_INVALID) }
        catch (_: IOException) { throw KnowledgeException(KnowledgeFailure.NETWORK_UNCERTAIN) }
}
