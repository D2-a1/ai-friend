package com.aifriend.template.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.task.domain.TaskIntent;

/**
 * 日常指令模板和删除幂等事实持久化端口。
 *
 * <p>所有创建、合并、淘汰和全量清除都必须先锁定同一 owner 命名空间，
 * 以防删除与未来学习流程交错后复活旧模板。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface RoutineCommandTemplateStorePort {

    /**
     * 确保命名空间存在并加写锁。
     *
     * @param ownerUserId 当前已认证 owner
     * @param now 当前 UTC 时间
     * @return 当前命名空间版本
     */
    long lockNamespace(UUID ownerUserId, Instant now);

    /**
     * 按 owner 与幂等键摘要读取既有删除事实。
     *
     * @param ownerUserId 当前已认证 owner
     * @param idempotencyKeyHash 幂等键 SHA-256
     * @return 既有删除事实，不存在时为空
     */
    Optional<RoutineCommandDeletionRecord> findDeletion(
            UUID ownerUserId,
            byte[] idempotencyKeyHash);

    /**
     * 统计 owner 的全部日常指令模板。
     *
     * @param ownerUserId 当前已认证 owner
     * @return 模板数量
     */
    int countByOwner(UUID ownerUserId);

    /**
     * 物理删除 owner 的全部日常指令模板和密文。
     *
     * @param ownerUserId 当前已认证 owner
     * @return 实际删除行数
     */
    int deleteAllByOwner(UUID ownerUserId);

    /**
     * 读取 owner 的同意图模板快照。
     *
     * @param ownerUserId 当前 owner UUID
     * @param intent 有限通信意图
     * @return 最多三十个模板元数据与密文快照
     */
    List<RoutineCommandTemplateRecord> findByOwnerAndIntent(
            UUID ownerUserId,
            TaskIntent intent);

    /**
     * 读取 owner 的全部模板，供硬上限和确定性淘汰使用。
     *
     * @param ownerUserId 当前 owner UUID
     * @return 最多三十个模板快照
     */
    List<RoutineCommandTemplateRecord> findAllByOwner(UUID ownerUserId);

    /**
     * 新增一条已经加密的日常指令模板。
     *
     * @param ownerUserId 当前 owner UUID
     * @param template 待写入模板
     */
    void insert(UUID ownerUserId, RoutineCommandTemplateWrite template);

    /**
     * 对唯一重复模板递增确认使用次数。
     *
     * @param ownerUserId 当前 owner UUID
     * @param templateId 模板 UUID
     * @param expectedVersion 模板旧版本
     * @param confirmedAt 最近确认时间
     */
    void incrementUsage(
            UUID ownerUserId,
            UUID templateId,
            long expectedVersion,
            Instant confirmedAt);

    /**
     * 按 owner、模板和版本删除确定性淘汰目标。
     *
     * @param ownerUserId 当前 owner UUID
     * @param templateId 模板 UUID
     * @param expectedVersion 模板旧版本
     */
    void deleteExact(
            UUID ownerUserId,
            UUID templateId,
            long expectedVersion);

    /**
     * 条件递增命名空间版本。
     *
     * @param ownerUserId 当前已认证 owner
     * @param expectedVersion 锁内读取的旧版本
     * @param nextVersion 新版本
     * @param now 当前 UTC 时间
     */
    void updateNamespace(
            UUID ownerUserId,
            long expectedVersion,
            long nextVersion,
            Instant now);

    /**
     * 保存不可逆的删除幂等事实。
     *
     * @param ownerUserId 当前已认证 owner
     * @param idempotencyKeyHash 幂等键 SHA-256
     * @param requestHash 请求语义 SHA-256
     * @param expectedVersion 客户端可选期望版本
     * @param deletedCount 首次实际删除数量
     * @param namespaceVersionAfter 删除后的命名空间版本
     * @param deletedAt 删除完成时间
     */
    void saveDeletion(
            UUID ownerUserId,
            byte[] idempotencyKeyHash,
            byte[] requestHash,
            Long expectedVersion,
            int deletedCount,
            long namespaceVersionAfter,
            Instant deletedAt);
}
