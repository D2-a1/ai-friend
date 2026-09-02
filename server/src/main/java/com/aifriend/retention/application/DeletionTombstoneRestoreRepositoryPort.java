package com.aifriend.retention.application;

import java.time.Instant;
import java.util.List;

/**
 * 删除墓碑幂等重放、复活检测与恢复核验事实端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface DeletionTombstoneRestoreRepositoryPort {

    /**
     * 使用短事务幂等合并一批已解密墓碑。
     *
     * <p>已有记录只能在 UUID、主体摘要、代次和全部时间/策略事实精确相同时重放；
     * 任一差异必须整批回滚。</p>
     *
     * @param records 已完成信封完整性校验的有序墓碑，最多 100 条
     * @param replayedAt 当前批次重放时间
     * @throws RuntimeException 数据库失败或已有墓碑事实冲突时抛出
     */
    void replayBatch(List<DeletionTombstoneExportRecord> records, Instant replayedAt);

    /**
     * 统计与任意已重放墓碑主体相同且代次不高于旧代次的在线账号。
     *
     * @return {@code ACTIVE/DELETING} 旧代账号数量
     * @throws RuntimeException 数据库核验失败时抛出
     */
    long countResurrectedAccounts();

    /**
     * 在全量数量、聚合摘要和复活核验通过后写入最小审计事实。
     *
     * @param snapshotIdHash 快照编号 SHA-256
     * @param manifestHash 规范快照清单 SHA-256
     * @param expectedItemCount 清单宣称墓碑数
     * @param replayedItemCount 实际校验重放墓碑数
     * @param sourceProofHash 恢复源证明 SHA-256
     * @param verifiedAt 当前启动核验完成时间
     * @throws RuntimeException 核验事实冲突或写入失败时抛出
     */
    void recordVerification(
            byte[] snapshotIdHash,
            byte[] manifestHash,
            long expectedItemCount,
            long replayedItemCount,
            byte[] sourceProofHash,
            Instant verifiedAt);
}
