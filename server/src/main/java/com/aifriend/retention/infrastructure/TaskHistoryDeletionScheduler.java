package com.aifriend.retention.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.TaskHistoryDeletionWorker;

/**
 * 任务历史清除有界调度入口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class TaskHistoryDeletionScheduler {
    private final TaskHistoryDeletionWorker worker;
    /**
     * 创建调度入口。
     *
     * @param worker 任务历史清除工作器
     */
    public TaskHistoryDeletionScheduler(TaskHistoryDeletionWorker worker) {
        this.worker = worker;
    }

    /** 定期处理可靠受理的清除作业。 */
    @Scheduled(fixedDelayString = "${ai-friend.retention.task-history-interval:30s}")
    public void process() {
        worker.processReady();
    }
}
