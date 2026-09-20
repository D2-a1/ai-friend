package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeDocument;
import com.aifriend.retrieval.domain.RetrievalQuery;

class ExactVectorIndexTest {
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile("space-a", 2);
    private static final RetrievalQuery QUERY = new RetrievalQuery("公开", "zh-CN", 1, 4);

    @Test void cosineGoldenSameOrthogonalAndOppositeVectors() {
        var index = new ExactVectorIndex(snapshot(3), PROFILE,
                Map.of(id(101), new float[] {3, 0}, id(102), new float[] {0, 2}, id(103), new float[] {-1, 0}));
        var result = index.search(QUERY, PROFILE, new float[] {100, 0}, 20);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).similarity()).isCloseTo(1, offset(1e-6));
        assertThat(result.get(1).similarity()).isCloseTo(0, offset(1e-6));
        assertThat(result.get(2).similarity()).isCloseTo(-1, offset(1e-6));
    }

    @Test void tinyAndLargeFiniteValuesNormalizeWithoutOverflowOrUnderflow() {
        for (float value : new float[] {Float.MIN_VALUE, Float.MAX_VALUE}) {
            var index = new ExactVectorIndex(snapshot(1), PROFILE, Map.of(id(101), new float[] {value, 0}));
            assertThat(index.search(QUERY, PROFILE, new float[] {value, 0}, 1).get(0).similarity())
                    .isCloseTo(1, offset(1e-6));
        }
    }

    @Test void rejectsZeroNonFiniteWrongDimensionAndIncompleteGeneration() {
        for (float[] invalid : new float[][] {{0, 0}, {1}, {Float.NaN, 1}, {Float.POSITIVE_INFINITY, 1}}) {
            assertThatThrownBy(() -> new ExactVectorIndex(snapshot(1), PROFILE, Map.of(id(101), invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ExactVectorIndex(snapshot(1), PROFILE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExactVectorIndex(snapshot(1), PROFILE, Map.of(id(999), new float[] {1, 0})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sameDimensionDifferentSpaceIsRejectedAtBuildAndQuery() {
        var other = new EmbeddingProfile("space-b", 2);
        assertThatThrownBy(() -> new ExactVectorIndex(snapshot(1), other, Map.of(id(101), new float[] {1, 0})))
                .isInstanceOf(IllegalArgumentException.class);
        var index = new ExactVectorIndex(snapshot(1), PROFILE, Map.of(id(101), new float[] {1, 0}));
        assertThatThrownBy(() -> index.search(QUERY, other, new float[] {1, 0}, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void vectorsAreCopiedAndTiesUseStableIds() {
        float[] mutable = {1, 0};
        var index = new ExactVectorIndex(snapshot(2), PROFILE, Map.of(id(102), mutable, id(101), mutable));
        mutable[0] = -1;
        assertThat(index.search(QUERY, PROFILE, new float[] {1, 0}, 20).stream().map(hit -> hit.chunk().id()))
                .containsExactly(id(101), id(102));
    }

    @Test void rangeMismatchAndEmptyCorpusDoNotReturnEvidence() {
        var index = new ExactVectorIndex(snapshot(1), PROFILE, Map.of(id(101), new float[] {1, 0}));
        assertThat(index.search(new RetrievalQuery("公开", "en", 1, 4), PROFILE, new float[] {1, 0}, 20)).isEmpty();
        assertThat(index.search(new RetrievalQuery("公开", "zh-CN", 2, 4), PROFILE, new float[] {1, 0}, 20)).isEmpty();
        assertThat(new ExactVectorIndex(snapshot(0), PROFILE, Map.of()).search(QUERY, PROFILE, new float[] {1, 0}, 20)).isEmpty();
        assertThatThrownBy(() -> index.search(QUERY, PROFILE, new float[] {1, 0}, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private KnowledgeRepositoryPort.Snapshot snapshot(int count) {
        var documents = new ArrayList<KnowledgeDocument>();
        var chunks = new ArrayList<KnowledgeChunk>();
        for (int i = 1; i <= count; i++) {
            documents.add(new KnowledgeDocument(id(i), "doc" + i, 1, "title", "zh-CN", 1, 1, "公开"));
            chunks.add(new KnowledgeChunk(id(i + 100), id(i), 1, 0, "", "公开", 0, 2, "v1"));
        }
        return new KnowledgeRepositoryPort.Snapshot(new IndexVersion(id(50), 1, Optional.of(PROFILE),
                KnowledgeTokenizer.VERSION, "v1"), documents, chunks);
    }
    private static UUID id(int n) { return new UUID(0, n); }
}
