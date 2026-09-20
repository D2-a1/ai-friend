package com.aifriend.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.knowledge.application.GraphProjectionException;
import com.aifriend.knowledge.application.ContactGraphQueryService;
import com.aifriend.knowledge.application.GraphQueryPort;
import com.aifriend.knowledge.domain.GraphQueryType;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.contact.infrastructure.ContactGraphSourceAdapter;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.knowledge.domain.GraphEdge;
import com.aifriend.knowledge.domain.GraphNode;
import com.aifriend.knowledge.domain.GraphSnapshot;
import com.aifriend.shared.error.BusinessException;

/** 实际适配器和SQL映射+模拟提交/回滚；不等于MySQL约束或锁验收。 */
class JdbcKnowledgeGraphAdapterTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ConsentGrantQueryPort consents = mock(ConsentGrantQueryPort.class);
    private Map<String, Stored> database = new HashMap<>();
    private final Map<TransactionStatus, Map<String, Stored>> before = new java.util.IdentityHashMap<>();
    private final List<String> statements = new ArrayList<>();
    private boolean granted = true;
    private boolean active = true;
    private boolean commitLost;
    private boolean failRead;
    private boolean forceCasFailure;
    private boolean loseNode;
    private String wrongBatchCount;
    private int failWriteAt;
    private int writes;
    private JdbcKnowledgeGraphAdapter adapter;

    @BeforeEach void setup() throws Exception {
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            var status = new SimpleTransactionStatus();
            var snapshot = new HashMap<String, Stored>();
            database.forEach((key, value) -> snapshot.put(key, value.copy()));
            before.put(status, snapshot); return status;
        });
        doAnswer(call -> { database = before.get(call.getArgument(0)); return null; })
                .when(transactions).rollback(any());
        doAnswer(call -> { if (commitLost) { throw new TransactionSystemException("DO_NOT_LEAK"); } return null; })
                .when(transactions).commit(any());
        when(consents.isGrantedForPolicy(any(), eq(ConsentType.CONTACT_GRAPH), eq("contact-graph-v1")))
                .thenAnswer(call -> granted);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0); statements.add(sql);
            if (failRead) { throw new DataAccessResourceFailureException("DO_NOT_LEAK"); }
            String owner = call.getArgument(2);
            var stored = database.getOrDefault(owner, new Stored());
            List<Map<String, Object>> rows;
            if (sql.contains("FROM app_user")) {
                assertThat(sql).contains("FOR UPDATE");
                rows = List.of(Map.of("id", owner, "status", active ? "ACTIVE" : "DELETING"));
            } else if (sql.contains("FROM knowledge_graph_snapshot")) {
                if (stored.header == null) { rows = List.of(); }
                else {
                    var header = new HashMap<>(stored.header);
                    header.put("total_nodes", (long) stored.nodes.size());
                    header.put("total_edges", (long) stored.edges.size());
                    rows = List.of(header);
                }
            } else if (sql.contains("FROM knowledge_graph_node")) {
                rows = stored.nodes.stream().filter(row -> row.get("generation").equals(call.getArgument(3))).toList();
            } else if (sql.contains("FROM knowledge_graph_edge")) {
                rows = stored.edges.stream().filter(row -> row.get("generation").equals(call.getArgument(3))).toList();
            } else { throw new AssertionError("Unknown SQL"); }
            RowMapper<?> mapper = call.getArgument(1);
            var mapped = new ArrayList<>();
            for (var row : rows) {
                ResultSet rs = mock(ResultSet.class);
                final boolean[] wasNull = {false};
                when(rs.getString(anyString())).thenAnswer(get -> (String) row.get(get.getArgument(0)));
                when(rs.getBytes(anyString())).thenAnswer(get -> row.get(get.getArgument(0)));
                when(rs.getLong(anyString())).thenAnswer(get -> {
                    Number value = (Number) row.get(get.getArgument(0)); wasNull[0] = value == null;
                    return value == null ? 0L : value.longValue();
                });
                when(rs.wasNull()).thenAnswer(get -> wasNull[0]);
                mapped.add(mapper.mapRow(rs, mapped.size()));
            }
            return mapped;
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0); statements.add(sql);
            var stored = database.getOrDefault(call.getArgument(2), new Stored());
            if (sql.contains("knowledge_graph_snapshot")) { return stored.header == null ? 0L : 1L; }
            if (sql.contains("knowledge_graph_node")) { return (long) stored.nodes.size(); }
            if (sql.contains("knowledge_graph_edge")) { return (long) stored.edges.size(); }
            throw new AssertionError("Unknown count");
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0); statements.add(sql);
            if (++writes == failWriteAt) { throw new DataAccessResourceFailureException("DO_NOT_LEAK"); }
            Object[] args = java.util.Arrays.copyOfRange(call.getArguments(), 1, call.getArguments().length);
            boolean updating = sql.startsWith("UPDATE");
            String owner = (String) args[updating ? 5 : 0];
            Stored stored = database.computeIfAbsent(owner, ignored -> new Stored());
            if (sql.startsWith("DELETE FROM knowledge_graph_edge")) { int size = stored.edges.size(); stored.edges.clear(); return size; }
            if (sql.startsWith("DELETE FROM knowledge_graph_node")) { int size = stored.nodes.size(); stored.nodes.clear(); return size; }
            if (sql.startsWith("DELETE FROM knowledge_graph_snapshot")) {
                if (forceCasFailure || stored.header == null || !stored.header.get("version").equals(args[1])) { return 0; }
                stored.header = null; return 1;
            }
            if (updating && (forceCasFailure || stored.header == null || !stored.header.get("version").equals(args[6]))) { return 0; }
            if (sql.startsWith("INSERT INTO knowledge_graph_snapshot") || updating) {
                int offset = updating ? 0 : 1;
                stored.header = new HashMap<>(Map.of("owner_id", owner, "generation", args[offset],
                        "source_digest", args[offset + 1], "status", "ACTIVE", "version", args[offset + 2],
                        "node_count", args[offset + 3], "edge_count", args[offset + 4]));
            } else if (sql.startsWith("INSERT INTO knowledge_graph_node")) {
                for(int p=0;p<args.length;p+=6) if (!loseNode) { stored.nodes.add(new HashMap<>(Map.of("owner_id", args[p], "generation", args[p+1],
                        "node_id", args[p+2], "node_type", args[p+3], "source_id", args[p+4], "source_version", args[p+5]))); }
                return args.length/6-("node".equals(wrongBatchCount)?1:0);
            } else if (sql.startsWith("INSERT INTO knowledge_graph_edge")) {
                for(int p=0;p<args.length;p+=6) stored.edges.add(new HashMap<>(Map.of("owner_id", args[p], "generation", args[p+1], "edge_id", args[p+2],
                        "from_id", args[p+3], "to_id", args[p+4], "relation_type", args[p+5])));
                return args.length/6-("edge".equals(wrongBatchCount)?1:0);
            } else { throw new AssertionError("Unknown update"); }
            return 1;
        });
        adapter = new JdbcKnowledgeGraphAdapter(jdbc, transactions, new KnowledgeAccessPolicy(consents));
    }

    @Test void publishesAndReadsCompleteGraphWithOnlyPrivateReferences() {
        var graph = graph(1, 10, 1);
        assertThat(adapter.findByOwner(id(1))).isEmpty();
        var saved = adapter.project(graph, 0);
        assertThat(saved.snapshot()).isEqualTo(graph);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(adapter.findByOwner(id(1))).contains(saved);
        assertThat(statements).noneMatch(sql -> sql.contains("template_cipher") || sql.contains("display_text")
                || sql.contains("wechat_locator"));
    }

    @Test void batchAffectedCountMismatchRollsBackRatherThanTrustingPartialSuccess() {
        var prior=adapter.project(graph(1,10,1),0);
        for(String type:List.of("node","edge")) {
            wrongBatchCount=type;
            failure("INVALID",()->adapter.project(graph(1,11,2),1));
            wrongBatchCount=null;
            assertThat(adapter.findByOwner(id(1))).contains(prior);
        }
    }

    @Test void fullSizeGraphUsesOnlyTwoBoundedInsertStatements() {
        var nodes=new ArrayList<GraphNode>();var edges=new ArrayList<GraphEdge>();
        nodes.add(new GraphNode(id(1),id(10),id(3),GraphNode.Type.USER,id(1),1));
        for(int n=0;n<199;n++) {
            nodes.add(new GraphNode(id(1),id(10),id(100+n),GraphNode.Type.CONTACT,id(1000+n),1));
            edges.add(new GraphEdge(id(1),id(10),id(2000+n),id(3),id(100+n),GraphEdge.Type.HAS_CONTACT));
        }
        var graph=new GraphSnapshot(id(1),id(10),"a".repeat(64),nodes,edges);
        assertThat(adapter.project(graph,0).snapshot()).isEqualTo(graph);
        assertThat(writes).isEqualTo(5);
        assertThat(statements.stream().filter(sql->sql.startsWith("INSERT INTO knowledge_graph_node"))).hasSize(1);
        assertThat(statements.stream().filter(sql->sql.startsWith("INSERT INTO knowledge_graph_edge"))).hasSize(1);
        assertThat(statements).noneMatch(sql->sql.contains(id(1).toString()));
    }

    @Test void newGenerationReplacesAllOldReferencesAndIncrementsVersion() {
        adapter.project(graph(1, 10, 1), 0);
        var next = graph(1, 11, 2);
        assertThat(adapter.project(next, 1).snapshot()).isEqualTo(next);
        assertThat(adapter.findByOwner(id(1)).orElseThrow().version()).isEqualTo(2);
        assertThat(database.get(id(1).toString()).nodes).hasSize(3)
                .allSatisfy(row -> assertThat(row.get("generation")).isEqualTo(id(11).toString()));
    }

    @Test void eachWriteFailureRollsBackEntireReplacement() {
        var previous = adapter.project(graph(1, 10, 1), 0);
        // 两次删除、一次控制行CAS、节点批量与边批量，共五个写入边界。
        for (int point = 1; point <= 5; point++) {
            writes = 0; failWriteAt = point;
            failure("STORAGE_UNAVAILABLE", () -> adapter.project(graph(1, 11, 2), 1));
            failWriteAt = 0;
            assertThat(adapter.findByOwner(id(1))).contains(previous);
        }
        verify(transactions, times(5)).rollback(any());
    }

    @Test void conflictingVersionAndLastCasFailureCannotErasePriorGraph() {
        var prior = adapter.project(graph(1, 10, 1), 0);
        failure("CONFLICT", () -> adapter.project(graph(1, 11, 2), 0));
        forceCasFailure = true;
        failure("CONFLICT", () -> adapter.project(graph(1, 11, 2), 1));
        forceCasFailure = false;
        assertThat(adapter.findByOwner(id(1))).contains(prior);
    }

    @Test void validReadUsesThreeQueriesAndCountsAllGenerationsInHeader() {
        var expected = adapter.project(graph(1, 10, 1), 0);
        statements.clear();
        assertThat(adapter.findByOwner(id(1))).contains(expected);
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("total_nodes", "total_edges",
                "n.owner_user_id=s.owner_user_id", "e.owner_user_id=s.owner_user_id");
        assertThat(statements).noneMatch(sql -> sql.startsWith("SELECT COUNT(*)"));
    }

    @Test void extraGenerationEdgeStillRejectsProjection() {
        adapter.project(graph(1, 10, 1), 0);
        var stored = database.get(id(1).toString());
        var stray = new HashMap<>(stored.edges.get(0));
        stray.put("generation", id(12).toString());
        stored.edges.add(stray);
        failure("INVALID", () -> adapter.findByOwner(id(1)));
    }

    @Test void acknowledgedButMissingNodeFailsReadbackAndRollsBack() {
        var prior = adapter.project(graph(1, 10, 1), 0); loseNode = true;
        failure("INVALID", () -> adapter.project(graph(1, 11, 2), 1));
        loseNode = false; assertThat(adapter.findByOwner(id(1))).contains(prior);
    }

    @Test void twoOwnersCanUseSameNodeIdsWithoutSharingRowsAndPurgeOnlyOne() {
        var first = adapter.project(graph(1, 10, 1), 0);
        var second = adapter.project(graph(2, 10, 1), 0);
        assertThat(first.snapshot().nodes().get(1).id()).isEqualTo(second.snapshot().nodes().get(1).id());
        assertThat(adapter.purge(id(1), 1)).isTrue();
        assertThat(adapter.findByOwner(id(1))).isEmpty();
        assertThat(adapter.findByOwner(id(2))).contains(second);
    }

    @Test void revokeOrInactiveOwnerPreventsWritesButDoesNotBlockCleanup() {
        adapter.project(graph(1, 10, 1), 0); int priorWrites = writes;
        granted = false;
        assertThatThrownBy(() -> adapter.project(graph(1, 11, 2), 1)).isInstanceOf(BusinessException.class);
        assertThat(writes).isEqualTo(priorWrites);
        active = false;
        assertThatThrownBy(() -> adapter.project(graph(1, 11, 2), 1)).isInstanceOf(BusinessException.class);
        assertThat(adapter.purge(id(1), 1)).isTrue();
        assertThat(adapter.purge(id(1), 1)).isTrue();
    }

    @Test void stalePurgeVersionDoesNotDeleteAnything() {
        var previous = adapter.project(graph(1, 10, 1), 0);
        assertThat(adapter.purge(id(1), 0)).isFalse();
        assertThat(adapter.findByOwner(id(1))).contains(previous);
    }

    @Test void cleanupFailuresRollBackAndDoNotReportSuccess() {
        var prior = adapter.project(graph(1, 10, 1), 0);
        for (int point = 1; point <= 3; point++) {
            writes = 0; failWriteAt = point;
            failure("STORAGE_UNAVAILABLE", () -> adapter.purge(id(1), 1));
            failWriteAt = 0; assertThat(adapter.findByOwner(id(1))).contains(prior);
        }
    }

    @Test void missingHeaderWithOrphanRowsIsInvalidButPurgeCanCleanIt() {
        adapter.project(graph(1, 10, 1), 0); database.get(id(1).toString()).header = null;
        failure("INVALID", () -> adapter.findByOwner(id(1)));
        assertThat(adapter.purge(id(1), 0)).isTrue();
        assertThat(adapter.findByOwner(id(1))).isEmpty();
    }

    @Test void mixedOwnerGenerationBrokenEdgesAndExtraGenerationRejectEntireProjection() {
        adapter.project(graph(1, 10, 1), 0);
        var stored = database.get(id(1).toString());
        stored.nodes.get(1).put("owner_id", id(2).toString());
        failure("INVALID", () -> adapter.findByOwner(id(1)));
        stored.nodes.get(1).put("owner_id", id(1).toString());
        stored.edges.get(0).put("to_id", id(999).toString());
        failure("INVALID", () -> adapter.findByOwner(id(1)));
        stored.edges.get(0).put("to_id", id(4).toString());
        var stray = new HashMap<>(stored.nodes.get(1)); stray.put("generation", id(12).toString());
        stored.nodes.add(stray);
        failure("INVALID", () -> adapter.findByOwner(id(1)));
    }

    @Test void malformedHeaderAndUnknownNodeTypeAreNotEmptyGraph() {
        adapter.project(graph(1, 10, 1), 0);
        var stored = database.get(id(1).toString());
        stored.header.put("source_digest", new byte[1]);
        failure("INVALID", () -> adapter.findByOwner(id(1)));
        stored.header.put("source_digest", new byte[32]);
        stored.nodes.get(0).put("node_type", "SYSTEM_PROMPT");
        failure("INVALID", () -> adapter.findByOwner(id(1)));
    }

    @Test void readOrCommitFailureIsUnavailableWithoutBlindRetry() {
        failRead = true; failure("STORAGE_UNAVAILABLE", () -> adapter.findByOwner(id(1)));
        failRead = false; commitLost = true;
        failure("STORAGE_UNAVAILABLE", () -> adapter.project(graph(1, 10, 1), 0));
        assertThat(writes).isEqualTo(5);
    }

    @Test void transactionDefinitionsKeepReadSnapshotsFreshAndCleanupInCallerTransaction() {
        adapter.project(graph(1, 10, 1), 0); adapter.findByOwner(id(1)); adapter.purge(id(1), 1);
        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(3)).getTransaction(definitions.capture());
        var all = definitions.getAllValues();
        assertThat(all.get(0).getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(all.get(0).getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(all.get(1).isReadOnly()).isTrue();
        assertThat(all.get(1).getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(all.get(2).getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(all).allSatisfy(def -> assertThat(def.getTimeout()).isEqualTo(2));
    }

    @Test void migrationHasOwnerGenerationForeignKeysAndNoPrivatePayloadColumns() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V37__private_knowledge_graph.sql"));
        assertThat(migration).contains("FOREIGN KEY (owner_user_id,generation)",
                "FOREIGN KEY (owner_user_id,generation,from_id)", "FOREIGN KEY (owner_user_id,generation,to_id)",
                "uq_graph_one_parent", "node_count BETWEEN 1 AND 200", "source_version>=0");
        assertThat(migration).doesNotContain("display_text", "template_cipher", "wechat_locator", "DROP TABLE");
    }

    @Test void smokeRealSourceProjectionQueryAndAesThenSourceChangeAndUnbind() throws Exception {
        var sourceJdbc = mock(JdbcTemplate.class);
        var dataSource = mock(DataSource.class);
        var compatibility = mock(AcousticTemplatePort.class);
        when(compatibility.isCompatible(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        var keys = mock(SecurityKeyMaterial.class);
        when(keys.dataEncryptionKey()).thenReturn(new SecretKeySpec(new byte[32], "AES"));
        var protector = new SensitiveDataProtector(keys);
        byte[] encrypted = protector.encrypt("老二");
        long[] sourceVersion = {1}; boolean[] unbound = {false};
        when(sourceJdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            assertThat(sql).matches("(?s)\\(?SELECT.*");
            assertThat((String) call.getArgument(2)).isEqualTo(id(1).toString());
            var row = new HashMap<String, Object>();
            row.put("version", sourceVersion[0]); row.put("status", "ACTIVE");
            row.put("owner_id", id(1).toString()); row.put("valid", true);
            var rows = new ArrayList<Map<String,Object>>();
            if (sql.contains("source_kind")) {
                assertThat(sql).contains("UNION ALL", "LIMIT 2", "LIMIT 200", "LIMIT 201");
                assertThat(call.getArguments()).hasSize(5);
                assertThat(call.getArgument(3, String.class)).isEqualTo(id(1).toString());
                assertThat(call.getArgument(4, String.class)).isEqualTo(id(1).toString());
                row.put("source_kind", "USER"); row.put("id", id(1).toString());
                rows.add(new HashMap<>(row));
                if (!unbound[0]) {
                    row.put("source_kind", "CONTACT"); row.put("id", id(40).toString());
                    rows.add(new HashMap<>(row));
                    row.put("source_kind", "ALIAS");
                    row.putAll(Map.of("id", id(50).toString(), "binding_id", id(40).toString(),
                        "material_present", true, "dialect_code", "cn", "dialect_package_version", "p1",
                        "template_model_version", "m1", "threshold_version", "t1"));
                    rows.add(new HashMap<>(row));
                }
            } else if (sql.contains("SELECT display_text_cipher")) {
                row.putAll(Map.of("display_text_cipher",encrypted.clone(),"id",id(50).toString(),"binding_id",id(40).toString()));
                rows.add(row);
            }
            else { throw new AssertionError("Unexpected source SQL"); }
            RowMapper<?> mapper = call.getArgument(1);
            var mapped = new ArrayList<Object>();
            for (var values : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(anyString())).thenAnswer(get -> (String) values.get(get.getArgument(0)));
                when(rs.getBytes(anyString())).thenAnswer(get -> values.get(get.getArgument(0)));
                when(rs.getLong(anyString())).thenAnswer(get -> values.get(get.getArgument(0)));
                when(rs.getBoolean(anyString())).thenAnswer(get -> values.getOrDefault(get.getArgument(0), false));
                mapped.add(mapper.mapRow(rs, mapped.size()));
            }
            return mapped;
        });
        var policy = new KnowledgeAccessPolicy(consents);
        var actualSource = new ContactGraphSourceAdapter(dataSource, transactions, policy, compatibility, protector);
        // 仅替换基础JDBC测试边界，source/project/query和AES生产代码全部真实执行。
        ReflectionTestUtils.setField(actualSource, "jdbc", sourceJdbc);
        var query = new ContactGraphQueryService(actualSource, adapter, policy);
        var list = new GraphQueryPort.Query(id(1), GraphQueryType.LIST_CONTACTS, Optional.empty(), Optional.empty());
        assertThat(query.query(list).candidates()).singleElement()
                .satisfies(display -> assertThat(display.aliases()).containsExactly("老二"));
        var find = new GraphQueryPort.Query(id(1), GraphQueryType.FIND_CONTACT_BY_ALIAS, Optional.empty(), Optional.of("老二"));
        assertThat(query.query(find).candidates()).hasSize(1);
        assertThat(adapter.findByOwner(id(1)).orElseThrow().version()).isEqualTo(1);
        sourceVersion[0] = 2;
        assertThat(query.query(list).candidates().get(0).contactVersion()).isEqualTo(2);
        assertThat(adapter.findByOwner(id(1)).orElseThrow().version()).isEqualTo(2);
        unbound[0] = true;
        assertThat(query.query(list).candidates()).isEmpty();
        assertThat(adapter.findByOwner(id(1)).orElseThrow().snapshot().nodes()).hasSize(1);
        verifyNoInteractions(dataSource);
        verify(sourceJdbc, never()).update(anyString(), any(Object[].class));
    }

    private static void failure(String kind, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(GraphProjectionException.class).hasMessage(kind).hasNoCause();
    }

    static GraphSnapshot graph(long owner, long generation, long sourceVersion) {
        var nodes = List.of(new GraphNode(id(owner), id(generation), id(3), GraphNode.Type.USER, id(owner), sourceVersion),
                new GraphNode(id(owner), id(generation), id(4), GraphNode.Type.CONTACT, id(40), sourceVersion),
                new GraphNode(id(owner), id(generation), id(5), GraphNode.Type.ALIAS, id(50), sourceVersion));
        var edges = List.of(new GraphEdge(id(owner), id(generation), id(6), id(3), id(4), GraphEdge.Type.HAS_CONTACT),
                new GraphEdge(id(owner), id(generation), id(7), id(4), id(5), GraphEdge.Type.HAS_ALIAS));
        return new GraphSnapshot(id(owner), id(generation), (sourceVersion == 1 ? "a" : "b").repeat(64), nodes, edges);
    }

    static UUID id(long value) { return new UUID(0, value); }

    private static final class Stored {
        Map<String, Object> header;
        final List<Map<String, Object>> nodes = new ArrayList<>();
        final List<Map<String, Object>> edges = new ArrayList<>();
        Stored copy() {
            var copy = new Stored(); copy.header = header == null ? null : new HashMap<>(header);
            nodes.forEach(row -> copy.nodes.add(new HashMap<>(row)));
            edges.forEach(row -> copy.edges.add(new HashMap<>(row))); return copy;
        }
    }
}
