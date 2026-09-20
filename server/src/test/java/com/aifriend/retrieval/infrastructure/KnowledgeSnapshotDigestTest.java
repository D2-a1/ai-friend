package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.aifriend.retrieval.application.KnowledgeRepositoryPort.Snapshot;
import com.aifriend.retrieval.domain.*;

class KnowledgeSnapshotDigestTest {
    private final KnowledgeSnapshotDigest digest = new KnowledgeSnapshotDigest();
    private final IndexVersion version = new IndexVersion(id(9), 1, Optional.empty(), "t1", "c1");
    private final KnowledgeDocument a = doc(1, "公开说明😀");
    private final KnowledgeDocument b = doc(2, "另一说明");
    private Snapshot snapshot(IndexVersion v, KnowledgeDocument... docs) {
        var documents = List.of(docs);
        return new Snapshot(v, documents, documents.stream().map(d -> new KnowledgeChunk(id((int) d.id().getLeastSignificantBits() + 10),
                d.id(), d.version(), 0, "", d.text(), 0, d.text().codePointCount(0, d.text().length()), "c1")).toList());
    }
    @Test void orderingDoesNotChangeDigest() {
        assertThat(digest.calculate(snapshot(version, a, b))).containsExactly(digest.calculate(snapshot(version, b, a)));
    }
    @Test void originalTextAndMetadataChangesAreDetected() {
        byte[] expected = digest.calculate(snapshot(version, a));
        assertThat(digest.calculate(snapshot(version, doc(1, "公开说明😁")))).isNotEqualTo(expected);
        var changed = new KnowledgeDocument(a.id(), a.sourceKey(), a.version(), "不同标题", a.locale(),
                a.minAppVersion(), a.maxAppVersion(), a.text());
        assertThat(digest.calculate(snapshot(version, changed))).isNotEqualTo(expected);
    }
    @Test void generationRevisionAndProfileAreBound() {
        byte[] expected = digest.calculate(snapshot(version, a));
        assertThat(digest.calculate(snapshot(new IndexVersion(id(8), 1, Optional.empty(), "t1", "c1"), a))).isNotEqualTo(expected);
        assertThat(digest.calculate(snapshot(new IndexVersion(id(9), 2, Optional.empty(), "t1", "c1"), a))).isNotEqualTo(expected);
        assertThat(digest.calculate(snapshot(new IndexVersion(id(9), 1, Optional.of(new EmbeddingProfile("p1", 2)), "t1", "c1"), a)))
                .isNotEqualTo(expected);
    }
    @Test void emptyCorpusHasStableNonEmptyDigest() {
        assertThat(digest.calculate(snapshot(version))).hasSize(32).containsExactly(digest.calculate(snapshot(version)));
    }
    private static KnowledgeDocument doc(int id, String text) {
        return new KnowledgeDocument(id(id), "source-" + id, 1, "标题", "zh-CN", 1, 10, text);
    }
    private static UUID id(int n) { return new UUID(0, n); }
}
