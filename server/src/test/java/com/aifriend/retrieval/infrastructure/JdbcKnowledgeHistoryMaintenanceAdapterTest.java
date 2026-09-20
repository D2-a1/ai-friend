package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class JdbcKnowledgeHistoryMaintenanceAdapterTest {
    @Test void removesOnlyUnreferencedMaterialAndRetainsVersionAndJobIdentity() throws Exception {
        var fixture = new Fixture();
        fixture.chunk(1, false, false);
        fixture.chunk(2, true, false);
        fixture.chunk(3, false, true);
        fixture.version(11, false, false, false, false);
        fixture.version(12, true, false, false, false);
        fixture.version(13, false, true, false, false);
        fixture.version(14, false, false, true, false);
        fixture.version(15, false, false, false, true);
        assertThat(fixture.adapter.sweep()).isEqualTo(new com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.Cleanup(1, 1));
        assertThat(fixture.chunks).extracting(c -> c.id).containsExactly(id(2), id(3));
        assertThat(fixture.versions).hasSize(5);
        assertThat(fixture.versions.get(0).purged).isTrue();
        assertThat(fixture.versions.subList(1, 5)).allMatch(v -> !v.purged);
        assertThat(fixture.adapter.sweep().chunks()).isZero();
        assertThat(fixture.adapter.sweep().versions()).isZero();
        assertThat(fixture.writes).allMatch(sql -> !sql.contains("DELETE FROM knowledge_import_job")
                && !sql.contains("DELETE FROM knowledge_document_version") && !sql.contains("knowledge_quota"));
    }

    @Test void validBuildLeaseBlocksEveryHistoryReadAndWrite() throws Exception {
        var fixture = new Fixture();
        fixture.busy = true;
        fixture.chunk(1, false, false);
        var result = fixture.adapter.sweep();
        assertThat(result.chunks()).isZero();
        assertThat(result.state()).isEqualTo(com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.State.DEFERRED);
        assertThat(fixture.selects).hasSize(1);
        assertThat(fixture.writes).isEmpty();
    }

    @Test void everyBatchIsBoundedAndNextSweepContinuesWithoutDroppingStubs() throws Exception {
        var fixture = new Fixture();
        for (int i = 0; i < 70; i++) { fixture.chunk(i, false, false); }
        for (int i = 100; i < 118; i++) { fixture.version(i, false, false, false, false); }
        var first = fixture.adapter.sweep();
        assertThat(first.chunks()).isEqualTo(64);
        assertThat(first.versions()).isEqualTo(16);
        var second = fixture.adapter.sweep();
        assertThat(second.chunks()).isEqualTo(6);
        assertThat(second.versions()).isEqualTo(2);
        assertThat(fixture.versions).hasSize(18).allMatch(v -> v.purged);
    }

    @Test void writeFailuresRollbackEarlierChunkDeletionAndVersionPurging() throws Exception {
        for (int failure = 1; failure <= 4; failure++) {
            var fixture = new Fixture();
            fixture.chunk(1, false, false);
            fixture.chunk(2, false, false);
            fixture.version(11, false, false, false, false);
            fixture.version(12, false, false, false, false);
            fixture.failAt = failure;
            assertThatThrownBy(fixture.adapter::sweep).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.chunks).hasSize(2);
            assertThat(fixture.versions).allMatch(v -> !v.purged);
            verify(fixture.transactions).rollback(any());
            verify(fixture.transactions, never()).commit(any());
        }
    }

    @Test void missingControlOrCompareAndSetFailureNeverReportsSuccess() throws Exception {
        var missing = new Fixture();
        missing.missing = true;
        assertThatThrownBy(missing.adapter::sweep).hasMessage("INVALID_INDEX_CONTROL");
        assertThat(missing.writes).isEmpty();
        var conflict = new Fixture();
        conflict.chunk(1, false, false);
        conflict.conflict = true;
        assertThatThrownBy(conflict.adapter::sweep).isInstanceOf(ConcurrencyFailureException.class);
        assertThat(conflict.chunks).hasSize(1);
    }

    @Test void migrationKeepsNonPurgedOriginalMandatoryAndVersionTombstone() throws Exception {
        String ddl = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V35__public_knowledge_index.sql"));
        assertThat(ddl).contains("status='PURGED' AND original_text IS NULL AND title=''",
                "status<>'PURGED' AND original_text IS NOT NULL", "fk_knowledge_import_version");
    }

    @Test void retainedReferencesAreDeferredAndOnlyNoHistoryIsDrained() throws Exception {
        var fixture = new Fixture();
        fixture.version(11, false, false, false, true);
        assertThat(fixture.adapter.sweep().state()).isEqualTo(com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.State.DEFERRED);
        assertThat(fixture.versions.get(0).purged).isFalse();
        assertThat(new Fixture().adapter.sweep().state()).isEqualTo(com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.State.DRAINED);
        assertThat(new com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.Cleanup(0, 0).state())
                .isEqualTo(com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.State.PROGRESSED);
    }

    @Test void invalidRemainingProbeCannotClaimDrained() throws Exception {
        var fixture = new Fixture();
        when(fixture.jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);
        assertThatThrownBy(fixture.adapter::sweep).hasMessage("INVALID_CLEANUP_REMAINING");
        verify(fixture.transactions).rollback(any());
    }

    private static UUID id(int value) { return new UUID(0, value); }
    private record Chunk(UUID id, boolean manifest, boolean queued) { }
    private static final class Version {
        final UUID id;
        final boolean active, chunks, queued, generation;
        boolean purged;
        Version(UUID id, boolean active, boolean chunks, boolean queued, boolean generation) {
            this.id = id; this.active = active; this.chunks = chunks; this.queued = queued; this.generation = generation;
        }
    }

    /** SQL predicates are asserted; this fixture does not claim MySQL execution or InnoDB locking. */
    static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final JdbcKnowledgeHistoryMaintenanceAdapter adapter = new JdbcKnowledgeHistoryMaintenanceAdapter(jdbc, transactions);
        final List<Chunk> chunks = new ArrayList<>();
        final List<Version> versions = new ArrayList<>();
        final List<String> selects = new ArrayList<>(), writes = new ArrayList<>();
        List<Chunk> beforeChunks;
        List<Boolean> beforePurged;
        boolean busy, missing, conflict;
        int failAt;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Fixture() throws Exception {
            when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenAnswer(call -> {
                assertThat((String)call.getArgument(0)).contains("v.status<>'PURGED'", "d.active_version IS NULL",
                        "knowledge_index_generation", "c.active_generation_id=g.id");
                return versions.stream().anyMatch(v -> !v.active && !v.purged) ? 1 : 0;
            });
            when(transactions.getTransaction(any())).thenAnswer(call -> {
                TransactionDefinition definition = call.getArgument(0);
                assertThat(definition.getTimeout()).isEqualTo(2);
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                beforeChunks = List.copyOf(chunks);
                beforePurged = versions.stream().map(v -> v.purged).toList();
                return new SimpleTransactionStatus();
            });
            doAnswer(call -> {
                chunks.clear(); chunks.addAll(beforeChunks);
                for (int i = 0; i < versions.size(); i++) { versions.get(i).purged = beforePurged.get(i); }
                return null;
            }).when(transactions).rollback(any());
            doAnswer(call -> {
                String sql = call.getArgument(0);
                selects.add(sql);
                RowMapper mapper = call.getArgument(1);
                if (sql.contains("FROM knowledge_index_control WHERE")) {
                    assertThat(sql).contains("FOR UPDATE", "UTC_TIMESTAMP(3)", "lease_until>");
                    if (missing) { return List.of(); }
                    var row = mock(ResultSet.class);
                    when(row.getLong("version")).thenReturn(1L);
                    when(row.getInt("busy")).thenReturn(busy ? 1 : 0);
                    return List.of(mapper.mapRow(row, 0));
                }
                assertThat(selects.get(0)).contains("FOR UPDATE");
                var rows = new ArrayList<>();
                if (sql.contains("FROM knowledge_chunk k WHERE")) {
                    assertThat(sql).contains(JdbcKnowledgeHistoryMaintenanceAdapter.UNUSED_CHUNK, "LIMIT 64");
                    for (var chunk : chunks.stream().filter(c -> !c.manifest && !c.queued).limit(64).toList()) {
                        rows.add(mapper.mapRow(row(chunk.id), 0));
                    }
                } else if (sql.startsWith("SELECT BIN_TO_UUID(g.id)")) {
                    assertThat(sql).contains(JdbcKnowledgeHistoryMaintenanceAdapter.UNUSED_GENERATION,"LIMIT 1");
                } else {
                    assertThat(sql).contains(JdbcKnowledgeHistoryMaintenanceAdapter.UNUSED_VERSION, "LIMIT 16");
                    for (var version : versions.stream().filter(v -> !v.purged && !v.active && !v.chunks && !v.queued && !v.generation).limit(16).toList()) {
                        rows.add(mapper.mapRow(row(version.id), 0));
                    }
                }
                return rows;
            }).when(jdbc).query(anyString(), any(RowMapper.class));
            doAnswer(call -> {
                String sql = call.getArgument(0);
                writes.add(sql);
                if (failAt == writes.size()) { throw new IllegalStateException("simulated storage failure"); }
                if (conflict) { return 0; }
                UUID target = UUID.fromString(call.getArgument(1));
                if (sql.startsWith("DELETE k")) {
                    assertThat(sql).contains(JdbcKnowledgeHistoryMaintenanceAdapter.UNUSED_CHUNK, "k.id=UUID_TO_BIN(?)");
                    chunks.removeIf(c -> c.id.equals(target));
                } else {
                    assertThat(sql).contains(JdbcKnowledgeHistoryMaintenanceAdapter.UNUSED_VERSION,
                            "v.original_text=NULL", "v.title=''", "v.status='PURGED'", "v.version=?");
                    versions.stream().filter(v -> v.id.equals(target)).forEach(v -> v.purged = true);
                }
                return 1;
            }).when(jdbc).update(anyString(), any(Object[].class));
        }
        void chunk(int value, boolean manifest, boolean queued) { chunks.add(new Chunk(id(value), manifest, queued)); }
        void version(int value, boolean active, boolean chunks, boolean queued, boolean generation) {
            versions.add(new Version(id(value), active, chunks, queued, generation));
        }
        ResultSet row(UUID id) throws Exception {
            var row = mock(ResultSet.class);
            when(row.getString("id")).thenReturn(id.toString());
            when(row.getLong("version")).thenReturn(1L);
            return row;
        }
    }
}
