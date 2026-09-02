package com.aifriend.retention.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 恢复快照中一个以墓碑 UUID 定位的加密信封。
 *
 * @param tombstoneId 独立介质对象绑定的墓碑 UUID
 * @param envelope 固定的版本化加密信封
 * @author Codex
 * @since 1.0.0
 */
public record DeletionTombstoneRestoreEntry(
        UUID tombstoneId,
        EncryptedDeletionTombstoneEnvelope envelope) {

    /**
     * 校验恢复对象不得缺失定位或信封。
     */
    public DeletionTombstoneRestoreEntry {
        Objects.requireNonNull(tombstoneId, "恢复墓碑 UUID 不能为空");
        Objects.requireNonNull(envelope, "恢复墓碑信封不能为空");
    }
}
