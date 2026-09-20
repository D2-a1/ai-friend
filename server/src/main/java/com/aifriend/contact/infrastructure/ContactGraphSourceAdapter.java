package com.aifriend.contact.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.knowledge.application.ContactGraphEvidencePort;
import com.aifriend.knowledge.application.GraphSourceException;
import com.aifriend.knowledge.application.GraphSourceException.Kind;
import com.aifriend.knowledge.domain.ContactDisplay;
import com.aifriend.knowledge.domain.GraphEdge;
import com.aifriend.knowledge.domain.GraphNode;
import com.aifriend.knowledge.domain.GraphSnapshot;
import com.aifriend.retrieval.domain.KnowledgeText;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 从既有亲友权威表读取私人图谱，不改写联系人，不加载实体或声学/定位材料。
 *
 * <p>每次来源读取使用新的只读一致性事务；显示解密后再开新事务复验，不能在旧
 * REPEATABLE_READ视图中声称看到了并发撤权。最终复验是本次读取的可见性边界，
 * 不承诺阻止该边界之后发生的撤权；后续会话重放仍须复验。由独立图谱开关控制装配。
 * @author Codex
 * @since 1.0.0
 */
public final class ContactGraphSourceAdapter implements ContactGraphEvidencePort {
    // 三类引用各自限量、按owner过滤；UNION ALL避免行去重掩盖坏数据。仅合并网络往返，事务不变。
    private static final String SOURCE_FACTS = """
            (SELECT 'USER' source_kind, BIN_TO_UUID(id) id, BIN_TO_UUID(id) owner_id,
                   NULL binding_id, version, status, TRUE valid, TRUE material_present,
                   NULL dialect_code, NULL dialect_package_version, NULL template_model_version, NULL threshold_version
             FROM app_user WHERE id = UUID_TO_BIN(?) LIMIT 2)
            UNION ALL
            (SELECT 'CONTACT' source_kind, BIN_TO_UUID(id) id, BIN_TO_UUID(owner_user_id) owner_id,
                   NULL binding_id, version, status,
                   (consented_at IS NOT NULL AND verified_at IS NOT NULL AND revoked_at IS NULL) valid,
                   TRUE material_present, NULL dialect_code, NULL dialect_package_version,
                   NULL template_model_version, NULL threshold_version
             FROM contact_binding WHERE owner_user_id = UUID_TO_BIN(?) AND status = 'ACTIVE'
             ORDER BY id LIMIT 200)
            UNION ALL
            (SELECT 'ALIAS' source_kind, BIN_TO_UUID(a.id) id, BIN_TO_UUID(a.owner_user_id) owner_id,
                   BIN_TO_UUID(a.binding_id) binding_id, a.version, a.status, (a.deleted_at IS NULL) valid,
                   (OCTET_LENGTH(a.display_text_cipher) > 0 AND OCTET_LENGTH(a.template_cipher) > 0
                    AND OCTET_LENGTH(a.template_digest) > 0) material_present,
                   a.dialect_code, a.dialect_package_version, a.template_model_version, a.threshold_version
            FROM contact_alias a JOIN contact_binding b ON b.id = a.binding_id
                 AND b.owner_user_id = a.owner_user_id
            WHERE a.owner_user_id = UUID_TO_BIN(?) AND a.status = 'ACTIVE' AND b.status = 'ACTIVE'
            ORDER BY a.id LIMIT 201)
            """;
    private static final String DISPLAY = """
            SELECT display_text_cipher, BIN_TO_UUID(id) id, BIN_TO_UUID(binding_id) binding_id, version
            FROM contact_alias WHERE owner_user_id = UUID_TO_BIN(?)
                  AND status = 'ACTIVE' AND deleted_at IS NULL AND (
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate freshStatement;
    private final KnowledgeAccessPolicy access;
    private final AcousticTemplatePort compatibility;
    private final SensitiveDataProtector protector;

    /**
     * 创建有界、只读的图谱源适配器，构造时不访问数据库或模型。
     * @param source 既有数据源
     * @param transactions 同一数据源的事务管理器
     * @param access 独立图谱用途策略
     * @param compatibility 只查询本地已验签版本注册表的端口
     * @param protector 既有显示称呼保护器
     */
    public ContactGraphSourceAdapter(DataSource source, PlatformTransactionManager transactions,
            KnowledgeAccessPolicy access, AcousticTemplatePort compatibility, SensitiveDataProtector protector) {
        this(new JdbcTemplate(Objects.requireNonNull(source)), transactions, access, compatibility, protector);
        jdbc.setQueryTimeout(2);
        jdbc.setFetchSize(64);
    }

    // 结果集模拟专用入口；不连接本机数据库。
    ContactGraphSourceAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            KnowledgeAccessPolicy access, AcousticTemplatePort compatibility, SensitiveDataProtector protector) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.access = Objects.requireNonNull(access);
        this.compatibility = Objects.requireNonNull(compatibility);
        this.protector = Objects.requireNonNull(protector);
        readTransaction = new TransactionTemplate(Objects.requireNonNull(transactions));
        readTransaction.setReadOnly(true);
        readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        readTransaction.setTimeout(2);
        freshStatement = new TransactionTemplate(transactions);
        // 只用于单条元数据SELECT；挂起外层事务，避免复用旧RR视图。
        freshStatement.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** {@inheritDoc} */
    @Override public GraphSnapshot snapshot(UUID ownerUserId) {
        requireOwner(ownerUserId);
        return freshSnapshot(ownerUserId);
    }

    /** {@inheritDoc} */
    @Override public List<ContactDisplay> displayCurrent(UUID ownerUserId, String expectedSourceDigest,
            List<UUID> contactIds) {
        return displayEvidence(ownerUserId, expectedSourceDigest, contactIds).displays();
    }

    /** {@inheritDoc} */
    @Override public Evidence displayEvidence(UUID ownerUserId, String expectedSourceDigest,
            List<UUID> contactIds) {
        requireOwner(ownerUserId);
        if (expectedSourceDigest == null || !expectedSourceDigest.matches("[a-f0-9]{64}")
                || contactIds == null || contactIds.size() > 20 || contactIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(contactIds).size() != contactIds.size()) {
            throw new IllegalArgumentException("INVALID_GRAPH_DISPLAY_REQUEST");
        }
        var requested = List.copyOf(contactIds);
        var displays = read(() -> {
            var current = facts(ownerUserId);
            requireDigest(current, expectedSourceDigest);
            var results = new ArrayList<ContactDisplay>();
            // 先验证全部候选及数量，再读取任一显示密文，避免错人时已有部分明文。
            for (UUID id : requested) {
                if (current.contacts.stream().noneMatch(row -> row.id.equals(id))) {
                    throw new GraphSourceException(Kind.SOURCE_CHANGED);
                }
                if (current.aliases.stream().filter(row -> row.parent.equals(id)).count() > 5) {
                    throw new GraphSourceException(Kind.GRAPH_LIMIT);
                }
            }
            var selectedAliases=current.aliases.stream().filter(row->requested.contains(row.parent)).toList();
            var displayNames=displayBatch(ownerUserId,selectedAliases);
            for (UUID id : requested) {
                var contact = current.contacts.stream().filter(row -> row.id.equals(id)).findFirst().orElseThrow();
                var names = current.aliases.stream().filter(row -> row.parent.equals(id))
                        .map(row -> displayNames.get(row.id)).toList();
                results.add(new ContactDisplay(id, contact.version, names));
            }
            return new DisplayRead(current.graph(ownerUserId), List.copyOf(results));
        });
        // 单条自动提交SELECT形成新的来源快照，不复用解密事务或调用方视图。
        var after = freshSnapshot(ownerUserId);
        if (!after.sourceDigest().equals(expectedSourceDigest)) {
            throw new GraphSourceException(Kind.SOURCE_CHANGED);
        }
        return new Evidence(displays.before(), after, displays.displays());
    }

    /** 元数据已在一个UNION语句中读取；前后同意均新读，无显示密文或多语句快照拼接。 */
    private GraphSnapshot freshSnapshot(UUID owner) {
        try {
            return Objects.requireNonNull(freshStatement.execute(status -> {
                var snapshot = facts(owner).graph(owner);
                access.requireGraphConsent(owner);
                return snapshot;
            }));
        } catch (DataAccessException failure) {
            throw new GraphSourceException(Kind.STORAGE_UNAVAILABLE);
        }
    }

    private record DisplayRead(GraphSnapshot before, List<ContactDisplay> displays) {
        @Override public String toString() { return "GraphDisplayRead[redacted]"; }
    }

    private Facts facts(UUID owner) {
        access.requireGraphConsent(owner);
        var rows = jdbc.query(SOURCE_FACTS, (row,index) -> {
            String kind=row.getString("source_kind");
            if ("USER".equals(kind) || "CONTACT".equals(kind)) {
                return new SourceRow(kind,new Reference(id(row,"id"),id(row,"owner_id"),version(row),
                        row.getString("status"),row.getBoolean("valid")),null);
            }
            if (!"ALIAS".equals(kind) || !owner.equals(id(row,"owner_id"))) {
                throw new GraphSourceException(Kind.SOURCE_INVALID);
            }
            return new SourceRow(kind,null,new AliasReference(id(row,"id"),id(row,"binding_id"),version(row),
                    row.getString("status"),row.getBoolean("valid"),row.getBoolean("material_present"),
                    row.getString("dialect_code"),row.getString("dialect_package_version"),
                    row.getString("template_model_version"),row.getString("threshold_version")));
        },owner.toString(),owner.toString(),owner.toString());
        var users=rows.stream().filter(row->"USER".equals(row.kind)).map(SourceRow::reference).toList();
        if (users.size() != 1 || !users.get(0).id.equals(owner) || !"ACTIVE".equals(users.get(0).status)) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        var contacts = new ArrayList<>(rows.stream().filter(row->"CONTACT".equals(row.kind)).map(SourceRow::reference).toList());
        if (contacts.size() >= 200) { throw new GraphSourceException(Kind.GRAPH_LIMIT); }
        var contactIds = new HashSet<UUID>();
        for (var row : contacts) {
            if (!owner.equals(row.parent) || !contactIds.add(row.id) || !row.valid || !"ACTIVE".equals(row.status)) {
                throw new GraphSourceException(Kind.SOURCE_INVALID);
            }
        }
        var allAliases=rows.stream().filter(row->"ALIAS".equals(row.kind)).map(SourceRow::alias).toList();
        if (allAliases.size() > 200) { throw new GraphSourceException(Kind.GRAPH_LIMIT); }
        var aliases = new ArrayList<Reference>();
        var aliasIds = new HashSet<UUID>();
        for (var row : allAliases) {
            if (!contactIds.contains(row.parent) || !aliasIds.add(row.id)) {
                throw new GraphSourceException(Kind.SOURCE_INVALID);
            }
            if ("DELETED".equals(row.status) || "INCOMPATIBLE".equals(row.status)) { continue; }
            if (!"ACTIVE".equals(row.status)) { throw new GraphSourceException(Kind.SOURCE_INVALID); }
            if (!row.valid) { continue; }
            if (!row.material || row.dialect == null || row.pack == null || row.model == null || row.threshold == null) {
                throw new GraphSourceException(Kind.SOURCE_INVALID);
            }
            if (compatibility.isCompatible(row.dialect, row.pack, row.model, row.threshold)) {
                aliases.add(new Reference(row.id, row.parent, row.version, "ACTIVE", true));
            }
        }
        if (1 + contacts.size() + aliases.size() > 200) { throw new GraphSourceException(Kind.GRAPH_LIMIT); }
        contacts.sort(Comparator.comparing(row -> row.id.toString()));
        aliases.sort(Comparator.comparing(row -> row.id.toString()));
        return new Facts(users.get(0), List.copyOf(contacts), List.copyOf(aliases));
    }

    private java.util.Map<UUID,String> displayBatch(UUID owner, List<Reference> aliases) {
        if(aliases.isEmpty())return java.util.Map.of();
        if(aliases.size()>100)throw new GraphSourceException(Kind.GRAPH_LIMIT);
        var expected=new java.util.HashMap<UUID,Reference>();
        var parameters=new ArrayList<Object>();parameters.add(owner.toString());
        var clauses=new ArrayList<String>();
        for(var alias:aliases) {
            if(expected.put(alias.id,alias)!=null)throw new GraphSourceException(Kind.SOURCE_INVALID);
            clauses.add("(binding_id = UUID_TO_BIN(?) AND id = UUID_TO_BIN(?) AND version = ?)");
            parameters.add(alias.parent.toString());parameters.add(alias.id.toString());parameters.add(alias.version);
        }
        var seen=new HashSet<UUID>();
        // 在RowMapper内部解密并finally清理，后续行映射或提交异常也不会遗留byte副本。
        var values = jdbc.query(DISPLAY+String.join(" OR ",clauses)+") LIMIT 101", (row, index) -> {
            UUID aliasId=id(row,"id");
            var alias=expected.get(aliasId);
            if(alias==null || !seen.add(aliasId) || !alias.parent.equals(id(row,"binding_id")) || alias.version!=version(row)) {
                throw new GraphSourceException(Kind.SOURCE_CHANGED);
            }
            byte[] encrypted = row.getBytes("display_text_cipher");
            byte[] plain = null;
            try {
                if (encrypted == null || encrypted.length > 512) { throw new IllegalArgumentException(); }
                plain = protector.decryptBytes(encrypted);
                if (plain == null || plain.length > 400) { throw new IllegalArgumentException(); }
                String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain)).toString();
                KnowledgeText.require(value, 100, 400, false);
                return java.util.Map.entry(aliasId,value);
            } catch (CharacterCodingException | IllegalArgumentException | IllegalStateException exception) {
                throw new GraphSourceException(Kind.DECRYPTION_FAILED);
            } finally {
                if (plain != null) { Arrays.fill(plain, (byte) 0); }
                if (encrypted != null) { Arrays.fill(encrypted, (byte) 0); }
            }
        }, parameters.toArray());
        if (values.size() != aliases.size() || !seen.equals(expected.keySet())) { throw new GraphSourceException(Kind.SOURCE_CHANGED); }
        var result=new java.util.HashMap<UUID,String>();
        values.forEach(entry->result.put(entry.getKey(),entry.getValue()));
        return java.util.Map.copyOf(result);
    }

    private <T> T read(Supplier<T> work) {
        try {
            return Objects.requireNonNull(readTransaction.execute(status -> work.get()));
        } catch (DataAccessException exception) {
            throw new GraphSourceException(Kind.STORAGE_UNAVAILABLE);
        }
    }

    private static void requireOwner(UUID owner) {
        if (owner == null) { throw new BusinessException(ErrorCode.AUTH_REQUIRED); }
    }

    private static void requireDigest(Facts facts, String expected) {
        if (!facts.digest().equals(expected)) { throw new GraphSourceException(Kind.SOURCE_CHANGED); }
    }

    private static UUID id(ResultSet row, String column) throws SQLException {
        try { return UUID.fromString(row.getString(column)); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new GraphSourceException(Kind.SOURCE_INVALID);
        }
    }

    private static long version(ResultSet row) throws SQLException {
        long value = row.getLong("version");
        if (row.wasNull() || value < 0) { throw new GraphSourceException(Kind.SOURCE_INVALID); }
        return value;
    }

    /** 合并语句内的有限行类型，不携带显示密文或声学材料。 */
    private record SourceRow(String kind,Reference reference,AliasReference alias) {
        @Override public String toString() { return "GraphSourceRow[redacted]"; }
    }

    /** 仅用于本次读取的权威引用，不携带显示文字。 */
    private record Reference(UUID id, UUID parent, long version, String status, boolean valid) {
        @Override public String toString() { return "GraphReference[redacted]"; }
    }

    /** 四项版本仅用于本地兼容性校验，不进入图谱。 */
    private record AliasReference(UUID id, UUID parent, long version, String status, boolean valid,
            boolean material, String dialect, String pack, String model, String threshold) {
        @Override public String toString() { return "GraphAliasReference[redacted]"; }
    }

    private record Facts(Reference user, List<Reference> contacts, List<Reference> aliases) {
        String digest() {
            var canonical = new StringBuilder("contact-graph-source-v1\n");
            append(canonical, "USER", user);
            contacts.forEach(row -> append(canonical, "CONTACT", row));
            aliases.forEach(row -> append(canonical, "ALIAS", row));
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
        }

        GraphSnapshot graph(UUID owner) {
            UUID generation = UUID.randomUUID();
            var nodes = new ArrayList<GraphNode>();
            var edges = new ArrayList<GraphEdge>();
            UUID root = nodeId(owner, "USER", owner);
            nodes.add(new GraphNode(owner, generation, root, GraphNode.Type.USER, owner, user.version));
            for (var row : contacts) {
                UUID node = nodeId(owner, "CONTACT", row.id);
                nodes.add(new GraphNode(owner, generation, node, GraphNode.Type.CONTACT, row.id, row.version));
                edges.add(new GraphEdge(owner, generation, nodeId(owner, "HAS_CONTACT", row.id),
                        root, node, GraphEdge.Type.HAS_CONTACT));
            }
            for (var row : aliases) {
                UUID node = nodeId(owner, "ALIAS", row.id);
                nodes.add(new GraphNode(owner, generation, node, GraphNode.Type.ALIAS, row.id, row.version));
                edges.add(new GraphEdge(owner, generation, nodeId(owner, "HAS_ALIAS", row.id),
                        nodeId(owner, "CONTACT", row.parent), node, GraphEdge.Type.HAS_ALIAS));
            }
            return new GraphSnapshot(owner, generation, digest(), nodes, edges);
        }

        private static void append(StringBuilder canonical, String type, Reference row) {
            canonical.append(type).append('|').append(row.id).append('|').append(row.parent)
                    .append('|').append(row.version).append('|').append(row.status).append('\n');
        }

        private static UUID nodeId(UUID owner, String type, UUID source) {
            return UUID.nameUUIDFromBytes(("contact-graph-node-v1|" + owner + "|" + type + "|" + source)
                    .getBytes(StandardCharsets.UTF_8));
        }

        @Override public String toString() { return "GraphFacts[redacted]"; }
    }
}
