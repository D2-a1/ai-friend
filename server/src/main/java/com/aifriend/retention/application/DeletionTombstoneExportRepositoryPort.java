package com.aifriend.retention.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 删除墓碑灾备导出状态持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface DeletionTombstoneExportRepositoryPort {

    /**
     * 查询一批到达重试时间且尚未确认导出的墓碑。
     *
     * @param now 当前 UTC 时间
     * @param batchSize 最大候选数
     * @return 有界候选快照
     */
    List<DeletionTombstoneExportCandidate> listReady(Instant now, int batchSize);

    /**
     * 首次固定墓碑密文包，后续重试不得重新生成随机 IV。
     *
     * @param tombstoneId 墓碑 UUID
     * @param envelope 待固定的加密信封
     * @param now 当前 UTC 时间
     * @return 当前调用是否成功固定
     */
    boolean prepare(UUID tombstoneId, EncryptedDeletionTombstoneEnvelope envelope, Instant now);

    /**
     * 在回读验证通过后原子写入可信导出事实并清除临时密文包。
     *
     * @param tombstoneId 墓碑 UUID
     * @param expectedEnvelopeHash 固定信封摘要
     * @param exportedAt 独立介质确认时间
     * @param receiptHash 回执证明 SHA-256
     * @return 条件更新是否成功
     */
    boolean markExported(
            UUID tombstoneId,
            byte[] expectedEnvelopeHash,
            Instant exportedAt,
            byte[] receiptHash);

    /**
     * 记录一次失败并推进下一重试时间。
     *
     * @param tombstoneId 墓碑 UUID
     * @param expectedEnvelopeHash 固定信封摘要
     * @param attemptedAt 本次尝试时间
     * @param nextAttemptAt 下一尝试时间
     */
    void markRetry(
            UUID tombstoneId,
            byte[] expectedEnvelopeHash,
            Instant attemptedAt,
            Instant nextAttemptAt);
}
