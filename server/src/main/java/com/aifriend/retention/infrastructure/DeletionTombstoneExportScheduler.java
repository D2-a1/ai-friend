package com.aifriend.retention.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.DeletionTombstoneExportWorker;

/**
 * 删除墓碑可信独立介质导出的有界调度入口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class DeletionTombstoneExportScheduler {

    /** 删除墓碑导出工作器。 */
    private final DeletionTombstoneExportWorker worker;

    /**
     * 创建删除墓碑导出调度入口。
     *
     * @param worker 删除墓碑导出工作器
     */
    public DeletionTombstoneExportScheduler(DeletionTombstoneExportWorker worker) {
        this.worker = worker;
    }

    /**
     * 每五分钟推进一次到达重试时间的墓碑导出。
     *
     * @throws RuntimeException 数据库或固定信封生成失败时抛出
     */
    @Scheduled(fixedDelayString = "${ai-friend.retention.disaster-recovery.export-interval:5m}")
    public void process() {
        worker.processReady();
    }
}
