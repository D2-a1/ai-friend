package com.aifriend.retention.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联系人解绑事件单条短事务清理服务。
 *
 * <p>称呼密文清除、非终态任务取消和事件完成必须原子提交。任一步失败时
 * 整体回滚，使事件保持待处理状态并在下一次调度中重试。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ContactUnboundCleanupTransactionService {

    private final ContactUnboundCleanupRepositoryPort repositoryPort;
    private final Clock clock;

    /**
     * 创建联系人解绑清理事务服务。
     *
     * @param repositoryPort 解绑清理持久化端口
     * @param clock UTC 时钟
     */
    public ContactUnboundCleanupTransactionService(
            ContactUnboundCleanupRepositoryPort repositoryPort,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.clock = clock;
    }

    /**
     * 在一个短事务内处理下一条可用解绑事件。
     *
     * @return 完成一条事件时返回 true；没有可用事件时返回 false
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean processNext() {
        Instant now = Instant.now(clock);
        ContactUnboundCleanupJob job = repositoryPort.lockNextReady(now)
                .orElse(null);
        if (job == null) {
            return false;
        }
        repositoryPort.scrubAliases(
                job.ownerUserId(), job.contactId(), now);
        repositoryPort.cancelNonTerminalTasks(
                job.ownerUserId(), job.contactId(), now);
        repositoryPort.markCompleted(job.eventId());
        return true;
    }
}
