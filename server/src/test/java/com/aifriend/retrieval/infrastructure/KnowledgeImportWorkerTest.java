package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;

import com.aifriend.retrieval.application.EmbeddingPort;
import com.aifriend.retrieval.application.KnowledgeGenerationPort;
import com.aifriend.retrieval.application.KnowledgeImportLeasePort;
import com.aifriend.retrieval.application.KnowledgeImportWorkPort;
import com.aifriend.retrieval.application.KnowledgeImportWorker;
import com.aifriend.retrieval.application.KnowledgeImportWorker.Outcome;
import com.aifriend.retrieval.application.KnowledgeQuotaPort;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.Decision;
import com.aifriend.retrieval.domain.EmbeddingBatch;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.KnowledgeImportJob.Failure;
import com.aifriend.retrieval.domain.RetrievalQuery;

class KnowledgeImportWorkerTest {
    @Test void lexicalWorkerBuildsCompleteGenerationAndProductionSearchWithoutAnyModelOrQuotaCall() throws Exception {
        var fixture = new Fixture(false);
        assertThat(fixture.worker().tick()).isEqualTo(Outcome.READY);
        var reader = new JdbcKnowledgeAdapter(fixture.db.jdbc, fixture.db.transactions);
        var snapshot = reader.readActive().orElseThrow();
        var search = new Bm25KeywordIndex(snapshot, new KnowledgeTokenizer(), 1.2, .75, 128L * 1024 * 1024);
        assertThat(search.search(new RetrievalQuery("消息", "zh-CN", 1, 4), 20)).isNotEmpty();
        assertThat(snapshot.documents()).hasSize(2);
        verifyNoInteractions(fixture.quota, fixture.embedding);
        verify(fixture.leases, never()).fail(any(), any(), anyBoolean());
    }

    @Test void vectorWorkerReservesEachBatchThenCallsOutsideTransactionAndPublishesReadableVectors() throws Exception {
        var fixture = new Fixture(true);
        var calls = new AtomicInteger();
        when(fixture.embedding.embed(any(), anyList(), any())).thenAnswer(c -> {
            assertThat(fixture.db.inTransaction).isFalse();
            assertThat(calls.incrementAndGet()).isEqualTo(mockingDetails(fixture.quota).getInvocations().size());
            return new EmbeddingBatch(c.getArgument(0), new float[][] {{1, 2}});
        });
        assertThat(fixture.worker().tick()).isEqualTo(Outcome.READY);
        assertThat(calls.get()).isEqualTo(2);
        var reader = new JdbcKnowledgeAdapter(fixture.db.jdbc, fixture.db.transactions);
        var active = reader.readActive().orElseThrow();
        assertThat(reader.load(active.version())).hasSize(2);
        verify(fixture.quota, times(2)).reserve(argThat(r -> r.operationId().equals(fixture.db.build.source().claim().job().id())
                && r.attempt() == 1 && r.ownerId().isEmpty() && r.profileId().equals("p1")));
    }

    @Test void noWorkOrUnclaimedJobDoesNotLoadSources() throws Exception {
        var fixture = new Fixture(false);
        when(fixture.work.due(1)).thenReturn(List.of());
        assertThat(fixture.worker().tick()).isEqualTo(Outcome.NO_WORK);
        verify(fixture.db.sources, never()).load(any());
        when(fixture.work.due(1)).thenReturn(List.of(UUID.randomUUID()));
        when(fixture.leases.claim(any(), any(), any())).thenReturn(Optional.empty());
        assertThat(fixture.worker().tick()).isEqualTo(Outcome.NO_WORK);
        verify(fixture.db.sources, never()).load(any());
    }

    @Test void deniedDuplicateDisabledAndExpiredPermitsNeverReachModel() throws Exception {
        for (Decision denied : List.of(Decision.LIMIT_EXCEEDED, Decision.DUPLICATE, Decision.DISABLED, Decision.EXPIRED)) {
            var fixture = new Fixture(true);
            when(fixture.quota.reserve(any())).thenReturn(denied);
            assertThat(fixture.worker().tick()).isEqualTo(Outcome.FAILED);
            verifyNoInteractions(fixture.embedding);
            assertThat(fixture.db.active).isEqualTo(new UUID(0, 88).toString());
            verify(fixture.leases).fail(any(), eq(Failure.RESOURCE_LIMIT), eq(false));
        }
    }

    @Test void quotaStorageFailureCannotCallModelOrBlindlyRewriteJob() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.quota.reserve(any())).thenThrow(new DataAccessResourceFailureException("simulated"));
        assertThatThrownBy(() -> fixture.worker().tick()).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(fixture.embedding);
        verify(fixture.leases, never()).fail(any(), any(), anyBoolean());
    }

    @Test void leaseLostAfterQuotaBeforeModelPreventsExternalCall() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.work.remaining(any())).thenReturn(Duration.ofSeconds(10))
                .thenThrow(new ConcurrencyFailureException("IMPORT_LEASE_LOST"));
        assertThatThrownBy(() -> fixture.worker().tick()).hasMessage("IMPORT_LEASE_LOST");
        verify(fixture.quota).reserve(any());
        verifyNoInteractions(fixture.embedding);
    }

    @Test void temporaryModelFailureIsPersistentlyDeferredWithoutLoopingOrPublishing() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.embedding.embed(any(), anyList(), any())).thenThrow(
                new com.aifriend.retrieval.application.KnowledgeGatewayException(
                        com.aifriend.retrieval.application.KnowledgeGatewayException.Kind.TEMPORARY));
        assertThat(fixture.worker().tick()).isEqualTo(Outcome.DEFERRED);
        verify(fixture.embedding, times(1)).embed(any(), anyList(), any());
        verify(fixture.leases).fail(any(), eq(Failure.TEMPORARY), eq(true));
        assertThat(fixture.db.active).isEqualTo(new UUID(0, 88).toString());
    }

    @Test void wrongProfileFromGatewayStopsWholeGeneration() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.embedding.embed(any(), anyList(), any())).thenReturn(new EmbeddingBatch(new EmbeddingProfile("different", 2),
                new float[][] {{1, 2}}));
        assertThat(fixture.worker().tick()).isEqualTo(Outcome.FAILED);
        verify(fixture.leases).fail(any(), eq(Failure.INDEX_INVALID), eq(false));
        assertThat(fixture.db.active).isEqualTo(new UUID(0, 88).toString());
    }

    @Test void memoryLimitRejectsBeforeCreatingAnyGeneration() throws Exception {
        var fixture = new Fixture(false);
        fixture.maxBytes = 1024 * 1024;
        assertThat(fixture.worker().tick()).isEqualTo(Outcome.FAILED);
        assertThat(fixture.db.generations).hasSize(1);
        verify(fixture.leases).fail(any(), eq(Failure.RESOURCE_LIMIT), eq(false));
    }

    @Test void interruptionBeforeWorkPreservesFlagAndDoesNotTouchStorage() throws Exception {
        var fixture = new Fixture(false);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> fixture.worker().tick()).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(fixture.work, fixture.leases);
        } finally { Thread.interrupted(); }
    }

    @Test void interruptionDuringModelDoesNotStageOrPublishLateResult() throws Exception {
        var fixture = new Fixture(true);
        when(fixture.embedding.embed(any(), anyList(), any())).thenAnswer(c -> {
            Thread.currentThread().interrupt();
            return new EmbeddingBatch(c.getArgument(0), new float[][] {{1, 2}});
        });
        try {
            assertThatThrownBy(() -> fixture.worker().tick()).isInstanceOf(CancellationException.class);
            assertThat(fixture.db.manifest).isEmpty();
            assertThat(fixture.db.active).isEqualTo(new UUID(0, 88).toString());
            verify(fixture.leases, never()).fail(any(), any(), anyBoolean());
        } finally { Thread.interrupted(); }
    }

    @Test void uncertainPublicationCommitDoesNotOverwriteReadyJobWithFailure() throws Exception {
        var fixture = new Fixture(false);
        var delegate = fixture.db.adapter;
        fixture.generations = new KnowledgeGenerationPort() {
            @Override public void begin(Build build) { delegate.begin(build); }
            @Override public void stage(Build build, List<UUID> ids, Map<UUID, float[]> vectors) { delegate.stage(build, ids, vectors); }
            @Override public void publish(Build build) { fixture.db.loseCommitResponse = true; delegate.publish(build); }
        };
        assertThatThrownBy(() -> fixture.worker().tick()).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(fixture.db.jobReady).isTrue();
        assertThat(fixture.db.active).isNotEqualTo(new UUID(0, 88).toString());
        verify(fixture.leases, never()).fail(any(), any(), anyBoolean());
    }

    private static final class Fixture {
        final JdbcKnowledgeGenerationAdapterTest.Simulation db;
        final KnowledgeImportWorkPort work = mock(KnowledgeImportWorkPort.class);
        final KnowledgeImportLeasePort leases = mock(KnowledgeImportLeasePort.class);
        final KnowledgeQuotaPort quota = mock(KnowledgeQuotaPort.class);
        final EmbeddingPort embedding = mock(EmbeddingPort.class);
        final boolean vector;
        KnowledgeGenerationPort generations;
        long maxBytes = 128L * 1024 * 1024;
        Fixture(boolean vector) throws Exception {
            this.vector = vector;
            db = new JdbcKnowledgeGenerationAdapterTest.Simulation(vector);
            generations = db.adapter;
            var claim = db.build.source().claim();
            when(work.due(1)).thenReturn(List.of(claim.job().id()));
            when(work.remaining(any())).thenReturn(Duration.ofSeconds(20));
            when(leases.claim(any(), any(), any())).thenReturn(Optional.of(claim));
            when(leases.fail(any(), any(), anyBoolean())).thenAnswer(c ->
                    claim.job().fail(db.now, claim.job().leaseToken().orElseThrow(), c.getArgument(1), c.getArgument(2)));
            when(db.sources.load(any())).thenReturn(db.build.source());
            when(quota.reserve(any())).thenReturn(Decision.GRANTED);
        }
        KnowledgeImportWorker worker() {
            return new KnowledgeImportWorker(work, leases, db.sources, generations, new KnowledgeChunker(400, 60, 600),
                    quota, vector ? Optional.of(embedding) : Optional.empty(),
                    new KnowledgeImportWorker.Settings(db.build.source().specification(), Duration.ofSeconds(30), 1,
                            maxBytes, Duration.ofSeconds(8)));
        }
    }
}
