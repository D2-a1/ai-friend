package com.aifriend.retention.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * 当前启动完成全量墓碑重放和复活核验后的内存报告。
 *
 * @param replayedItemCount 已验证并幂等重放的墓碑数
 * @param manifestHash 当前快照清单的规范 SHA-256
 * @param verifiedAt 当前启动完成核验的 UTC 时间
 * @author Codex
 * @since 1.0.0
 */
public record DeletionTombstoneRestoreReport(
        long replayedItemCount,
        byte[] manifestHash,
        Instant verifiedAt) {

    /**
     * 校验报告并隔离可变摘要数组。
     */
    public DeletionTombstoneRestoreReport {
        if (replayedItemCount < 0L) {
            throw new IllegalArgumentException("灾备恢复重放数量无效");
        }
        if (manifestHash == null || manifestHash.length != 32) {
            throw new IllegalArgumentException("灾备恢复清单摘要无效");
        }
        Objects.requireNonNull(verifiedAt, "灾备恢复验证时间不能为空");
        manifestHash = Arrays.copyOf(manifestHash, manifestHash.length);
    }

    /**
     * 返回清单摘要的防御性副本。
     *
     * @return 32 字节清单摘要
     */
    @Override
    public byte[] manifestHash() {
        return Arrays.copyOf(manifestHash, manifestHash.length);
    }
}
