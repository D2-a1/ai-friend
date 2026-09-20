package com.aifriend.feature.knowledge

import com.aifriend.contract.model.*
import com.aifriend.feature.auth.AuthSessionRepository
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class KnowledgePhase { IDLE, OPENING, READY, SUBMITTING, PROCESSING, PRESENTING, ERROR, CANCELLED, CLOSED }

/** 只保留当前受检回答，不持久化正文、关系或历史缓存。 */
data class KnowledgeUiState(
    val phase: KnowledgePhase = KnowledgePhase.IDLE,
    val purpose: AssistantPurpose = AssistantPurpose.PUBLIC_KNOWLEDGE,
    val answer: AssistantQuestionResult? = null,
    val failure: KnowledgeFailure? = null,
    val canQueryOriginal: Boolean = false,
    val cleanupPending: Boolean = false,
) {
    override fun toString() = "KnowledgeUiState[phase=$phase,purpose=$purpose]"
}

/**
 * 独立页面状态机。所有入口由同一个UI调度器调用，scope由页面生命周期持有。
 * 退出适配层必须调用shutdown；清理最多3秒，失败明确保留cleanupPending，不宣称远端已经关闭。
 * 不调用任务/音频/微信端口；语音适配和实际Compose入口另行接入。
 */
class KnowledgeConversation(
    private val repository: KnowledgeRepository,
    private val auth: AuthSessionRepository,
    private val scope: CoroutineScope,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow(KnowledgeUiState())
    val state: StateFlow<KnowledgeUiState> = mutableState.asStateFlow()
    private var session: KnowledgeSession? = null
    private var operation: Job? = null
    private var generation = 0L
    private var requestKey: String? = null
    private var createKey: String? = null
    private var disposed = false
    private val identityObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        auth.session.collect { current ->
            val active = session
            if (active != null && (current?.userId != active.login.expected.userId || current.userStatus != "ACTIVE" ||
                    auth.loginEpoch != active.login.epoch)) stop(authChanged = true)
        }
    }

    fun start(purpose: AssistantPurpose) {
        if (disposed || (busy() && state.value.purpose == purpose)) return
        val old = session; val oldKey = cleanupKey()
        val priorCleanup = state.value.cleanupPending || state.value.phase == KnowledgePhase.OPENING
        val key = createKey?.takeIf { old == null && state.value.purpose == purpose &&
            state.value.failure == KnowledgeFailure.NETWORK_UNCERTAIN } ?: newKey()
        generation++; operation?.cancel(); session = null; requestKey = null; createKey = key
        val token = generation
        mutableState.value = KnowledgeUiState(KnowledgePhase.OPENING, purpose, cleanupPending = priorCleanup || old != null)
        operation = scope.launch {
            try {
                val cleaned = cleanup(old, oldKey)
                if (!alive(token)) return@launch
                mutableState.value = state.value.copy(cleanupPending = !cleaned || priorCleanup)
                val created = repository.create(purpose, key)
                if (!alive(token)) return@launch
                if (!repository.isCurrent(created)) throw KnowledgeException(KnowledgeFailure.AUTH_CHANGED)
                session = created
                mutableState.value = state.value.copy(phase = KnowledgePhase.READY)
            } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: KnowledgeException) { showFailure(token, failure.failure, false) }
                catch (_: Exception) { showFailure(token, KnowledgeFailure.UNAVAILABLE, false) }
        }
    }

    fun submit(question: KnowledgeQuestion) {
        if (disposed || busy() || state.value.canQueryOriginal) return
        val active = session ?: return
        if (!repository.isCurrent(active)) { stop(authChanged = true); return }
        val key = newKey(); requestKey = key
        generation++; val token = generation
        mutableState.value = state.value.copy(phase = KnowledgePhase.SUBMITTING, answer = null, failure = null, canQueryOriginal = false)
        operation = scope.launch {
            try { acceptAndPoll(token, repository.ask(active, question, key), key) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: KnowledgeException) { showFailure(token, failure.failure, failure.failure == KnowledgeFailure.NETWORK_UNCERTAIN) }
            catch (_: Exception) { showFailure(token, KnowledgeFailure.UNAVAILABLE, false) }
        }
    }

    /** 只能查询原key，不重发旧问题或偷偷创建新会话。 */
    fun queryOriginal() {
        if (disposed || busy() || !state.value.canQueryOriginal) return
        val active = session ?: return
        val key = requestKey ?: return
        if (!repository.isCurrent(active)) { stop(authChanged = true); return }
        generation++; val token = generation
        mutableState.value = state.value.copy(phase = KnowledgePhase.PROCESSING, answer = null, failure = null, canQueryOriginal = false)
        operation = scope.launch {
            try { acceptAndPoll(token, repository.read(active, key), key) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: KnowledgeException) { showFailure(token, failure.failure, failure.failure == KnowledgeFailure.NETWORK_UNCERTAIN) }
            catch (_: Exception) { showFailure(token, KnowledgeFailure.UNAVAILABLE, false) }
        }
    }

    fun cancel() = stop(authChanged = false)

    private fun stop(authChanged: Boolean) {
        if (disposed) return
        val old = session; val key = cleanupKey()
        val priorCleanup = state.value.cleanupPending || state.value.phase == KnowledgePhase.OPENING
        generation++; operation?.cancel(); session = null; requestKey = null; createKey = null
        val token = generation
        mutableState.value = state.value.copy(phase = KnowledgePhase.CANCELLED, answer = null,
            failure = if (authChanged) KnowledgeFailure.AUTH_CHANGED else null, canQueryOriginal = false, cleanupPending = priorCleanup || old != null)
        operation = scope.launch {
            val cleaned = cleanup(old, key)
            if (alive(token)) mutableState.value = state.value.copy(cleanupPending = !cleaned || priorCleanup)
        }
    }

    /** 先关闭本机结果接收门闩，再有限尝试服务端关闭；取消不允许迟到答案复活。 */
    suspend fun shutdown() {
        if (disposed) return
        val priorCleanup = state.value.cleanupPending || state.value.phase == KnowledgePhase.OPENING
        disposed = true; generation++; identityObserver.cancel(); operation?.cancel()
        val old = session; val key = cleanupKey()
        session = null; requestKey = null; createKey = null
        mutableState.value = state.value.copy(phase = KnowledgePhase.CLOSED, answer = null, canQueryOriginal = false, cleanupPending = priorCleanup || old != null)
        val cleaned = withContext(NonCancellable) { cleanup(old, key) }
        mutableState.value = state.value.copy(cleanupPending = !cleaned || priorCleanup)
    }

    private suspend fun acceptAndPoll(token: Long, first: KnowledgeExchange, key: String) {
        var exchange = first
        repeat(9) { attempt ->
            if (!alive(token)) return
            if (!repository.isCurrent(exchange.session)) throw KnowledgeException(KnowledgeFailure.AUTH_CHANGED)
            session = exchange.session
            if (exchange.result.status != AssistantResultStatus.PROCESSING) {
                mutableState.value = state.value.copy(phase = KnowledgePhase.PRESENTING, answer = exchange.result, failure = null, canQueryOriginal = false)
                return
            }
            mutableState.value = state.value.copy(phase = KnowledgePhase.PROCESSING, answer = null)
            if (attempt == 8) {
                showFailure(token, KnowledgeFailure.NETWORK_UNCERTAIN, true)
                return
            }
            delay(1000)
            if (!alive(token)) return
            exchange = repository.read(exchange.session, key)
        }
    }

    // 最终响应已提供受检会话版本，关闭不需要再次解密/复验正文。并发变更仍由关闭 CAS 拒绝。
    // 未知/在途响应保留原 key 查询，不能用猜测版本覆盖服务端状态。
    private fun cleanupKey(): String? = requestKey.takeUnless { state.value.phase == KnowledgePhase.PRESENTING }

    private suspend fun cleanup(old: KnowledgeSession?, key: String?): Boolean {
        if (old == null) return true
        if (!repository.isCurrent(old)) return false // 不使用新账号去关闭旧账号会话。
        return withTimeoutOrNull(3000) {
            var current = old
            try {
                // 取消发生在提交后时版本可能已前进，仅读取原key取得当前版本，绝不显示其正文。
                if (key != null) {
                    try { current = repository.read(old, key).session }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: KnowledgeException) { /* 用已知版本尝试，冲突绝不绕过 */ }
                }
                repository.close(current); true
            } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
        } ?: false
    }

    private suspend fun alive(token: Long): Boolean {
        currentCoroutineContext().ensureActive()
        return !disposed && generation == token
    }
    private fun busy() = state.value.phase in setOf(KnowledgePhase.OPENING, KnowledgePhase.SUBMITTING, KnowledgePhase.PROCESSING)
    private fun showFailure(token: Long, failure: KnowledgeFailure, query: Boolean) {
        if (disposed || generation != token) return
        mutableState.value = state.value.copy(phase = KnowledgePhase.ERROR, answer = null, failure = failure, canQueryOriginal = query)
    }
}
