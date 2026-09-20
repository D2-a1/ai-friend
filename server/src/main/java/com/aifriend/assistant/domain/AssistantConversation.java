package com.aifriend.assistant.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.aifriend.retrieval.domain.KnowledgeText;

/**
 * 最多四对问题/摘要，不能静默淘汰旧意图。这里只存短期内容和持久结果引用。
 * 使用任何历史条目前，应用层必须按requestId/resultVersion读取并复验原结果证据与当前授权；
 * 此对象本身不是外发许可。图谱会话始终不得传给外部模型。
 * @param purpose 隔离用途
 * @param turns 有界历史
 * @author Codex
 * @since 1.0.0
 */
public record AssistantConversation(AssistantAnswer.Purpose purpose, List<Turn> turns) {
    /** 验证大小及结果引用唯一性，拒绝可变调用方列表。 */
    public AssistantConversation {
        Objects.requireNonNull(purpose);
        if (turns == null || turns.size() > 4) { throw new IllegalArgumentException("INVALID_ASSISTANT_CONTEXT"); }
        turns = List.copyOf(turns);
        var ids = new HashSet<UUID>();
        long previousVersion = 0;
        for (Turn turn : turns) {
            if (!ids.add(turn.requestId())) { throw new IllegalArgumentException("DUPLICATE_CONTEXT_REQUEST"); }
            if (turn.resultVersion() <= previousVersion) { throw new IllegalArgumentException("UNORDERED_CONTEXT_VERSION"); }
            previousVersion = turn.resultVersion();
        }
    }

    /**
     * 添加已验证结果的短摘要；第五对禁止追加。
     * @param turn 新历史
     * @return 新上下文，不修改当前对象
     */
    public AssistantConversation append(Turn turn) {
        if (turns.size() == 4) { throw new AssistantSessionException(AssistantReason.RESOURCE_LIMIT); }
        var next = new ArrayList<>(turns); next.add(Objects.requireNonNull(turn));
        return new AssistantConversation(purpose, next);
    }

    /**
     * 关联请求表中可复验证据的结果，不把无来源摘要变成新证据。
     * @param requestId 服务端持久问题ID
     * @param resultVersion 该结果提交时的会话版本
     * @param question 最多500码点的问题或本地结构化图谱问题描述
     * @param answerSummary 最多360码点的原答案短摘要，不另调用模型总结
     */
    public record Turn(UUID requestId, long resultVersion, String question, String answerSummary) {
        /** 四对最多3440码点/13760 UTF-8字节；只校验，不把内容写日志。 */
        public Turn {
            Objects.requireNonNull(requestId);
            if (resultVersion < 1) { throw new IllegalArgumentException("INVALID_CONTEXT_RESULT_VERSION"); }
            question = KnowledgeText.require(question, 500, 2000, false);
            answerSummary = KnowledgeText.require(answerSummary, 360, 1440, false);
        }
        @Override public String toString() { return "AssistantTurn[redacted]"; }
    }

    @Override public String toString() { return "AssistantConversation[purpose=" + purpose + ", turns=" + turns.size() + "]"; }
}
