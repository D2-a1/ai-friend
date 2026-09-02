package com.aifriend.retention.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.RetentionLifecycleWorker;

/**
 * 统一敏感数据与删除墓碑生命周期清理的有界调度入口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RetentionLifecycleScheduler {
    /** 生命周期清理工作器。 */
    private final RetentionLifecycleWorker worker;

    /**
     * 创建生命周期调度入口。
     *
     * @param worker 生命周期清理工作器
     */
    public RetentionLifecycleScheduler(RetentionLifecycleWorker worker) {
        this.worker = worker;
    }

    /**
     * 每五分钟推进一次到期数据和已安全导出墓碑清理。
     *
     * @throws RuntimeException 当数据库扫描或清理失败时抛出
     */
    @Scheduled(fixedDelayString = "${ai-friend.retention.cleanup-interval:5m}")
    public void process() {
        worker.processReady();
    }
}
