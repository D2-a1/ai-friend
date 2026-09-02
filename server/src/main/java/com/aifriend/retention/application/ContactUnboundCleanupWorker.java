package com.aifriend.retention.application;

import org.springframework.stereotype.Service;

/**
 * 联系人解绑清理的有界工作器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ContactUnboundCleanupWorker {

    private static final int MAXIMUM_EVENTS_PER_RUN = 10;

    private final ContactUnboundCleanupTransactionService transactionService;

    /**
     * 创建联系人解绑清理工作器。
     *
     * @param transactionService 单条短事务服务
     */
    public ContactUnboundCleanupWorker(
            ContactUnboundCleanupTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * 有界处理当前可用的解绑事件。
     *
     * @return 本次完成的事件数量
     */
    public int processReady() {
        int completed = 0;
        while (completed < MAXIMUM_EVENTS_PER_RUN
                && transactionService.processNext()) {
            completed++;
        }
        return completed;
    }
}
