package com.aifriend.template.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 日常指令运行时匹配的 owner 范围只读投影端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface RoutineCommandRuntimeStorePort {

    /**
     * 锁定并读取已存在的 owner 命名空间版本。
     *
     * @param ownerUserId 当前 owner UUID
     * @return 命名空间版本；不存在时为空
     */
    Optional<Long> findNamespaceVersionForUpdate(UUID ownerUserId);

    /**
     * 只读获取 owner 当前命名空间版本。
     *
     * @param ownerUserId 当前 owner UUID
     * @return 当前版本；不存在时为空
     */
    Optional<Long> findNamespaceVersion(UUID ownerUserId);

    /**
     * 读取 owner 的 ACTIVE 日常指令模板。
     *
     * @param ownerUserId 当前 owner UUID
     * @return 最多三十一行，用于由应用层复验三十条硬上限
     */
    List<RoutineCommandTemplateRecord> findActiveByOwner(UUID ownerUserId);
}
