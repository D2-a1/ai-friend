package com.aifriend.assistant.domain;

import java.util.List;
import java.util.Objects;

import com.aifriend.knowledge.domain.ContactDisplay;
import com.aifriend.retrieval.domain.KnowledgeText;
import com.aifriend.retrieval.domain.RetrievalEvidence;

/**
 * 独立问答结果，不包含联系动作、确认资格或模型工具。
 * @param purpose 严格隔离的用途
 * @param status 业务结果
 * @param mode 实际回答方式
 * @param reason 固定原因
 * @param text 当前响应文本
 * @param citations 公开知识证据
 * @param candidates 私人关系候选
 * @author codex
 * @since 1.0.0
 */
public record AssistantAnswer(Purpose purpose, Status status, Mode mode, AssistantReason reason,
        String text, List<RetrievalEvidence> citations, List<ContactDisplay> candidates) {
    /** 会话用途不可互换。 */
    public enum Purpose {
        /** 公开操作知识。 */ PUBLIC_KNOWLEDGE,
        /** 私人亲友关系。 */ CONTACT_GRAPH
    }
    /** 与OpenAPI保持一致的业务结果。 */
    public enum Status {
        /** 已回答。 */ ANSWERED,
        /** 仅原文证据。 */ EVIDENCE_ONLY,
        /** 需用户说明。 */ NEEDS_CLARIFICATION,
        /** 无充分证据。 */ NO_EVIDENCE,
        /** 服务不可用。 */ UNAVAILABLE,
        /** 处理中。 */ PROCESSING
    }
    /** 实际处理方式，不以摘录冒充生成。 */
    public enum Mode {
        /** 模型生成。 */ GENERATED,
        /** 原文摘录。 */ EXTRACTIVE,
        /** 本地关系模板。 */ TEMPLATE,
        /** 未生成内容。 */ NONE
    }

    /** 校验用途隔离、输出限额及结果/模式不变量。 */
    public AssistantAnswer {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(reason, "reason");
        text = KnowledgeText.require(text, 3000, 12000, true);
        if (citations == null || candidates == null || citations.size() > 4 || candidates.size() > 20) {
            throw new IllegalArgumentException("INVALID_ANSWER_SIZE");
        }
        citations = List.copyOf(citations);
        candidates = List.copyOf(candidates);
        if ((purpose == Purpose.PUBLIC_KNOWLEDGE && (!candidates.isEmpty() || mode == Mode.TEMPLATE))
                || (purpose == Purpose.CONTACT_GRAPH
                    && (!citations.isEmpty() || mode == Mode.GENERATED || mode == Mode.EXTRACTIVE))
                || (mode == Mode.EXTRACTIVE && status != Status.EVIDENCE_ONLY)
                || (status == Status.EVIDENCE_ONLY && (mode != Mode.EXTRACTIVE || citations.isEmpty()))
                || (status == Status.ANSWERED && (text.isBlank()
                    || (purpose == Purpose.PUBLIC_KNOWLEDGE && (mode != Mode.GENERATED || citations.isEmpty()))
                    || (purpose == Purpose.CONTACT_GRAPH && mode != Mode.TEMPLATE)))
                || (mode == Mode.GENERATED && status != Status.ANSWERED)
                || ((status == Status.PROCESSING || status == Status.UNAVAILABLE || status == Status.NO_EVIDENCE)
                    && (mode != Mode.NONE || !citations.isEmpty() || !candidates.isEmpty()))) {
            throw new IllegalArgumentException("INVALID_ANSWER_MODE");
        }
    }

    /** 响应内容不能写入默认日志。 */
    @Override public String toString() {
        return "AssistantAnswer[purpose=" + purpose + ", status=" + status + ", mode=" + mode + "]";
    }
}
