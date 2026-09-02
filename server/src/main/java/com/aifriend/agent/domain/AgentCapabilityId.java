package com.aifriend.agent.domain;

/**
 * Agent 能力标识。
 *
 * <p>基础通信能力按现有开发计划实现，未来能力仅用于稳定模块边界，默认不对外暴露。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum AgentCapabilityId {
    /** 基础通信能力。 */
    COMMUNICATION,
    /** 取消当前任务能力。 */
    CANCELLATION,
    /** 纠正联系人、动作或内容能力。 */
    CORRECTION,
    /** 动作型确认能力。 */
    CONFIRMATION,
    /** 语义候选匹配扩展位，当前禁用。 */
    SEMANTIC_MATCHING,
    /** 生活问答扩展位，当前禁用。 */
    QUESTION_ANSWERING,
    /** 提醒扩展位，当前禁用。 */
    REMINDER,
    /** 外部知识问答扩展位，当前禁用。 */
    EXTERNAL_KNOWLEDGE,
    /** 个人记忆扩展位，当前禁用。 */
    PERSONAL_MEMORY,
    /** 知识图谱扩展位，当前禁用。 */
    KNOWLEDGE_GRAPH,
    /** 向量检索扩展位，当前禁用。 */
    VECTOR_RETRIEVAL,
    /** 云端模型扩展位，当前禁用。 */
    CLOUD_MODEL
}
