package com.aifriend.template.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aifriend.template.domain.SafetyCommandTemplateStatus;

/**
 * 安全指令声学模板 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface SafetyCommandTemplateJpaRepository
        extends JpaRepository<SafetyCommandTemplateEntity, UUID> {

    /**
     * 查询 owner 指定状态模板，按固定指令类型排序。
     *
     * @param ownerUserId owner UUID
     * @param status 生命周期状态
     * @return 稳定排序的模板列表
     */
    List<SafetyCommandTemplateEntity> findByOwnerUserIdAndStatusOrderByCommandTypeAsc(
            UUID ownerUserId,
            SafetyCommandTemplateStatus status);

    /**
     * 按 owner、模板 UUID 和状态查询模板。
     *
     * @param ownerUserId owner UUID
     * @param id 模板 UUID
     * @param status 生命周期状态
     * @return owner 范围模板实体
     */
    Optional<SafetyCommandTemplateEntity> findByOwnerUserIdAndIdAndStatus(
            UUID ownerUserId,
            UUID id,
            SafetyCommandTemplateStatus status);

    /**
     * 批量清空 owner 当前有效模板材料并转为 REPLACED。
     *
     * @param ownerUserId owner UUID
     * @param replacedAt 替换时间
     * @return 更新行数
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SafetyCommandTemplateEntity template "
            + "set template.status = com.aifriend.template.domain."
            + "SafetyCommandTemplateStatus.REPLACED, "
            + "template.templateCipher = null, template.templateDigest = null, "
            + "template.updatedAt = :replacedAt, template.replacedAt = :replacedAt, "
            + "template.version = template.version + 1 "
            + "where template.ownerUserId = :ownerUserId "
            + "and template.status = com.aifriend.template.domain."
            + "SafetyCommandTemplateStatus.ACTIVE")
    int replaceActive(
            @Param("ownerUserId") UUID ownerUserId,
            @Param("replacedAt") java.time.Instant replacedAt);
}
