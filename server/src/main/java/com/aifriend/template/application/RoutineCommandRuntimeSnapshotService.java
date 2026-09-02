package com.aifriend.template.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 日常指令运行时匹配的短事务快照和命名空间复验服务。
 *
 * <p>MFCC、DTW 和模板解密均在本服务事务外执行。快照只锁定当前 owner，
 * 不会为从未学习日常指令的普通任务创建命名空间。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class RoutineCommandRuntimeSnapshotService {

    private static final int MAXIMUM_TEMPLATES = 30;

    private final RoutineCommandRuntimeStorePort storePort;

    /**
     * 创建运行时模板快照服务。
     *
     * @param storePort owner 范围运行时只读投影端口
     */
    public RoutineCommandRuntimeSnapshotService(
            RoutineCommandRuntimeStorePort storePort) {
        this.storePort = storePort;
    }

    /**
     * 在短事务内锁定命名空间并读取 ACTIVE 模板密文快照。
     *
     * @param ownerUserId 当前 owner UUID
     * @return 未建立命名空间时为空，否则返回版本化快照
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<RoutineCommandRuntimeSnapshot> snapshot(UUID ownerUserId) {
        Optional<Long> namespaceVersion =
                storePort.findNamespaceVersionForUpdate(ownerUserId);
        if (namespaceVersion.isEmpty()) {
            return Optional.empty();
        }
        List<RoutineCommandTemplateRecord> templates =
                storePort.findActiveByOwner(ownerUserId);
        if (templates.size() > MAXIMUM_TEMPLATES) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return Optional.of(new RoutineCommandRuntimeSnapshot(
                namespaceVersion.orElseThrow(), templates));
    }

    /**
     * 在事务外计算后复验 owner 命名空间仍为原版本。
     *
     * @param ownerUserId 当前 owner UUID
     * @param expectedVersion 声学计算所依据的版本
     * @return 当前版本仍一致时返回 {@code true}
     */
    @Transactional(readOnly = true)
    public boolean isCurrent(UUID ownerUserId, long expectedVersion) {
        return storePort.findNamespaceVersion(ownerUserId)
                .filter(version -> version == expectedVersion)
                .isPresent();
    }
}
