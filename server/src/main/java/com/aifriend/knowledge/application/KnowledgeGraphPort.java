package com.aifriend.knowledge.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.knowledge.domain.GraphSnapshot;

/**
 * MySQL图谱投影端口；权威业务源与最终授权复验仍在此端口之外。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeGraphPort {
    /**
     * 读取单owner完整投影，存储损坏必须抛错，不能返回部分图。
     * @param ownerUserId 当前主体
     * @return 当前投影或确实不存在
     */
    Optional<Projection> findByOwner(UUID ownerUserId);

    /**
     * 按控制版本原子发布完整快照。
     * @param snapshot 已通过源一致性校验的图
     * @param expectedVersion 期望控制版本，首次为0
     * @return 新投影；CAS失败必须抛冲突
     */
    Projection project(GraphSnapshot snapshot, long expectedVersion);

    /**
     * 撤权/注销清理不依赖能力开关；并发冲突不假报成功。
     * @param ownerUserId 目标owner
     * @param expectedVersion 控制版本
     * @return 是否删除或确认无残留
     */
    boolean purge(UUID ownerUserId, long expectedVersion);

    /**
     * 已持久化的投影和控制版本。
     * @param snapshot 完整图
     * @param version 控制版本
     */
    record Projection(GraphSnapshot snapshot, long version) {
        /** 确保仅返回持久化后的正版本。 */
        public Projection {
            Objects.requireNonNull(snapshot, "snapshot");
            if (version < 1) {
                throw new IllegalArgumentException("INVALID_PROJECTION_VERSION");
            }
        }
    }
}
