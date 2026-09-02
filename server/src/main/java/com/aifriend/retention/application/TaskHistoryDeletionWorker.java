package com.aifriend.retention.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 有界执行任务历史清除并在逐存储复验后完成作业。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TaskHistoryDeletionWorker {
    /** 脱敏记录任务历史删除失败的日志组件。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(
            TaskHistoryDeletionWorker.class);
    /** 任务历史删除稳定阶段码。 */
    private static final String FAILURE_STAGE = "TASK_HISTORY_DELETION";
    private static final int BATCH_SIZE = 50;
    private final TaskHistoryDeletionRepositoryPort repositoryPort;
    private final TaskHistoryStoragePort storagePort;
    private final Clock clock;

    /**
     * 创建异步清除工作器。
     *
     * @param repositoryPort 作业持久化端口
     * @param storagePort 目标存储清除端口
     * @param clock UTC 时钟
     */
    public TaskHistoryDeletionWorker(TaskHistoryDeletionRepositoryPort repositoryPort, TaskHistoryStoragePort storagePort, Clock clock) {
        this.repositoryPort = repositoryPort;
        this.storagePort = storagePort;
        this.clock = clock;
    }

    /**
     * 处理一批到期清除作业。
     *
     * @return 本次完成并通过逐存储复验的作业数量
     */
    public int processReady() {
        Instant now = Instant.now(clock);
        int completed = 0;
        for (TaskHistoryDeletionJob job : repositoryPort.findReady(now, 10)) {
            try {
                storagePort.cleanupBatch(job.ownerUserId(), job.cutoffAt(), BATCH_SIZE);
                if (storagePort.isCleared(job.ownerUserId(), job.cutoffAt())) {
                    repositoryPort.markCompleted(job.id(), now);
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
                repositoryPort.markRetry(job.id(), now, nextAttemptAt);
            }
        }
        return completed;
    }
}
