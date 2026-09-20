package com.aifriend.retrieval.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 版本化分块配置；全部字段进入算法版本标识。
 * @param targetChars 目标码点数
 * @param overlapChars 重叠码点数
 * @param maxChars 片段硬上限
 * @author codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.knowledge.chunk")
public record KnowledgeChunkProperties(@DefaultValue("400") int targetChars,
        @DefaultValue("60") int overlapChars, @DefaultValue("600") int maxChars) {
    /** 与生产分块器使用同一校验。 */
    public KnowledgeChunkProperties { new KnowledgeChunker(targetChars, overlapChars, maxChars); }
}
