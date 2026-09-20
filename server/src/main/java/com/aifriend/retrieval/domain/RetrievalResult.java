package com.aifriend.retrieval.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 单世代检索结果，显式说明实际运行的通道。
 * @param indexVersion 当前世代
 * @param mode 实际检索方式
 * @param evidence 最多四份不可变证据
 * @author codex
 * @since 1.0.0
 */
public record RetrievalResult(IndexVersion indexVersion, Mode mode, List<RetrievalEvidence> evidence) {
    /** 检索通道枚举，不表示生成答案或语义置信度。 */
    public enum Mode {
        /** 关键词和同profile向量融合。 */ HYBRID,
        /** 仅关键词检索。 */ KEYWORD_ONLY,
        /** 未运行检索。 */ NONE
    }

    /** 拒绝重复片段、混用来源版本及不真实的通道声明。 */
    public RetrievalResult {
        Objects.requireNonNull(indexVersion, "indexVersion");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.size() > 4 || (mode == Mode.NONE && !evidence.isEmpty())
                || (mode == Mode.HYBRID && indexVersion.embeddingProfile().isEmpty())) {
            throw new IllegalArgumentException("INVALID_RETRIEVAL_RESULT");
        }
        evidence = List.copyOf(evidence);
        var ids = new HashSet<UUID>();
        var sourceVersions = new HashMap<UUID, Long>();
        int characters = 0;
        for (var item : evidence) {
            var chunk = item.chunk();
            Long previous = sourceVersions.putIfAbsent(chunk.documentId(), chunk.documentVersion());
            if (!ids.add(chunk.id()) || !chunk.chunkerVersion().equals(indexVersion.chunkerVersion())
                    || (previous != null && previous.longValue() != chunk.documentVersion())) {
                throw new IllegalArgumentException("MIXED_RETRIEVAL_EVIDENCE");
            }
            characters += chunk.sourceEnd() - chunk.sourceStart();
        }
        if (characters > 2400) {
            throw new IllegalArgumentException("INPUT_LIMIT");
        }
    }
}
