package com.aifriend.assistant.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import com.aifriend.assistant.application.AssistantSessionRepository;
import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 独立短事务会话创建/读取；由AssistantSessionConfiguration条件装配，不运行迁移、不调用模型。
 * 创建幂等受同owner锁和唯一键保护；同源请求仓储复用包内短事务与会话CAS。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcAssistantSessionRepository implements AssistantSessionRepository {
    private static final String SELECT = """
            SELECT BIN_TO_UUID(id) id,BIN_TO_UUID(owner_user_id) owner_id,purpose,policy_version,state,version,
                   accepted_questions,encrypted_context,created_at,last_activity_at,expires_at,
                   BIN_TO_UUID(pending_request_id) pending_id,BIN_TO_UUID(lease_token) lease_id,
                   pending_started_at,pending_deadline,create_request_digest
            FROM assistant_session WHERE owner_user_id=UUID_TO_BIN(?)
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KnowledgeAccessPolicy access;
    private final AssistantRequestFingerprint fingerprints;
    private final AssistantContextCipher cipher;
    /**
     * 创建隔离存储器；构造零连接，调用由未来默认关闭的应用装配决定。
     * @param source 受控数据源
     * @param manager 同源事务管理器
     * @param access 独立关系同意检查
     * @param fingerprints 带秘密的幂等指纹
     * @param cipher 绑定上下文加密器
     */
    public JdbcAssistantSessionRepository(DataSource source, PlatformTransactionManager manager, KnowledgeAccessPolicy access,
            AssistantRequestFingerprint fingerprints, AssistantContextCipher cipher) {
        this(new JdbcTemplate(Objects.requireNonNull(source)),manager,access,fingerprints,cipher);
        jdbc.setQueryTimeout(2); jdbc.setFetchSize(2);
    }
    JdbcAssistantSessionRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, KnowledgeAccessPolicy access,
            AssistantRequestFingerprint fingerprints, AssistantContextCipher cipher) {
        this.jdbc=Objects.requireNonNull(jdbc); this.access=Objects.requireNonNull(access);
        this.fingerprints=Objects.requireNonNull(fingerprints); this.cipher=Objects.requireNonNull(cipher);
        transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transaction.setTimeout(2);
    }
    @Override public AssistantSession create(UUID owner, Purpose purpose, String key) {
        var fingerprint=fingerprints.creation(owner,key,purpose);
        return run(() -> {
            lockOwner(owner); authorize(owner,purpose);
            Instant now=now();
            var existing=rows(SELECT+" AND create_key_hash=? LIMIT 2",owner,
                    HexFormat.of().parseHex(fingerprint.keyHash()));
            if (!existing.isEmpty()) {
                Row row=single(existing);
                if (!row.createDigest.equals(fingerprint.requestDigest()) || row.session.purpose()!=purpose) {
                    throw failure(AssistantReason.IDEMPOTENCY_CONFLICT);
                }
                return usable(row.session,now);
            }
            var session=AssistantSession.create(UUID.randomUUID(),owner,purpose,policy(purpose),now);
            byte[] encrypted=cipher.encrypt(binding(session),session.conversation());
            try {
                int changed=jdbc.update("""
                        INSERT INTO assistant_session
                        (id,owner_user_id,purpose,create_key_hash,create_request_digest,policy_version,state,version,
                         accepted_questions,encrypted_context,created_at,last_activity_at,expires_at)
                        VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?, 'OPEN',0,0,?,?,?,?)
                        """,session.id().toString(),owner.toString(),purpose.name(),HexFormat.of().parseHex(fingerprint.keyHash()),
                        HexFormat.of().parseHex(fingerprint.requestDigest()),session.policyVersion(),encrypted,
                        Timestamp.from(now),Timestamp.from(now),Timestamp.from(session.expiresAt()));
                if (changed!=1) { throw failure(AssistantReason.STALE_REQUEST); }
                var stored=single(rows(SELECT+" AND id=UUID_TO_BIN(?) LIMIT 2",owner,session.id().toString()));
                if (!session.equals(stored.session) || !fingerprint.requestDigest().equals(stored.createDigest)) {
                    throw failure(AssistantReason.STORAGE_UNAVAILABLE);
                }
                return usable(stored.session,now());
            } finally { Arrays.fill(encrypted,(byte)0); }
        });
    }
    @Override public Optional<AssistantSession> find(UUID owner, UUID sessionId) {
        Objects.requireNonNull(owner); Objects.requireNonNull(sessionId);
        return run(() -> {
            lockOwner(owner);
            var rows=rows(SELECT+" AND id=UUID_TO_BIN(?) LIMIT 2",owner,sessionId.toString());
            if (rows.isEmpty()) { return Optional.empty(); }
            var session=single(rows).session;
            if (!session.id().equals(sessionId)) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
            authorize(owner,session.purpose()); return Optional.of(usable(session,now()));
        });
    }
    private List<Row> rows(String sql, UUID owner, Object selector) {
        var rows=jdbc.query(sql,(rs,index)->read(rs,owner),owner.toString(),selector);
        if (rows.size()>1) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
        return rows;
    }
    private Row read(ResultSet rs, UUID owner) throws SQLException {
        byte[] encrypted=null;
        try {
            UUID storedOwner=UUID.fromString(rs.getString("owner_id")), id=UUID.fromString(rs.getString("id"));
            if (!owner.equals(storedOwner)) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
            Purpose purpose=Purpose.valueOf(rs.getString("purpose"));
            // 在读取/解密私人上下文之前复验当前同意，避免撤权后先解密再拒绝。
            authorize(owner,purpose);
            String policy=rs.getString("policy_version");
            if (!policy(purpose).equals(policy)) { throw failure(AssistantReason.AUTH_CHANGED); }
            long version=number(rs,"version"), count=number(rs,"accepted_questions");
            if (count>4) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
            var state=AssistantSession.State.valueOf(rs.getString("state"));
            encrypted=rs.getBytes("encrypted_context");
            var context=state==AssistantSession.State.OPEN
                    ? cipher.decrypt(new AssistantContextCipher.Binding(owner,id,purpose,policy,version),encrypted)
                    : new AssistantConversation(purpose,List.of());
            if (state!=AssistantSession.State.OPEN && encrypted!=null) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
            String pendingId=rs.getString("pending_id"),token=rs.getString("lease_id");
            Timestamp started=rs.getTimestamp("pending_started_at"),deadline=rs.getTimestamp("pending_deadline");
            Optional<AssistantSession.Pending> pending=Optional.empty();
            if (pendingId!=null || token!=null || started!=null || deadline!=null) {
                pending=Optional.of(new AssistantSession.Pending(UUID.fromString(pendingId),UUID.fromString(token),
                        started.toInstant(),deadline.toInstant()));
            }
            byte[] digest=rs.getBytes("create_request_digest");
            if (digest==null || digest.length!=32) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
            return new Row(new AssistantSession(id,owner,purpose,policy,state,version,
                    rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_activity_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),(int)count,pending,context),HexFormat.of().formatHex(digest));
        } catch (IllegalArgumentException | NullPointerException corrupt) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
        finally { if (encrypted!=null) { Arrays.fill(encrypted,(byte)0); } }
    }
    private AssistantSession usable(AssistantSession session,Instant now) {
        if (now.isBefore(session.lastActivityAt())) { throw failure(AssistantReason.CLOCK_UNRELIABLE); }
        if (session.state()==AssistantSession.State.CLOSED) { throw failure(AssistantReason.SESSION_CLOSED); }
        if (session.state()==AssistantSession.State.EXPIRED || !now.isBefore(session.expiresAt())) {
            throw failure(AssistantReason.SESSION_EXPIRED);
        }
        return session;
    }
    private void lockOwner(UUID owner) {
        var active=jdbc.query("SELECT BIN_TO_UUID(id) id,status FROM app_user WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                (rs,index)->owner.toString().equals(rs.getString("id")) && "ACTIVE".equals(rs.getString("status")),owner.toString());
        if (active.size()!=1 || !active.get(0)) { throw new BusinessException(ErrorCode.AUTH_REQUIRED); }
    }
    private void authorize(UUID owner,Purpose purpose) {
        if (purpose==Purpose.CONTACT_GRAPH) { access.requireGraphConsent(owner); }
        // 公开本地摘录不外发；独立模型同意必须由问题执行/重放层依据冻结模式再次检查。
    }
    // 仅同包JDBC请求仓储使用；回调不能做网络/模型调用，不暴露给应用层。
    <T> T locked(UUID owner,UUID id,Function<AssistantSession,T> work) {
        Objects.requireNonNull(owner); Objects.requireNonNull(id); Objects.requireNonNull(work);
        return run(()->{
            lockOwner(owner);
            var existing=rows(SELECT+" AND id=UUID_TO_BIN(?) LIMIT 2",owner,id.toString());
            if (existing.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND); }
            var session=single(existing).session;
            if (!session.id().equals(id)) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
            return work.apply(usable(session,now()));
        });
    }
    // 必须在locked回调内，和请求账本使用同源JdbcTemplate/同一事务。
    AssistantSession advance(AssistantSession prior,AssistantSession next) {
        if (!prior.id().equals(next.id()) || !prior.owner().equals(next.owner()) || prior.purpose()!=next.purpose()
                || !prior.policyVersion().equals(next.policyVersion()) || !prior.createdAt().equals(next.createdAt())
                || prior.version()==Long.MAX_VALUE || next.version()!=prior.version()+1) {
            throw failure(AssistantReason.STALE_REQUEST);
        }
        var pending=next.pending().orElse(null);
        byte[] encrypted=next.state()==AssistantSession.State.OPEN ? cipher.encrypt(binding(next),next.conversation()) : null;
        try {
            int count=jdbc.update("""
                    UPDATE assistant_session SET state=?,version=?,accepted_questions=?,encrypted_context=?,last_activity_at=?,expires_at=?,
                        pending_request_id=UUID_TO_BIN(?),lease_token=UUID_TO_BIN(?),pending_started_at=?,pending_deadline=?
                    WHERE owner_user_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?) AND version=? AND state='OPEN'
                    """,next.state().name(),next.version(),next.acceptedQuestions(),encrypted,
                    Timestamp.from(next.lastActivityAt()),Timestamp.from(next.expiresAt()),
                    pending==null?null:pending.requestId().toString(),pending==null?null:pending.leaseToken().toString(),
                    pending==null?null:Timestamp.from(pending.startedAt()),pending==null?null:Timestamp.from(pending.deadline()),
                    next.owner().toString(),next.id().toString(),prior.version());
            if (count!=1) { throw failure(AssistantReason.STALE_REQUEST); }
            var stored=single(rows(SELECT+" AND id=UUID_TO_BIN(?) LIMIT 2",next.owner(),next.id().toString())).session;
            if (!next.equals(stored)) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
            return stored;
        } finally { if (encrypted!=null) { Arrays.fill(encrypted,(byte)0); } }
    }
    Instant now() {
        Timestamp value=jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",Timestamp.class);
        if (value==null || value.getNanos()%1_000_000!=0) { throw failure(AssistantReason.CLOCK_UNRELIABLE); }
        return value.toInstant();
    }
    private static String policy(Purpose purpose) {
        return purpose==Purpose.CONTACT_GRAPH ? KnowledgeAccessPolicy.GRAPH_POLICY : KnowledgeAccessPolicy.MODEL_POLICY;
    }
    private static AssistantContextCipher.Binding binding(AssistantSession session) {
        return new AssistantContextCipher.Binding(session.owner(),session.id(),session.purpose(),session.policyVersion(),session.version());
    }
    private static long number(ResultSet rs,String column) throws SQLException {
        long value=rs.getLong(column); if (rs.wasNull() || value<0) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); } return value;
    }
    private static Row single(List<Row> rows) {
        if (rows.size()!=1) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); } return rows.get(0);
    }
    private <T> T run(Supplier<T> work) {
        boolean[] bodyCompleted={false};
        try { return Objects.requireNonNull(transaction.execute(status->{ T result=work.get(); bodyCompleted[0]=true; return result; })); }
        catch (DuplicateKeyException conflict) { throw failure(AssistantReason.IDEMPOTENCY_CONFLICT); }
        catch (DataAccessException failure) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
        catch (TransactionException failure) {
            throw failure(bodyCompleted[0] ? AssistantReason.COMMIT_UNCERTAIN : AssistantReason.STORAGE_UNAVAILABLE);
        }
    }
    private static AssistantSessionException failure(AssistantReason reason) { return new AssistantSessionException(reason); }
    private record Row(AssistantSession session,String createDigest) {
        @Override public String toString() { return "StoredAssistantSession[redacted]"; }
    }
}
