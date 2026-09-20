package com.aifriend.assistant.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.aifriend.assistant.application.AssistantTurnRepository;
import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantTurnRequest.State;

/**
 * 同owner锁下会话CAS与问题账本同事务，所有外部处理都在本适配器之外。
 * 由AssistantSessionConfiguration条件装配，不做后台清理。结果密文绑定/来源/模型同意由应用层负责。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcAssistantTurnRepository implements AssistantTurnRepository {
    private static final String SELECT="""
            SELECT BIN_TO_UUID(id) id,BIN_TO_UUID(owner_user_id) owner_id,BIN_TO_UUID(session_id) session_id,
                purpose,request_key_hash,request_digest,admitted_version,BIN_TO_UUID(lease_token) lease_id,
                state,result_version,encrypted_result,created_at,deadline,expires_at
            FROM assistant_turn_request WHERE owner_user_id=UUID_TO_BIN(?) AND session_id=UUID_TO_BIN(?)
            """;
    private final JdbcTemplate jdbc;
    private final JdbcAssistantSessionRepository sessions;
    private final AssistantRequestFingerprint fingerprints;
    /**
     * 创建同源事务适配器，构造不会访问数据库。
     * @param source 受控数据源
     * @param manager 同源事务管理器
     * @param access 用途授权
     * @param fingerprints 请求HMAC
     * @param cipher 绑定上下文编解码器
     */
    public JdbcAssistantTurnRepository(DataSource source,PlatformTransactionManager manager,KnowledgeAccessPolicy access,
            AssistantRequestFingerprint fingerprints,AssistantContextCipher cipher) {
        this(new JdbcTemplate(Objects.requireNonNull(source)),manager,access,fingerprints,cipher);
        jdbc.setQueryTimeout(2); jdbc.setFetchSize(2);
    }
    JdbcAssistantTurnRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,KnowledgeAccessPolicy access,
            AssistantRequestFingerprint fingerprints,AssistantContextCipher cipher) {
        this.jdbc=Objects.requireNonNull(jdbc); this.fingerprints=Objects.requireNonNull(fingerprints);
        sessions=new JdbcAssistantSessionRepository(jdbc,manager,access,fingerprints,cipher);
    }
    @Override public Receipt admit(UUID owner,UUID sessionId,AssistantQuestion question) {
        var fingerprint=fingerprints.question(owner,sessionId,question);
        return sessions.locked(owner,sessionId,session->{
            if (session.purpose()!=question.payload().purpose()) { throw failure(AssistantReason.INVALID_REQUEST); }
            var prior=read(session,"request_key_hash=?",HexFormat.of().parseHex(fingerprint.keyHash()));
            if (prior.isPresent()) {
                prior.get().requireSameRequest(fingerprint.requestDigest());
                return settle(session,prior.get());
            }
            Instant now=sessions.now();
            UUID requestId=UUID.randomUUID(), token=UUID.randomUUID();
            var next=session.begin(question.expectedVersion(),requestId,token,now);
            var pending=next.pending().orElseThrow();
            var request=new AssistantTurnRequest(requestId,owner,sessionId,session.purpose(),fingerprint.keyHash(),
                    fingerprint.requestDigest(),next.version(),token,now,pending.deadline(),session.createdAt().plusSeconds(900),State.PROCESSING,null,null);
            sessions.advance(session,next);
            int inserted=jdbc.update("""
                    INSERT INTO assistant_turn_request (id,owner_user_id,session_id,purpose,request_key_hash,request_digest,
                        admitted_version,lease_token,state,created_at,deadline,expires_at)
                    VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,UUID_TO_BIN(?),'PROCESSING',?,?,?)
                    """,request.id().toString(),owner.toString(),sessionId.toString(),request.purpose().name(),
                    HexFormat.of().parseHex(request.keyHash()),HexFormat.of().parseHex(request.requestDigest()),request.admittedVersion(),
                    token.toString(),Timestamp.from(now),Timestamp.from(request.deadline()),Timestamp.from(request.expiresAt()));
            one(inserted);
            var stored=requireRequest(next,requestId);
            requireSameRow(request,stored);
            var receipt=settle(next,stored);
            return new Receipt(receipt.session(),receipt.request(),receipt.request().state()==State.PROCESSING);
        });
    }
    @Override public Optional<Receipt> findRequest(UUID owner,UUID sessionId,String key) {
        String hash=fingerprints.questionKey(owner,sessionId,key);
        return sessions.locked(owner,sessionId,session->read(session,"request_key_hash=?",HexFormat.of().parseHex(hash))
                .map(request->settle(session,request)));
    }
    @Override public Optional<Receipt> findRequestById(UUID owner,UUID sessionId,UUID requestId) {
        Objects.requireNonNull(requestId);
        return sessions.locked(owner,sessionId,session->read(session,"id=UUID_TO_BIN(?)",requestId.toString())
                .map(request->{
                    if(!requestId.equals(request.id())) throw failure(AssistantReason.STORAGE_UNAVAILABLE);
                    return settle(session,request);
                }));
    }
    @Override public Receipt complete(UUID owner,UUID sessionId,UUID requestId,long version,UUID token,
            byte[] encryptedResult,Optional<AssistantConversation.Turn> history) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(token); Objects.requireNonNull(history);
        return sessions.locked(owner,sessionId,session->{
            var request=requireRequest(session,requestId);
            if (request.admittedVersion()!=version || !request.leaseToken().equals(token)) { throw failure(AssistantReason.RESULT_STALE); }
            // 未知提交的原worker再来时只能读取已保存内容，绝不覆盖已提交答案或再次追加历史。
            var prior=settle(session,request);
            if (prior.request().state()!=State.PROCESSING) { return prior; }
            Instant now=sessions.now();
            if (!now.isBefore(request.deadline())) { return settle(session,request); }
            var completed=request.complete(token,version,encryptedResult,now);
            var next=session.complete(version,token,history,now);
            updateRequest(completed,State.PROCESSING);
            sessions.advance(session,next);
            var stored=requireRequest(next,requestId); requireSameRow(completed,stored);
            // DB操作耗时也计入原截止；迟到提交整体回滚，不发回超时答案。
            Instant end=sessions.now();
            if (end.isBefore(now)) { throw failure(AssistantReason.CLOCK_UNRELIABLE); }
            if (!end.isBefore(request.deadline())) { throw failure(AssistantReason.RESULT_STALE); }
            return new Receipt(next,stored,false);
        });
    }
    private Receipt settle(AssistantSession session,AssistantTurnRequest request) {
        Instant now=sessions.now();
        if (now.isBefore(session.lastActivityAt()) || now.isBefore(request.createdAt())) { throw failure(AssistantReason.CLOCK_UNRELIABLE); }
        if (!now.isBefore(session.expiresAt()) || !now.isBefore(request.expiresAt())) { throw failure(AssistantReason.RESULT_STALE); }
        if (request.state()!=State.PROCESSING) { return new Receipt(session,request,false); }
        var pending=session.pending().orElseThrow(()->failure(AssistantReason.STORAGE_UNAVAILABLE));
        if (session.version()!=request.admittedVersion() || !pending.requestId().equals(request.id())
                || !pending.leaseToken().equals(request.leaseToken()) || !pending.startedAt().equals(request.createdAt())
                || !pending.deadline().equals(request.deadline())) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
        if (now.isBefore(request.deadline())) { return new Receipt(session,request,false); }
        var expired=request.expire(now);
        var next=session.timeout(session.version(),request.leaseToken(),now);
        updateRequest(expired,State.PROCESSING); sessions.advance(session,next);
        var stored=requireRequest(next,request.id()); requireSameRow(expired,stored);
        return new Receipt(next,stored,false);
    }
    private void updateRequest(AssistantTurnRequest request,State expected) {
        byte[] result=request.encryptedResult();
        try {
            one(jdbc.update("""
                    UPDATE assistant_turn_request SET state=?,result_version=?,encrypted_result=?
                    WHERE owner_user_id=UUID_TO_BIN(?) AND session_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                        AND admitted_version=? AND lease_token=UUID_TO_BIN(?) AND state=?
                    """,request.state().name(),request.resultVersion(),result,request.owner().toString(),request.sessionId().toString(),
                    request.id().toString(),request.admittedVersion(),request.leaseToken().toString(),expected.name()));
        } finally { if (result!=null) { Arrays.fill(result,(byte)0); } }
    }
    // predicate仅来自本类两个固定字面量，不允许调用方提供SQL。
    private Optional<AssistantTurnRequest> read(AssistantSession session,String predicate,Object selector) {
        var rows=jdbc.query(SELECT+" AND "+predicate+" LIMIT 2",(rs,index)->map(rs,session),
                session.owner().toString(),session.id().toString(),selector);
        if (rows.size()>1) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
        return rows.stream().findFirst();
    }
    private AssistantTurnRequest requireRequest(AssistantSession session,UUID id) {
        var request=read(session,"id=UUID_TO_BIN(?)",id.toString()).orElseThrow(()->failure(AssistantReason.STORAGE_UNAVAILABLE));
        if (!request.id().equals(id)) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); } return request;
    }
    private AssistantTurnRequest map(ResultSet rs,AssistantSession session) throws SQLException {
        byte[] encrypted=null;
        try {
            UUID owner=UUID.fromString(rs.getString("owner_id")), sessionId=UUID.fromString(rs.getString("session_id"));
            Purpose purpose=Purpose.valueOf(rs.getString("purpose"));
            if (!session.owner().equals(owner) || !session.id().equals(sessionId) || session.purpose()!=purpose) {
                throw failure(AssistantReason.STORAGE_UNAVAILABLE);
            }
            long admitted=rs.getLong("admitted_version"); if (rs.wasNull()) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
            long version=rs.getLong("result_version"); Long resultVersion=rs.wasNull()?null:version;
            encrypted=rs.getBytes("encrypted_result");
            var result=new AssistantTurnRequest(UUID.fromString(rs.getString("id")),owner,sessionId,purpose,
                    hex(rs.getBytes("request_key_hash")),hex(rs.getBytes("request_digest")),admitted,UUID.fromString(rs.getString("lease_id")),
                    rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("deadline").toInstant(),rs.getTimestamp("expires_at").toInstant(),
                    State.valueOf(rs.getString("state")),resultVersion,encrypted);
            if (admitted>session.version() || (resultVersion!=null && resultVersion>session.version())
                    || result.createdAt().isBefore(session.createdAt())
                    || !result.expiresAt().equals(session.createdAt().plusSeconds(900))) {
                throw failure(AssistantReason.STORAGE_UNAVAILABLE);
            }
            return result;
        } catch (IllegalArgumentException | NullPointerException corrupt) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
        finally { if (encrypted!=null) { Arrays.fill(encrypted,(byte)0); } }
    }
    private static String hex(byte[] value) {
        if (value==null || value.length!=32) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); } return HexFormat.of().formatHex(value);
    }
    private static void requireSameRow(AssistantTurnRequest expected,AssistantTurnRequest actual) {
        // record的byte[]默认equals按引用比较；显式比较全部字段以及密文内容。
        byte[] a=expected.encryptedResult(), b=actual.encryptedResult();
        try {
            var left=new AssistantTurnRequest(expected.id(),expected.owner(),expected.sessionId(),expected.purpose(),expected.keyHash(),expected.requestDigest(),
                    expected.admittedVersion(),expected.leaseToken(),expected.createdAt(),expected.deadline(),expected.expiresAt(),State.PROCESSING,null,null);
            var right=new AssistantTurnRequest(actual.id(),actual.owner(),actual.sessionId(),actual.purpose(),actual.keyHash(),actual.requestDigest(),
                    actual.admittedVersion(),actual.leaseToken(),actual.createdAt(),actual.deadline(),actual.expiresAt(),State.PROCESSING,null,null);
            if (!left.equals(right) || expected.state()!=actual.state() || !Objects.equals(expected.resultVersion(),actual.resultVersion())
                    || !Arrays.equals(a,b)) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
        } finally { if(a!=null) Arrays.fill(a,(byte)0); if(b!=null) Arrays.fill(b,(byte)0); }
    }
    private static void one(int count) { if (count!=1) { throw failure(AssistantReason.STALE_REQUEST); } }
    private static AssistantSessionException failure(AssistantReason reason) { return new AssistantSessionException(reason); }
}
