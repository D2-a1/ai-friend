package com.aifriend.retrieval.application;

import java.util.Optional;
import java.util.UUID;

/**
 * 公开知识回收的持久重试与匿名告警事实；不承载文档、用户或异常正文。
 * 调用方只能在取得租约后运行一个有界批次；数据库结果未知时不得在进程内重放。
 * @author Codex
 * @since 1.0.0
 */
public interface KnowledgeCleanupRetryPort {
    /**
     * 尝试取得一个持久化的回收批次租约。
     * @return 已提交的30秒批次租约；未到期限或已有租约时为空
     */
    Optional<UUID> acquire();

    /**
     * 完成一次批次。DRAINED仅允许用于控制锁内确认无剩余可回收材料，不是删除数量为零。
     * @param token 已取得租约
     * @param outcome 批次事实，忙碌/取消不能当DRAINED
     * @return 租约有效且结果已经持久化；过期或旧租约为false
     */
    boolean finish(UUID token, Outcome outcome);

    /**
     * 读取并推进数据库时钟下的匿名维护状态。
     * @return 数据库时钟下的匿名状态；会持久化过期租约和15分钟升级事实
     */
    KnowledgeCleanupRetryState status();

    /**
     * 仅在外部通道已验证送达后确认对应累计序号；通道受理/超时均不得调用。
     * 较旧回执不能覆盖新事实，清理完成也不能删除未送达告警。
     * @param level 告警类别
     * @param sequence 已验证送达的累计序号
     * @return 回执有效；未来序号或重复/旧回执为false
     */
    boolean acknowledge(AlertLevel level, long sequence);

    /** 有限批次结论。 */
    enum Outcome {
        /** 控制锁内已确认历史排空。 */
        DRAINED,
        /** 本批有回收进展，仍需继续检查。 */
        PROGRESSED,
        /** 引用、租约或取消导致本批暂缓。 */
        DEFERRED,
        /** 本批失败，按持久化策略重试并记录告警。 */
        FAILED
    }
    /** 两类匿名累计告警；没有联系人、原文或错误详情。 */
    enum AlertLevel {
        /** 新的首次失败累计事件。 */
        FIRST_FAILURE,
        /** 超过维护期限的升级累计事件。 */
        OVERDUE
    }
}
