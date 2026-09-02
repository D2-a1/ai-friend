package com.aifriend.template.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.template.domain.SafetyCommandTemplate;

/**
 * owner 范围安全指令模板、命名空间与幂等批次持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface SafetyCommandTemplateRepositoryPort {

    /**
     * 无锁预查 owner 的注册幂等批次。
     *
     * @param ownerUserId owner UUID
     * @param idempotencyKeyHash 幂等键摘要
     * @return 已完成批次
     */
    Optional<SafetyCommandEnrollment> findEnrollment(
            UUID ownerUserId,
            byte[] idempotencyKeyHash);

    /**
     * 创建或锁定 owner 安全指令命名空间。
     *
     * @param ownerUserId owner UUID
     * @param now 首次创建时间
     * @return 锁定后的命名空间版本
     */
    long lockNamespace(UUID ownerUserId, Instant now);

    /**
     * 在 owner 命名空间锁后查询幂等批次。
     *
     * @param ownerUserId owner UUID
     * @param idempotencyKeyHash 幂等键摘要
     * @return 已完成批次
     */
    Optional<SafetyCommandEnrollment> findEnrollmentForUpdate(
            UUID ownerUserId,
            byte[] idempotencyKeyHash);

    /**
     * 查询 owner 当前四类有效模板。
     *
     * @param ownerUserId owner UUID
     * @return 按指令类型排序的有效模板
     */
    List<SafetyCommandTemplate> findActiveByOwner(UUID ownerUserId);

    /**
     * 按 owner 与模板 UUID 查询当前有效安全指令模板。
     *
     * @param ownerUserId owner UUID
     * @param templateId 模板 UUID
     * @return 当前有效模板
     */
    Optional<SafetyCommandTemplate> findActiveByOwnerAndId(
            UUID ownerUserId,
            UUID templateId);

    /**
     * 将 owner 当前有效四类模板立即清密并刷新为 REPLACED。
     *
     * <p>此操作必须在插入新 ACTIVE 模板之前完成 SQL flush，
     * 避免 owner+类型+有效占位唯一键冲突。
     *
     * @param ownerUserId owner UUID
     * @param now 替换时间
     * @return 被替换的模板数量，只允许 0 或 4
     */
    int replaceActiveAndFlush(UUID ownerUserId, Instant now);

    /**
     * 保存一个模板快照。
     *
     * @param template 模板快照
     * @return 保存后模板
     */
    SafetyCommandTemplate saveTemplate(SafetyCommandTemplate template);

    /**
     * 保存整批幂等记录。
     *
     * @param enrollment 注册批次
     */
    void saveEnrollment(SafetyCommandEnrollment enrollment);

    /**
     * 以乐观版本推进 owner 命名空间。
     *
     * @param ownerUserId owner UUID
     * @param expectedVersion 当前命名空间版本
     * @param now 更新时间
     */
    void incrementNamespace(UUID ownerUserId, long expectedVersion, Instant now);
}
