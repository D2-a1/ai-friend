package com.aifriend.feature.knowledge

import com.aifriend.contract.model.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class KnowledgeGraphSpeechParserTest {
    private val candidates = (1..20).map { KnowledgeGraphCandidate(UUID(0, it.toLong()), 2, listOf("合成亲友$it")) }
    @Test fun finiteListQuestionsNeverContainPrivateText() {
        listOf("列出亲友", "列出已绑定亲友。", "有哪些亲友", "我绑定了哪些亲友").forEach {
            val query = KnowledgeGraphSpeechParser.parse(it, emptyList())!!
            assertEquals(KnowledgeGraphQueryType.LIST_CONTACTS, query.queryType); assertNull(query.aliasText); assertNull(query.contactId)
        }
    }
    @Test fun aliasLookupPreservesExactBoundedAlias() {
        val query = KnowledgeGraphSpeechParser.parse("查找称呼 合成老三。", emptyList())!!
        assertEquals("合成老三", query.aliasText); assertNull(query.contactId)
        assertNotNull(KnowledgeGraphSpeechParser.parse("查找称呼" + "😀".repeat(100), emptyList()))
        assertNull(KnowledgeGraphSpeechParser.parse("查找称呼" + "😀".repeat(101), emptyList()))
    }
    @Test fun ordinalsOnlyUseCurrentCandidateIds() {
        mapOf("第一位" to 1, "第十个" to 10, "第十一位" to 11, "第十九位" to 19, "第二十位" to 20, "第2个" to 2).forEach { (spoken, index) ->
            val query = KnowledgeGraphSpeechParser.parse(spoken, candidates)!!
            assertEquals(KnowledgeGraphQueryType.LIST_ALIASES, query.queryType)
            assertEquals(candidates[index - 1].contactId, query.contactId); assertNull(query.aliasText)
        }
    }
    @Test fun invalidOrStaleCandidateSelectionNeverGuesses() {
        listOf("第零位", "第0位", "第21位", "第一十一位", "第二十一个", "第99位", "第一位然后打电话").forEach { assertNull(KnowledgeGraphSpeechParser.parse(it, candidates)) }
        assertNull(KnowledgeGraphSpeechParser.parse("第一位", emptyList()))
        assertNull(KnowledgeGraphSpeechParser.parse("第二位", listOf(candidates[0], candidates[0])))
    }
    @Test fun contactActionsAndUnsupportedRelationshipsStayLocalUnsupported() {
        listOf("打电话给合成老三", "合成老三的父亲是谁", "查找称呼", "查找称呼老三然后打电话", "查找称呼老\n三", "列出亲友并且发消息").forEach { assertNull(KnowledgeGraphSpeechParser.parse(it, candidates)) }
    }
}
