package com.aifriend.feature.knowledge

import com.aifriend.contract.model.*

/** 私人关系只读口述语法，不是通用语义模型；不认识的句子返回帮助，不猜测关系。 */
internal object KnowledgeGraphSpeechParser {
    const val HELP = "可以说列出亲友，或查找称呼后加上称呼。看到候选后，可以说第一位等序号。"

    fun parse(text: String, candidates: List<KnowledgeGraphCandidate>): KnowledgeGraphQuery? {
        val value = text.trim().trimEnd('。', '！', '？', '.', '!', '?')
        if (value in setOf("列出亲友", "列出已绑定亲友", "有哪些亲友", "我绑定了哪些亲友"))
            return KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_CONTACTS)
        val ordinal = Regex("第([一二三四五六七八九十]+|[0-9]{1,2})[个位]").matchEntire(value)?.groupValues?.get(1)
        if (ordinal != null) {
            val index = ordinal.toIntOrNull() ?: chineseOrdinal(ordinal) ?: return null
            if (index !in 1..20 || candidates.size > 20 || candidates.map { it.contactId }.distinct().size != candidates.size) return null
            val candidate = candidates.getOrNull(index - 1) ?: return null
            return KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_ALIASES, contactId = candidate.contactId)
        }
        if (!value.startsWith("查找称呼")) return null
        val alias = value.removePrefix("查找称呼").trim()
        if (alias.isBlank() || alias.codePointCount(0, alias.length) > 100 || alias.any { Character.isISOControl(it) }) return null
        // 不把完整联系动作或复合命令误当称呼，显示称呼本身永远不成为执行授权。
        if (listOf("打电话", "发消息", "发送", "拨打", "视频通话", "然后", "并且").any { it in alias }) return null
        return KnowledgeGraphQuery(KnowledgeGraphQueryType.FIND_CONTACT_BY_ALIAS, aliasText = alias)
    }

    private fun chineseOrdinal(value: String): Int? {
        val digits = "一二三四五六七八九"
        if (value.length == 1) return if (value == "十") 10 else digits.indexOf(value).takeIf { it >= 0 }?.plus(1)
        if (value == "二十") return 20
        if (value.length == 2 && value[0] == '十') return digits.indexOf(value[1]).takeIf { it >= 0 }?.plus(11)
        return null
    }
}
