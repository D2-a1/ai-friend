package com.aifriend.knowledge.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.knowledge.application.GraphProjectionException;
import com.aifriend.knowledge.application.GraphProjectionException.Kind;
import com.aifriend.knowledge.application.KnowledgeGraphPort;
import com.aifriend.knowledge.domain.GraphEdge;
import com.aifriend.knowledge.domain.GraphNode;
import com.aifriend.knowledge.domain.GraphSnapshot;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 私人图谱三表存储，完整读校验、owner锁及控制版本CAS，不保留显示文本。
 *
 * <p>发布在独立短事务内锁定账号后复验同意；独立撤权清理器取得相同账号锁，
 * 防止撤权提交后旧发布复活投影。此处purge是版本化维护，损坏投影由GraphOwnerCleanupPort清理。
 * 来源是否仍有效由查询应用层最终复验，投影本身没有联系人执行权限。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeGraphAdapter implements KnowledgeGraphPort {
    private static final String HEADER = """
            SELECT BIN_TO_UUID(owner_user_id) owner_id, BIN_TO_UUID(generation) generation,
                   source_digest,status,version,node_count,edge_count,
                   (SELECT COUNT(*) FROM knowledge_graph_node n WHERE n.owner_user_id=s.owner_user_id) total_nodes,
                   (SELECT COUNT(*) FROM knowledge_graph_edge e WHERE e.owner_user_id=s.owner_user_id) total_edges
            FROM knowledge_graph_snapshot s WHERE owner_user_id=UUID_TO_BIN(?)
            """;
    private static final String NODES = """
            SELECT BIN_TO_UUID(owner_user_id) owner_id, BIN_TO_UUID(generation) generation,
                   BIN_TO_UUID(node_id) node_id,node_type,BIN_TO_UUID(source_id) source_id,source_version
            FROM knowledge_graph_node WHERE owner_user_id=UUID_TO_BIN(?) AND generation=UUID_TO_BIN(?)
            ORDER BY node_id LIMIT 201
            """;
    private static final String EDGES = """
            SELECT BIN_TO_UUID(owner_user_id) owner_id,BIN_TO_UUID(generation) generation,
                   BIN_TO_UUID(edge_id) edge_id,BIN_TO_UUID(from_id) from_id,BIN_TO_UUID(to_id) to_id,relation_type
            FROM knowledge_graph_edge WHERE owner_user_id=UUID_TO_BIN(?) AND generation=UUID_TO_BIN(?)
            ORDER BY edge_id LIMIT 501
            """;
    private static final String LOCK_OWNER = """
            SELECT BIN_TO_UUID(id) id,status FROM app_user WHERE id=UUID_TO_BIN(?) FOR UPDATE
            """;
    private final JdbcTemplate jdbc;
    private final KnowledgeAccessPolicy access;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate publishTransaction;
    private final TransactionTemplate cleanupTransaction;

    /**
     * 创建不在构造期间访问数据库的存储器。
     * @param source 受控数据源
     * @param transactions 同源事务管理器
     * @param access 独立关系查询同意
     */
    public JdbcKnowledgeGraphAdapter(DataSource source, PlatformTransactionManager transactions,
            KnowledgeAccessPolicy access) {
        this(new JdbcTemplate(Objects.requireNonNull(source)), transactions, access);
        jdbc.setQueryTimeout(2);
        jdbc.setFetchSize(64);
    }

    // 只供模拟结果集和事务回滚测试。
    JdbcKnowledgeGraphAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions, KnowledgeAccessPolicy access) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.access = Objects.requireNonNull(access);
        readTransaction = transaction(transactions, true, TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        publishTransaction = transaction(transactions, false, TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        cleanupTransaction = transaction(transactions, false, TransactionDefinition.PROPAGATION_REQUIRED,
                TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** {@inheritDoc} */
    @Override public Optional<Projection> findByOwner(UUID ownerUserId) {
        Objects.requireNonNull(ownerUserId);
        return run(readTransaction, () -> read(ownerUserId));
    }

    /** {@inheritDoc} */
    @Override public Projection project(GraphSnapshot snapshot, long expectedVersion) {
        Objects.requireNonNull(snapshot);
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("INVALID_GRAPH_EXPECTED_VERSION");
        }
        return run(publishTransaction, () -> {
            UUID owner = snapshot.ownerUserId();
            lockOwner(owner, true);
            access.requireGraphConsent(owner);
            var prior = header(owner);
            if (prior.map(row -> row.version).orElse(0L) != expectedVersion) {
                throw new GraphProjectionException(Kind.CONFLICT);
            }
            // 锁内先移除边再节点，整体事务提交；失败回滚保留完整旧世代。
            jdbc.update("DELETE FROM knowledge_graph_edge WHERE owner_user_id=UUID_TO_BIN(?)", owner.toString());
            jdbc.update("DELETE FROM knowledge_graph_node WHERE owner_user_id=UUID_TO_BIN(?)", owner.toString());
            long next = expectedVersion + 1;
            byte[] digest = HexFormat.of().parseHex(snapshot.sourceDigest());
            int changed;
            if (prior.isEmpty()) {
                changed = jdbc.update("""
                        INSERT INTO knowledge_graph_snapshot
                        (owner_user_id,generation,source_digest,status,version,node_count,edge_count,updated_at)
                        VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),?,'ACTIVE',?,?,?,UTC_TIMESTAMP(3))
                        """, owner.toString(), snapshot.generation().toString(), digest, next,
                        snapshot.nodes().size(), snapshot.edges().size());
            } else {
                changed = jdbc.update("""
                        UPDATE knowledge_graph_snapshot SET generation=UUID_TO_BIN(?),source_digest=?,status='ACTIVE',
                        version=?,node_count=?,edge_count=?,updated_at=UTC_TIMESTAMP(3)
                        WHERE owner_user_id=UUID_TO_BIN(?) AND version=?
                        """, snapshot.generation().toString(), digest, next, snapshot.nodes().size(),
                        snapshot.edges().size(), owner.toString(), expectedVersion);
            }
            requireOne(changed);
            insertReferences(snapshot);
            // 实际回读和无额外世代检查，不能只相信INSERT受理返回值。
            var saved = read(owner).orElseThrow(() -> new GraphProjectionException(Kind.INVALID));
            if (saved.version() != next || !sameGraph(saved.snapshot(), snapshot)) {
                throw new GraphProjectionException(Kind.INVALID);
            }
            return saved;
        });
    }

    /** 完整图已在领域层限量；节点和边各一条参数化INSERT，仍在同一锁内事务回读复验。 */
    private void insertReferences(GraphSnapshot snapshot) {
        var nodeArgs=new java.util.ArrayList<Object>();
        for(var node:snapshot.nodes()) java.util.Collections.addAll(nodeArgs,
                snapshot.ownerUserId().toString(),snapshot.generation().toString(),node.id().toString(),
                node.type().name(),node.sourceId().toString(),node.sourceVersion());
        String nodeValues=String.join(",",java.util.Collections.nCopies(snapshot.nodes().size(),
                "(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,UUID_TO_BIN(?),?)"));
        int nodes=jdbc.update("INSERT INTO knowledge_graph_node (owner_user_id,generation,node_id,node_type,source_id,source_version) VALUES "+nodeValues,nodeArgs.toArray());
        if(nodes!=snapshot.nodes().size())throw new GraphProjectionException(Kind.INVALID);
        if(snapshot.edges().isEmpty())return;
        var edgeArgs=new java.util.ArrayList<Object>();
        for(var edge:snapshot.edges()) java.util.Collections.addAll(edgeArgs,
                snapshot.ownerUserId().toString(),snapshot.generation().toString(),edge.id().toString(),
                edge.fromId().toString(),edge.toId().toString(),edge.type().name());
        String edgeValues=String.join(",",java.util.Collections.nCopies(snapshot.edges().size(),
                "(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)"));
        int edges=jdbc.update("INSERT INTO knowledge_graph_edge (owner_user_id,generation,edge_id,from_id,to_id,relation_type) VALUES "+edgeValues,edgeArgs.toArray());
        if(edges!=snapshot.edges().size())throw new GraphProjectionException(Kind.INVALID);
    }

    /** {@inheritDoc} */
    @Override public boolean purge(UUID ownerUserId, long expectedVersion) {
        Objects.requireNonNull(ownerUserId);
        if (expectedVersion < 0) { throw new IllegalArgumentException("INVALID_GRAPH_EXPECTED_VERSION"); }
        return run(cleanupTransaction, () -> {
            lockOwner(ownerUserId, false);
            var prior = header(ownerUserId);
            if (prior.isPresent() && prior.get().version != expectedVersion) { return false; }
            jdbc.update("DELETE FROM knowledge_graph_edge WHERE owner_user_id=UUID_TO_BIN(?)", ownerUserId.toString());
            jdbc.update("DELETE FROM knowledge_graph_node WHERE owner_user_id=UUID_TO_BIN(?)", ownerUserId.toString());
            if (prior.isPresent()) {
                requireOne(jdbc.update("DELETE FROM knowledge_graph_snapshot WHERE owner_user_id=UUID_TO_BIN(?) AND version=?",
                        ownerUserId.toString(), expectedVersion));
            }
            if (count("knowledge_graph_snapshot", ownerUserId) != 0 || count("knowledge_graph_node", ownerUserId) != 0
                    || count("knowledge_graph_edge", ownerUserId) != 0) {
                throw new GraphProjectionException(Kind.INVALID);
            }
            return true;
        });
    }

    private Optional<Projection> read(UUID owner) {
        var control = header(owner);
        if (control.isEmpty()) {
            if (count("knowledge_graph_node", owner) != 0 || count("knowledge_graph_edge", owner) != 0) {
                throw new GraphProjectionException(Kind.INVALID);
            }
            return Optional.empty();
        }
        var row = control.get();
        try {
            var nodes = jdbc.query(NODES, (rs, index) -> new GraphNode(id(rs, "owner_id"), id(rs, "generation"),
                    id(rs, "node_id"), GraphNode.Type.valueOf(rs.getString("node_type")), id(rs, "source_id"),
                    nonnegative(rs, "source_version")), owner.toString(), row.generation.toString());
            var edges = jdbc.query(EDGES, (rs, index) -> new GraphEdge(id(rs, "owner_id"), id(rs, "generation"),
                    id(rs, "edge_id"), id(rs, "from_id"), id(rs, "to_id"),
                    GraphEdge.Type.valueOf(rs.getString("relation_type"))), owner.toString(), row.generation.toString());
            if (nodes.size() != row.nodes || edges.size() != row.edges) { throw new GraphProjectionException(Kind.INVALID); }
            return Optional.of(new Projection(new GraphSnapshot(owner, row.generation, row.digest, nodes, edges), row.version));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new GraphProjectionException(Kind.INVALID);
        }
    }

    private Optional<Header> header(UUID owner) {
        var rows = jdbc.query(HEADER, (rs, index) -> {
            byte[] digest = rs.getBytes("source_digest");
            long version = nonnegative(rs, "version");
            long nodes = nonnegative(rs, "node_count");
            long edges = nonnegative(rs, "edge_count");
            // Counts include every generation for this owner; keep orphan/extra-generation detection
            // in the same snapshot while avoiding two additional database round trips per read.
            if (nonnegative(rs, "total_nodes") != nodes || nonnegative(rs, "total_edges") != edges
                    || !owner.equals(id(rs, "owner_id")) || !"ACTIVE".equals(rs.getString("status")) || version < 1
                    || digest == null || digest.length != 32 || nodes < 1 || nodes > 200 || edges > 500) {
                throw new GraphProjectionException(Kind.INVALID);
            }
            return new Header(id(rs, "generation"), HexFormat.of().formatHex(digest), version, nodes, edges);
        }, owner.toString());
        if (rows.size() > 1) { throw new GraphProjectionException(Kind.INVALID); }
        return rows.stream().findFirst();
    }

    private void lockOwner(UUID owner, boolean activeRequired) {
        List<Boolean> owners = jdbc.query(LOCK_OWNER, (rs, index) -> owner.equals(id(rs, "id"))
                && "ACTIVE".equals(rs.getString("status")), owner.toString());
        if (owners.size() > 1 || (activeRequired && (owners.isEmpty() || !owners.get(0)))) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
    }

    // table仅来自本类三个固定字面量，绝不接收用户输入。
    private long count(String table, UUID owner) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_user_id=UUID_TO_BIN(?)",
                Long.class, owner.toString());
        if (value == null || value < 0) { throw new GraphProjectionException(Kind.INVALID); }
        return value;
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly,
            int propagation, int isolation) {
        var template = new TransactionTemplate(Objects.requireNonNull(manager));
        template.setReadOnly(readOnly); template.setPropagationBehavior(propagation);
        template.setIsolationLevel(isolation); template.setTimeout(2);
        return template;
    }

    private <T> T run(TransactionTemplate transaction, Supplier<T> work) {
        try { return Objects.requireNonNull(transaction.execute(status -> work.get())); }
        catch (DuplicateKeyException exception) { throw new GraphProjectionException(Kind.CONFLICT); }
        catch (DataAccessException | TransactionException exception) {
            throw new GraphProjectionException(Kind.STORAGE_UNAVAILABLE);
        }
    }

    private static UUID id(ResultSet rs, String column) throws SQLException {
        try { return UUID.fromString(rs.getString(column)); }
        catch (IllegalArgumentException | NullPointerException exception) { throw new GraphProjectionException(Kind.INVALID); }
    }

    private static long nonnegative(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull() || value < 0) { throw new GraphProjectionException(Kind.INVALID); }
        return value;
    }

    private static void requireOne(int changed) {
        if (changed != 1) { throw new GraphProjectionException(Kind.CONFLICT); }
    }

    private static boolean sameGraph(GraphSnapshot first, GraphSnapshot second) {
        return first.ownerUserId().equals(second.ownerUserId()) && first.generation().equals(second.generation())
                && first.sourceDigest().equals(second.sourceDigest())
                && new java.util.HashSet<>(first.nodes()).equals(new java.util.HashSet<>(second.nodes()))
                && new java.util.HashSet<>(first.edges()).equals(new java.util.HashSet<>(second.edges()));
    }

    private record Header(UUID generation, String digest, long version, long nodes, long edges) {
        @Override public String toString() { return "GraphHeader[redacted]"; }
    }
}
