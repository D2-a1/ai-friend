package com.aifriend.feature.knowledge

import com.aifriend.contract.model.*
import com.aifriend.feature.auth.AuthSession
import kotlinx.coroutines.sync.Mutex

/** 独立只读问答；不提供任务、确认、拨号或发送端口。幂等键由页面为每次用户操作生成并保留。 */
interface KnowledgeRepository {
    suspend fun create(purpose: AssistantPurpose, key: String): KnowledgeSession
    suspend fun ask(session: KnowledgeSession, question: KnowledgeQuestion, key: String): KnowledgeExchange
    suspend fun read(session: KnowledgeSession, key: String): KnowledgeExchange
    suspend fun close(session: KnowledgeSession)
    fun isCurrent(session: KnowledgeSession): Boolean
}

sealed interface KnowledgeQuestion {
    class Text(val value: String, val locale: String, val appVersion: Long) : KnowledgeQuestion {
        override fun toString() = "KnowledgeQuestion.Text[redacted]"
    }
    class Graph(val query: KnowledgeGraphQuery, val locale: String, val appVersion: Long) : KnowledgeQuestion {
        override fun toString() = "KnowledgeQuestion.Graph[redacted]"
    }
}

/** 仅内存保存，禁止写入SavedStateHandle、日志或恢复为执行资格。 */
class KnowledgeSession internal constructor(
    internal val login: KnowledgeLogin,
    val metadata: AssistantSession,
) {
    override fun toString() = "KnowledgeSession[redacted]"
}

internal class KnowledgeLogin(@Volatile var expected: AuthSession, val epoch: Long) {
    val mutex = Mutex()
}

class KnowledgeExchange(val session: KnowledgeSession, val result: AssistantQuestionResult) {
    override fun toString() = "KnowledgeExchange[redacted]"
}

enum class KnowledgeFailure(val message: String) {
    AUTH_CHANGED("登录状态已变化，请重新进入问答"),
    AUTH_REQUIRED("登录已失效，请重新登录"),
    ACCESS_DENIED("当前没有这项问答权限，请检查独立用途授权"),
    NOT_FOUND("会话或请求不存在，请重新进入问答"),
    CONFLICT("会话或资料已变化，请重新进入问答"),
    INPUT_INVALID("问题格式不正确或内容过长，请修改后再说"),
    RATE_LIMITED("请求过于频繁或额度不足，请稍后再试"),
    NETWORK_UNCERTAIN("网络中断，结果尚不确定，请查询原请求状态"),
    PROTOCOL_INVALID("问答返回内容无法验证，未显示该结果"),
    UNAVAILABLE("问答服务暂时不可用，请稍后再试"),
}

class KnowledgeException(val failure: KnowledgeFailure, val reason: AssistantReasonCode? = null) :
    RuntimeException(failure.message)
