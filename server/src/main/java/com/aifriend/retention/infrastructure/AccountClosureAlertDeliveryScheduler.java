package com.aifriend.retention.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.AccountClosureAlertDeliveryWorker;

/**
 * 账号注销匿名告警投递的有界调度入口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class AccountClosureAlertDeliveryScheduler {

    /** 注销告警投递工作器。 */
    private final AccountClosureAlertDeliveryWorker worker;

    /**
     * 创建注销告警投递调度器。
     *
     * @param worker 注销告警投递工作器
     */
    public AccountClosureAlertDeliveryScheduler(AccountClosureAlertDeliveryWorker worker) {
        this.worker = worker;
    }

    /**
     * 定期物化并投递一批到期注销告警。
     *
     * @throws RuntimeException 当 Outbox 或投递状态数据库操作失败时抛出
     */
    @Scheduled(fixedDelayString =
            "${ai-friend.retention.account-closure-alert-delivery-interval:30s}")
    public void process() {
        worker.processReady();
    }
}
