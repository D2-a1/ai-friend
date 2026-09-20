package com.aifriend.retrieval.infrastructure;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.aifriend.retrieval.application.RankFusionPort;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.RetrievalEvidence;

/**
 * RRF按去重后从1开始的排名求和，缺席路贡献0，UUID字符串打破并列。
 * @author codex
 * @since 1.0.0
 */
public final class RrfFusion implements RankFusionPort {
    private final int k;

    /**
     * 构造融合器。
     * @param k 排名平滑常数，初始60；有限正值
     */
    public RrfFusion(int k) {
        if (k < 1 || k > 1000) { throw new IllegalArgumentException("INVALID_RRF_CONFIGURATION"); }
        this.k = k;
    }

    /** {@inheritDoc} */
    @Override public List<RetrievalEvidence> fuse(List<KnowledgeChunk> keywordRanking,
            List<KnowledgeChunk> vectorRanking, int limit) {
        if (keywordRanking == null || vectorRanking == null || keywordRanking.size() > 20
                || vectorRanking.size() > 20 || limit < 1 || limit > 4) {
            throw new IllegalArgumentException("INVALID_RRF_INPUT");
        }
        var sources = new HashMap<UUID, KnowledgeChunk>();
        var scores = new HashMap<UUID, Double>();
        for (var ranking : List.of(keywordRanking, vectorRanking)) {
            var seen = new HashSet<UUID>();
            int rank = 0;
            for (var chunk : ranking) {
                if (chunk == null) { throw new IllegalArgumentException("INVALID_RRF_SOURCE"); }
                var previous = sources.putIfAbsent(chunk.id(), chunk);
                if (previous != null && !previous.equals(chunk)) { throw new IllegalArgumentException("MIXED_RRF_SOURCE"); }
                if (seen.add(chunk.id())) { scores.merge(chunk.id(), 1.0 / (k + ++rank), Double::sum); }
            }
        }
        return scores.entrySet().stream().map(entry -> new RetrievalEvidence(sources.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingDouble(RetrievalEvidence::score).reversed()
                        .thenComparing(item -> item.chunk().id().toString())).limit(limit).toList();
    }
}
