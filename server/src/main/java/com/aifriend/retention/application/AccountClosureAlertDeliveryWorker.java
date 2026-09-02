package com.aifriend.retention.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aifriend.shared.security.DigestService;

/**
 * 有界物化并在数据库事务外投递账号注销匿名告警。
 *
 * <p>提交受理和最终送达严格分开：供应商流水号先以短事务加密保存，后续调度只查询
 * 该流水号，不会把一次异步受理误报为送达。供应商明确失败时才清除旧流水号并重新
 * 提交；查询异常只退避且保留流水号。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class AccountClosureAlertDeliveryWorker {

    /** 脱敏记录告警投递失败的日志组件。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(
            AccountClosureAlertDeliveryWorker.class);
    /** 账号注销告警投递稳定阶段码。 */
    private static final String FAILURE_STAGE = "ACCOUNT_CLOSURE_ALERT_DELIVERY";
    /** 单次最多物化的源告警数。 */
    private static final int MATERIALIZE_BATCH_SIZE = 50;
    /** 单次最多调用外部通道的投递数。 */
    private static final int DELIVERY_BATCH_SIZE = 50;
    /** 异步供应商状态复验间隔。 */
    private static final Duration VERIFICATION_INTERVAL = Duration.ofSeconds(30);
    /** 告警投递状态仓储。 */
    private final AccountClosureAlertDeliveryRepositoryPort repositoryPort;
    /** 外部通知通道。 */
    private final AccountClosureAlertDeliveryPort deliveryPort;
    /** SHA-256 摘要组件。 */
    private final DigestService digestService;
    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建注销告警投递工作器。
     *
     * @param repositoryPort 告警投递状态仓储
     * @param deliveryPort 外部通知通道
     * @param digestService SHA-256 摘要组件
     * @param clock UTC 时钟
     */
    public AccountClosureAlertDeliveryWorker(
            AccountClosureAlertDeliveryRepositoryPort repositoryPort,
            AccountClosureAlertDeliveryPort deliveryPort,
            DigestService digestService,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.deliveryPort = deliveryPort;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 物化源事件并逐项推进当前到期告警。
     *
     * @return 本次取得稳定送达回执并完成确认的责任组投递数
     * @throws RuntimeException 源 Outbox 物化或数据库状态写入失败时抛出
     */
    public int processReady() {
        Instant now = Instant.now(clock);
        repositoryPort.materializePending(now, MATERIALIZE_BATCH_SIZE);
        int delivered = 0;
        for (AccountClosureAlertDelivery delivery
                : repositoryPort.listReady(now, DELIVERY_BATCH_SIZE)) {
            try {
                delivered += delivery.hasProviderReference()
                        ? verifySubmitted(delivery, now)
                        : submitPending(delivery, now);
            } catch (RuntimeException exception) {
                Instant nextAttemptAt = nextRetryAt(delivery.retryCount(), now);
                LOGGER.warn(
                        "可恢复后台任务失败 stage={} errorType={} retryCount={} nextAttemptAt={}",
                        FAILURE_STAGE,
                        exception.getClass().getSimpleName(),
                        delivery.retryCount(),
                        nextAttemptAt);
                repositoryPort.markRetry(
                        delivery.deliveryId(),
                        now,
                        nextAttemptAt);
            }
        }
        return delivered;
    }

    private int submitPending(AccountClosureAlertDelivery delivery, Instant now) {
        AccountClosureAlertSubmission submission = deliveryPort.submit(delivery);
        repositoryPort.confirmSubmitted(
                delivery.deliveryId(),
                submission.providerReference(),
                now,
                now.plus(VERIFICATION_INTERVAL));
        return 0;
    }

    private int verifySubmitted(AccountClosureAlertDelivery delivery, Instant now) {
        AccountClosureAlertDeliveryVerification verification = deliveryPort.verify(delivery);
        if (verification.status() == AccountClosureAlertVerificationStatus.PENDING) {
            repositoryPort.scheduleVerification(
                    delivery.deliveryId(), now, now.plus(VERIFICATION_INTERVAL));
            return 0;
        }
        if (verification.status() == AccountClosureAlertVerificationStatus.FAILED) {
            repositoryPort.resetFailedSubmission(
                    delivery.deliveryId(),
                    now,
                    nextRetryAt(delivery.retryCount(), now));
            return 0;
        }
        byte[] proof = verification.receiptProof();
        try {
            byte[] receiptHash = digestService.sha256(proof);
            return repositoryPort.confirmDelivered(
                    delivery.deliveryId(), receiptHash, now) ? 1 : 0;
        } finally {
            Arrays.fill(proof, (byte) 0);
        }
    }

    private Instant nextRetryAt(int retryCount, Instant now) {
        long delaySeconds = Math.min(900L, 30L << Math.min(retryCount, 5));
        return now.plusSeconds(delaySeconds);
    }
}
