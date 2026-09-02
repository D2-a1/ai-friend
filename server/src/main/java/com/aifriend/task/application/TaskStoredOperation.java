package com.aifriend.task.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.task.domain.TaskOperationType;

/**
 * 任务会话写操作幂等墓碑。
 *
 * @param id 操作 UUID
 * @param taskSessionId 会话 UUID
 * @param ownerUserId owner UUID
 * @param operationType 写操作类型
 * @param idempotencyKeyHash 幂等键摘要
 * @param requestHash 请求摘要
 * @param resultingSessionVersion 本操作产生的会话版本
 * @param completedAt 完成时间
 * @author Codex
 * @since 1.0.0
 */
public record TaskStoredOperation(
        UUID id,
        UUID taskSessionId,
        UUID ownerUserId,
        TaskOperationType operationType,
        byte[] idempotencyKeyHash,
        byte[] requestHash,
        long resultingSessionVersion,
        Instant completedAt) {

    /** 创建带防御性数组副本的操作墓碑。 */
    public TaskStoredOperation {
        idempotencyKeyHash = idempotencyKeyHash.clone();
        requestHash = requestHash.clone();
    }

    /**
     * 获取幂等键摘要副本。
     *
     * @return 幂等键摘要副本
     */
    @Override
    public byte[] idempotencyKeyHash() {
        return idempotencyKeyHash.clone();
    }

    /**
     * 获取请求摘要副本。
     *
     * @return 请求摘要副本
     */
    @Override
    public byte[] requestHash() {
        return requestHash.clone();
    }
}
