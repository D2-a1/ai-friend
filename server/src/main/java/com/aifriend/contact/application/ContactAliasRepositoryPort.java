package com.aifriend.contact.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.contact.domain.ContactAlias;

/**
 * owner 范围联系人称呼持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ContactAliasRepositoryPort {

    /**
     * 无锁预查创建幂等记录，避免安全重放重新读取已消费音频。
     *
     * @param ownerUserId owner UUID
     * @param idempotencyKeyHash 创建幂等键摘要
     * @return 已存在称呼
     */
    Optional<ContactAlias> findByOwnerAndCreateKey(
            UUID ownerUserId,
            byte[] idempotencyKeyHash);

    /**
     * 在 owner 锁后查询创建幂等记录。
     *
     * @param ownerUserId owner UUID
     * @param idempotencyKeyHash 创建幂等键摘要
     * @return 加锁后的称呼
     */
    Optional<ContactAlias> findByOwnerAndCreateKeyForUpdate(
            UUID ownerUserId,
            byte[] idempotencyKeyHash);

    /**
     * 按 owner、联系人和称呼编号加锁查询，防止 IDOR。
     *
     * @param ownerUserId owner UUID
     * @param bindingId 联系人绑定 UUID
     * @param aliasId 称呼 UUID
     * @return 加锁后的称呼
     */
    Optional<ContactAlias> findByOwnerAndBindingAndIdForUpdate(
            UUID ownerUserId,
            UUID bindingId,
            UUID aliasId);

    /**
     * 查询 owner 的全部有效称呼，最多由业务上限约束为 100 个。
     *
     * @param ownerUserId owner UUID
     * @return 按创建时间稳定排序的有效称呼
     */
    List<ContactAlias> findActiveByOwner(UUID ownerUserId);

    /**
     * 统计 owner 的有效称呼总数。
     *
     * @param ownerUserId owner UUID
     * @return owner 有效称呼数量
     */
    long countActiveByOwner(UUID ownerUserId);

    /**
     * 统计联系人的有效称呼。
     *
     * @param ownerUserId owner UUID
     * @param bindingId 联系人绑定 UUID
     * @return 有效称呼数量
     */
    long countActiveByBinding(UUID ownerUserId, UUID bindingId);

    /**
     * 保存称呼快照。
     *
     * @param alias 称呼快照
     * @return 保存后的称呼
     */
    ContactAlias save(ContactAlias alias);
}
