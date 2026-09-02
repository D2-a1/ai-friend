package com.aifriend.template.application;

import java.util.List;

/**
 * owner 日常指令命名空间和 ACTIVE 模板的短事务快照。
 *
 * @param namespaceVersion 快照时命名空间版本
 * @param templates 最多三十个模板密文快照
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandRuntimeSnapshot(
        long namespaceVersion,
        List<RoutineCommandTemplateRecord> templates) {

    /** 固化模板快照列表。 */
    public RoutineCommandRuntimeSnapshot {
        templates = List.copyOf(templates);
    }
}
