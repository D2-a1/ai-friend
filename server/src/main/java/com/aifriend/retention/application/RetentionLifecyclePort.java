package com.aifriend.retention.application;

import java.time.Instant;

/**
 * 临时音频、任务正文、邀请临时数据与删除墓碑的统一生命周期清理端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface RetentionLifecyclePort {

    /**
     * 有界推进一批到期数据的物理清理。
     *
     * <p>对象存储删除必须位于数据库事务外；数据库记录按外键顺序使用短事务清理。
     * 删除墓碑必须同时具备可信灾备导出时间和回执摘要，且达到重放截止时间。
     *
     * @param now 当前 UTC 时间
     * @param taskContentCutoff 任务密文创建时间截止点
     * @param batchSize 每种数据单次最多处理的主记录数
     * @return 本次成功清理或推进的主记录总数
     * @throws IllegalArgumentException 当批次大小不在允许范围内时抛出
     * @throws RuntimeException 当数据库清理失败时抛出
     */
    int cleanupBatch(Instant now, Instant taskContentCutoff, int batchSize);
}
