package com.aifriend.retrieval.application;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import com.aifriend.retrieval.application.KnowledgeCleanupAlertDeliveryPort.Event;
import com.aifriend.retrieval.application.KnowledgeCleanupRetryPort.AlertLevel;

/**
 * 每次最多两条告警的同步协调器，不创建线程、不持有跨网络事务、不重试未知提交。
 * 尚未装配到扫描器；通道及调度选择须独立完成。
 * @author Codex
 * @since 1.0.0
 */
public final class KnowledgeCleanupAlertDispatcher {
    private final KnowledgeCleanupRetryPort ledger;
    private final KnowledgeCleanupAlertDeliveryPort delivery;

    /**
     * 创建区分持久状态与外部送达确认的告警协调器。
     * @param ledger 自行提交短事务的持久账本
     * @param delivery 有界且独立验证送达的通道
     */
    public KnowledgeCleanupAlertDispatcher(KnowledgeCleanupRetryPort ledger, KnowledgeCleanupAlertDeliveryPort delivery) {
        this.ledger = Objects.requireNonNull(ledger);
        this.delivery = Objects.requireNonNull(delivery);
    }

    /**
     * 从持久账本重建进度，一类一次只确认最旧未送达序号，不跳过中间事件。
     * 传输失败不阻碍另一类别；账本失败停止本轮，下一轮须重新读状态而非补写。
     * @return 本轮确认成功的事件数，不表示全部积压清空
     */
    public int dispatch() {
        checkCancelled();
        KnowledgeCleanupRetryState state;
        try { state = Objects.requireNonNull(ledger.status()); }
        catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException unavailable) { throw unavailable(); }
        int acknowledged = 0;
        if (state.firstAcknowledged() < state.firstFailures()) {
            acknowledged += send(new Event(AlertLevel.FIRST_FAILURE, state.firstAcknowledged() + 1));
        }
        if (state.escalationAcknowledged() < state.escalations()) {
            acknowledged += send(new Event(AlertLevel.OVERDUE, state.escalationAcknowledged() + 1));
        }
        return acknowledged;
    }

    private int send(Event event) {
        checkCancelled();
        Optional<Event> receipt;
        try { receipt = delivery.deliverAndVerify(event); }
        catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException transportFailure) { checkCancelled(); return 0; }
        checkCancelled();
        if (receipt == null || receipt.isEmpty() || !event.equals(receipt.get())) { return 0; }
        try { return ledger.acknowledge(event.level(), event.sequence()) ? 1 : 0; }
        catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException commitUnknown) { throw unavailable(); }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) { throw new CancellationException("CLEANUP_ALERT_CANCELLED"); }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("KNOWLEDGE_CLEANUP_ALERT_LEDGER_UNAVAILABLE");
    }
}
