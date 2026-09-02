package com.aifriend.template.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aifriend.template.application.RoutineCommandLearningWorker;

/**
 * 日常指令可靠学习 Outbox 的本地定时触发器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RoutineCommandLearningScheduler {

    private final RoutineCommandLearningWorker worker;

    /**
     * 创建日常指令学习调度器。
     *
     * @param worker 事务外学习工作器
     */
    public RoutineCommandLearningScheduler(
            RoutineCommandLearningWorker worker) {
        this.worker = worker;
    }

    /** 固定间隔有界处理已到期学习任务。 */
    @Scheduled(fixedDelayString = "${ai-friend.routine-learning.interval:30s}")
    public void processReady() {
        worker.processReady();
    }
}
