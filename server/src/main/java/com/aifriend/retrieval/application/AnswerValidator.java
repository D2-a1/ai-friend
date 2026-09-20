package com.aifriend.retrieval.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import com.aifriend.retrieval.domain.KnowledgeAnswerDraft;
import com.aifriend.retrieval.domain.RetrievalEvidence;
import com.aifriend.retrieval.domain.RetrievalResult;

/**
 * 将模型临时证据标签绑定到本轮真实来源；本类不以引用存在冒充语义支持率。
 * @author codex
 * @since 1.0.0
 */
public final class AnswerValidator {
    /** 创建无状态校验器。 */
    public AnswerValidator() { }

    /**
     * 验证每句的全部引用，不忽略某个伪造引用后保留其他句子。
     * 来源最终有效性由回答服务使用权威仓储重新核验，不能只依赖此对象。
     * @param draft 有界模型输出
     * @param retrieval 本轮检索证据
     * @return 整体有效的绑定草稿；不代表答案已被证据语义支持
     * @throws KnowledgeGatewayException 引用不在本轮时抛协议错误
     */
    public BoundDraft validate(KnowledgeAnswerDraft draft, RetrievalResult retrieval) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(retrieval, "retrieval");
        var indices = new LinkedHashSet<Integer>();
        for (var sentence : draft.sentences()) {
            for (String id : sentence.evidenceIds()) {
                int index = id.charAt(1) - '1';
                if (index >= retrieval.evidence().size()) {
                    throw new KnowledgeGatewayException(KnowledgeGatewayException.Kind.PROTOCOL);
                }
                indices.add(index);
            }
        }
        return new BoundDraft(draft, indices.stream().map(retrieval.evidence()::get).toList());
    }

    /**
     * 不携带任何模型生成的来源URL或身份。
     * @param draft 原样草稿
     * @param citations 按首次引用顺序解析的真实证据
     */
    public record BoundDraft(KnowledgeAnswerDraft draft, List<RetrievalEvidence> citations) {
        /** 复制不可变来源列表。 */
        public BoundDraft {
            Objects.requireNonNull(draft, "draft");
            citations = List.copyOf(citations);
        }
        /** 不回显内容。 */
        @Override public String toString() { return "BoundDraft[content=redacted]"; }
    }
}
