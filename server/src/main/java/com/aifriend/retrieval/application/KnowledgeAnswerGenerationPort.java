package com.aifriend.retrieval.application;

import java.time.Duration;

import com.aifriend.retrieval.domain.KnowledgeAnswerDraft;
import com.aifriend.retrieval.domain.RetrievalResult;

/**
 * 单次公开知识生成；调用方先验证独立同意和持久额度，模型没有数据库或通信工具。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeAnswerGenerationPort {
    /**
     * 已复验公开历史的生成入口；旧实现不能悄悄忽略非空上下文。
     * @param question 当前完整问题
     * @param evidence 本轮有效证据
     * @param history 已经会话层来源及独立同意复验的公开上下文
     * @param repair 本问题有限协议修复
     * @param budget 原问题剩余预算
     * @return 仍须来源/语义校验的草稿
     */
    default KnowledgeAnswerDraft generate(String question, RetrievalResult evidence,
            com.aifriend.assistant.domain.AssistantConversation history, boolean repair, Duration budget) {
        if (history==null || history.purpose()!=com.aifriend.assistant.domain.AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE
                || !history.turns().isEmpty()) throw new KnowledgeGatewayException(KnowledgeGatewayException.Kind.CONFIGURATION);
        return generate(question,evidence,repair,budget);
    }
    /**
     * 读取生成模型的外部配置版本。
     * @return 固定外置profile版本，用于额度与会话绑定，不包含凭据
     */
    String profileId();

    /**
     * 只进行一次尝试，不内部重试；修复只改变系统提示，不转发上一无效响应。
     * @param question 最小化公开知识问题，最多500码点
     * @param evidence 本轮完整受检证据，最多4份2400码点
     * @param repair 是否为应用层许可的唯一修复尝试
     * @param budget 服从剩余总期限的本次预算
     * @return 结构有效但仍待来源/语义检查的草稿
     * @throws KnowledgeGatewayException 固定网络、配置或协议故障
     */
    KnowledgeAnswerDraft generate(String question, RetrievalResult evidence, boolean repair, Duration budget);
}
