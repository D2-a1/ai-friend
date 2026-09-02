package com.aifriend.contact.application;

import java.util.Optional;
import java.util.UUID;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;

/**
 * 联系人绑定 owner 范围持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ContactBindingRepositoryPort {

    /**
     * 锁定 owner 账号，串行化同一 owner 的稳定定位唯一性写入。
     *
     * @param ownerUserId owner UUID
     */
    void lockOwner(UUID ownerUserId);

    /**
     * 统计占用联系人总上限的未解除绑定。
     *
     * @param ownerUserId owner UUID
     * @return 非 REVOKED 绑定数量
     */
    long countOccupying(UUID ownerUserId);

    /**
     * 按 owner 与微信主体 HMAC 加锁查询全生命周期唯一绑定。
     *
     * @param ownerUserId owner UUID
     * @param contactSubjectHash 亲友微信主体 HMAC
     * @return 已存在绑定，不存在时为空
     */
    Optional<ContactBinding> findByOwnerAndSubjectForUpdate(
            UUID ownerUserId,
            byte[] contactSubjectHash);

    /**
     * 按 owner 与绑定 UUID 加锁查询联系人，防止 IDOR 和并发状态覆盖。
     *
     * @param ownerUserId owner UUID
     * @param contactId 联系人绑定 UUID
     * @return 加锁后的 owner 范围绑定，不存在时为空
     */
    Optional<ContactBinding> findByOwnerAndIdForUpdate(UUID ownerUserId, UUID contactId);

    /**
     * 按 owner 与绑定 UUID 查询无锁快照，仅供事务外预检。
     *
     * @param ownerUserId owner UUID
     * @param contactId 联系人绑定 UUID
     * @return owner 范围绑定快照
     */
    Optional<ContactBinding> findByOwnerAndId(UUID ownerUserId, UUID contactId);

    /**
     * 判断同一 owner 的其他绑定是否已占用稳定定位 HMAC。
     *
     * @param ownerUserId owner UUID
     * @param contactId 当前联系人绑定 UUID
     * @param locatorHash 稳定定位域隔离 HMAC
     * @return 其他绑定已占用时返回 true
     */
    boolean existsOtherByOwnerAndLocatorHash(
            UUID ownerUserId,
            UUID contactId,
            byte[] locatorHash);

    /**
     * 保存联系人绑定快照。
     *
     * @param binding 绑定快照
     * @return 保存后的绑定
     */
    ContactBinding save(ContactBinding binding);

    /**
     * 分页查询指定 owner 的联系人绑定。
     *
     * @param ownerUserId 当前老人账号 UUID
     * @param status 可选状态过滤
     * @param page 页码，从 0 开始
     * @param size 每页数量，最大 20
     * @return owner 范围分页结果
     */
    ContactBindingPage findByOwner(
            UUID ownerUserId,
            ContactStatus status,
            int page,
            int size);
}
