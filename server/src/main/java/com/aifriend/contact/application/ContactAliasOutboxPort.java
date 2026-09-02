package com.aifriend.contact.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 联系人称呼投影与缓存清理 Outbox 端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ContactAliasOutboxPort {

    /**
     * 写入称呼创建事件，不包含展示文字、提示或声学模板。
     *
     * @param aliasId 称呼 UUID
     * @param bindingId 联系人绑定 UUID
     * @param ownerUserId owner UUID
     * @param createdAt 创建时间
     */
    void appendCreated(UUID aliasId, UUID bindingId, UUID ownerUserId, Instant createdAt);

    /**
     * 写入称呼删除事件，供后续清理检索投影和缓存。
     *
     * @param aliasId 称呼 UUID
     * @param bindingId 联系人绑定 UUID
     * @param ownerUserId owner UUID
     * @param createdAt 创建时间
     */
    void appendDeleted(UUID aliasId, UUID bindingId, UUID ownerUserId, Instant createdAt);
}
