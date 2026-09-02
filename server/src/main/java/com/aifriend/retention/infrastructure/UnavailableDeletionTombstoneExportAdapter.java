package com.aifriend.retention.infrastructure;

import java.util.UUID;

import com.aifriend.retention.application.DeletionTombstoneExportPort;
import com.aifriend.retention.application.DeletionTombstoneExportReceipt;
import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 可信独立灾备介质尚未接入时的失败关闭适配器。
 *
 * <p>不得回落到应用本地目录、普通数据库 Outbox 或同一对象存储，也不得生成假回执。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public class UnavailableDeletionTombstoneExportAdapter
        implements DeletionTombstoneExportPort {

    /**
     * 创建失败关闭灾备导出适配器。
     */
    public UnavailableDeletionTombstoneExportAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public DeletionTombstoneExportReceipt export(
            UUID tombstoneId,
            EncryptedDeletionTombstoneEnvelope envelope) {
        throw new UpstreamFailureException("可信灾备墓碑存储暂不可用");
    }
}
