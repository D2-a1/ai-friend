package com.aifriend.retention.application;

/**
 * 可信独立介质的删除墓碑恢复源端口。
 *
 * <p>实现必须在返回清单前完成供应端身份、快照不可变性和源证明验证；
 * 游标和介质凭证只能留在当前恢复调用内存，不得记录或持久化。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public interface DeletionTombstoneRestoreSourcePort {

    /**
     * 打开并验证当前恢复显式指定的不可变快照。
     *
     * @param expectedSnapshotId 环境配置绑定的快照编号
     * @return 已完成源认证的快照清单
     * @throws RuntimeException 介质不可用、快照不匹配或源证明无效时抛出
     */
    DeletionTombstoneRestoreManifest openSnapshot(String expectedSnapshotId);

    /**
     * 读取一批不可变快照对象。
     *
     * @param snapshotId 已验证快照编号
     * @param cursor 上一批返回的不透明游标；首批为空
     * @param maxItems 本批最大条数，范围 1—100
     * @return 严格按墓碑 UUID 升序的有界批次
     * @throws RuntimeException 介质不可用、游标无效或快照发生变化时抛出
     */
    DeletionTombstoneRestoreBatch readBatch(
            String snapshotId,
            String cursor,
            int maxItems);
}
