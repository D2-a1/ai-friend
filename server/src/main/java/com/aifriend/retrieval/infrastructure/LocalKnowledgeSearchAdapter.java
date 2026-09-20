package com.aifriend.retrieval.infrastructure;

import java.util.List;
import java.util.Objects;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.application.KnowledgeSearchPort;
import com.aifriend.retrieval.application.KnowledgeVectorRepositoryPort;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.RetrievalEvidence;
import com.aifriend.retrieval.domain.RetrievalQuery;

/**
 * 无共享陈旧缓存的首期检索适配器，加载失败不复用上一代数据。
 * @author codex
 * @since 1.0.0
 */
public final class LocalKnowledgeSearchAdapter implements KnowledgeSearchPort {
    private final KnowledgeVectorRepositoryPort vectors;
    private final double k1;
    private final double b;
    private final long memoryBudget;

    /**
     * 创建本地检索算法适配器。
     * @param vectors 完整向量仓储
     * @param k1 BM25参数
     * @param b 文长参数
     * @param memoryBudget 已分配给本次构建的内存保守预算
     */
    public LocalKnowledgeSearchAdapter(KnowledgeVectorRepositoryPort vectors, double k1, double b, long memoryBudget) {
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        if (!Double.isFinite(k1) || k1 <= 0 || k1 > 3 || !Double.isFinite(b) || b < 0 || b > 1
                || memoryBudget < 1 || memoryBudget > 128L * 1024 * 1024) {
            throw new IllegalArgumentException("INVALID_SEARCH_CONFIGURATION");
        }
        this.k1 = k1;
        this.b = b;
        this.memoryBudget = memoryBudget;
    }

    /** {@inheritDoc} */
    @Override public List<RetrievalEvidence> keyword(KnowledgeRepositoryPort.Snapshot snapshot, RetrievalQuery query, int topK) {
        return new Bm25KeywordIndex(snapshot, new KnowledgeTokenizer(), k1, b, memoryBudget).search(query, topK);
    }

    /** {@inheritDoc} */
    @Override public List<VectorHit> vector(KnowledgeRepositoryPort.Snapshot snapshot, RetrievalQuery query,
            EmbeddingProfile profile, float[] queryVector, int topK) {
        long conservativeVectorBytes = (long) snapshot.chunks().size() * profile.dimension() * Float.BYTES * 3L;
        long textBytes = snapshot.documents().stream().mapToLong(doc -> doc.text().length() * 4L + 256).sum();
        if (conservativeVectorBytes + textBytes > memoryBudget) {
            throw new IllegalArgumentException("RESOURCE_LIMIT");
        }
        return new ExactVectorIndex(snapshot, profile, vectors.load(snapshot.version()))
                .search(query, profile, queryVector, topK);
    }
}
