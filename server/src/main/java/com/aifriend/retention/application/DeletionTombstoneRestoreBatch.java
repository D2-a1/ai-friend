package com.aifriend.retention.application;

import java.util.List;

/**
 * 独立灾备介质返回的有界墓碑恢复批次。
 *
 * @param entries 当前批次严格按墓碑 UUID 升序的对象
 * @param nextCursor 下一批不透明游标；已完成时为空
 * @param complete 当前批次是否为快照终点
 * @author Codex
 * @since 1.0.0
 */
public record DeletionTombstoneRestoreBatch(
        List<DeletionTombstoneRestoreEntry> entries,
        String nextCursor,
        boolean complete) {

    /**
     * 校验批次大小与游标状态。
     */
    public DeletionTombstoneRestoreBatch {
        if (entries == null
                || entries.size() > 100
                || entries.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("灾备恢复批次无效");
        }
        entries = List.copyOf(entries);
        if (complete) {
            if (nextCursor != null) {
                throw new IllegalArgumentException("灾备恢复终点不得携带游标");
            }
        } else if (entries.isEmpty()
                || nextCursor == null
                || nextCursor.isBlank()
                || nextCursor.length() > 256) {
            throw new IllegalArgumentException("灾备恢复未完成批次缺少有效游标");
        }
    }
}
