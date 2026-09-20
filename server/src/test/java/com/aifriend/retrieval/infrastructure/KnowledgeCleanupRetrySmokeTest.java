package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.aifriend.retrieval.application.RetryingKnowledgeHistoryMaintenance;
import com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort.State;

/** 实际清理与重试适配器串联；两份SQL/事务夹具均模拟，无真实删除。 */
class KnowledgeCleanupRetrySmokeTest {
    @Test void failureRollbackBackoffRecoveryAndDrainedProofFormOneBoundedFlow() throws Exception {
        var data = new JdbcKnowledgeHistoryMaintenanceAdapterTest.Fixture();
        var ledger = new JdbcKnowledgeCleanupRetryAdapterTest.Fixture();
        data.chunk(1, false, false); data.version(11, false, false, false, false); data.failAt = 1;
        var retry = ledger.adapter();
        var service = new RetryingKnowledgeHistoryMaintenance(() -> {
            assertThat(ledger.lock.isHeldByCurrentThread()).isFalse();
            return data.adapter.sweep();
        }, retry);
        assertThatThrownBy(service::sweep).hasMessage("KNOWLEDGE_CLEANUP_BATCH_FAILED");
        assertThat(data.chunks).hasSize(1);
        assertThat(retry.status().pendingSince()).isEqualTo(ledger.now);
        int writes = data.writes.size();
        assertThat(service.sweep().state()).isEqualTo(State.DEFERRED);
        assertThat(data.writes).hasSize(writes);
        ledger.now = ledger.stored.nextAttempt(); data.failAt = 0;
        assertThat(service.sweep().state()).isEqualTo(State.PROGRESSED);
        assertThat(data.chunks).isEmpty();
        assertThat(retry.status().pendingSince()).isNotNull();
        ledger.now = ledger.stored.nextAttempt();
        assertThat(service.sweep().state()).isEqualTo(State.DRAINED);
        assertThat(retry.status().pendingSince()).isNull();
        assertThat(ledger.stored.firstFailures()).isEqualTo(1);
        assertThat(ledger.stored.firstAcknowledged()).isZero();
    }

    @Test void fifteenMinuteFailuresRetainEscalationAfterRecoveryWithNoAlertTransport() throws Exception {
        var data = new JdbcKnowledgeHistoryMaintenanceAdapterTest.Fixture();
        var ledger = new JdbcKnowledgeCleanupRetryAdapterTest.Fixture();
        data.chunk(1, false, false);
        var service = new RetryingKnowledgeHistoryMaintenance(data.adapter, ledger.adapter());
        for (int n = 0; n < 6; n++) {
            ledger.now = ledger.stored.nextAttempt(); data.failAt = data.writes.size() + 1;
            assertThatThrownBy(service::sweep).hasMessage("KNOWLEDGE_CLEANUP_BATCH_FAILED");
            assertThat(data.chunks).hasSize(1);
        }
        ledger.now = Instant.parse("2026-01-01T00:15:00Z");
        assertThat(ledger.adapter().status().escalations()).isEqualTo(1);
        assertThat(service.sweep().state()).isEqualTo(State.DEFERRED);
        data.failAt = 0; ledger.now = ledger.stored.nextAttempt();
        assertThat(service.sweep().state()).isEqualTo(State.PROGRESSED);
        ledger.now = ledger.stored.nextAttempt();
        assertThat(service.sweep().state()).isEqualTo(State.DRAINED);
        assertThat(ledger.stored.pendingSince()).isNull();
        assertThat(ledger.stored.escalations()).isEqualTo(1);
        assertThat(ledger.stored.escalationAcknowledged()).isZero();
    }
}
