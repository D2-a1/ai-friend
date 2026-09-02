package com.aifriend.retention.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 有界执行账号注销物理清理、交叉核验和内部告警。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class AccountClosureWorker {
    /** 脱敏记录账号注销失败的日志组件。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountClosureWorker.class);
    /** 账号注销清理稳定阶段码。 */
    private static final String FAILURE_STAGE = "ACCOUNT_CLOSURE_CLEANUP";
    /** 单个作业一次最多清理的主记录数。 */
    private static final int DATA_BATCH_SIZE = 50;
    /** 单次调度最多扫描的作业数。 */
    private static final int JOB_BATCH_SIZE = 10;
    /** 单次调度最多扫描的告警记录数。 */
    private static final int ALERT_BATCH_SIZE = 50;

    /** 注销作业、重试与告警事实端口。 */
    private final AccountClosureJobRepositoryPort jobRepositoryPort;
    /** 跨存储物理清理端口。 */
    private final AccountClosureCleanupPort cleanupPort;
    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建账号注销工作器。
     *
     * @param jobRepositoryPort 注销作业和告警端口
     * @param cleanupPort 跨存储清理端口
     * @param clock UTC 时钟
     */
    public AccountClosureWorker(
            AccountClosureJobRepositoryPort jobRepositoryPort,
            AccountClosureCleanupPort cleanupPort,
            Clock clock) {
        this.jobRepositoryPort = jobRepositoryPort;
        this.cleanupPort = cleanupPort;
        this.clock = clock;
    }

    /**
     * 处理一批注销作业；任何外部或数据库失败都只安排重试，不产生完成事实。
     *
     * @return 本次完成并通过锁内逐表复验的作业数量
     * @throws RuntimeException 当作业扫描、告警发布或重试事实写入失败时抛出
     */
    public int processReady() {
        Instant now = Instant.now(clock);
        jobRepositoryPort.publishDueAlerts(now, ALERT_BATCH_SIZE);
        int completed = 0;
        for (AccountClosureJob job : jobRepositoryPort.findReady(now, JOB_BATCH_SIZE)) {
            try {
                cleanupPort.cleanupBatch(job.ownerUserId(), DATA_BATCH_SIZE);
                if (cleanupPort.finalizeIfCleared(job.id(), job.ownerUserId(), now)) {
                    completed++;
                }
            } catch (RuntimeException exception) {
                long delaySeconds = Math.min(900L, 30L << Math.min(job.retryCount(), 5));
                Instant nextAttemptAt = now.plus(Duration.ofSeconds(delaySeconds));
                LOGGER.warn(
                        "可恢复后台任务失败 stage={} errorType={} retryCount={} nextAttemptAt={}",
                        FAILURE_STAGE,
                        exception.getClass().getSimpleName(),
                        job.retryCount(),
                        nextAttemptAt);
                jobRepositoryPort.markRetry(
                        job.id(), now, nextAttemptAt);
            }
        }
        return completed;
    }
}
