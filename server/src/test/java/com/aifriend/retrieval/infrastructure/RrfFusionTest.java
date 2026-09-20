package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.retrieval.domain.KnowledgeChunk;

class RrfFusionTest {
    private final RrfFusion fusion = new RrfFusion(60);

    @Test void addsOneBasedRankContributionsAndNotRawScores() {
        var a = chunk(1);
        var b = chunk(2);
        var c = chunk(3);
        var result = fusion.fuse(List.of(a, b), List.of(b, c), 4);
        assertThat(result.stream().map(item -> item.chunk().id())).containsExactly(b.id(), a.id(), c.id());
        assertThat(result.get(0).score()).isCloseTo(1.0 / 61 + 1.0 / 62, offset(1e-12));
    }

    @Test void absentLaneIsZeroAndBothEmptyRemainEmpty() {
        assertThat(fusion.fuse(List.of(chunk(1)), List.of(), 4).get(0).score())
                .isCloseTo(1.0 / 61, offset(1e-12));
        assertThat(fusion.fuse(List.of(), List.of(), 4)).isEmpty();
    }

    @Test void repeatedChunkCannotMultiplyContributionWithinOneLane() {
        var a = chunk(1);
        assertThat(fusion.fuse(List.of(a, a), List.of(a), 4).get(0).score())
                .isCloseTo(2.0 / 61, offset(1e-12));
    }

    @Test void sameIdWithConflictingSourceIsRejected() {
        var a = chunk(1);
        var changed = new KnowledgeChunk(a.id(), a.documentId(), 2, 0, "", "公开", 0, 2, "v1");
        assertThatThrownBy(() -> fusion.fuse(List.of(a), List.of(changed), 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void stableTiesAndFinalLimitAreEnforced() {
        var result = fusion.fuse(List.of(chunk(2)), List.of(chunk(1)), 1);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).chunk().id()).isEqualTo(chunk(1).id());
        assertThatThrownBy(() -> fusion.fuse(List.of(), List.of(), 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fusion.fuse(java.util.Collections.nCopies(21, chunk(1)), List.of(), 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private KnowledgeChunk chunk(int n) {
        return new KnowledgeChunk(new UUID(0, n), new UUID(0, n + 100), 1, 0, "", "公开", 0, 2, "v1");
    }
}
