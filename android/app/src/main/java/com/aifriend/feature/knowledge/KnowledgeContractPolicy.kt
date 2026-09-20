package com.aifriend.feature.knowledge

import com.aifriend.contract.model.*

/** DTO反序列化成功不等于可展示。只验证协议与用途，不以客户端判断取代服务端权限复验。 */
internal object KnowledgeContractPolicy {
    fun key(value: String) = input(value.matches(Regex("[A-Za-z0-9._:-]{16,128}")))

    fun request(session: KnowledgeSession, question: KnowledgeQuestion, key: String): AskAssistantQuestionRequest {
        key(key)
        val result = when (question) {
            is KnowledgeQuestion.Text -> {
                input(session.metadata.purpose == AssistantPurpose.PUBLIC_KNOWLEDGE)
                input(validText(question.value, 500) && question.value.isNotBlank())
                AskAssistantQuestionRequest(session.metadata.version, key, question.locale, question.appVersion, text = question.value)
            }
            is KnowledgeQuestion.Graph -> {
                input(session.metadata.purpose == AssistantPurpose.CONTACT_GRAPH)
                val q = question.query
                input(when (q.queryType) {
                    KnowledgeGraphQueryType.LIST_CONTACTS -> q.contactId == null && q.aliasText == null
                    KnowledgeGraphQueryType.LIST_ALIASES -> q.contactId != null && q.aliasText == null
                    KnowledgeGraphQueryType.FIND_CONTACT_BY_ALIAS -> q.contactId == null && q.aliasText != null &&
                        validText(q.aliasText, 100) && q.aliasText.isNotBlank()
                })
                AskAssistantQuestionRequest(session.metadata.version, key, question.locale, question.appVersion, graphQuery = q)
            }
        }
        input(result.appVersionCode in 1..Int.MAX_VALUE.toLong())
        input(result.locale.length <= 35 && result.locale.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,4}")))
        input(session.metadata.state == AssistantSessionState.OPEN && session.metadata.version >= 0)
        return result
    }

    fun session(value: AssistantSession, purpose: AssistantPurpose, previous: AssistantSession? = null, closing: Boolean = false) {
        protocol(value.purpose == purpose && value.version >= 0)
        protocol(if (closing) value.state == AssistantSessionState.CLOSED || value.state == AssistantSessionState.EXPIRED
            else value.state == AssistantSessionState.OPEN)
        if (previous != null) protocol(value.id == previous.id && value.version >= previous.version)
    }

    fun result(value: AssistantQuestionResult, session: KnowledgeSession, key: String) {
        protocol(value.requestKey == key && value.version >= session.metadata.version)
        protocol(validText(value.text, 3000) && value.citations.size <= 4 && value.candidates.size <= 20)
        val public = session.metadata.purpose == AssistantPurpose.PUBLIC_KNOWLEDGE
        protocol(if (public) value.candidates.isEmpty() && value.answerMode != AssistantAnswerMode.TEMPLATE
            else value.citations.isEmpty() && value.retrievalMode == KnowledgeRetrievalMode.NONE &&
                value.answerMode != AssistantAnswerMode.GENERATED && value.answerMode != AssistantAnswerMode.EXTRACTIVE)
        protocol(value.citations.map { it.chunkId }.distinct().size == value.citations.size)
        protocol(value.citations.map { it.evidenceId }.distinct().size == value.citations.size)
        value.citations.forEach { c ->
            protocol(c.documentVersion >= 1 && c.sourceStart >= 0 && c.sourceEnd > c.sourceStart)
            protocol(validText(c.text, 600) && c.text.isNotBlank() && validText(c.title, 200) && c.title.isNotBlank())
            protocol(c.sourceEnd - c.sourceStart == c.text.codePointCount(0, c.text.length).toLong())
            protocol(c.evidenceId.matches(Regex("e[1-4]")))
        }
        protocol(value.candidates.map { it.contactId }.distinct().size == value.candidates.size)
        value.candidates.forEach { c ->
            protocol(c.contactVersion >= 0 && c.aliases.size <= 5 && c.aliases.all { validText(it, 100) && it.isNotBlank() })
        }
        when (value.status) {
            AssistantResultStatus.ANSWERED -> protocol(value.text.isNotBlank() && if (public)
                value.answerMode == AssistantAnswerMode.GENERATED && value.citations.isNotEmpty()
                else value.answerMode == AssistantAnswerMode.TEMPLATE)
            AssistantResultStatus.EVIDENCE_ONLY -> protocol(public && value.answerMode == AssistantAnswerMode.EXTRACTIVE && value.citations.isNotEmpty())
            AssistantResultStatus.PROCESSING, AssistantResultStatus.UNAVAILABLE, AssistantResultStatus.NO_EVIDENCE ->
                protocol(value.answerMode == AssistantAnswerMode.NONE && value.citations.isEmpty() && value.candidates.isEmpty())
            AssistantResultStatus.NEEDS_CLARIFICATION -> protocol(value.answerMode == AssistantAnswerMode.NONE || !public && value.answerMode == AssistantAnswerMode.TEMPLATE)
        }
        if (value.citations.isNotEmpty()) protocol(value.retrievalMode != KnowledgeRetrievalMode.NONE)
    }

    /** 拒绝孤立代理字符，按码点限长，不截断合法emoji。 */
    private fun validText(value: String, limit: Int): Boolean {
        if (value.length > limit * 2 || value.codePointCount(0, value.length) > limit) return false
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return false
                i += 2
            } else {
                if (c.isLowSurrogate() || Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') return false
                i++
            }
        }
        return true
    }
    private fun input(ok: Boolean) { if (!ok) throw KnowledgeException(KnowledgeFailure.INPUT_INVALID) }
    private fun protocol(ok: Boolean) { if (!ok) throw KnowledgeException(KnowledgeFailure.PROTOCOL_INVALID) }
}
