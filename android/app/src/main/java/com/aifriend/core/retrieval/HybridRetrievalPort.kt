package com.aifriend.core.retrieval

/**
 * 本地关键词、向量、图谱和记忆混合检索端口。
 *
 * 当前只保留边界，不提供外部知识或云端实现。
 */
interface HybridRetrievalPort {
    /** 检索去标识化候选标识。 */
    suspend fun retrieve(query: String, limit: Int): List<String>
}
