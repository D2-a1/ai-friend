package com.aifriend.template.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 日常指令学习 Outbox 持久化端口。
 *
 * <p>调用方负责短事务边界；领取必须使用数据库行锁和租约，完成、重试和跳过
 * 必须同时校验任务 UUID 与租约 UUID。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface RoutineCommandLearningQueuePort {

    /**
     * 在确认事务内写入或复用来源任务唯一的学习事实。
     *
     * @param request 最小学习命令
     * @param namespaceVersionAtEnqueue 当前 owner 命名空间版本
     * @param now 入队时间
     */
    void enqueue(
            RoutineCommandLearningRequest request,
            long namespaceVersionAtEnqueue,
            Instant now);

    /**
     * 有界领取已到期或租约失效的任务。
     *
     * @param now 当前 UTC 时间
     * @param leaseUntil 本次租约截止时间
     * @param limit 最大领取数
     * @return 已写入唯一租约的任务
     */
    List<RoutineCommandLearningJob> claimReady(
            Instant now,
            Instant leaseUntil,
            int limit);

    /**
     * 加锁读取仍由指定租约持有的任务。
     *
     * @param jobId 学习任务 UUID
     * @param leaseToken 当前租约 UUID
     * @return 当前 PROCESSING 任务
     */
    Optional<RoutineCommandLearningJob> findClaimedForUpdate(
            UUID jobId,
            UUID leaseToken);

    /**
     * 将指定租约任务置为完成。
     *
     * @param jobId 学习任务 UUID
     * @param leaseToken 当前租约 UUID
     * @param now 完成时间
     */
    void markDone(UUID jobId, UUID leaseToken, Instant now);

    /**
     * 将指定租约任务退避重试。
     *
     * @param jobId 学习任务 UUID
     * @param leaseToken 当前租约 UUID
     * @param errorCode 有界稳定错误码
     * @param availableAt 下次可领取时间
     * @param now 更新时间
     */
    void markRetry(
            UUID jobId,
            UUID leaseToken,
            String errorCode,
            Instant availableAt,
            Instant now);

    /**
     * 将指定租约任务永久跳过。
     *
     * @param jobId 学习任务 UUID
     * @param leaseToken 当前租约 UUID
     * @param reasonCode 有界稳定原因码
     * @param now 跳过时间
     */
    void markSkipped(
            UUID jobId,
            UUID leaseToken,
            String reasonCode,
            Instant now);

    /**
     * 在模板全量清除事务内作废 owner 的全部未完成学习任务。
     *
     * @param ownerUserId 当前 owner UUID
     * @param reasonCode 固定作废原因码
     * @param now 作废时间
     * @return 实际作废行数
     */
    int cancelOutstandingByOwner(
            UUID ownerUserId,
            String reasonCode,
            Instant now);
}
