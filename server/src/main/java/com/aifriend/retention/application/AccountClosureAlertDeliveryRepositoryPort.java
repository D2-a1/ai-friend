package com.aifriend.retention.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 注销告警 Outbox 物化、投递状态和回执事实端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AccountClosureAlertDeliveryRepositoryPort {

    /**
     * 将一批内部告警 Outbox 原子物化为按责任组隔离的投递并消费源事件。
     *
     * @param now 当前 UTC 时间
     * @param batchSize 最大源事件数
     * @return 新物化的责任组投递数
     */
    int materializePending(Instant now, int batchSize);

    /**
     * 查询一批到期且尚未完成的投递。
     *
     * @param now 当前 UTC 时间
     * @param batchSize 最大投递数
     * @return 有界匿名投递列表
     */
    List<AccountClosureAlertDelivery> listReady(Instant now, int batchSize);

    /**
     * 保存供应商受理流水号并进入待复验状态。
     *
     * @param deliveryId 随机投递 UUID
     * @param providerReference 供应商消息流水号
     * @param submittedAt 提交时间
     * @param verifyAt 首次复验时间
     * @return 首次保存或相同流水号幂等重放时返回 true
     */
    boolean confirmSubmitted(
            UUID deliveryId,
            String providerReference,
            Instant submittedAt,
            Instant verifyAt);

    /**
     * 保持既有供应商流水号并安排下一次状态复验。
     *
     * @param deliveryId 随机投递 UUID
     * @param checkedAt 本次复验时间
     * @param verifyAt 下次复验时间
     */
    void scheduleVerification(UUID deliveryId, Instant checkedAt, Instant verifyAt);

    /**
     * 处理供应商明确失败，清除旧流水号后允许重新提交。
     *
     * @param deliveryId 随机投递 UUID
     * @param attemptedAt 当前尝试时间
     * @param nextAttemptAt 下次允许提交时间
     */
    void resetFailedSubmission(UUID deliveryId, Instant attemptedAt, Instant nextAttemptAt);

    /**
     * 短事务确认稳定回执；同一投递不同回执必须冲突。
     *
     * @param deliveryId 随机投递 UUID
     * @param receiptHash 稳定回执 SHA-256
     * @param deliveredAt 投递确认时间
     * @return 首次确认或相同回执幂等重放时返回 true
     */
    boolean confirmDelivered(UUID deliveryId, byte[] receiptHash, Instant deliveredAt);

    /**
     * 记录一次通道失败并安排下一次允许尝试时间，已保存的供应商流水号不得丢失。
     *
     * @param deliveryId 随机投递 UUID
     * @param attemptedAt 当前尝试时间
     * @param nextAttemptAt 下次允许尝试时间
     */
    void markRetry(UUID deliveryId, Instant attemptedAt, Instant nextAttemptAt);
}
