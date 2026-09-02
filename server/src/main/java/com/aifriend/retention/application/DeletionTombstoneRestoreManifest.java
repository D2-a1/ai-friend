package com.aifriend.retention.application;

import java.util.Arrays;

/**
 * 经恢复源适配器验证的删除墓碑快照清单。
 *
 * <p>适配器必须在返回前验证供应端身份、快照不可变性和源证明。
 * 应用层仍会独立复核数量、顺序、信封完整性和聚合摘要。</p>
 *
 * @param snapshotId 当前恢复显式绑定的快照编号
 * @param expectedItemCount 快照宣称的墓碑总数
 * @param aggregateHash 全量有序墓碑对象的规范 SHA-256
 * @param sourceProofHash 供应端身份与快照证明的 SHA-256
 * @author Codex
 * @since 1.0.0
 */
public record DeletionTombstoneRestoreManifest(
        String snapshotId,
        long expectedItemCount,
        byte[] aggregateHash,
        byte[] sourceProofHash) {

    private static final String SNAPSHOT_ID_PATTERN = "[A-Za-z0-9._:-]{8,128}";

    /**
     * 校验快照清单边界并隔离可变摘要数组。
     */
    public DeletionTombstoneRestoreManifest {
        if (snapshotId == null || !snapshotId.matches(SNAPSHOT_ID_PATTERN)) {
            throw new IllegalArgumentException("灾备恢复快照编号无效");
        }
        if (expectedItemCount < 0L || expectedItemCount > 1_000_000L) {
            throw new IllegalArgumentException("灾备恢复墓碑总数超出上限");
        }
        if (aggregateHash == null || aggregateHash.length != 32) {
            throw new IllegalArgumentException("灾备恢复聚合摘要无效");
        }
        if (sourceProofHash == null || sourceProofHash.length != 32) {
            throw new IllegalArgumentException("灾备恢复源证明摘要无效");
        }
        aggregateHash = Arrays.copyOf(aggregateHash, aggregateHash.length);
        sourceProofHash = Arrays.copyOf(sourceProofHash, sourceProofHash.length);
    }

    /**
     * 返回聚合摘要的防御性副本。
     *
     * @return 32 字节聚合摘要
     */
    @Override
    public byte[] aggregateHash() {
        return Arrays.copyOf(aggregateHash, aggregateHash.length);
    }

    /**
     * 返回恢复源证明摘要的防御性副本。
     *
     * @return 32 字节源证明摘要
     */
    @Override
    public byte[] sourceProofHash() {
        return Arrays.copyOf(sourceProofHash, sourceProofHash.length);
    }
}
