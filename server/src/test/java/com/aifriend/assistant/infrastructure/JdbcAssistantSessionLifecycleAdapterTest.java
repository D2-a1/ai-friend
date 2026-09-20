package com.aifriend.assistant.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.aifriend.assistant.application.AssistantSessionLifecyclePort;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantSession.State;
import com.aifriend.consent.domain.ConsentType;

/** 真实适配器与行映射，模拟两表、数据库时间和回滚；不证明MySQL锁/SQL语法。 */
class JdbcAssistantSessionLifecycleAdapterTest {
    private static final UUID OWNER=new UUID(0,1), OTHER=new UUID(0,2), ID=new UUID(0,3);
    private static final Instant NOW=Instant.parse("2026-09-10T00:00:00Z");
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final List<String> statements=new ArrayList<>();
    private Map<UUID,Row> rows=new LinkedHashMap<>(), before;
    private Instant time=NOW;
    private int writes, failWrite, propagation=TransactionDefinition.PROPAGATION_REQUIRES_NEW;
    private boolean inactive, wrongOwner, casMiss, retainRequest, commitLost, renewAfterScan;
    private JdbcAssistantSessionLifecycleAdapter adapter;

    @BeforeEach void setup() throws Exception {
        rows.put(ID,new Row(OWNER,Purpose.PUBLIC_KNOWLEDGE));
        when(manager.getTransaction(any())).thenAnswer(call->{
            TransactionDefinition tx=call.getArgument(0);
            assertThat(tx.getPropagationBehavior()).isEqualTo(propagation);
            assertThat(tx.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(tx.getTimeout()).isEqualTo(2);
            before=copy(rows); return new SimpleTransactionStatus();
        });
        doAnswer(call->{ rows=copy(before); return null; }).when(manager).rollback(any());
        doAnswer(call->{ if(commitLost) { commitLost=false; throw new TransactionSystemException("PRIVATE_COMMIT_DETAIL"); } return null; })
                .when(manager).commit(any());
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0); statements.add(sql); RowMapper<?> mapper=call.getArgument(1);
            List<Object> result=new ArrayList<>();
            if(sql.contains("FROM app_user")) {
                assertThat(sql).contains("FOR UPDATE");
                var rs=mock(ResultSet.class);
                when(rs.getString("id")).thenReturn(wrongOwner?OTHER.toString():call.getArgument(2));
                when(rs.getString("status")).thenReturn(inactive?"DELETING":"ACTIVE");
                result.add(mapper.mapRow(rs,0));
            } else if(sql.contains("ORDER BY expires_at")) {
                int limit=call.getArgument(2);
                assertThat(sql).contains("state='OPEN'", "expires_at<=UTC_TIMESTAMP(3)", "LIMIT ?");
                for(var entry:rows.entrySet()) {
                    if(result.size()==limit) break;
                    if(entry.getValue().state.equals("OPEN") && !entry.getValue().expiry.isAfter(time))
                        result.add(mapper.mapRow(resultSet(entry.getKey(),entry.getValue()),result.size()));
                }
                if(renewAfterScan) rows.get(ID).expiry=time.plusSeconds(300);
            } else {
                assertThat(sql).contains("FROM assistant_session WHERE owner_user_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)")
                        .doesNotContain("encrypted_context", "encrypted_result");
                UUID owner=UUID.fromString(call.getArgument(2)), id=UUID.fromString(call.getArgument(3));
                Row row=rows.get(id);
                if(row!=null && row.owner.equals(owner)) result.add(mapper.mapRow(resultSet(id,row),0));
            }
            return result;
        });
        when(jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(3)"),eq(Timestamp.class))).thenAnswer(call->Timestamp.from(time));
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0); statements.add(sql);
            UUID owner=UUID.fromString(call.getArgument(2)); String value=call.getArgument(3);
            return rows.entrySet().stream().filter(e->scoped(e,owner,value,sql))
                    .filter(e->sql.contains("assistant_turn_request")?e.getValue().requestDirty:e.getValue().dirty).count();
        });
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0); statements.add(sql);
            if(++writes==failWrite) throw new DataAccessResourceFailureException("PRIVATE_SQL_DETAIL");
            boolean purpose=sql.contains("purpose=?"), request=sql.contains("UPDATE assistant_turn_request");
            int ownerIndex=purpose?1:request?2:3;
            UUID owner=UUID.fromString(call.getArgument(ownerIndex)); String value=call.getArgument(ownerIndex+1);
            int changed=0;
            for(var entry:rows.entrySet()) {
                Row row=entry.getValue(); if(!scoped(entry,owner,value,sql)) continue;
                if(request) {
                    if(!row.requestDirty) continue;
                    changed++; if(!retainRequest) { row.requestDirty=false; row.requestState=purpose?"INVALIDATED":call.getArgument(1); }
                } else {
                    if(!row.dirty) continue;
                    if(!purpose && (casMiss || !row.state.equals("OPEN") || row.version!=(long)call.getArgument(5))) continue;
                    row.state=purpose?"CLOSED":call.getArgument(1);
                    row.version=purpose?(row.version==Long.MAX_VALUE?row.version:row.version+1):(long)call.getArgument(2);
                    row.dirty=false; changed++;
                }
            }
            return changed;
        });
        adapter=new JdbcAssistantSessionLifecycleAdapter(jdbc,manager);
    }

    @Test void closeClearsBothTablesPreservesTombstonesAndRepeatsWithoutVersionBump() {
        var result=adapter.close(OWNER,ID,4);
        assertThat(result.state()).isEqualTo(State.CLOSED); assertThat(result.version()).isEqualTo(5);
        assertThat(rows.get(ID).requestState).isEqualTo("CANCELLED");
        assertThat(rows.get(ID).requestDirty).isFalse(); assertThat(rows.get(ID).dirty).isFalse();
        assertThat(adapter.close(OWNER,ID,4)).isEqualTo(result);
        assertThat(statements).noneMatch(s->s.startsWith("DELETE") || s.contains("consent") || s.contains("request_digest="));
        assertThat(rows).hasSize(1);
    }
    @Test void exactExpiryClosesAsExpired() {
        rows.get(ID).expiry=NOW;
        assertThat(adapter.close(OWNER,ID,4).state()).isEqualTo(State.EXPIRED);
        assertThat(rows.get(ID).requestState).isEqualTo("EXPIRED");
    }
    @Test void wrongVersionStopsBeforeWrites() {
        assertThatThrownBy(()->adapter.close(OWNER,ID,3)).hasMessage("VERSION_MISMATCH"); assertThat(writes).isZero();
    }
    @Test void wrongOwnerAndInactiveAccountCannotClose() {
        assertThatThrownBy(()->adapter.close(OTHER,ID,4)).isInstanceOf(RuntimeException.class);
        inactive=true; assertThatThrownBy(()->adapter.close(OWNER,ID,4)).isInstanceOf(RuntimeException.class);
        assertThat(writes).isZero();
    }
    @Test void bothWriteFailuresRollbackBothTablesAndDoNotRetry() {
        for(int point=1;point<=2;point++) {
            failWrite=point; writes=0;
            assertThatThrownBy(()->adapter.close(OWNER,ID,4)).hasMessage("STORAGE_UNAVAILABLE").hasNoCause();
            assertThat(writes).isEqualTo(point); assertUnchanged();
        }
    }
    @Test void casMissRollsBackEarlierResultErasure() {
        casMiss=true; assertThatThrownBy(()->adapter.close(OWNER,ID,4)).hasMessage("STALE_REQUEST"); assertUnchanged();
    }
    @Test void residualCipherFailsVerificationAndRollsBack() {
        retainRequest=true; assertThatThrownBy(()->adapter.close(OWNER,ID,4)).hasMessage("CLEANUP_PENDING"); assertUnchanged();
    }
    @Test void commitUncertaintyNeverRetriesAndRepeatRecoversExistingClosedState() {
        commitLost=true;
        assertThatThrownBy(()->adapter.close(OWNER,ID,4)).hasMessage("COMMIT_UNCERTAIN").hasNoCause();
        assertThat(writes).isEqualTo(2);
        assertThat(adapter.close(OWNER,ID,4).version()).isEqualTo(5);
    }
    @Test void revokeIsOwnerAndPurposeScopedJoinsTransactionAndIgnoresAccountState() {
        propagation=TransactionDefinition.PROPAGATION_REQUIRED; inactive=true;
        UUID privateId=new UUID(0,4), otherId=new UUID(0,5);
        rows.put(privateId,new Row(OWNER,Purpose.CONTACT_GRAPH)); rows.put(otherId,new Row(OTHER,Purpose.PUBLIC_KNOWLEDGE));
        assertThat(adapter.revoke(OWNER,Purpose.PUBLIC_KNOWLEDGE)).isEqualTo(2);
        assertThat(rows.get(ID).requestState).isEqualTo("INVALIDATED");
        assertThat(rows.get(privateId).dirty).isTrue(); assertThat(rows.get(otherId).requestDirty).isTrue();
        assertThat(adapter.revoke(OWNER,Purpose.PUBLIC_KNOWLEDGE)).isZero();
    }
    @Test void revokeFailureRollsBackBothTables() {
        propagation=TransactionDefinition.PROPAGATION_REQUIRED; failWrite=2;
        assertThatThrownBy(()->adapter.revoke(OWNER,Purpose.PUBLIC_KNOWLEDGE)).hasMessage("STORAGE_UNAVAILABLE"); assertUnchanged();
    }
    @Test void saturatedVersionDoesNotPreventRevocationErasure() {
        propagation=TransactionDefinition.PROPAGATION_REQUIRED; rows.get(ID).version=Long.MAX_VALUE;
        assertThat(adapter.revoke(OWNER,Purpose.PUBLIC_KNOWLEDGE)).isEqualTo(2);
        assertThat(rows.get(ID).dirty).isFalse(); assertThat(rows.get(ID).version).isEqualTo(Long.MAX_VALUE);
    }
    @Test void expiredScanRechecksFreshExpiryUnderOwnerLock() {
        rows.get(ID).expiry=NOW; renewAfterScan=true;
        assertThat(adapter.expireBatch(1)).isZero(); assertThat(writes).isZero(); assertUnchanged();
    }
    @Test void expiryBatchIsBoundedAndTerminalRowsAreNotRepeated() {
        rows.get(ID).expiry=NOW; Row second=new Row(OWNER,Purpose.CONTACT_GRAPH); second.expiry=NOW;
        rows.put(new UUID(0,4),second);
        assertThat(adapter.expireBatch(1)).isEqualTo(1);
        assertThat(adapter.expireBatch(1)).isEqualTo(1);
        assertThat(adapter.expireBatch(1)).isZero();
        assertThat(rows.values()).allMatch(r->r.state.equals("EXPIRED") && !r.dirty && !r.requestDirty);
    }
    @Test void invalidBatchAndCancellationDoNotAccessStorage() {
        assertThatIllegalArgumentException().isThrownBy(()->adapter.expireBatch(0));
        assertThatIllegalArgumentException().isThrownBy(()->adapter.expireBatch(65));
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(()->adapter.expireBatch(1)).isInstanceOf(java.util.concurrent.CancellationException.class); }
        finally { Thread.interrupted(); }
        verifyNoInteractions(jdbc,manager);
    }
    @Test void backwardOrSubmillisecondDatabaseClockCannotClose() {
        time=NOW.minusSeconds(2);
        assertThatThrownBy(()->adapter.close(OWNER,ID,4)).hasMessage("CLOCK_UNRELIABLE");
        time=NOW.plusNanos(1);
        assertThatThrownBy(()->adapter.close(OWNER,ID,4)).hasMessage("CLOCK_UNRELIABLE"); assertThat(writes).isZero();
    }
    @Test void invalidMetadataAndWrongOwnerLockFailWithoutContentReads() {
        rows.get(ID).state="SECRET_INVALID_STATE";
        assertThatThrownBy(()->adapter.close(OWNER,ID,4)).hasMessage("STORAGE_UNAVAILABLE").hasNoCause();
        wrongOwner=true;
        assertThatThrownBy(()->adapter.close(OWNER,ID,4)).hasMessage("STORAGE_UNAVAILABLE"); assertThat(writes).isZero();
    }
    @Test void consentHandlerRoutesOnlyTwoPurposesAndPropagatesFailure() {
        var port=mock(AssistantSessionLifecyclePort.class); var handler=new AssistantConsentRevocationHandler(port);
        for(var type:ConsentType.values()) handler.cleanup(OWNER,type,NOW);
        verify(port).revoke(OWNER,Purpose.PUBLIC_KNOWLEDGE); verify(port).revoke(OWNER,Purpose.CONTACT_GRAPH); verifyNoMoreInteractions(port);
        when(port.revoke(OWNER,Purpose.CONTACT_GRAPH)).thenThrow(new IllegalStateException("CLEANUP_FAILED"));
        assertThatThrownBy(()->handler.cleanup(OWNER,ConsentType.CONTACT_GRAPH,NOW)).hasMessage("CLEANUP_FAILED");
    }
    private void assertUnchanged() {
        assertThat(rows.get(ID).state).isEqualTo("OPEN"); assertThat(rows.get(ID).version).isEqualTo(4);
        assertThat(rows.get(ID).dirty).isTrue(); assertThat(rows.get(ID).requestDirty).isTrue();
    }
    private static boolean scoped(Map.Entry<UUID,Row> entry,UUID owner,String value,String sql) {
        return entry.getValue().owner.equals(owner) && (sql.contains("purpose=?")?
                entry.getValue().purpose.name().equals(value):entry.getKey().toString().equals(value));
    }
    private static ResultSet resultSet(UUID id,Row row) throws Exception {
        var rs=mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(id.toString()); when(rs.getString("owner_id")).thenReturn(row.owner.toString());
        when(rs.getString("state")).thenReturn(row.state); when(rs.getLong("version")).thenReturn(row.version);
        when(rs.getString("purpose")).thenReturn(row.purpose.name());
        when(rs.getTimestamp("last_activity_at")).thenReturn(Timestamp.from(NOW.minusSeconds(1)));
        when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(row.expiry)); return rs;
    }
    private static Map<UUID,Row> copy(Map<UUID,Row> source) {
        Map<UUID,Row> result=new LinkedHashMap<>(); source.forEach((id,row)->result.put(id,row.copy())); return result;
    }
    private static final class Row {
        final UUID owner; final Purpose purpose; String state="OPEN", requestState="COMPLETED";
        long version=4; boolean dirty=true,requestDirty=true; Instant expiry=NOW.plusSeconds(60);
        Row(UUID owner,Purpose purpose) { this.owner=owner; this.purpose=purpose; }
        Row copy() { var r=new Row(owner,purpose); r.state=state; r.requestState=requestState; r.version=version;
            r.dirty=dirty; r.requestDirty=requestDirty; r.expiry=expiry; return r; }
    }
}
