package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeDocument;
import com.aifriend.retrieval.domain.RetrievalQuery;

class Bm25KeywordIndexTest {
    private static final long MEMORY = 128L * 1024 * 1024;

    @Test void matchesHandCalculatedGoldenScoreAndDeduplicatesQueryTerms() {
        var index = index(doc(1, "alpha alpha beta"), doc(2, "beta"));
        var result = index.search(query("alpha"), 20);
        // N=2, df=1, tf=2, dl=3, avgdl=2: ln(2)*4.4/3.65
        assertThat(result).hasSize(1);
        assertThat(result.get(0).score()).isCloseTo(Math.log(2) * 4.4 / 3.65, offset(1e-12));
        assertThat(index.search(query("alpha alpha"), 20)).isEqualTo(result);
    }

    @Test void filtersVersionAndLocaleBeforeComputingCorpusStatistics() {
        var applicable = doc(1, "alpha");
        var english = new KnowledgeDocument(id(2), "doc2", 1, "title", "en", 1, 1, "alpha alpha");
        var otherVersion = new KnowledgeDocument(id(3), "doc3", 1, "title", "zh-CN", 2, 3, "alpha beta");
        var result = index(applicable, english, otherVersion).search(query("alpha"), 20);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).score()).isCloseTo(Math.log(4.0 / 3), offset(1e-12));
    }

    @Test void identicalScoresHaveStableIdOrderingIndependentOfImportOrder() {
        var forward = index(doc(1, "alpha"), doc(2, "alpha")).search(query("alpha"), 20);
        var reverse = index(doc(2, "alpha"), doc(1, "alpha")).search(query("alpha"), 20);
        assertThat(forward).isEqualTo(reverse);
        assertThat(forward.stream().map(item -> item.chunk().id())).containsExactly(id(101), id(102));
    }

    @Test void emptyCorpusZeroAverageSymbolsAndUnknownTermsHaveNoEvidence() {
        assertThat(index().search(query("alpha"), 20)).isEmpty();
        assertThat(index(doc(1, "!!!")).search(query("alpha"), 20)).isEmpty();
        assertThat(index(doc(1, "alpha")).search(query("!!!"), 20)).isEmpty();
        assertThat(index(doc(1, "alpha")).search(query("unknown"), 20)).isEmpty();
    }

    @Test void chineseAndEnglishUseSameVersionedTokenizer() {
        var result = index(doc(1, "微信如何开启守护"), doc(2, "电话")).search(query("开启"), 20);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).chunk().documentId()).isEqualTo(id(1));
        assertThat(index(doc(1, "ＡＢＣ")).search(query("abc"), 1)).hasSize(1);
    }

    @Test void sharedChineseCharactersAloneMustNotBecomeEvidence() {
        var index = index(doc(1,"默认偏好可以更改。"));
        assertThat(index.search(query("天空的颜色"),20)).isEmpty();
        assertThat(index.search(query("红色可以搭配什么衣服"),20)).isNotEmpty();
        // “可以”双字匹配仍不证明语义正确，明确保留此词法能力边界。
        assertThat(index.search(query("默认偏好"),20)).hasSize(1);
        assertThat(index.search(query("偏"),20)).hasSize(1);
        assertThat(index.search(query("默 好"),20)).isEmpty();
    }

    @Test void budgetBoundsAndTokenizerMismatchAreRejected() {
        var snapshot = snapshot(doc(1, "alpha"));
        assertThatThrownBy(() -> new Bm25KeywordIndex(snapshot, new KnowledgeTokenizer(), 1.2, .75, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("RESOURCE_LIMIT");
        for (double k1 : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new Bm25KeywordIndex(snapshot, new KnowledgeTokenizer(), k1, .75, MEMORY))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var wrong = new KnowledgeRepositoryPort.Snapshot(new IndexVersion(id(50), 1, Optional.empty(), "old", "v1"),
                snapshot.documents(), snapshot.chunks());
        assertThatThrownBy(() -> new Bm25KeywordIndex(wrong, new KnowledgeTokenizer(), 1.2, .75, MEMORY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index(doc(1, "alpha")).search(query("alpha"), 21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void goldenScoreForTwoDistinctQueryTerms() {
        var result = index(doc(1, "alpha beta"), doc(2, "beta gamma")).search(query("beta alpha"), 20);
        assertThat(result.get(0).score()).isCloseTo(Math.log(2) + Math.log(1.2), offset(1e-12));
    }

    @Test void repeatedWordsAcrossTwoThousandChunksDoNotAccumulateTemporaryTokenObjects() {
        var snapshot = fullCorpus("测试".repeat(200));
        var index = new Bm25KeywordIndex(snapshot, new KnowledgeTokenizer(), 1.2, .75, MEMORY);
        assertThat(snapshot.chunks()).hasSize(2000);
        assertThat(index.search(query("测试"), 4)).hasSize(4);
    }

    @Test void twoThousandChunksWithActuallyDistinctTermsStillRespectOriginalMemoryLimit() {
        var text = new StringBuilder();
        for (int i = 0; i < 400; i++) { text.appendCodePoint(0x4e00 + i); }
        assertThatThrownBy(() -> new Bm25KeywordIndex(fullCorpus(text.toString()),
                new KnowledgeTokenizer(), 1.2, .75, MEMORY)).hasMessage("RESOURCE_LIMIT");
    }

    private KnowledgeRepositoryPort.Snapshot fullCorpus(String part) {
        var documents = new ArrayList<KnowledgeDocument>();
        var chunks = new ArrayList<KnowledgeChunk>();
        var chunker = new KnowledgeChunker(400, 0, 400);
        for (int i = 0; i < 100; i++) {
            var document = doc(i + 1, part.repeat(20));
            documents.add(document); chunks.addAll(chunker.chunk(document));
        }
        return new KnowledgeRepositoryPort.Snapshot(new IndexVersion(id(900), 1, Optional.empty(),
                KnowledgeTokenizer.VERSION, chunker.version()), documents, chunks);
    }

    private Bm25KeywordIndex index(KnowledgeDocument... documents) {
        return new Bm25KeywordIndex(snapshot(documents), new KnowledgeTokenizer(), 1.2, .75, MEMORY);
    }
    private KnowledgeRepositoryPort.Snapshot snapshot(KnowledgeDocument... documents) {
        var chunks = new ArrayList<KnowledgeChunk>();
        for (var doc : documents) {
            chunks.add(new KnowledgeChunk(id((int) doc.id().getLeastSignificantBits() + 100), doc.id(), doc.version(),
                    0, "", doc.text(), 0, doc.text().codePointCount(0, doc.text().length()), "v1"));
        }
        return new KnowledgeRepositoryPort.Snapshot(new IndexVersion(id(50), 1, Optional.empty(),
                KnowledgeTokenizer.VERSION, "v1"), List.of(documents), chunks);
    }
    private KnowledgeDocument doc(int n, String text) {
        return new KnowledgeDocument(id(n), "doc" + n, 1, "title", "zh-CN", 1, 1, text);
    }
    private RetrievalQuery query(String text) { return new RetrievalQuery(text, "zh-CN", 1, 4); }
    private static UUID id(int n) { return new UUID(0, n); }
}
