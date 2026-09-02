package com.aifriend.core.question

/**
 * 生活问答端口。
 *
 * 当前只占位，不调用云模型、外部搜索或第三方服务。
 */
interface QuestionAnsweringPort {
    /** 生成带来源的问答草稿。 */
    suspend fun prepareAnswer(question: String): String
}
