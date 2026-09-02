package com.aifriend.template.application;

import java.util.List;

/**
 * 短事务锁定 owner 命名空间后取得的同意图模板快照。
 *
 * @param namespaceVersion 快照对应命名空间版本
 * @param templates 同一有限意图的最多三十个模板
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandTemplateSnapshot(
        long namespaceVersion,
        List<RoutineCommandTemplateRecord> templates) {

    /** 固化模板列表。 */
    public RoutineCommandTemplateSnapshot {
        templates = List.copyOf(templates);
    }
}
