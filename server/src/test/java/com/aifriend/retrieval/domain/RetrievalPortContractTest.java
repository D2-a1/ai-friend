package com.aifriend.retrieval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort;

class RetrievalPortContractTest {
    private static final UUID ID = new UUID(0, 1);
    private static final IndexVersion INDEX = new IndexVersion(ID, 1, Optional.empty(), "v1", "v1");
    private static final KnowledgeDocument DOC =
            new KnowledgeDocument(ID, "guide", 1, "title", "zh-CN", 1, 5, "公开说明");

    @Test void vectorsCannotBeMutatedThroughInputOrOutput() {
        float[][] values = {{1, 2}};
        var batch = new EmbeddingBatch(new EmbeddingProfile("a", 2), values);
        values[0][0] = 99;
        batch.vector(0)[1] = 99;
        assertThat(batch.vector(0)).containsExactly(1, 2);
        assertThat(batch.size()).isEqualTo(1);
        assertThat(batch.toString()).doesNotContain("1.0", "2.0");
    }

    @Test void rejectsEveryInvalidVectorWithoutPartialBatch() {
        var profile = new EmbeddingProfile("a", 2);
        for (float[][] invalid : new float[][][] {
                {}, {{1}}, {{1, 2}, {0, 0}}, {{1, Float.NaN}}, {{Float.POSITIVE_INFINITY, 1}}}) {
            assertThatThrownBy(() -> new EmbeddingBatch(profile, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new EmbeddingBatch(profile, new float[65][2]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new EmbeddingBatch(profile, new float[][] {{Float.MIN_VALUE, 0}})).isNotNull();
    }

    @Test void queryIsBoundedAndNotLogged() {
        assertThat(new RetrievalQuery("怎么使用", "zh-CN", 1, 4).toString()).doesNotContain("怎么使用");
        for (int limit : new int[] {-1, 0, 5, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new RetrievalQuery("问题", "zh-CN", 1, limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RetrievalQuery("x".repeat(501), "zh-CN", 1, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void evidenceIsImmutableAndUnique() {
        var list = new ArrayList<>(List.of(new RetrievalEvidence(chunk(ID, 1, 0, 0, 4), 1)));
        var result = new RetrievalResult(INDEX, RetrievalResult.Mode.KEYWORD_ONLY, list);
        list.clear();
        assertThat(result.evidence()).hasSize(1);
        assertThatThrownBy(() -> result.evidence().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RetrievalResult(INDEX, RetrievalResult.Mode.KEYWORD_ONLY,
                List.of(result.evidence().get(0), result.evidence().get(0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsMixedVersionsAndFalseHybrid() {
        var one = new RetrievalEvidence(chunk(ID, 1, 0, 0, 4), 1);
        var two = new RetrievalEvidence(chunk(new UUID(0, 2), 2, 1, 0, 4), 1);
        assertThatThrownBy(() -> new RetrievalResult(INDEX, RetrievalResult.Mode.KEYWORD_ONLY, List.of(one, two)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalResult(INDEX, RetrievalResult.Mode.HYBRID, List.of(one)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalResult(INDEX, RetrievalResult.Mode.NONE, List.of(one)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void snapshotRejectsMissingOrIncorrectSourceAndGaps() {
        assertThatThrownBy(() -> new KnowledgeRepositoryPort.Snapshot(INDEX, List.of(), List.of(chunk(ID, 1, 0, 0, 4))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeRepositoryPort.Snapshot(INDEX, List.of(DOC), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeRepositoryPort.Snapshot(INDEX, List.of(DOC), List.of(chunk(ID, 2, 0, 0, 4))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeRepositoryPort.Snapshot(INDEX, List.of(DOC),
                List.of(chunk(ID, 1, 0, 0, 1), chunk(new UUID(0, 2), 1, 1, 2, 4))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void snapshotAcceptsOverlappingCompleteCorpusAndDefensiveCopies() {
        var chunks = new ArrayList<>(List.of(chunk(ID, 1, 0, 0, 3), chunk(new UUID(0, 2), 1, 1, 2, 4)));
        var snapshot = new KnowledgeRepositoryPort.Snapshot(INDEX, List.of(DOC), chunks);
        chunks.clear();
        assertThat(snapshot.chunks()).hasSize(2);
        assertThatThrownBy(() -> snapshot.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private KnowledgeChunk chunk(UUID id, long version, int ordinal, int start, int end) {
        return new KnowledgeChunk(id, ID, version, ordinal, "", KnowledgeText.slice(DOC.text(), start, end),
                start, end, "v1");
    }
}
