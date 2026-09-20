package com.aifriend.assistant.application;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * 已持久受理问题的有界执行边界；不授予执行资格、不重试、不重置原截止。
 * @author Codex
 * @since 1.0.0
 */
public interface AssistantExecutionPort {
    /**
     * 等待处理完成或明确失败；队列等待计入deadline。
     * @param deadline 原受理截止
     * @param operation 同步工作，阶段间调用control.check
     */
    void execute(Instant deadline, Consumer<Control> operation);
    /**
     * 使用共享进程预算执行；原截止只供旧同域适配器兼容，不得重新形成预算。
     * @param originalDeadline 原数据库截止
     * @param budget 从受理前开始的共享预算
     * @param operation 阶段间检查取消和剩余预算的工作
     */
    default void executeBudget(Instant originalDeadline, Budget budget, Consumer<Control> operation) {
        budget.remaining();
        execute(originalDeadline, control -> operation.accept(() -> { control.check(); budget.remaining(); }));
    }
    /** 内部可信预算，不从HTTP字段创建，不授予执行资格。 */
    @FunctionalInterface interface Budget {
        /**
         * 读取不可续期的剩余预算。
         * @return 正数且最多八秒；到期抛Expired
         */
        java.time.Duration remaining();
    }
    /** 独立于线程中断位的取消/截止检查，禁止组件吞中断后继续提交。 */
    @FunctionalInterface interface Control {
        /** 超时或取消时抛出异常。 */
        void check();
    }
    /** 不携带正文的执行过期信号。 */
    final class Expired extends RuntimeException {
        /** 固定错误。 */
        public Expired() { super("ASSISTANT_EXECUTION_EXPIRED"); }
    }
}
