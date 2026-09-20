package com.aifriend.assistant.domain;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantTurnRequest.State;

/** 请求状态/迁移静态约束，不模拟MySQL真实事务已经通过。 */
class AssistantTurnRequestTest {
    private static final UUID ID = new UUID(0,1), OWNER = new UUID(0,2), SESSION = new UUID(0,3), TOKEN = new UUID(0,4);
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static AssistantTurnRequest pending() {
        return new AssistantTurnRequest(ID,OWNER,SESSION,Purpose.PUBLIC_KNOWLEDGE,"a".repeat(64),"b".repeat(64),
                1,TOKEN,NOW,NOW.plusSeconds(8),NOW.plusSeconds(300),State.PROCESSING,null,null);
    }
    @Test void sameKeySameBodyIsValidButChangedBodyConflicts() {
        pending().requireSameRequest("b".repeat(64));
        assertThatThrownBy(() -> pending().requireSameRequest("c".repeat(64))).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> pending().requireSameRequest(null)).hasMessage("IDEMPOTENCY_CONFLICT");
    }
    @Test void deadlineBeforeAtAndAfterAndNoRenewal() {
        var completed = pending().complete(TOKEN,1,new byte[29],NOW.plusMillis(7999));
        assertThat(completed.state()).isEqualTo(State.COMPLETED); assertThat(completed.resultVersion()).isEqualTo(2L);
        assertThat(completed.deadline()).isEqualTo(pending().deadline());
        assertThat(completed.leaseToken()).isEqualTo(TOKEN);
        for (long ms : new long[]{8000,8001}) {
            assertThatThrownBy(() -> pending().complete(TOKEN,1,new byte[29],NOW.plusMillis(ms))).hasMessage("RESULT_STALE");
        }
    }
    @Test void tokenVersionAndAlreadyCompletedCannotCommitAgain() {
        assertThatThrownBy(() -> pending().complete(ID,1,new byte[29],NOW)).hasMessage("RESULT_STALE");
        assertThatThrownBy(() -> pending().complete(TOKEN,2,new byte[29],NOW)).hasMessage("RESULT_STALE");
        var done = pending().complete(TOKEN,1,new byte[29],NOW);
        assertThatThrownBy(() -> done.complete(TOKEN,1,new byte[29],NOW)).hasMessage("RESULT_STALE");
    }
    @Test void expiryIsTerminalAndCannotBeReclaimed() {
        assertThatThrownBy(() -> pending().expire(NOW.plusMillis(7999))).hasMessage("STALE_REQUEST");
        var expired = pending().expire(NOW.plusSeconds(8));
        assertThat(expired.state()).isEqualTo(State.EXPIRED);
        assertThat(expired.expire(NOW.plusSeconds(9))).isSameAs(expired);
        assertThatThrownBy(() -> expired.complete(TOKEN,1,new byte[29],NOW.plusSeconds(9))).hasMessage("RESULT_STALE");
        assertThatThrownBy(() -> expired.resultForRevalidation(NOW.plusSeconds(9))).hasMessage("RESULT_STALE");
    }
    @Test void replayAllowedAfterExecutionDeadlineButOnlyBeforeResultExpiry() {
        var done = pending().complete(TOKEN,1,new byte[29],NOW);
        assertThat(done.resultForRevalidation(NOW.plusSeconds(9))).hasSize(29);
        assertThat(done.resultForRevalidation(NOW.plusMillis(299999))).hasSize(29);
        for (long ms : new long[]{300000,300001}) {
            assertThatThrownBy(() -> done.resultForRevalidation(NOW.plusMillis(ms))).hasMessage("RESULT_STALE");
        }
    }
    @Test void revocationAndClosureDropResultAndRejectLateWorker() {
        for (boolean revoked : new boolean[]{true,false}) {
            var stopped = pending().complete(TOKEN,1,new byte[29],NOW).invalidate(revoked,NOW.plusSeconds(1));
            assertThat(stopped.encryptedResult()).isNull(); assertThat(stopped.resultVersion()).isNull();
            assertThat(stopped.state()).isEqualTo(revoked ? State.INVALIDATED : State.CANCELLED);
            assertThatThrownBy(() -> stopped.resultForRevalidation(NOW.plusSeconds(1))).hasMessage("RESULT_STALE");
            assertThatThrownBy(() -> stopped.complete(TOKEN,1,new byte[29],NOW.plusSeconds(1))).hasMessage("RESULT_STALE");
        }
    }
    @Test void resultArraysAreDefensiveAndBounded() {
        byte[] original = new byte[29]; original[0]=1;
        var done = pending().complete(TOKEN,1,original,NOW); original[0]=9;
        byte[] read = done.encryptedResult(); read[0]=8;
        assertThat(done.resultForRevalidation(NOW)[0]).isEqualTo((byte)1);
        assertThat(pending().complete(TOKEN,1,new byte[AssistantTurnRequest.MAX_RESULT_BYTES],NOW)).isNotNull();
        for (byte[] bad : new byte[][]{null,new byte[28],new byte[AssistantTurnRequest.MAX_RESULT_BYTES+1]}) {
            assertThatThrownBy(() -> pending().complete(TOKEN,1,bad,NOW)).hasMessage("INVALID_TURN_RESULT");
        }
    }
    @Test void clockRollbackAndSubmillisecondInputFailClosed() {
        assertThatThrownBy(() -> pending().complete(TOKEN,1,new byte[29],NOW.minusMillis(1))).hasMessage("CLOCK_UNRELIABLE");
        assertThatThrownBy(() -> pending().expire(NOW.plusNanos(1))).hasMessage("INVALID_TURN_TIME");
    }
    @Test void corruptedPersistentStateIsRejected() {
        assertThatThrownBy(() -> new AssistantTurnRequest(ID,OWNER,SESSION,Purpose.PUBLIC_KNOWLEDGE,"a".repeat(64),"b".repeat(64),
                1,TOKEN,NOW,NOW.plusSeconds(9),NOW.plusSeconds(300),State.PROCESSING,null,null)).hasMessage("INVALID_TURN_REQUEST");
        assertThatThrownBy(() -> new AssistantTurnRequest(ID,OWNER,SESSION,Purpose.PUBLIC_KNOWLEDGE,"a".repeat(64),"b".repeat(64),
                1,TOKEN,NOW,NOW.plusSeconds(8),NOW.plusSeconds(300),State.COMPLETED,3L,new byte[29])).hasMessage("INVALID_TURN_RESULT");
        assertThatThrownBy(() -> new AssistantTurnRequest(ID,OWNER,SESSION,Purpose.PUBLIC_KNOWLEDGE,"a".repeat(64),"b".repeat(64),
                1,TOKEN,NOW,NOW.plusSeconds(8),NOW.plusSeconds(300),State.CANCELLED,2L,new byte[29])).hasMessage("UNEXPECTED_TURN_RESULT");
    }
    @Test void migrationHasScopedKeysForeignKeyAndBoundedCipherColumns() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V38__assistant_sessions.sql"));
        assertThat(sql).contains("UNIQUE KEY uq_assistant_creation (owner_user_id,create_key_hash)",
                "UNIQUE KEY uq_assistant_request_key (owner_user_id,session_id,request_key_hash)",
                "FOREIGN KEY (owner_user_id,session_id,purpose)","REFERENCES assistant_session (owner_user_id,id,purpose)",
                "accepted_questions BETWEEN 0 AND 4","INTERVAL 8 SECOND","INTERVAL 300 SECOND","INTERVAL 900 SECOND",
                "OCTET_LENGTH(encrypted_context) BETWEEN 29 AND 49180","OCTET_LENGTH(encrypted_result) BETWEEN 29 AND 196636",
                "encrypted_result IS NULL","encrypted_context IS NULL");
        assertThat(sql).doesNotContain("ON DELETE CASCADE","DROP TABLE","request_text","alias_text","audio_object");
    }
    @Test void defaultLogOnlyContainsFixedState() {
        assertThat(pending().toString()).doesNotContain(ID.toString(),OWNER.toString(),TOKEN.toString(),"a".repeat(64));
    }
}
