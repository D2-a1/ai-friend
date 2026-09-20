package com.aifriend.retrieval.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 生成模型的受限数据，不包含URL、动作或执行权限；结构有效不证明语义正确。
 * @param status 仅ANSWER或NO_EVIDENCE
 * @param sentences 最多三句，各自引用本轮e1至e4
 * @author codex
 * @since 1.0.0
 */
public record KnowledgeAnswerDraft(Status status, List<Sentence> sentences) {
    /** 有限状态。 */
    public enum Status {
        /** 模型提出答案，仍需来源和质量检查。 */ ANSWER,
        /** 不足以回答。 */ NO_EVIDENCE
    }

    /** 防御性复制并拒绝空答案、虚假拒答及句数越限。 */
    public KnowledgeAnswerDraft {
        Objects.requireNonNull(status, "status");
        if (sentences == null || sentences.size() > 3
                || (status == Status.ANSWER && sentences.isEmpty())
                || (status == Status.NO_EVIDENCE && !sentences.isEmpty())) {
            throw new IllegalArgumentException("INVALID_ANSWER_DRAFT");
        }
        sentences = List.copyOf(sentences);
    }

    /**
     * 一句待验证答案；引用顺序保留、不得重复。
     * @param text 原样文字，最多120码点
     * @param evidenceIds 本轮证据标签，不接受真实身份/来源URL
     */
    public record Sentence(String text, List<String> evidenceIds) {
        /** 校验文字及有限证据标签。 */
        public Sentence {
            text = KnowledgeText.require(text, 120, 480, false);
            if (evidenceIds == null || evidenceIds.isEmpty() || evidenceIds.size() > 4) {
                throw new IllegalArgumentException("INVALID_ANSWER_CITATIONS");
            }
            evidenceIds = List.copyOf(evidenceIds);
            if (evidenceIds.stream().anyMatch(id -> !id.matches("e[1-4]"))
                    || new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
                throw new IllegalArgumentException("INVALID_ANSWER_CITATIONS");
            }
        }
        /** 不通过默认日志暴露内容。 */
        @Override public String toString() { return "Sentence[content=redacted]"; }
    }

    /** 不通过默认日志暴露问题或回答。 */
    @Override public String toString() { return "KnowledgeAnswerDraft[status=" + status + ", content=redacted]"; }
}
