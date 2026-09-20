package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 内存模拟外键/事务；执行真实回收适配器，不声称验证MySQL语法或锁。 */
class KnowledgeGenerationRetirementTest {
    @Test void retiredGenerationReleasesChunksAndOriginalWithoutAnotherImport() throws Exception {
        var f=new Fixture(70);
        var first=f.adapter.sweep();
        assertThat(first.chunks()).isEqualTo(64);
        assertThat(first.versions()).isZero();
        assertThat(f.state.manifest).hasSize(6);
        assertThat(f.state.vectors).hasSize(6);
        assertThat(f.state.generation).isTrue();
        var second=f.adapter.sweep();
        assertThat(second.chunks()).isEqualTo(6);
        assertThat(second.versions()).isEqualTo(1);
        assertThat(f.state.generation).isFalse();
        assertThat(f.state.purged).isTrue();
        assertThat(f.state.chunks).isEmpty();
        var drained=f.adapter.sweep();
        assertThat(drained.chunks()).isZero();
        assertThat(drained.state()).isEqualTo(com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.State.DRAINED);
        assertThat(f.writes).noneMatch(sql->sql.contains("knowledge_import_job SET")
                || sql.contains("DELETE FROM knowledge_document_version") || sql.contains("UPDATE knowledge_index_control"));
    }
    @Test void activePointerAndPendingBuildProtectAllReferencesWithoutLease() throws Exception {
        for(boolean active:List.of(true,false)) {
            var f=new Fixture(2); f.active=active; f.pending=!active;
            var protectedHistory=f.adapter.sweep();
            assertThat(protectedHistory.chunks()).isZero();
            assertThat(protectedHistory.state()).isEqualTo(active
                    ? com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.State.DRAINED
                    : com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.State.DEFERRED);
            assertThat(f.state.manifest).hasSize(2);
            assertThat(f.writes).isEmpty();
        }
    }
    @Test void validLeasePreventsEvenGenerationEnumeration() throws Exception {
        var f=new Fixture(2); f.busy=true;
        assertThat(f.adapter.sweep().versions()).isZero();
        assertThat(f.reads).hasSize(1);
        assertThat(f.writes).isEmpty();
    }
    @Test void sharedActiveChunkIsNotDeletedWhenOldManifestIsRemoved() throws Exception {
        var f=new Fixture(2); f.shared.add(id(1));
        assertThat(f.adapter.sweep().chunks()).isEqualTo(1);
        assertThat(f.state.generation).isFalse();
        assertThat(f.state.chunks).containsExactly(id(1));
        assertThat(f.state.purged).isFalse();
    }
    @Test void everyFailureRestoresVectorsManifestGenerationChunksAndOriginal() throws Exception {
        // 2*(向量+清单)+世代+2片段+1原文，共8处写入。
        for(int fail=1;fail<=8;fail++) {
            var f=new Fixture(2); f.failAt=fail;
            assertThatThrownBy(f.adapter::sweep).hasMessage("SIMULATED_WRITE_FAILURE");
            assertThat(f.state.manifest).hasSize(2);
            assertThat(f.state.vectors).hasSize(2);
            assertThat(f.state.chunks).hasSize(2);
            assertThat(f.state.generation).isTrue();
            assertThat(f.state.purged).isFalse();
            verify(f.tx).rollback(any()); verify(f.tx,never()).commit(any());
        }
    }
    @Test void failedManifestCasRollsBackEarlierVectorDeletion() throws Exception {
        var f=new Fixture(1); f.manifestConflict=true;
        assertThatThrownBy(f.adapter::sweep).hasMessage("KNOWLEDGE_CLEANUP_CONFLICT");
        assertThat(f.state.vectors).hasSize(1);
        assertThat(f.state.manifest).hasSize(1);
    }
    @Test void emptyRetiredGenerationCanBeReclaimedAndVersionPurged() throws Exception {
        var f=new Fixture(0);
        assertThat(f.adapter.sweep().versions()).isEqualTo(1);
        assertThat(f.state.generation).isFalse();
        assertThat(f.state.purged).isTrue();
    }
    @Test void missingVectorsAreAllowedForLexicalGeneration() throws Exception {
        var f=new Fixture(1); f.state.vectors.clear();
        assertThat(f.adapter.sweep().chunks()).isEqualTo(1);
        assertThat(f.state.purged).isTrue();
    }

    private static UUID id(int n) { return new UUID(0,n); }
    private static final class State {
        Set<UUID> manifest=new LinkedHashSet<>(),vectors=new LinkedHashSet<>(),chunks=new LinkedHashSet<>();
        boolean generation=true,purged;
        State copy() { var s=new State(); s.manifest.addAll(manifest);s.vectors.addAll(vectors);s.chunks.addAll(chunks);
            s.generation=generation;s.purged=purged;return s; }
    }
    private static final class Fixture {
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        final JdbcKnowledgeHistoryMaintenanceAdapter adapter=new JdbcKnowledgeHistoryMaintenanceAdapter(jdbc,tx);
        final List<String> reads=new ArrayList<>(),writes=new ArrayList<>();
        final Set<UUID> shared=new HashSet<>();
        State state=new State(),before;
        boolean active,pending,busy,manifestConflict;
        int failAt;
        @SuppressWarnings({"rawtypes","unchecked"}) Fixture(int count) throws Exception {
            for(int i=1;i<=count;i++) { state.manifest.add(id(i));state.vectors.add(id(i));state.chunks.add(id(i)); }
            when(tx.getTransaction(any())).thenAnswer(c->{before=state.copy();return new SimpleTransactionStatus();});
            doAnswer(c->{state=before;return null;}).when(tx).rollback(any());
            Answer<List<?>> query=c->{
                String sql=c.getArgument(0);reads.add(sql);RowMapper mapper=c.getArgument(1);
                if(sql.contains("FROM knowledge_index_control WHERE")) {
                    assertThat(sql).contains("FOR UPDATE","UTC_TIMESTAMP(3)");
                    var row=row(id(999));when(row.getInt("busy")).thenReturn(busy?1:0);
                    return List.of(mapper.mapRow(row,0));
                }
                if(sql.startsWith("SELECT BIN_TO_UUID(g.id)")) {
                    assertThat(sql).contains(JdbcKnowledgeHistoryMaintenanceAdapter.UNUSED_GENERATION,"LIMIT 1");
                    return state.generation && !active && !pending ? List.of(mapper.mapRow(row(id(999)),0)):List.of();
                }
                List<UUID> ids;
                if(sql.contains("FROM knowledge_generation_chunk WHERE")) {
                    assertThat(sql).contains("LIMIT 64");ids=state.manifest.stream().limit(64).toList();
                } else if(sql.contains("FROM knowledge_chunk k WHERE")) {
                    assertThat(sql).contains(JdbcKnowledgeHistoryMaintenanceAdapter.UNUSED_CHUNK,"LIMIT 64");
                    ids=state.chunks.stream().filter(i->!state.manifest.contains(i)&&!shared.contains(i)).limit(64).toList();
                } else {
                    assertThat(sql).contains(JdbcKnowledgeHistoryMaintenanceAdapter.UNUSED_VERSION,"LIMIT 16");
                    ids=!state.generation&&state.chunks.isEmpty()&&!state.purged?List.of(id(998)):List.of();
                }
                var result=new ArrayList<>();for(UUID i:ids)result.add(mapper.mapRow(row(i),result.size()));return result;
            };
            doAnswer(query).when(jdbc).query(anyString(),any(RowMapper.class));
            doAnswer(query).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
            when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenAnswer(c->{
                assertThat((String)c.getArgument(0)).contains("COUNT(*) FROM knowledge_generation_chunk");return state.manifest.size();});
            when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenAnswer(c->{
                String sql=c.getArgument(0);reads.add(sql);
                assertThat(sql).contains("SELECT CASE WHEN EXISTS", "v.status<>'PURGED'",
                        "d.active_version<>v.version", "c.active_generation_id=g.id");
                // 此夹具仅有一个文档版本和一个世代；active同时表示二者均为活动版本。
                return (!state.purged && !active) || (state.generation && !active) ? 1 : 0;
            });
            doAnswer(c->{
                String sql=c.getArgument(0);writes.add(sql);
                if(writes.size()==failAt)throw new IllegalStateException("SIMULATED_WRITE_FAILURE");
                if(sql.startsWith("DELETE FROM knowledge_embedding"))return state.vectors.remove(UUID.fromString(c.getArgument(2)))?1:0;
                if(sql.startsWith("DELETE FROM knowledge_generation_chunk")) {
                    UUID chunk=UUID.fromString(c.getArgument(2));assertThat(state.vectors).doesNotContain(chunk);
                    return manifestConflict?0:state.manifest.remove(chunk)?1:0;
                }
                if(sql.startsWith("DELETE g")) {
                    assertThat(sql).contains(JdbcKnowledgeHistoryMaintenanceAdapter.UNUSED_GENERATION);
                    assertThat(state.manifest).isEmpty();assertThat(active||pending).isFalse();state.generation=false;return 1;
                }
                if(sql.startsWith("DELETE k")) {
                    UUID chunk=UUID.fromString(c.getArgument(1));assertThat(shared).doesNotContain(chunk);return state.chunks.remove(chunk)?1:0;
                }
                assertThat(sql).contains("original_text=NULL","status='PURGED'");
                assertThat(state.chunks).isEmpty();assertThat(state.generation).isFalse();state.purged=true;return 1;
            }).when(jdbc).update(anyString(),any(Object[].class));
        }
        private ResultSet row(UUID id) throws Exception {
            var row=mock(ResultSet.class);when(row.getString("id")).thenReturn(id.toString());when(row.getLong("version")).thenReturn(1L);return row;
        }
    }
}
