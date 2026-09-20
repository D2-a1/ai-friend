package com.aifriend.retrieval.application;

import java.util.List;

import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.RetrievalEvidence;

/**
 * 两路排名的确定性融合边界，不把分数视作概率。
 * @author codex
 * @since 1.0.0
 */
public interface RankFusionPort {
    /**
     * 每路最多20，单路重复来源不能重复贡献。
     * @param keywordRanking 词法排名
     * @param vectorRanking 余弦排名
     * @param limit 最多4份证据
     * @return 稳定排序的来源证据
     */
    List<RetrievalEvidence> fuse(List<KnowledgeChunk> keywordRanking, List<KnowledgeChunk> vectorRanking, int limit);
}
