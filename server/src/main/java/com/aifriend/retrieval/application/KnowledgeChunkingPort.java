package com.aifriend.retrieval.application;

import java.util.List;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeDocument;

/**
 * 版本化公开文档切分，应用编排不依赖具体算法适配器。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeChunkingPort {
    /**
     * 读取可用于索引一致性校验的切分算法版本。
     * @return 固定分块算法版本
     */
    String version();
    /**
     * 保留原文码点范围的完整有界切分。
     * @param document 公开文档版本
     * @return 完整片段列表
     */
    List<KnowledgeChunk> chunk(KnowledgeDocument document);
}
