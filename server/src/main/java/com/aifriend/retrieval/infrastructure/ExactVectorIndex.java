package com.aifriend.retrieval.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.application.KnowledgeSearchPort.VectorHit;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeDocument;
import com.aifriend.retrieval.domain.RetrievalQuery;

/**
 * 小语料精确余弦索引，复制及归一化向量，不接受缺行/混profile。
 * @author codex
 * @since 1.0.0
 */
public final class ExactVectorIndex {
    private final EmbeddingProfile profile;
    private final List<Entry> entries;

    /**
     * 接收与完整generation清单一一对应的向量。
     * @param snapshot 通过来源校验的语料
     * @param profile 存储向量空间
     * @param vectors 按片段ID定位的完整向量
     */
    public ExactVectorIndex(KnowledgeRepositoryPort.Snapshot snapshot, EmbeddingProfile profile,
            Map<UUID, float[]> vectors) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.profile = Objects.requireNonNull(profile, "profile");
        if (!snapshot.version().embeddingProfile().equals(java.util.Optional.of(profile))
                || vectors == null || vectors.size() != snapshot.chunks().size()) {
            throw new IllegalArgumentException("INVALID_VECTOR_SNAPSHOT");
        }
        var documents = new HashMap<UUID, KnowledgeDocument>();
        snapshot.documents().forEach(doc -> documents.put(doc.id(), doc));
        var built = new ArrayList<Entry>();
        for (var chunk : snapshot.chunks()) {
            built.add(new Entry(chunk, documents.get(chunk.documentId()), normalized(vectors.get(chunk.id()), profile.dimension())));
        }
        entries = List.copyOf(built);
    }

    /**
     * 同空间查询，按实际语言/versionCode过滤后稳定排序；负相似仍如实保留。
     * @param query 适用范围
     * @param queryProfile 查询向量空间
     * @param vector 查询向量
     * @param topK 1至20
     * @return 原始余弦候选
     */
    public List<VectorHit> search(RetrievalQuery query, EmbeddingProfile queryProfile, float[] vector, int topK) {
        Objects.requireNonNull(query, "query");
        if (!profile.equals(queryProfile) || topK < 1 || topK > 20) {
            throw new IllegalArgumentException("INVALID_VECTOR_QUERY");
        }
        float[] normalized = normalized(vector, profile.dimension());
        var hits = new ArrayList<VectorHit>();
        for (var entry : entries) {
            if (!entry.document().appliesTo(query.locale(), query.appVersionCode())) { continue; }
            double dot = 0;
            for (int i = 0; i < normalized.length; i++) { dot += (double) normalized[i] * entry.vector()[i]; }
            hits.add(new VectorHit(entry.chunk(), Math.max(-1, Math.min(1, dot))));
        }
        return hits.stream().sorted(Comparator.comparingDouble(VectorHit::similarity).reversed()
                .thenComparing(hit -> hit.chunk().id().toString())).limit(topK).toList();
    }

    private static float[] normalized(float[] input, int dimension) {
        if (input == null || input.length != dimension) { throw new IllegalArgumentException("INVALID_VECTOR_DIMENSION"); }
        float[] vector = input.clone();
        double squared = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) { throw new IllegalArgumentException("INVALID_VECTOR_VALUE"); }
            squared += (double) value * value;
        }
        if (squared == 0 || !Double.isFinite(squared)) { throw new IllegalArgumentException("INVALID_VECTOR_NORM"); }
        double norm = Math.sqrt(squared);
        for (int i = 0; i < vector.length; i++) { vector[i] = (float) (vector[i] / norm); }
        return vector;
    }

    private record Entry(KnowledgeChunk chunk, KnowledgeDocument document, float[] vector) { }
}
