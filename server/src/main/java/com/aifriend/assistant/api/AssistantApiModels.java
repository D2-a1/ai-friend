package com.aifriend.assistant.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.aifriend.assistant.domain.AssistantAnswer;
import com.aifriend.assistant.domain.AssistantReason;
import com.aifriend.assistant.domain.AssistantSession;
import com.aifriend.retrieval.domain.RetrievalResult;

/**
 * 独立HTTP响应白名单；与OpenAPI同步，不暴露存储实体或模型证明。
 * @author Codex
 * @since 1.0.0
 */
public final class AssistantApiModels {
    private AssistantApiModels() { }
    /**
     * 独立问答会话的公开状态快照。
     * @param id 会话标识
     * @param purpose 独立用途
     * @param state 当前状态
     * @param version 当前版本
     * @param expiresAt 当前期限
     */
    public record Session(UUID id, AssistantAnswer.Purpose purpose, AssistantSession.State state,
            long version, OffsetDateTime expiresAt) { }
    /**
     * 当前问题的受检回答；公开证据与私人候选使用各自的有限响应结构。
     * @param requestKey 原始幂等键
     * @param status 回答状态
     * @param answerMode 实际回答方式
     * @param reasonCode 固定原因
     * @param text 受检回答
     * @param citations 公开证据
     * @param candidates 私人候选
     * @param version 当前会话版本
     * @param retrievalMode 实际检索方式
     */
    public record AssistantQuestionResult(String requestKey, AssistantAnswer.Status status,
            AssistantAnswer.Mode answerMode, AssistantReason reasonCode, String text,
            List<KnowledgeCitation> citations, List<KnowledgeGraphCandidate> candidates,
            long version, RetrievalResult.Mode retrievalMode) {
        /** 响应正文不得出现在默认日志。 */
        @Override public String toString() { return "AssistantQuestionResult[redacted]"; }
    }
    /**
     * 回答引用的公开文档片段及其版本和原文区间。
     * @param evidenceId 当前回答的证据序号
     * @param documentId 文档标识
     * @param documentVersion 文档版本
     * @param chunkId 片段标识
     * @param title 片段标题，缺失时明确标示未命名
     * @param text 原文
     * @param sourceStart 原文起始码点
     * @param sourceEnd 原文结束码点
     */
    public record KnowledgeCitation(String evidenceId, UUID documentId, long documentVersion,
            UUID chunkId, String title, String text, long sourceStart, long sourceEnd) {
        /** 原文不写默认日志。 */
        @Override public String toString() { return "KnowledgeCitation[redacted]"; }
    }
    /**
     * 当前所有者范围内重新验证的亲友候选，不包含微信执行定位。
     * @param contactId 当前owner下联系人标识
     * @param contactVersion 当前联系人版本
     * @param aliases 已验证展示称呼
     */
    public record KnowledgeGraphCandidate(UUID contactId, long contactVersion, List<String> aliases) {
        /** 私人称呼不写默认日志。 */
        @Override public String toString() { return "KnowledgeGraphCandidate[redacted]"; }
    }
}
