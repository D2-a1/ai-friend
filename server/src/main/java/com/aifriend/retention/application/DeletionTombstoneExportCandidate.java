package com.aifriend.retention.application;

/**
 * 待导出墓碑及其可重用的固定密文包快照。
 *
 * @param record 最小墓碑事实
 * @param preparedEnvelope 已固定的密文包；首次准备前为空
 * @param retryCount 已失败次数
 * @author Codex
 * @since 1.0.0
 */
public record DeletionTombstoneExportCandidate(
        DeletionTombstoneExportRecord record,
        EncryptedDeletionTombstoneEnvelope preparedEnvelope,
        int retryCount) {

    /**
     * 校验候选不变量。
     */
    public DeletionTombstoneExportCandidate {
        if (record == null) {
            throw new IllegalArgumentException("灾备导出墓碑不能为空");
        }
        if (retryCount < 0) {
            throw new IllegalArgumentException("灾备导出重试次数无效");
        }
    }
}
