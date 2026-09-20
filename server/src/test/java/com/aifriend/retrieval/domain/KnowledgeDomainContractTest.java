package com.aifriend.retrieval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class KnowledgeDomainContractTest {
    private static final UUID ID = new UUID(0, 1);

    @Test void unicodeCountsCodePointsAndKeepsOriginal() {
        assertThat(KnowledgeText.require("Ａ😀中", 3, 10, false)).isEqualTo("Ａ😀中");
        assertThat(KnowledgeText.slice("Ａ😀中", 1, 2)).isEqualTo("😀");
        assertThatThrownBy(() -> KnowledgeText.require("Ａ😀中", 2, 10, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeText.require("Ａ😀中", 3, 9, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsMalformedUnicodeAndBinaryButAllowsTextWhitespace() {
        for (String invalid : new String[] {String.valueOf((char) 0xd800), String.valueOf((char) 0xdc00),
                "a" + (char) 0, "a" + (char) 0x1b}) {
            assertThatThrownBy(() -> KnowledgeText.require(invalid, 20, 80, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(KnowledgeText.require("\r\n\t", 3, 3, true)).isEqualTo("\r\n\t");
        assertThatThrownBy(() -> KnowledgeText.require(null, 20, 80, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void documentHasClosedVersionRangeAndExactLanguage() {
        var doc = new KnowledgeDocument(ID, "guide-v1", 1, "指南", "zh-CN", 3, 5, "公开说明");
        assertThat(doc.appliesTo("zh-cn", 3)).isTrue();
        assertThat(doc.appliesTo("zh-CN", 5)).isTrue();
        assertThat(doc.appliesTo("zh-CN", 2)).isFalse();
        assertThat(doc.appliesTo("zh-CN", 6)).isFalse();
        assertThat(doc.appliesTo("en", 3)).isFalse();
        assertThat(doc.toString()).doesNotContain("公开说明", "指南");
    }

    @Test void rejectsInvalidDocumentAndByteOverflow() {
        for (String key : new String[] {"../guide", "https://example.invalid", "", "x".repeat(101)}) {
            assertThatThrownBy(() -> new KnowledgeDocument(ID, key, 1, "title", "zh-CN", 1, 1, "text"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new KnowledgeDocument(ID, "guide", 1, "title", "zh-CN", 2, 1, "text"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeDocument(ID, "guide", 1, "title", "zh-CN", 1, 1,
                "中".repeat(87382))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new KnowledgeDocument(ID, "guide", 1, "title", "zh-CN", 1, 1, "x".repeat(262144)))
                .isNotNull();
    }

    @Test void chunkChecksOffsetsAndHardLimit() {
        assertThat(new KnowledgeChunk(ID, ID, 1, 0, "", "😀中", 3, 5, "v1").sourceEnd()).isEqualTo(5);
        assertThatThrownBy(() -> new KnowledgeChunk(ID, ID, 1, 0, "", "😀中", 3, 6, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeChunk(ID, ID, 1, 0, "", "x".repeat(601), 0, 601, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new KnowledgeChunk(ID, ID, 1, 1999, "", "x".repeat(600), 0, 600, "v1")).isNotNull();
        assertThatThrownBy(() -> new KnowledgeChunk(ID, ID, 1, 2000, "", "x", 0, 1, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void vectorSpaceUsesIdentityNotOnlyDimension() {
        assertThat(new EmbeddingProfile("a-v1", 32)).isNotEqualTo(new EmbeddingProfile("b-v1", 32));
        assertThat(new EmbeddingProfile("a-v1", 4096)).isNotNull();
        for (int dim : new int[] {-1, 0, 4097}) {
            assertThatThrownBy(() -> new EmbeddingProfile("a-v1", dim))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new IndexVersion(ID, -1, Optional.empty(), "v1", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new IndexVersion(ID, 0, Optional.empty(), "v1", "v1").embeddingProfile()).isEmpty();
    }

    @Test void rejectsNonFiniteEvidenceScore() {
        var chunk = new KnowledgeChunk(ID, ID, 1, 0, "", "text", 0, 4, "v1");
        for (double score : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new RetrievalEvidence(chunk, score))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new RetrievalEvidence(chunk, 0).score()).isZero();
    }
}
