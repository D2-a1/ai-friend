package com.aifriend.retrieval.application;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.retrieval.application.KnowledgeRetrievalException.Kind;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.RetrievalEvidence;
import com.aifriend.retrieval.domain.RetrievalQuery;
import com.aifriend.retrieval.domain.RetrievalResult;

/**
 * 公开知识的词法/向量协调。默认本地检索；外发需要上层已核验的用途许可及预算。
 * @author codex
 * @since 1.0.0
 */
public final class HybridRetrievalService implements HybridRetrievalPort {
    private final KnowledgeRepositoryPort repository;
    private final KnowledgeSearchPort search;
    private final RankFusionPort fusion;
    private final Optional<EmbeddingPort> embedding;
    private final Duration embeddingBudget;

    /**
     * 依赖端口而非数据库、HTTP或具体算法。
     * @param repository 权威来源与最终复验
     * @param search 本地搜索算法
     * @param fusion 排名融合
     * @param embedding 未启用时empty
     * @param embeddingBudget 在线查询向量最多2秒
     */
    public HybridRetrievalService(KnowledgeRepositoryPort repository, KnowledgeSearchPort search,
            RankFusionPort fusion, Optional<EmbeddingPort> embedding, Duration embeddingBudget) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.search = Objects.requireNonNull(search, "search");
        this.fusion = Objects.requireNonNull(fusion, "fusion");
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        if (embeddingBudget == null || embeddingBudget.compareTo(Duration.ofMillis(1)) < 0
                || embeddingBudget.compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException("INVALID_RETRIEVAL_BUDGET");
        }
        this.embeddingBudget = embeddingBudget;
    }

    /** {@inheritDoc} */
    @Override public RetrievalResult retrieve(RetrievalQuery query) {
        Objects.requireNonNull(query, "query");
        var snapshot = repository.readActive().orElseThrow(() -> new KnowledgeRetrievalException(Kind.NO_INDEX));
        List<RetrievalEvidence> keyword;
        try {
            keyword = search.keyword(snapshot, query, 20);
            validateSources(snapshot, query, keyword.stream().map(RetrievalEvidence::chunk).toList());
        } catch (IllegalArgumentException invalid) {
            throw new KnowledgeRetrievalException(Kind.INDEX_INVALID);
        }
        List<KnowledgeChunk> vector = List.of();
        var mode = RetrievalResult.Mode.KEYWORD_ONLY;
        if (query.externalProcessing() == RetrievalQuery.ExternalProcessing.ALLOWED
                && snapshot.version().embeddingProfile().isPresent() && embedding.isPresent()
                && snapshot.documents().stream().anyMatch(doc -> doc.appliesTo(query.locale(), query.appVersionCode()))
                && query.text().codePoints().anyMatch(Character::isLetterOrDigit)) {
            var profile = snapshot.version().embeddingProfile().orElseThrow();
            try {
                var batch = embedding.orElseThrow().embed(profile, List.of(query.text()), embeddingBudget);
                if (!profile.equals(batch.profile()) || batch.size() != 1) {
                    throw new KnowledgeGatewayException(KnowledgeGatewayException.Kind.PROTOCOL);
                }
                var hits = search.vector(snapshot, query, profile, batch.vector(0), 20);
                validateSources(snapshot, query, hits.stream().map(KnowledgeSearchPort.VectorHit::chunk).toList());
                vector = hits.stream().filter(hit -> hit.similarity() > 0).map(KnowledgeSearchPort.VectorHit::chunk).toList();
                mode = RetrievalResult.Mode.HYBRID;
            } catch (KnowledgeGatewayException unavailable) {
                // 已独立验证词法快照，单次外发失败只明示降级，不切供应商或再试。
                mode = RetrievalResult.Mode.KEYWORD_ONLY;
            } catch (IllegalArgumentException invalidIndex) {
                throw new KnowledgeRetrievalException(Kind.INDEX_INVALID);
            }
        }
        List<RetrievalEvidence> evidence = fusion.fuse(keyword.stream().map(RetrievalEvidence::chunk).toList(), vector, query.limit());
        validateSources(snapshot, query, evidence.stream().map(RetrievalEvidence::chunk).toList());
        if (evidence.size() > query.limit()) { throw new KnowledgeRetrievalException(Kind.INDEX_INVALID); }
        if (!repository.isCurrent(snapshot.version(), evidence.stream().map(RetrievalEvidence::chunk).toList())) {
            throw new KnowledgeRetrievalException(Kind.SOURCE_CHANGED);
        }
        return new RetrievalResult(snapshot.version(), mode, evidence);
    }

    private void validateSources(KnowledgeRepositoryPort.Snapshot snapshot, RetrievalQuery query, List<KnowledgeChunk> chunks) {
        if (chunks.size() > 20) { throw new IllegalArgumentException("INVALID_SEARCH_RESULTS"); }
        var byId = new HashMap<UUID, KnowledgeChunk>();
        snapshot.chunks().forEach(chunk -> byId.put(chunk.id(), chunk));
        var applicable = new java.util.HashSet<UUID>();
        snapshot.documents().stream().filter(doc -> doc.appliesTo(query.locale(), query.appVersionCode()))
                .forEach(doc -> applicable.add(doc.id()));
        for (var chunk : chunks) {
            if (!chunk.equals(byId.get(chunk.id())) || !applicable.contains(chunk.documentId())) {
                throw new IllegalArgumentException("INVALID_SEARCH_SOURCE");
            }
        }
    }
}
