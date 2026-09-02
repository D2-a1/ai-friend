package com.aifriend.retention.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.ContactUnboundCleanupWorker;

/**
 * 联系人解绑后续清理调度入口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class ContactUnboundCleanupScheduler {

    private final ContactUnboundCleanupWorker worker;

    /**
     * 创建联系人解绑清理调度入口。
     *
     * @param worker 解绑清理工作器
     */
    public ContactUnboundCleanupScheduler(
            ContactUnboundCleanupWorker worker) {
        this.worker = worker;
    }

    /** 定期处理可靠写入的联系人解绑清理事件。 */
    @Scheduled(fixedDelayString =
            "${ai-friend.retention.contact-unbound-interval:30s}")
    public void process() {
        worker.processReady();
    }
}
