package com.aifriend.retention.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 需要写入独立灾备介质的最小删除墓碑事实。
 *
 * @param tombstoneId 墓碑随机 UUID，用作外部幂等对象键
 * @param subjectHash 微信主体不可逆 HMAC
 * @param oldAccountGeneration 旧账号代次
 * @param acceptedAt 注销受理时间
 * @param completedAt 在线删除完成时间
 * @param reRegistrationNotBefore 重新注册最早时间
 * @param policyVersion 删除墓碑政策版本
 * @param replayUntil 最迟重放保护截止时间
 * @author Codex
 * @since 1.0.0
 */
public record DeletionTombstoneExportRecord(
        UUID tombstoneId,
        byte[] subjectHash,
        long oldAccountGeneration,
        Instant acceptedAt,
        Instant completedAt,
        Instant reRegistrationNotBefore,
        String policyVersion,
        Instant replayUntil) {

    private static final String POLICY_PATTERN = "[A-Za-z0-9._-]{1,40}";

    /**
     * 校验墓碑事实并隔离可变摘要数组。
     */
    public DeletionTombstoneExportRecord {
        Objects.requireNonNull(tombstoneId, "墓碑 UUID 不能为空");
        if (subjectHash == null || subjectHash.length != 32) {
            throw new IllegalArgumentException("墓碑主体摘要必须为 32 字节");
        }
        subjectHash = Arrays.copyOf(subjectHash, subjectHash.length);
        if (oldAccountGeneration < 1L) {
            throw new IllegalArgumentException("墓碑账号代次必须大于零");
        }
        Objects.requireNonNull(acceptedAt, "注销受理时间不能为空");
        Objects.requireNonNull(completedAt, "注销完成时间不能为空");
        Objects.requireNonNull(reRegistrationNotBefore, "重新注册下限不能为空");
        Objects.requireNonNull(replayUntil, "墓碑重放截止不能为空");
        if (completedAt.isBefore(acceptedAt)
                || reRegistrationNotBefore.isBefore(acceptedAt)
                || replayUntil.isBefore(completedAt)) {
            throw new IllegalArgumentException("墓碑时间顺序无效");
        }
        if (policyVersion == null || !policyVersion.matches(POLICY_PATTERN)) {
            throw new IllegalArgumentException("墓碑政策版本无效");
        }
    }

    /**
     * 返回主体摘要副本，防止调用方修改墓碑事实。
     *
     * @return 32 字节主体摘要副本
     */
    @Override
    public byte[] subjectHash() {
        return Arrays.copyOf(subjectHash, subjectHash.length);
    }
}
