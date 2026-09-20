package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.ConcurrencyFailureException;

import com.aifriend.retrieval.application.KnowledgeBuildSourcePort.Source;
import com.aifriend.retrieval.application.KnowledgeGenerationPort.Build;
import com.aifriend.retrieval.application.KnowledgeImportLeasePort.Claim;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.BuildSpecification;
import com.aifriend.retrieval.application.KnowledgeRetrievalException;
import com.aifriend.retrieval.application.EmbeddingPort;
import com.aifriend.retrieval.application.HybridRetrievalService;
import com.aifriend.retrieval.application.KnowledgeGatewayException;
import com.aifriend.retrieval.domain.EmbeddingBatch;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.KnowledgeImportJob;
import com.aifriend.retrieval.domain.RetrievalQuery;
import com.aifriend.retrieval.domain.RetrievalResult;

/** F矩阵：生产发布/读取在同一SQL模拟存储上连续换空间；不是实库或模型质量验收。 */
class KnowledgeEmbeddingProfileSwitchTest {
    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void completeRebuildSwitchesSpaceThenRebuildsBackWithoutMixingVectors(int dimension) throws Exception {
        var db = new JdbcKnowledgeGenerationAdapterTest.Simulation(true);
        db.fill();
        db.adapter.publish(db.build);
        var reader = new JdbcKnowledgeAdapter(db.jdbc, db.transactions);
        var original = reader.readActive().orElseThrow();
        var configured = new AtomicReference<>(original.version().embeddingProfile().orElseThrow());
        EmbeddingPort model = (profile, texts, remaining) -> {
            if (!profile.equals(configured.get())) {
                throw new KnowledgeGatewayException(KnowledgeGatewayException.Kind.CONFIGURATION);
            }
            var value = new float[profile.dimension()];
            Arrays.fill(value, 1f);
            return new EmbeddingBatch(profile, new float[][] {value});
        };
        var retrieval = new HybridRetrievalService(reader,
                new LocalKnowledgeSearchAdapter(reader, 1.2, .75, 128L * 1024 * 1024),
                new RrfFusion(60), Optional.of(model), Duration.ofSeconds(2));
        var query = new RetrievalQuery("守护", "zh-CN", 1, 4, RetrievalQuery.ExternalProcessing.ALLOWED);
        assertThat(retrieval.retrieve(query).mode()).isEqualTo(RetrievalResult.Mode.HYBRID);
        var next = prepare(db, 101, new EmbeddingProfile("p2", dimension));
        configured.set(next.snapshot().version().embeddingProfile().orElseThrow());
        db.adapter.begin(next);
        var ids = next.snapshot().chunks().stream().map(c -> c.id()).toList();
        var vectors = vectors(next, 7f);
        db.adapter.stage(next, ids.subList(0, 1), Map.of(ids.get(0), vectors.get(ids.get(0))));
        assertThat(reader.readActive()).contains(original);
        var transitional = retrieval.retrieve(query);
        assertThat(transitional.mode()).isEqualTo(RetrievalResult.Mode.KEYWORD_ONLY);
        assertThat(transitional.evidence()).isNotEmpty();
        reader.load(original.version()).values().forEach(v -> assertThat(v).containsExactly(1f, 2f));
        var incomplete = db.checkpoint();
        assertThatThrownBy(() -> db.adapter.publish(next)).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.checkpoint()).isEqualTo(incomplete);
        db.adapter.stage(next, ids.subList(1, ids.size()), Map.of(ids.get(1), vectors.get(ids.get(1))));
        db.adapter.publish(next);
        var changed = reader.readActive().orElseThrow();
        assertThat(retrieval.retrieve(query).mode()).isEqualTo(RetrievalResult.Mode.HYBRID);
        assertThat(changed).isEqualTo(next.snapshot());
        assertThat(changed.documents()).isEqualTo(original.documents());
        assertThat(changed.chunks()).isEqualTo(original.chunks());
        reader.load(changed.version()).forEach((id, value) -> assertThat(value).containsExactly(vectors.get(id)));
        assertThat(reader.isCurrent(original.version(), original.chunks())).isFalse();
        assertThatThrownBy(() -> reader.load(original.version())).isInstanceOf(KnowledgeRetrievalException.class);

        // 切回是新租约下完整重建，不直接重新激活已退休的旧世代。
        var back = prepare(db, 102, original.version().embeddingProfile().orElseThrow());
        configured.set(original.version().embeddingProfile().orElseThrow());
        db.adapter.begin(back);
        assertThat(reader.readActive()).contains(changed);
        assertThat(retrieval.retrieve(query).mode()).isEqualTo(RetrievalResult.Mode.KEYWORD_ONLY);
        var rebuilt = vectors(back, 9f);
        db.adapter.stage(back, ids, rebuilt);
        db.adapter.publish(back);
        var restored = reader.readActive().orElseThrow();
        assertThat(retrieval.retrieve(query).mode()).isEqualTo(RetrievalResult.Mode.HYBRID);
        assertThat(restored.version().embeddingProfile()).isEqualTo(original.version().embeddingProfile());
        assertThat(restored.version().generation()).isNotEqualTo(original.version().generation());
        assertThat(restored.documents()).isEqualTo(original.documents());
        reader.load(restored.version()).forEach((id, value) -> assertThat(value).containsExactly(rebuilt.get(id)));
        assertThat(reader.isCurrent(changed.version(), changed.chunks())).isFalse();
        assertThatThrownBy(() -> reader.load(changed.version())).isInstanceOf(KnowledgeRetrievalException.class);
        assertThat(db.generations).hasSize(2); // 仅保留当前和刚退休世代。
    }

    @Test
    void sameDimensionOldSpaceBlobCannotBePublishedInNewSpace() throws Exception {
        var db = new JdbcKnowledgeGenerationAdapterTest.Simulation(true);
        db.fill();
        db.adapter.publish(db.build);
        var reader = new JdbcKnowledgeAdapter(db.jdbc, db.transactions);
        var original = reader.readActive().orElseThrow();
        var next = prepare(db, 101, new EmbeddingProfile("p2", 2));
        db.adapter.begin(next);
        var ids = next.snapshot().chunks().stream().map(c -> c.id()).toList();
        db.adapter.stage(next, ids, vectors(next, 7f));
        var id = ids.get(0);
        db.embeddings.put(next.snapshot().version().generation() + ":" + id,
                new HashMap<>(db.embeddings.get(original.version().generation() + ":" + id)));
        var corrupted = db.checkpoint();
        assertThatThrownBy(() -> db.adapter.publish(next)).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.checkpoint()).isEqualTo(corrupted);
        assertThat(reader.readActive()).contains(original);
        reader.load(original.version()).values().forEach(v -> assertThat(v).containsExactly(1f, 2f));
    }

    private static Build prepare(JdbcKnowledgeGenerationAdapterTest.Simulation db, int generation,
            EmbeddingProfile profile) {
        var previous = db.build.source();
        var claim = new Claim(KnowledgeImportJob.pending(new UUID(0, generation + 1000), db.now, db.now.plusSeconds(600))
                .claim(db.now, new UUID(0, generation + 2000), Duration.ofSeconds(30)), generation, generation);
        var specification = new BuildSpecification(Optional.of(profile), previous.specification().tokenizerVersion(),
                previous.specification().chunkerVersion());
        var source = new Source(claim, specification, previous.targetDocument(), previous.targetVersion(), previous.documents());
        var result = new Build(source, source.snapshot(new UUID(0, generation), db.build.snapshot().chunks()));
        db.jobReady = false;
        doAnswer(call -> {
            assertThat((Claim) call.getArgument(0)).isEqualTo(claim);
            if (db.jobReady) { throw new ConcurrencyFailureException("BUILD_SOURCE_CHANGED"); }
            return source;
        }).when(db.sources).loadLocked(any());
        return result;
    }

    private static Map<UUID, float[]> vectors(Build build, float value) {
        var result = new HashMap<UUID, float[]>();
        for (var chunk : build.snapshot().chunks()) {
            var vector = new float[build.snapshot().version().embeddingProfile().orElseThrow().dimension()];
            Arrays.fill(vector, value);
            result.put(chunk.id(), vector);
        }
        return result;
    }
}
