package com.aifriend.assistant.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.aifriend.assistant.application.AssistantSessionLifecyclePort;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantSession.State;
import com.aifriend.shared.error.*;

/**
 * 不读取/解密内容的助手会话清理。相同owner锁与受理/提交串行，写后复验；不注册bean或定时器。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcAssistantSessionLifecycleAdapter implements AssistantSessionLifecyclePort {
    private static final String DIRTY_SESSION="(state='OPEN' OR encrypted_context IS NOT NULL OR pending_request_id IS NOT NULL OR lease_token IS NOT NULL OR pending_started_at IS NOT NULL OR pending_deadline IS NOT NULL)";
    private static final String DIRTY_REQUEST="(state IN ('PROCESSING','COMPLETED') OR encrypted_result IS NOT NULL OR result_version IS NOT NULL)";
    private static final String METADATA="""
            SELECT BIN_TO_UUID(id) id,BIN_TO_UUID(owner_user_id) owner_id,purpose,state,version,last_activity_at,expires_at
            FROM assistant_session WHERE owner_user_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?) LIMIT 2
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final TransactionTemplate joined;
    /**
     * 构造不访问数据库；运行时装配与迁移验收另行进行。
     * @param source 同源数据源
     * @param manager 同源事务管理器
     */
    public JdbcAssistantSessionLifecycleAdapter(DataSource source,PlatformTransactionManager manager) {
        this(new JdbcTemplate(Objects.requireNonNull(source)),manager); jdbc.setQueryTimeout(2); jdbc.setFetchSize(64);
    }
    JdbcAssistantSessionLifecycleAdapter(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=Objects.requireNonNull(jdbc);
        independent=transaction(manager,TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        joined=transaction(manager,TransactionDefinition.PROPAGATION_REQUIRED);
    }
    /** {@inheritDoc} */
    @Override public Closed close(UUID owner,UUID sessionId,long expectedVersion) {
        Objects.requireNonNull(owner); Objects.requireNonNull(sessionId);
        if(expectedVersion<0) throw new IllegalArgumentException("INVALID_CLOSE_VERSION");
        return run(independent,()->{
            lockOwner(owner,true);
            var prior=metadata(owner,sessionId).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
            Instant now=now(); if(now.isBefore(prior.lastActivity())) throw fail(AssistantReason.CLOCK_UNRELIABLE);
            if(prior.state()==State.OPEN && prior.version()!=expectedVersion) throw fail(AssistantReason.VERSION_MISMATCH);
            State target=prior.state()==State.OPEN ? (now.isBefore(prior.expires())?State.CLOSED:State.EXPIRED) : prior.state();
            return terminate(owner,prior,target);
        });
    }
    /** {@inheritDoc} */
    @Override public int revoke(UUID owner,Purpose purpose) {
        Objects.requireNonNull(owner); Objects.requireNonNull(purpose);
        return run(joined,()->{
            lockOwner(owner,false);
            String scope="owner_user_id=UUID_TO_BIN(?) AND purpose=?";
            int requests=nonnegative(jdbc.update("UPDATE assistant_turn_request SET state='INVALIDATED',encrypted_result=NULL,result_version=NULL WHERE "+scope+" AND "+DIRTY_REQUEST,
                    owner.toString(),purpose.name()));
            int sessions=nonnegative(jdbc.update("""
                    UPDATE assistant_session SET state='CLOSED',encrypted_context=NULL,pending_request_id=NULL,lease_token=NULL,
                        pending_started_at=NULL,pending_deadline=NULL,
                        version=CASE WHEN version<9223372036854775807 THEN version+1 ELSE version END
                    WHERE owner_user_id=UUID_TO_BIN(?) AND purpose=? AND
                    """+DIRTY_SESSION,owner.toString(),purpose.name()));
            zero("assistant_turn_request",scope+" AND "+DIRTY_REQUEST,owner.toString(),purpose.name());
            zero("assistant_session",scope+" AND "+DIRTY_SESSION,owner.toString(),purpose.name());
            return Math.addExact(requests,sessions);
        });
    }
    /** {@inheritDoc} */
    @Override public int expireBatch(int limit) {
        if(limit<1 || limit>64) throw new IllegalArgumentException("INVALID_CLEANUP_LIMIT");
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("ASSISTANT_CLEANUP_CANCELLED");
        var candidates=run(independent,()->jdbc.query("""
                SELECT BIN_TO_UUID(owner_user_id) owner_id,BIN_TO_UUID(id) id FROM assistant_session
                WHERE state='OPEN' AND expires_at<=UTC_TIMESTAMP(3) ORDER BY expires_at,id LIMIT ?
                """,(rs,index)->new Candidate(UUID.fromString(rs.getString("owner_id")),UUID.fromString(rs.getString("id"))),limit));
        if(candidates.size()>limit || new HashSet<>(candidates).size()!=candidates.size()) throw fail(AssistantReason.STORAGE_UNAVAILABLE);
        int changed=0;
        for(var candidate:candidates) {
            if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("ASSISTANT_CLEANUP_CANCELLED");
            boolean done=run(independent,()->{
                lockOwner(candidate.owner(),false);
                var existing=metadata(candidate.owner(),candidate.id());
                if(existing.isEmpty() || existing.get().state()!=State.OPEN) return false;
                var prior=existing.get(); Instant now=now();
                if(now.isBefore(prior.lastActivity())) throw fail(AssistantReason.CLOCK_UNRELIABLE);
                if(now.isBefore(prior.expires())) return false;
                terminate(candidate.owner(),prior,State.EXPIRED); return true;
            });
            if(done) changed++;
        }
        return changed;
    }
    /** 两表清除同事务，CAS失败不得只留下清空了结果的半关闭状态。 */
    private Closed terminate(UUID owner,Metadata prior,State target) {
        String scope="owner_user_id=UUID_TO_BIN(?) AND session_id=UUID_TO_BIN(?)";
        String requestState=target==State.EXPIRED?"EXPIRED":"CANCELLED";
        nonnegative(jdbc.update("UPDATE assistant_turn_request SET state=?,encrypted_result=NULL,result_version=NULL WHERE "+scope+" AND "+DIRTY_REQUEST,
                requestState,owner.toString(),prior.id().toString()));
        long next=prior.version();
        if(prior.state()==State.OPEN) {
            if(next==Long.MAX_VALUE) throw fail(AssistantReason.RESOURCE_LIMIT);
            next++;
            int updated=jdbc.update("""
                    UPDATE assistant_session SET state=?,version=?,encrypted_context=NULL,pending_request_id=NULL,lease_token=NULL,
                        pending_started_at=NULL,pending_deadline=NULL
                    WHERE owner_user_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?) AND version=? AND state='OPEN'
                    """,target.name(),next,owner.toString(),prior.id().toString(),prior.version());
            if(updated!=1) throw fail(AssistantReason.STALE_REQUEST);
        }
        var current=metadata(owner,prior.id()).orElseThrow(()->fail(AssistantReason.STORAGE_UNAVAILABLE));
        if(current.state()!=target || current.version()!=next || !current.expires().equals(prior.expires())
                || !current.lastActivity().equals(prior.lastActivity()) || current.purpose()!=prior.purpose()) throw fail(AssistantReason.STORAGE_UNAVAILABLE);
        zero("assistant_turn_request",scope+" AND "+DIRTY_REQUEST,owner.toString(),prior.id().toString());
        zero("assistant_session","owner_user_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?) AND "+DIRTY_SESSION,owner.toString(),prior.id().toString());
        return new Closed(prior.id(),next,target,prior.purpose(),prior.expires());
    }
    /** 只选元数据，不把损坏密文或已撤用途同意作为清理前置条件。 */
    private Optional<Metadata> metadata(UUID owner,UUID id) {
        var rows=jdbc.query(METADATA,(rs,index)->{
            if(!owner.toString().equals(rs.getString("owner_id")) || !id.toString().equals(rs.getString("id"))) throw fail(AssistantReason.STORAGE_UNAVAILABLE);
            long version=rs.getLong("version"); if(rs.wasNull() || version<0) throw fail(AssistantReason.STORAGE_UNAVAILABLE);
            String state=rs.getString("state");
            Timestamp activity=rs.getTimestamp("last_activity_at"), expiry=rs.getTimestamp("expires_at");
            if(state==null || activity==null || expiry==null) throw fail(AssistantReason.STORAGE_UNAVAILABLE);
            try { return new Metadata(id,State.valueOf(state),version,activity.toInstant(),expiry.toInstant(),Purpose.valueOf(rs.getString("purpose"))); }
            catch(IllegalArgumentException | NullPointerException failure) { throw fail(AssistantReason.STORAGE_UNAVAILABLE); }
        },owner.toString(),id.toString());
        if(rows.size()>1) throw fail(AssistantReason.STORAGE_UNAVAILABLE);
        return rows.stream().findFirst();
    }
    /** 撤权/过期可清理已删除账号的遗留内容；用户主动关闭仍须ACTIVE。 */
    private void lockOwner(UUID owner,boolean requireActive) {
        var rows=jdbc.query("SELECT BIN_TO_UUID(id) id,status FROM app_user WHERE id=UUID_TO_BIN(?) FOR UPDATE",(rs,index)->{
            if(!owner.toString().equals(rs.getString("id"))) throw fail(AssistantReason.STORAGE_UNAVAILABLE);
            return "ACTIVE".equals(rs.getString("status"));
        },owner.toString());
        if(rows.size()>1) throw fail(AssistantReason.STORAGE_UNAVAILABLE);
        if(requireActive && (rows.size()!=1 || !rows.get(0))) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
    }
    /** 固定表与条件仅来自本类，不接收用户SQL。 */
    private void zero(String table,String scope,Object...args) {
        Long value=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+scope,Long.class,args);
        if(value==null || value!=0) throw fail(AssistantReason.CLEANUP_PENDING);
    }
    private Instant now() {
        Timestamp value=jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",Timestamp.class);
        if(value==null || value.getNanos()%1_000_000!=0) throw fail(AssistantReason.CLOCK_UNRELIABLE);
        return value.toInstant();
    }
    private static int nonnegative(int value) { if(value<0) throw fail(AssistantReason.STORAGE_UNAVAILABLE); return value; }
    private static TransactionTemplate transaction(PlatformTransactionManager manager,int propagation) {
        var result=new TransactionTemplate(Objects.requireNonNull(manager)); result.setPropagationBehavior(propagation);
        result.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); result.setTimeout(2); return result;
    }
    private static <T> T run(TransactionTemplate transaction,Supplier<T> body) {
        boolean[] completed={false};
        try { return Objects.requireNonNull(transaction.execute(status->{T result=body.get();completed[0]=true;return result;})); }
        catch(DataAccessException failure) { throw fail(AssistantReason.STORAGE_UNAVAILABLE); }
        catch(TransactionException failure) { throw fail(completed[0]?AssistantReason.COMMIT_UNCERTAIN:AssistantReason.STORAGE_UNAVAILABLE); }
    }
    private static AssistantSessionException fail(AssistantReason reason) { return new AssistantSessionException(reason); }
    private record Candidate(UUID owner,UUID id) { @Override public String toString() { return "CleanupCandidate[redacted]"; } }
    private record Metadata(UUID id,State state,long version,Instant lastActivity,Instant expires,Purpose purpose) { @Override public String toString() { return "SessionMetadata[redacted]"; } }
}
