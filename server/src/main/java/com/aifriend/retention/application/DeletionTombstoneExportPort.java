package com.aifriend.retention.application;

import java.util.UUID;

/**
 * 删除墓碑独立灾备介质导出端口。
 *
 * <p>实现必须以 tombstoneId 和信封摘要作为幂等语义，完成独立介质持久化后回读摘要，
 * 不得只凭上传请求成功返回回执。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public interface DeletionTombstoneExportPort {

    /**
     * 幂等写入并回读验证一个墓碑密文包。
     *
     * @param tombstoneId 墓碑随机 UUID
     * @param envelope 固定且可安全重试的加密信封
     * @return 独立介质回读摘要与有界回执证明
     * @throws RuntimeException 独立介质不可用、内容冲突或回读失败时抛出
     */
    DeletionTombstoneExportReceipt export(
            UUID tombstoneId,
            EncryptedDeletionTombstoneEnvelope envelope);
}
