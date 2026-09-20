package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.KnowledgeDocument;
import com.aifriend.retrieval.domain.KnowledgeText;

class KnowledgeChunkerTest {
    private final KnowledgeChunker chunker = new KnowledgeChunker(400, 60, 600);

    @Test void strictUtf8RejectsMalformedBinaryBlankAndOversize() {
        for (byte[] invalid : new byte[][] {{(byte) 0xc3, 0x28}, {0}, {}, new byte[262145], {0x20}}) {
            assertThatThrownBy(() -> KnowledgeChunker.decodeUtf8(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        String text = "中文😀\r\nＡ";
        assertThat(KnowledgeChunker.decodeUtf8(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);
    }

    @Test void shortOriginalIsNotNormalizedOrTrimmed() {
        var doc = document("  ＡＢＣ😀\r\n原文  ");
        var parts = chunker.chunk(doc);
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).text()).isEqualTo(doc.text());
        assertThat(parts.get(0).sourceStart()).isZero();
    }

    @Test void coverageAndOffsetsRemainExactAcrossSupplementaryUnicodeAndOverlap() {
        var doc = document("😀中".repeat(650));
        var parts = chunker.chunk(doc);
        var reconstructed = new StringBuilder();
        int covered = 0;
        for (var part : parts) {
            assertThat(part.sourceStart()).isLessThanOrEqualTo(covered);
            assertThat(part.sourceEnd()).isGreaterThan(covered);
            assertThat(part.text()).isEqualTo(KnowledgeText.slice(doc.text(), part.sourceStart(), part.sourceEnd()));
            assertThat(part.sourceEnd() - part.sourceStart()).isLessThanOrEqualTo(600);
            reconstructed.append(KnowledgeText.slice(doc.text(), covered, part.sourceEnd()));
            covered = part.sourceEnd();
        }
        assertThat(reconstructed.toString()).isEqualTo(doc.text());
        assertThat(parts.get(1).sourceStart()).isEqualTo(parts.get(0).sourceEnd() - 60);
        var index = new IndexVersion(UUID.randomUUID(), 1, Optional.empty(), KnowledgeTokenizer.VERSION, chunker.version());
        assertThat(new KnowledgeRepositoryPort.Snapshot(index, List.of(doc), parts)).isNotNull();
    }

    @Test void paragraphAndHeadingArePreferredAndRetained() {
        String first = "# 操作指南\n" + "甲".repeat(240) + "\n\n";
        var parts = chunker.chunk(document(first + "乙".repeat(800)));
        assertThat(parts.get(0).sourceEnd()).isEqualTo(first.codePointCount(0, first.length()));
        assertThat(parts.get(0).heading()).isEqualTo("操作指南");
    }

    @Test void sentenceBoundaryCanExtendPastTargetButNeverHardMaximum() {
        var parts = chunker.chunk(document("甲".repeat(449) + "。" + "乙".repeat(800)));
        assertThat(parts.get(0).sourceEnd()).isEqualTo(450);
        var longSentence = chunker.chunk(document("甲".repeat(900)));
        assertThat(longSentence.get(0).sourceEnd()).isEqualTo(400);
    }

    @Test void idsAreDeterministicAndConfigurationIsVersioned() {
        var doc = document("说明".repeat(700));
        assertThat(chunker.chunk(doc)).isEqualTo(chunker.chunk(doc));
        assertThat(new KnowledgeChunker(300, 60, 600).version()).isNotEqualTo(chunker.version());
        assertThatThrownBy(() -> chunker.chunk(doc).clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void handlesTargetBoundariesAndNoOverlap() {
        for (int size : new int[] {1, 399, 400, 401, 599, 600, 601}) {
            var parts = chunker.chunk(document("字".repeat(size)));
            assertThat(parts.get(parts.size() - 1).sourceEnd()).isEqualTo(size);
        }
        var noOverlap = new KnowledgeChunker(2, 0, 2).chunk(document("一二三四五"));
        assertThat(noOverlap.stream().map(part -> part.text())).containsExactly("一二", "三四", "五");
    }

    @Test void invalidConfigurationAndChunkOverflowFailWholeDocument() {
        for (int[] config : new int[][] {{0, 0, 1}, {10, 10, 20}, {10, -1, 20}, {10, 0, 9}, {10, 0, 601}}) {
            assertThatThrownBy(() -> new KnowledgeChunker(config[0], config[1], config[2]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new KnowledgeChunker(1, 0, 1).chunk(document("a".repeat(2001))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CHUNK_LIMIT");
    }

    private KnowledgeDocument document(String text) {
        return new KnowledgeDocument(new UUID(0, 1), "guide", 1, "指南", "zh-CN", 1, 1, text);
    }
}
