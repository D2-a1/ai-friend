package com.aifriend.assistant.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import com.aifriend.assistant.domain.AssistantAnswer.Mode;

/**
 * 独立知识回答与本地检索配置，默认仅原文摘录，不能由请求改变模式。
 * @param mode EXTRACTIVE或GENERATED
 * @param generationBudget 单次生成预算
 * @param bm25K1 词频饱和参数
 * @param bm25B 文长归一化参数
 * @param rrfK 融合排名常数
 * @param maximumSnapshotBytes 保守内存预算
 * @author codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.knowledge.answer")
public record KnowledgeAnswerProperties(@DefaultValue("EXTRACTIVE") Mode mode,
        @DefaultValue("4s") Duration generationBudget, @DefaultValue("1.2") double bm25K1,
        @DefaultValue("0.75") double bm25B, @DefaultValue("60") int rrfK,
        @DefaultValue("134217728") long maximumSnapshotBytes) {
    /** 所有数值有界；mode只允许两种实际公开知识处理方式。 */
    public KnowledgeAnswerProperties {
        if ((mode != Mode.EXTRACTIVE && mode != Mode.GENERATED) || generationBudget == null
                || generationBudget.compareTo(Duration.ofMillis(1)) < 0
                || generationBudget.compareTo(Duration.ofSeconds(4)) > 0
                || !Double.isFinite(bm25K1) || bm25K1 <= 0 || bm25K1 > 3
                || !Double.isFinite(bm25B) || bm25B < 0 || bm25B > 1
                || rrfK < 1 || rrfK > 1000 || maximumSnapshotBytes < 1024 * 1024
                || maximumSnapshotBytes > 128L * 1024 * 1024) {
            throw new IllegalArgumentException("INVALID_KNOWLEDGE_ANSWER_CONFIGURATION");
        }
    }
}
