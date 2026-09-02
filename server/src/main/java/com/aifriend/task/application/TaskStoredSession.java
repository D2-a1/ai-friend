package com.aifriend.task.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.task.domain.TaskState;

/**
 * 任务会话持久化快照，敏感业务字段只以密文存在。
 *
 * @param id 会话 UUID
 * @param ownerUserId owner UUID
 * @param sourceAudioObjectId TASK 音频对象 UUID
 * @param clientTaskId 客户端任务编号
 * @param createIdempotencyKeyHash 创建幂等键摘要
 * @param createRequestHash 创建请求摘要
 * @param state 当前状态
 * @param payloadCipher AES-GCM 加密载荷
 * @param selectedContactId 已唯一选择的联系人 UUID，可空
 * @param summaryHash 复述摘要哈希，可空
 * @param planId 动作计划编号，可空
 * @param planExpiresAt 动作计划过期时间，可空
 * @param sessionVersion 对外状态版本
 * @param expiresAt 会话过期时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @author Codex
 * @since 1.0.0
 */
public record TaskStoredSession(
        UUID id,
        UUID ownerUserId,
        UUID sourceAudioObjectId,
        String clientTaskId,
        byte[] createIdempotencyKeyHash,
        byte[] createRequestHash,
        TaskState state,
        byte[] payloadCipher,
        UUID selectedContactId,
        String summaryHash,
        String planId,
        Instant planExpiresAt,
        long sessionVersion,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    /** 创建带防御性数组副本的快照。 */
    public TaskStoredSession {
        createIdempotencyKeyHash = createIdempotencyKeyHash.clone();
        createRequestHash = createRequestHash.clone();
        payloadCipher = payloadCipher.clone();
    }

    /**
     * 获取创建幂等摘要副本。
     *
     * @return 创建幂等摘要副本
     */
    @Override
    public byte[] createIdempotencyKeyHash() {
        return createIdempotencyKeyHash.clone();
    }

    /**
     * 获取创建请求摘要副本。
     *
     * @return 创建请求摘要副本
     */
    @Override
    public byte[] createRequestHash() {
        return createRequestHash.clone();
    }

    /**
     * 获取敏感载荷密文副本。
     *
     * @return 敏感载荷密文副本
     */
    @Override
    public byte[] payloadCipher() {
        return payloadCipher.clone();
    }
}
