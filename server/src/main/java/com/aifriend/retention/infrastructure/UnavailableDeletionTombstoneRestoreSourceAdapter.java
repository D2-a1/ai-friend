package com.aifriend.retention.infrastructure;

import com.aifriend.retention.application.DeletionTombstoneRestoreBatch;
import com.aifriend.retention.application.DeletionTombstoneRestoreManifest;
import com.aifriend.retention.application.DeletionTombstoneRestoreSourcePort;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 未配置可信独立灾备恢复源时的失败关闭适配器。
 *
 * <p>该适配器不会从在线数据库或本地临时文件伪造灾备快照。真实供应商适配器必须先完成
 * 供应端身份验证、不可变清单验证和受限分页，才能替换此默认实现。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public class UnavailableDeletionTombstoneRestoreSourceAdapter
        implements DeletionTombstoneRestoreSourcePort {

    /**
     * 创建失败关闭的默认恢复源。
     */
    public UnavailableDeletionTombstoneRestoreSourceAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public DeletionTombstoneRestoreManifest openSnapshot(String expectedSnapshotId) {
        throw unavailable();
    }

    /** {@inheritDoc} */
    @Override
    public DeletionTombstoneRestoreBatch readBatch(
            String snapshotId,
            String cursor,
            int maxItems) {
        throw unavailable();
    }

    private UpstreamFailureException unavailable() {
        return new UpstreamFailureException("可信灾备恢复源未配置，拒绝恢复实例上线");
    }
}
