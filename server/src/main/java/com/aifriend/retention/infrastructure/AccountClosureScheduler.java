package com.aifriend.retention.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.AccountClosureWorker;

/**
 * 账号注销物理清理与内部告警的有界调度入口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class AccountClosureScheduler {
    /** 账号注销工作器。 */
    private final AccountClosureWorker worker;

    /**
     * 创建账号注销调度入口。
     *
     * @param worker 账号注销工作器
     */
    public AccountClosureScheduler(AccountClosureWorker worker) {
        this.worker = worker;
    }

    /**
     * 定期推进可靠受理的账号注销作业。
     *
     * @throws RuntimeException 当作业扫描或持久化失败时抛出
     */
    @Scheduled(fixedDelayString = "${ai-friend.retention.account-closure-interval:30s}")
    public void process() {
        worker.processReady();
    }
}
